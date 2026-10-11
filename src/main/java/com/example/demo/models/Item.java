package com.example.demo.models;

import java.math.BigDecimal;
import java.util.Objects;

public abstract class Item {
    private final String id;
    private final String name;
    private final BigDecimal price;
    private int stockQuantity;

    protected Item(String id, String name, BigDecimal price, int stockQuantity) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.price = Objects.requireNonNull(price, "price must not be null");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("stockQuantity must not be negative");
        }
        this.stockQuantity = stockQuantity;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public synchronized int getStockQuantity() {
        return stockQuantity;
    }

    public synchronized void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (quantity > stockQuantity) {
            throw new IllegalArgumentException("insufficient stock");
        }
        stockQuantity -= quantity;
    }
}