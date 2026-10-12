package com.example.demo.services;

import com.example.demo.models.FlashSale;
import com.example.demo.models.Item;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public class FlashSaleService {

    public boolean isActive(FlashSale sale, LocalDateTime now) {
        Objects.requireNonNull(sale, "sale must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return !now.isBefore(sale.getStartsAt()) && now.isBefore(sale.getEndsAt());
    }

    public BigDecimal priceFor(Item item, FlashSale sale, LocalDateTime now) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(sale, "sale must not be null");
        boolean included = sale.getItems().stream()
                .anyMatch(saleItem -> saleItem.getId().equals(item.getId()));
        if (!included) {
            throw new IllegalArgumentException("item is not included in the sale");
        }
        return isActive(sale, now) ? sale.getSalePrice().min(item.getPrice()) : item.getPrice();
    }
}
