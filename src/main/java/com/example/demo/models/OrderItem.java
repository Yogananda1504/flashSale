package com.example.demo.models;

import java.math.BigDecimal;
import java.util.Objects;

public class OrderItem {
    private final String itemId;
    private final String itemName;
    private final BigDecimal unitPrice;
    private final int quantity;

    public OrderItem(String itemId, String itemName, BigDecimal unitPrice, int quantity) {
        this.itemId = Objects.requireNonNull(itemId, "itemId must not be null");
        this.itemName = Objects.requireNonNull(itemName, "itemName must not be null");
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        if (unitPrice.signum() < 0 || quantity <= 0) {
            throw new IllegalArgumentException("unitPrice must not be negative and quantity must be positive");
        }
        this.quantity = quantity;
    }

    public String getItemId() {
        return itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getSubtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
