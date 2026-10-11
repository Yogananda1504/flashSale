package com.example.demo.models;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public class Order {
    private final String id;
    private final String userId;
    private final List<OrderItem> items;
    private final LocalDateTime createdAt;
    private final BigDecimal total;
    private PaymentStatus paymentStatus;
    private DeliveryStatus deliveryStatus;

    public Order(String id, String userId, List<OrderItem> items) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty() || items.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("order must contain non-null items");
        }
        this.items = List.copyOf(items);
        this.createdAt = LocalDateTime.now();
        this.total = this.items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.paymentStatus = PaymentStatus.PENDING;
        this.deliveryStatus = DeliveryStatus.NOT_STARTED;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public void markPaymentPaid() {
        paymentStatus = PaymentStatus.PAID;
    }

    public void markPaymentFailed() {
        paymentStatus = PaymentStatus.FAILED;
    }

    public void markRefunded() {
        paymentStatus = PaymentStatus.REFUNDED;
    }

    public void updateDeliveryStatus(DeliveryStatus status) {
        deliveryStatus = Objects.requireNonNull(status, "status must not be null");
    }
}
