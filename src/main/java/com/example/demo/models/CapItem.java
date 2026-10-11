package com.example.demo.models;

import java.math.BigDecimal;
import java.util.Objects;

public class CapItem extends Item {
    private final CapSize size;

    public CapItem(
            String id,
            String name,
            BigDecimal price,
            int stockQuantity,
            CapSize size) {
        super(id, name, price, stockQuantity);
        this.size = Objects.requireNonNull(size, "size must not be null");
    }

    public CapSize getSize() {
        return size;
    }
}