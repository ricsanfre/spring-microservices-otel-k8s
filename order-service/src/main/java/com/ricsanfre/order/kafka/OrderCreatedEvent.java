package com.ricsanfre.order.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published to Kafka topic {@code order.created.v1} whenever a new order is placed.
 *
 * <p>Consumers (e.g. notification-service) use this event to trigger downstream workflows such as
 * sending order confirmation emails or push notifications.
 *
 * @param orderId     internal UUID of the newly created order
 * @param userId      internal UUID of the user who placed the order (ADR-004: never the IAM sub)
 * @param totalAmount computed sum of (unitPrice × quantity) for all line items
 * @param itemCount   number of distinct line items in the order
 * @param createdAt   timestamp when the order was persisted
 */
public record OrderCreatedEvent(
        UUID orderId,
        UUID userId,
        double totalAmount,
        int itemCount,
        Instant createdAt) {
}
