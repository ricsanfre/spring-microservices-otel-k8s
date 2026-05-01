package com.ricsanfre.cart.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed from {@code order.confirmed.v1} — triggers cart clearing after a successful
 * order confirmation (two-phase checkout, Phase 2 completion).
 */
public record OrderConfirmedEvent(
        UUID orderId,
        UUID userId,
        double totalAmount,
        int itemCount,
        Instant confirmedAt) {}
