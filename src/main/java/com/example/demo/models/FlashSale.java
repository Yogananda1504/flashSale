package com.example.demo.models;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public class FlashSale {
    private final List<Item> items;
    private final BigDecimal salePrice;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
    private final int quantityLimit;

    public FlashSale(
            List<Item> items,
            BigDecimal salePrice,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int quantityLimit) {
        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (items.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("items must not contain null values");
        }
        this.items = List.copyOf(items);
        this.salePrice = Objects.requireNonNull(salePrice, "salePrice must not be null");
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt must not be null");
        this.endsAt = Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (salePrice.signum() < 0) {
            throw new IllegalArgumentException("salePrice must not be negative");
        }
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("startsAt must be before endsAt");
        }
        if (quantityLimit <= 0) {
            throw new IllegalArgumentException("quantityLimit must be greater than zero");
        }
        this.quantityLimit = quantityLimit;
    }

    public List<Item> getItems() {
        return items;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public LocalDateTime getStartsAt() {
        return startsAt;
    }

    public LocalDateTime getEndsAt() {
        return endsAt;
    }

    public int getQuantityLimit() {
        return quantityLimit;
    }
}
