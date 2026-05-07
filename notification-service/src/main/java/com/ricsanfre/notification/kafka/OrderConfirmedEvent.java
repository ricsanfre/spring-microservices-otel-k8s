package com.ricsanfre.notification.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirror of the {@code OrderConfirmedEvent} produced by order-service on topic
 * {@code order.confirmed.v1}.
 *
 * <p>Declared locally to decouple notification-service from order-service's package
 * structure. Type-header-based deserialization is disabled in {@code application.yaml}
 * via {@code spring.json.use.type.headers: false}.
 */
public record OrderConfirmedEvent(
        UUID orderId,
        UUID userId,
        double totalAmount,
        int itemCount,
        Instant confirmedAt) {}
