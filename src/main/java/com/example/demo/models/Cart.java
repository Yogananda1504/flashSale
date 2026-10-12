package com.example.demo.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Cart {
    private final String userId;
    private final List<CartItem> items = new ArrayList<>();

    public Cart(String userId) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
    }

    public String getUserId() {
        return userId;
    }

    public synchronized List<CartItem> getItems() {
        return List.copyOf(items);
    }

    public synchronized void add(Item item, int quantity) {
        Objects.requireNonNull(item, "item must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }

        for (CartItem cartItem : items) {
            if (cartItem.getItem().getId().equals(item.getId())) {
                cartItem.setQuantity(cartItem.getQuantity() + quantity);
                return;
            }
        }
        items.add(new CartItem(item, quantity));
    }

    public synchronized void remove(String itemId) {
        items.removeIf(cartItem -> cartItem.getItem().getId().equals(itemId));
    }

    public synchronized void clear() {
        items.clear();
    }

    public synchronized void consume(String itemId, int quantity) {
        for (CartItem cartItem : items) {
            if (cartItem.getItem().getId().equals(itemId)) {
                if (cartItem.getQuantity() > quantity) cartItem.setQuantity(cartItem.getQuantity() - quantity);
                else items.remove(cartItem);
                return;
            }
        }
    }
}
