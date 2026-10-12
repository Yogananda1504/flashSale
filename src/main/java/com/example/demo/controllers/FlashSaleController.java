package com.example.demo.controllers;

import com.example.demo.models.CapItem;
import com.example.demo.models.DeliveryStatus;
import com.example.demo.models.Item;
import com.example.demo.models.JerseyItem;
import com.example.demo.models.Order;
import com.example.demo.models.OrderItem;
import com.example.demo.services.InventoryStore;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class FlashSaleController {
    private final InventoryStore store;
    private final ConcurrentMap<String, CompletableFuture<Order>> checkoutRequests =
            new ConcurrentHashMap<>();

    public FlashSaleController(InventoryStore store) {
        this.store = store;
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

    @GetMapping("/cart")
    public CartResponse cart() {
        List<CartItemResponse> cartItems = store.getDemoUser().getCart().getItems().stream()
                .map(cartItem -> new CartItemResponse(
                        cartItem.getItem().getId(),
                        cartItem.getItem().getName(),
                        cartItem.getQuantity()))
                .toList();
        return new CartResponse(cartItems);
    }

    @PostMapping("/cart/items")
    public ResponseEntity<?> addToCart(@RequestBody AddCartItemRequest request) {
        Item item = store.getItems().get(request.itemId());
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Item not found");
        }
        if (request.quantity() <= 0 || request.quantity() > item.getStockQuantity()) {
            return ResponseEntity.badRequest().body("Requested quantity is unavailable");
        }
        store.getDemoUser().getCart().add(item, request.quantity());
        return ResponseEntity.ok(cart());
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody CheckoutRequest request) {
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString()
                : request.requestId();
        CompletableFuture<Order> checkout = checkoutRequests.computeIfAbsent(
                requestId,
                ignored -> {
                    CompletableFuture<Order> future = store.getQueue()
                            .enqueue(store.getDemoUser());
                    future.thenAccept(order -> store.getOrders().put(order.getId(), order))
                            .exceptionally(error -> {
                                checkoutRequests.remove(requestId, future);
                                return null;
                            });
                    return future;
                });
        try {
            Order order = checkout.get(12, TimeUnit.SECONDS);
            return ResponseEntity.ok(toOrderResponse(order));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Objects.requireNonNullElse(exception.getCause(), exception).getMessage());
        }
    }

    @GetMapping("/orders")
    public List<OrderResponse> orders() {
        return store.getOrders().values().stream().map(this::toOrderResponse).toList();
    }

    @PostMapping("/orders/{orderId}/payment")
    public ResponseEntity<?> pay(@PathVariable String orderId) {
        Order order = store.getOrders().get(orderId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        order.markPaymentPaid();
        return ResponseEntity.ok(toOrderResponse(order));
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
