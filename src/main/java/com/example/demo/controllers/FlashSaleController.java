package com.example.demo.controllers;

import com.example.demo.models.CapItem;
import com.example.demo.models.Item;
import com.example.demo.models.JerseyItem;
import com.example.demo.models.Order;
import com.example.demo.models.OrderItem;
import com.example.demo.services.InventoryStore;
import com.example.demo.services.CartService;
import com.example.demo.services.FlashSaleService;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.security.Principal;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class FlashSaleController {
    private final InventoryStore store;
    private final Clock clock;
    private final ScheduledExecutorService timeoutWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "payment-timeouts");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentMap<String, CheckoutSession> checkoutRequests =
            new ConcurrentHashMap<>();

    @Autowired
    public FlashSaleController(InventoryStore store) {
        this(store, Clock.systemDefaultZone());
    }

    FlashSaleController(InventoryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        timeoutWorker.scheduleAtFixedRate(this::expireSessions, 1, 1, TimeUnit.SECONDS);
    }

    @GetMapping("/items")
    public List<ItemResponse> items() {
        return store.getItems().values().stream().map(this::toItemResponse).toList();
    }

    @GetMapping("/sale")
    public SaleResponse sale() {
        return new SaleResponse(
                store.getSale().getEndsAt(),
                store.getSale().getQuantityLimit());
    }

    @GetMapping("/me/cart")
    public CartResponse cart(Principal principal) {
        List<CartItemResponse> cartItems = currentUser(principal).getCart().getItems().stream()
                .map(cartItem -> new CartItemResponse(
                        cartItem.getItem().getId(),
                        cartItem.getItem().getName(),
                        cartItem.getQuantity()))
                .toList();
        return new CartResponse(cartItems);
    }

    @PostMapping("/me/cart/items")
    public ResponseEntity<?> addToCart(
            @RequestBody AddCartItemRequest request, Principal principal) {
        Item item = store.getItems().get(request.itemId());
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Item not found");
        }
        if (request.quantity() <= 0 || request.quantity() > item.getStockQuantity()) {
            return ResponseEntity.badRequest().body("Requested quantity is unavailable");
        }
        currentUser(principal).getCart().add(item, request.quantity());
        return ResponseEntity.ok(cart(principal));
    }

    @PostMapping("/me/checkout")
    public ResponseEntity<?> checkout(
            @RequestBody CheckoutRequest request, Principal principal) {
        String userId = currentUser(principal).getId();
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString()
                : request.requestId();
        CheckoutSession checkout = checkoutRequests.computeIfAbsent(
                userId + ":" + requestId,
                ignored -> {
                    CheckoutSession session = new CheckoutSession();
                    store.getQueue().awaitTurn(currentUser(principal)).whenComplete((ready, failure) -> {
                        synchronized (session) {
                            if (session.cancelled) return;
                            if (failure != null) {
                                session.ready.completeExceptionally(failure);
                                return;
                            }
                            try {
                                LocalDateTime now = LocalDateTime.now(clock);
                                FlashSaleService pricing = new FlashSaleService();
                                if (!pricing.isActive(store.getSale(), now)) throw new IllegalStateException("flash sale is not active");
                                session.reservation = new CartService().reserve(currentUser(principal), item -> pricing.priceFor(item, store.getSale(), now));
                                session.ready.complete(clock.instant().plusSeconds(120));
                            } catch (RuntimeException exception) {
                                session.ready.completeExceptionally(exception);
                            }
                        }
                    });
                    return session;
                });
        return checkoutResponse(requestId, checkout);
    }

    @GetMapping("/me/checkout/{requestId}")
    public ResponseEntity<?> checkoutStatus(@PathVariable String requestId, Principal principal) {
        CheckoutSession checkout = checkoutRequests.get(
                currentUser(principal).getId() + ":" + requestId);
        if (checkout == null) {
            return ResponseEntity.notFound().build();
        }
        return checkoutResponse(requestId, checkout);
    }

    private ResponseEntity<CheckoutResponse> checkoutResponse(
            String requestId, CheckoutSession checkout) {
        synchronized (checkout) {
            if (checkout.cancelled) {
                return ResponseEntity.ok(new CheckoutResponse(requestId, "CANCELLED", null, "Payment cancelled. Reserved items returned to inventory.", null));
            }
            if (!checkout.ready.isDone()) {
                return ResponseEntity.accepted().header("Retry-After", "1")
                        .body(new CheckoutResponse(requestId, "PENDING", null, null, null));
            }
            try {
                Instant expiresAt = checkout.ready.join();
                if (checkout.order != null) {
                    return ResponseEntity.ok(new CheckoutResponse(requestId, "COMPLETED", toOrderResponse(checkout.order), null, expiresAt));
                }
                if (checkout.failure != null) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(new CheckoutResponse(requestId, "FAILED", null, checkout.failure, expiresAt));
                }
                if (!clock.instant().isBefore(expiresAt)) {
                    checkout.reservation.release();
                    return ResponseEntity.status(HttpStatus.GONE).body(new CheckoutResponse(requestId, "EXPIRED", null, "Payment timed out. Join the queue again to try checkout.", expiresAt));
                }
                return ResponseEntity.ok(new CheckoutResponse(requestId, "READY_FOR_PAYMENT", null, null, expiresAt));
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause();
                boolean conflict = cause instanceof IllegalStateException
                        || cause instanceof IllegalArgumentException;
                return ResponseEntity.status(conflict ? HttpStatus.CONFLICT : HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new CheckoutResponse(requestId, "FAILED", null,
                                conflict ? cause.getMessage() : "Checkout processing failed", null));
            }
        }
    }

    @PostMapping("/me/checkout/{requestId}/payment")
    public ResponseEntity<?> pay(@PathVariable String requestId, @RequestBody PaymentRequest payment, Principal principal) {
        CheckoutSession checkout = checkoutRequests.get(currentUser(principal).getId() + ":" + requestId);
        if (checkout == null) return ResponseEntity.notFound().build();
        synchronized (checkout) {
            ResponseEntity<CheckoutResponse> state = checkoutResponse(requestId, checkout);
            if (!"READY_FOR_PAYMENT".equals(state.getBody().status())) return state;
            if (!payment.paymentConfirmed()) {
                return ResponseEntity.badRequest().body(new CheckoutResponse(requestId, "PAYMENT_DECLINED", null, "Confirm the demo payment to place your order.", checkout.ready.join()));
            }
            try {
                LocalDateTime now = LocalDateTime.now(clock);
                FlashSaleService pricing = new FlashSaleService();
                if (!pricing.isActive(store.getSale(), now)) throw new IllegalStateException("flash sale is not active");
                Order order = checkout.reservation.placeOrder();
                order.markPaymentPaid();
                checkout.order = order;
                store.getOrders().put(order.getId(), order);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                checkout.reservation.release();
                checkout.failure = exception.getMessage();
            }
            return checkoutResponse(requestId, checkout);
        }
    }

    @PostMapping("/me/checkout/{requestId}/cancel")
    public ResponseEntity<?> cancelPayment(@PathVariable String requestId, Principal principal) {
        CheckoutSession checkout = checkoutRequests.get(currentUser(principal).getId() + ":" + requestId);
        if (checkout == null) return ResponseEntity.notFound().build();
        synchronized (checkout) {
            if (checkout.order == null && !checkout.cancelled) {
                checkout.cancelled = true;
                if (checkout.reservation != null) checkout.reservation.release();
                currentUser(principal).getCart().clear();
            }
            return checkoutResponse(requestId, checkout);
        }
    }

    void expireSessions() {
        for (CheckoutSession checkout : checkoutRequests.values()) {
            synchronized (checkout) {
                if (checkout.order == null && checkout.reservation != null
                        && checkout.ready.isDone() && !checkout.ready.isCompletedExceptionally()
                        && !clock.instant().isBefore(checkout.ready.join())) {
                    checkout.reservation.release();
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        timeoutWorker.shutdownNow();
        for (CheckoutSession checkout : checkoutRequests.values()) {
            synchronized (checkout) {
                checkout.cancelled = true;
                if (checkout.reservation != null) checkout.reservation.release();
            }
        }
    }

    @GetMapping("/me/orders")
    public List<OrderResponse> orders(Principal principal) {
        String userId = currentUser(principal).getId();
        return store.getOrders().values().stream()
                .filter(order -> userId.equals(order.getUserId()))
                .map(this::toOrderResponse).toList();
    }

    private com.example.demo.models.User currentUser(Principal principal) {
        com.example.demo.models.User user = store.getUser(principal.getName());
        if (user == null) {
            throw new IllegalStateException("Authenticated user is not registered");
        }
        return user;
    }

    private ItemResponse toItemResponse(Item item) {
        String type;
        List<String> sizes;
        if (item instanceof JerseyItem) {
            type = "Jersey";
            sizes = List.of("S", "M", "L");
        } else if (item instanceof CapItem) {
            type = "Cap";
            sizes = List.of("ONE_SIZE");
        } else {
            type = "Item";
            sizes = List.of();
        }
        return new ItemResponse(
                item.getId(), item.getName(), type, item.getPrice(),
                item.getStockQuantity(), sizes);
    }

    private OrderResponse toOrderResponse(Order order) {
        return new OrderResponse(
                order.getId(), order.getUserId(), order.getTotal(),
                order.getPaymentStatus().name(), order.getDeliveryStatus().name(),
                order.getCreatedAt(), order.getItems().stream()
                        .map(this::toOrderItemResponse).toList());
    }

    private OrderItemResponse toOrderItemResponse(OrderItem item) {
        return new OrderItemResponse(
                item.getItemId(), item.getItemName(),
                item.getUnitPrice(), item.getQuantity());
    }

    public record ItemResponse(
            String id, String name, String type, BigDecimal price,
            int stock, List<String> sizes) {
    }

    public record SaleResponse(LocalDateTime endsAt, int requestsPerSecond) {
    }

    public record AddCartItemRequest(String itemId, String size, int quantity) {
    }

    public record CheckoutRequest(String requestId) {
    }

    public record PaymentRequest(boolean paymentConfirmed) {
    }

    private static class CheckoutSession {
        final CompletableFuture<Instant> ready = new CompletableFuture<>();
        CartService.Reservation reservation;
        boolean cancelled;
        Order order;
        String failure;

    }

    public record CheckoutResponse(String requestId, String status, OrderResponse order, String message, Instant paymentExpiresAt) {
    }

    public record CartResponse(List<CartItemResponse> items) {
    }

    public record CartItemResponse(String itemId, String name, int quantity) {
    }

    public record OrderResponse(
            String id, String userId, BigDecimal total,
            String paymentStatus, String deliveryStatus,
            LocalDateTime createdAt, List<OrderItemResponse> items) {
    }

    public record OrderItemResponse(
            String itemId, String itemName, BigDecimal unitPrice, int quantity) {
    }
}
