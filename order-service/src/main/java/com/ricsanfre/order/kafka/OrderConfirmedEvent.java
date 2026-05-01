package com.ricsanfre.order.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published to {@code order.confirmed.v1} when an order transitions
 * PENDING → CONFIRMED (stock reserved successfully).
 */
public record OrderConfirmedEvent(
        UUID orderId,
        UUID userId,
        double totalAmount,
        int itemCount,
        Instant confirmedAt) {}
