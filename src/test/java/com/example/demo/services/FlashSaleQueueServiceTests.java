package com.example.demo.services;

import com.example.demo.models.CapItem;
import com.example.demo.models.CapSize;
import com.example.demo.models.FlashSale;
import com.example.demo.models.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlashSaleQueueServiceTests {
    @Test
    void queueWaitAndAdmissionLeaveStockAndCartUntouched() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T12:00:00Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        CapItem item = new CapItem("cap", "Cap", new BigDecimal("5.00"), 2, CapSize.ONE_SIZE);
        User user = new User("user", "Customer", "customer@example.com", "hash");
        user.getCart().add(item, 1);
        LocalDateTime start = LocalDateTime.ofInstant(now.get(), ZoneOffset.UTC);
        FlashSale sale = new FlashSale(List.of(item), BigDecimal.TEN, start, start.plusMinutes(5), 10);

        try (FlashSaleQueueService queue = new FlashSaleQueueService(sale, 10, clock)) {
            var checkout = queue.awaitTurn(user);
            now.updateAndGet(instant -> instant.plusSeconds(2));
            assertThrows(TimeoutException.class, () -> checkout.get(1200, TimeUnit.MILLISECONDS));
            assertEquals(2, item.getStockQuantity());
            assertEquals(1, user.getCart().getItems().size());

            now.updateAndGet(instant -> instant.plusSeconds(1));
            assertNull(checkout.get(3, TimeUnit.SECONDS));
            assertEquals(2, item.getStockQuantity());
            assertEquals(1, user.getCart().getItems().size());
        }
    }
}
