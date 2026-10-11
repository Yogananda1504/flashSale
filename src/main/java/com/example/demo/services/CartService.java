package com.example.demo.services;

import com.example.demo.models.Cart;
import com.example.demo.models.CartItem;
import com.example.demo.models.Item;
import com.example.demo.models.Order;
import com.example.demo.models.OrderItem;
import com.example.demo.models.User;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public class CartService {

    public void refreshAvailability(Cart cart) {
        Objects.requireNonNull(cart, "cart must not be null");
        synchronized (cart) {
            List<CartItem> cartItems = cart.getItems().stream()
                    .sorted(Comparator.comparing(cartItem -> cartItem.getItem().getId()))
                    .toList();
            refreshAvailabilityWithLocks(cart, cartItems, 0);
        }
    }

    private void refreshAvailabilityWithLocks(
            Cart cart,
            List<CartItem> cartItems,
            int lockIndex) {
        if (lockIndex < cartItems.size()) {
            synchronized (cartItems.get(lockIndex).getItem()) {
                refreshAvailabilityWithLocks(cart, cartItems, lockIndex + 1);
            }
            return;
        }

        for (CartItem cartItem : cartItems) {
            int available = cartItem.getItem().getStockQuantity();
            if (available == 0) {
                cart.remove(cartItem.getItem().getId());
            } else if (cartItem.getQuantity() > available) {
                cartItem.setQuantity(available);
            }
        }
    }

    public Order checkout(User user) {
        return checkout(user, Item::getPrice);
    }

    public Order checkout(User user, Function<Item, java.math.BigDecimal> priceProvider) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(priceProvider, "priceProvider must not be null");
        Cart cart = user.getCart();
        synchronized (cart) {
            List<CartItem> cartItems = cart.getItems().stream()
                    .sorted(Comparator.comparing(cartItem -> cartItem.getItem().getId()))
                    .toList();
            if (cartItems.isEmpty()) {
                throw new IllegalStateException("cart has no available items");
            }
            return checkoutWithLocks(user, cart, cartItems, priceProvider, 0);
        }
    }

    private Order checkoutWithLocks(
            User user,
            Cart cart,
            List<CartItem> cartItems,
            Function<Item, java.math.BigDecimal> priceProvider,
            int lockIndex) {
        if (lockIndex < cartItems.size()) {
            Item item = cartItems.get(lockIndex).getItem();
            synchronized (item) {
                return checkoutWithLocks(
                        user,
                        cart,
                        cartItems,
                        priceProvider,
                        lockIndex + 1);
            }
        }

        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            Item item = cartItem.getItem();
            if (cartItem.getQuantity() > item.getStockQuantity()) {
                refreshAvailability(cart);
                throw new IllegalStateException(
                        "cart availability changed; please review the cart");
            }
            orderItems.add(new OrderItem(
                    item.getId(),
                    item.getName(),
                    Objects.requireNonNull(
                            priceProvider.apply(item),
                            "priceProvider returned null"),
                    cartItem.getQuantity()));
        }

        for (CartItem cartItem : cartItems) {
            cartItem.getItem().decreaseStock(cartItem.getQuantity());
        }
        cart.clear();
        return new Order(UUID.randomUUID().toString(), user.getId(), orderItems);
    }
}
