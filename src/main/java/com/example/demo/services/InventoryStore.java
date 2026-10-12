package com.example.demo.services;

import com.example.demo.models.CapItem;
import com.example.demo.models.CapSize;
import com.example.demo.models.FlashSale;
import com.example.demo.models.Item;
import com.example.demo.models.JerseyItem;
import com.example.demo.models.JerseySize;
import com.example.demo.models.Order;
import com.example.demo.models.User;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InventoryStore {
    private final Map<String, Item> items = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final FlashSale sale;
    private final FlashSaleQueueService queue;

    public InventoryStore() {
        Item jersey = new JerseyItem(
                "jersey-1", "City Edition Jersey",
                new BigDecimal("29.99"), 12, JerseySize.M);
        Item cap = new CapItem(
                "cap-1", "Classic Team Cap",
                new BigDecimal("14.99"), 6, CapSize.ONE_SIZE);
        items.put(jersey.getId(), jersey);
        items.put(cap.getId(), cap);
        User demoUser = new User("user-1", "Demo Customer", "demo@example.com");
        users.put(demoUser.getEmail(), demoUser);
        sale = new FlashSale(
                List.of(jersey, cap),
                new BigDecimal("29.99"),
                LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(18).plusSeconds(42),
                10);
        queue = new FlashSaleQueueService(sale, sale.getQuantityLimit());
    }

    public Map<String, Item> getItems() {
        return items;
    }

    public User getUser(String email) {
        return users.get(email);
    }

    public FlashSale getSale() {
        return sale;
    }

    public Map<String, Order> getOrders() {
        return orders;
    }

    public FlashSaleQueueService getQueue() {
        return queue;
    }

    @PreDestroy
    public void close() {
        queue.close();
    }
}
