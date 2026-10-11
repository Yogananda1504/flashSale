package com.example.demo.models;

import java.math.BigDecimal;
import java.util.Objects;

public class JerseyItem extends Item {
    private final JerseySize size;

    public JerseyItem(
            String id,
            String name,
            BigDecimal price,
            int stockQuantity,
            JerseySize size) {
        super(id, name, price, stockQuantity);
        this.size = Objects.requireNonNull(size, "size must not be null");
    }

    public JerseySize getSize() {
        return size;
    }
}