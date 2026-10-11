package com.example.demo.services;

import com.example.demo.models.FlashSale;
import com.example.demo.models.Item;
import com.example.demo.models.Order;
import com.example.demo.models.User;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Processes flash-sale checkout requests in FIFO order, in fixed-size batches.
 */
public class FlashSaleQueueService implements AutoCloseable {
    private final FlashSale sale;
    private final FlashSaleService flashSaleService;
    private final CartService cartService;
    private final Clock clock;
    private final int requestsPerSecond;
    private final BlockingQueue<CheckoutRequest> requests = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService worker;
    private final ExecutorService batchWorkers;

    public FlashSaleQueueService(
            FlashSale sale,
            int requestsPerSecond) {
        this(sale, requestsPerSecond, Clock.systemDefaultZone());
    }

    FlashSaleQueueService(
            FlashSale sale,
            int requestsPerSecond,
            Clock clock) {
        this.sale = Objects.requireNonNull(sale, "sale must not be null");
        this.flashSaleService = new FlashSaleService();
        this.cartService = new CartService();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond must be greater than zero");
        }
        this.requestsPerSecond = requestsPerSecond;
        this.batchWorkers = Executors.newFixedThreadPool(requestsPerSecond, runnable -> {
            Thread thread = new Thread(runnable, "flash-sale-batch-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "flash-sale-queue-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.worker.scheduleAtFixedRate(
                this::processBatch,
                0,
                1,
                TimeUnit.SECONDS);
    }

    public CompletableFuture<Order> enqueue(User user) {
        Objects.requireNonNull(user, "user must not be null");
        CompletableFuture<Order> result = new CompletableFuture<>();
        requests.add(new CheckoutRequest(user, result));
        return result;
    }

    public int getQueueSize() {
        return requests.size();
    }

    private void processBatch() {
        for (int index = 0; index < requestsPerSecond; index++) {
            CheckoutRequest request = requests.poll();
            if (request == null) {
                return;
            }
            batchWorkers.submit(() -> process(request));
        }
    }

    private void process(CheckoutRequest request) {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            if (!flashSaleService.isActive(sale, now)) {
                throw new IllegalStateException("flash sale is not active");
            }
            if (request.user.getCart().getItems().stream()
                    .anyMatch(cartItem -> sale.getItems().stream()
                            .noneMatch(saleItem -> saleItem.getId()
                                    .equals(cartItem.getItem().getId())))) {
                throw new IllegalArgumentException(
                        "cart contains an item that is not included in the flash sale");
            }
            request.result.complete(cartService.checkout(
                    request.user,
                    item -> priceForSaleItem(item, now)));
        } catch (RuntimeException exception) {
            request.result.completeExceptionally(exception);
        }
    }

    private java.math.BigDecimal priceForSaleItem(Item item, LocalDateTime now) {
        return flashSaleService.priceFor(item, sale, now);
    }

    @Override
    public void close() {
        worker.shutdownNow();
        batchWorkers.shutdownNow();
        CheckoutRequest request;
        while ((request = requests.poll()) != null) {
            request.result.completeExceptionally(
                    new IllegalStateException("flash-sale queue has been closed"));
        }
    }

    private record CheckoutRequest(User user, CompletableFuture<Order> result) {
    }
}
