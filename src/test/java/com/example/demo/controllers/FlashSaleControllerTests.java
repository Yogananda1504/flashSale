package com.example.demo.controllers;

import com.example.demo.models.*;
import com.example.demo.services.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlashSaleControllerTests {
    private final InventoryStore store = mock(InventoryStore.class);
    private final FlashSaleQueueService queue = mock(FlashSaleQueueService.class);
    private final User user = new User("user-1", "Customer", "customer@example.com", "hash");
    private final Principal principal = user::getEmail;
    private final CompletableFuture<Void> queued = new CompletableFuture<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T12:00:00Z"));
    private final Clock clock = mock(Clock.class);
    private final FlashSaleController controller = new FlashSaleController(store, clock);
    private final CapItem item = new CapItem("cap", "Cap", new BigDecimal("5.00"), 2, CapSize.ONE_SIZE);

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(store.getUser(user.getEmail())).thenReturn(user);
        when(store.getQueue()).thenReturn(queue);
        when(store.getOrders()).thenReturn(orders);
        when(queue.awaitTurn(user)).thenReturn(queued);
        var start = LocalDateTime.ofInstant(now.get(), ZoneOffset.UTC);
        when(store.getSale()).thenReturn(new FlashSale(List.of(item), BigDecimal.TEN, start, start.plusMinutes(10), 10));
        user.getCart().add(item, 1);
    }

    private void ready() {
        controller.checkout(new FlashSaleController.CheckoutRequest("request-1"), principal);
        queued.complete(null);
    }

    @AfterEach
    void closeController() { controller.close(); }

    @Test
    void queueCompletionReservesStockForPaymentWithoutCreatingAnOrder() {
        var request = new FlashSaleController.CheckoutRequest("request-1");
        assertEquals(202, controller.checkout(request, principal).getStatusCode().value());
        assertEquals(202, controller.checkout(request, principal).getStatusCode().value());
        queued.complete(null);
        var state = controller.checkoutStatus("request-1", principal);
        assertEquals("READY_FOR_PAYMENT", body(state).status());
        assertEquals(now.get().plusSeconds(120), body(state).paymentExpiresAt());
        assertNull(body(state).order());
        assertTrue(orders.isEmpty());
        assertEquals(1, item.getStockQuantity());
        assertEquals(1, user.getCart().getItems().size());
        verify(queue, times(1)).awaitTurn(user);
    }

    @Test
    void paymentPlacesExactlyOnePaidOrderAndRetriesReturnItEvenAfterTheDeadline() {
        ready();
        var paid = controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal);
        assertEquals("COMPLETED", body(paid).status());
        assertEquals("PAID", body(paid).order().paymentStatus());
        assertEquals(new BigDecimal("5.00"), body(paid).order().total());
        assertEquals(1, orders.size());
        assertEquals(1, item.getStockQuantity());
        assertTrue(user.getCart().getItems().isEmpty());
        now.updateAndGet(instant -> instant.plusSeconds(180));
        var retry = controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal);
        assertEquals(body(paid).order().id(), body(retry).order().id());
        assertEquals(1, orders.size());
    }

    @Test
    void paymentAtTheDeadlineIsRejectedWithoutAnOrderOrStockChange() {
        ready();
        now.updateAndGet(instant -> instant.plusSeconds(120));
        var expired = controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal);
        assertEquals(410, expired.getStatusCode().value());
        assertEquals("EXPIRED", body(expired).status());
        assertEquals(410, controller.checkoutStatus("request-1", principal).getStatusCode().value());
        assertTrue(orders.isEmpty());
        assertEquals(2, item.getStockQuantity());
        assertEquals(1, user.getCart().getItems().size());
    }

    @Test
    void paymentBeforeTheTurnDoesNotPlaceAnOrder() {
        controller.checkout(new FlashSaleController.CheckoutRequest("request-1"), principal);
        assertEquals(202, controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal).getStatusCode().value());
        assertTrue(orders.isEmpty());
    }

    @Test
    void unconfirmedPaymentCanBeRetriedWithinTheDeadline() {
        ready();
        assertEquals(400, controller.pay("request-1", new FlashSaleController.PaymentRequest(false), principal).getStatusCode().value());
        assertTrue(orders.isEmpty());
        assertEquals(200, controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal).getStatusCode().value());
    }

    @Test
    void otherCustomersCannotConsumeTheReservedUnit() {
        ready();
        item.decreaseStock(1);
        var paid = controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal);
        assertEquals(200, paid.getStatusCode().value());
        assertEquals("PAID", body(paid).order().paymentStatus());
        assertEquals(0, item.getStockQuantity());
    }

    @Test
    void queueFailureRemainsQueryable() {
        controller.checkout(new FlashSaleController.CheckoutRequest("request-1"), principal);
        queued.completeExceptionally(new IllegalStateException("flash sale is not active"));
        var failed = controller.checkoutStatus("request-1", principal);
        assertEquals(409, failed.getStatusCode().value());
        assertEquals("FAILED", body(failed).status());
        verify(queue, times(1)).awaitTurn(user);
    }

    @Test
    void sessionCannotBeReadOrPaidByAnotherUser() {
        ready();
        User other = new User("user-2", "Other", "other@example.com", "hash");
        when(store.getUser(other.getEmail())).thenReturn(other);
        assertEquals(404, controller.checkoutStatus("request-1", other::getEmail).getStatusCode().value());
        assertEquals(404, controller.pay("request-1", new FlashSaleController.PaymentRequest(true), other::getEmail).getStatusCode().value());
        assertEquals(404, controller.cancelPayment("request-1", other::getEmail).getStatusCode().value());
    }

    @Test
    void cancellationRestoresStockOnceAndPreventsPayment() {
        ready();
        assertEquals(1, item.getStockQuantity());
        assertEquals("CANCELLED", body(controller.cancelPayment("request-1", principal)).status());
        controller.cancelPayment("request-1", principal);
        assertEquals("CANCELLED", body(controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal)).status());
        assertEquals(2, item.getStockQuantity());
        assertTrue(user.getCart().getItems().isEmpty());
        assertTrue(orders.isEmpty());
    }

    @Test
    void repeatingCancellationCannotClearItemsAddedAfterCancellation() {
        ready();
        controller.cancelPayment("request-1", principal);
        user.getCart().add(item, 1);
        controller.cancelPayment("request-1", principal);
        assertEquals(1, user.getCart().getItems().size());
        assertEquals(2, item.getStockQuantity());
    }

    @Test
    void timeoutReturnsStockWithoutPollingAndCancellationCannotReturnItAgain() {
        ready();
        now.updateAndGet(instant -> instant.plusSeconds(120));
        controller.expireSessions();
        controller.expireSessions();
        assertEquals(2, item.getStockQuantity());
        controller.cancelPayment("request-1", principal);
        assertEquals(2, item.getStockQuantity());
        assertTrue(orders.isEmpty());
    }

    @Test
    void cancellingAPaidOrderCannotReturnPurchasedStock() {
        ready();
        controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal);
        assertEquals("COMPLETED", body(controller.cancelPayment("request-1", principal)).status());
        assertEquals(1, item.getStockQuantity());
        assertEquals(1, orders.size());
    }

    @Test
    void cancellingWhileQueuedPreventsLaterReservation() {
        controller.checkout(new FlashSaleController.CheckoutRequest("request-1"), principal);
        controller.cancelPayment("request-1", principal);
        queued.complete(null);
        assertEquals("CANCELLED", body(controller.checkoutStatus("request-1", principal)).status());
        assertEquals(2, item.getStockQuantity());
    }

    @Test
    void paymentAndCancellationRaceHasOneConsistentOutcome() throws Exception {
        ready();
        CountDownLatch start = new CountDownLatch(1);
        var pay = CompletableFuture.supplyAsync(() -> {
            try { start.await(); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
            return controller.pay("request-1", new FlashSaleController.PaymentRequest(true), principal);
        });
        var cancel = CompletableFuture.supplyAsync(() -> {
            try { start.await(); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
            return controller.cancelPayment("request-1", principal);
        });
        start.countDown();
        pay.get(3, TimeUnit.SECONDS);
        cancel.get(3, TimeUnit.SECONDS);
        String state = body(controller.checkoutStatus("request-1", principal)).status();
        if (state.equals("COMPLETED")) {
            assertEquals(1, orders.size());
            assertEquals(1, item.getStockQuantity());
        } else {
            assertEquals("CANCELLED", state);
            assertTrue(orders.isEmpty());
            assertEquals(2, item.getStockQuantity());
        }
    }

    private FlashSaleController.CheckoutResponse body(ResponseEntity<?> response) {
        return (FlashSaleController.CheckoutResponse) response.getBody();
    }
}
