package com.example.demo.services;

import com.example.demo.models.FlashSale;
import com.example.demo.models.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Admits flash-sale customers to payment in fixed-size batches.
 */
public class FlashSaleQueueService implements AutoCloseable {
    private static final Duration CHECKOUT_BUFFER = Duration.ofSeconds(3);
    private final FlashSale sale;
    private final FlashSaleService flashSaleService;
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

    public CompletableFuture<Void> awaitTurn(User user) {
        Objects.requireNonNull(user, "user must not be null");
        CompletableFuture<Void> result = new CompletableFuture<>();
        requests.add(new CheckoutRequest(user, result, clock.instant().plus(CHECKOUT_BUFFER)));
        return result;
    }

    public int getQueueSize() {
        return requests.size();
    }

    private void processBatch() {
        for (int index = 0; index < requestsPerSecond; index++) {
            CheckoutRequest next = requests.peek();
            if (next == null || clock.instant().isBefore(next.readyAt())) {
                return;
            }
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
            if (request.user.getCart().getItems().isEmpty()) {
                throw new IllegalStateException("cart has no available items");
            }
            if (request.user.getCart().getItems().stream()
                    .anyMatch(cartItem -> sale.getItems().stream()
                            .noneMatch(saleItem -> saleItem.getId()
                                    .equals(cartItem.getItem().getId())))) {
                throw new IllegalArgumentException(
                        "cart contains an item that is not included in the flash sale");
            }
            request.result.complete(null);
        } catch (RuntimeException exception) {
            request.result.completeExceptionally(exception);
        }
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

    private record CheckoutRequest(User user, CompletableFuture<Void> result, Instant readyAt) {
    }
}
