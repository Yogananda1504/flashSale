package com.example.demo.models;

import java.util.Objects;

public class User {
    private final String id;
    private final String name;
    private final String email;
    private final Cart cart;

    public User(String id, String name, String email) {
        this.id = requireText(id, "id");
        this.name = requireText(name, "name");
        this.email = requireText(email, "email");
        this.cart = new Cart(id);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public Cart getCart() {
        return cart;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
