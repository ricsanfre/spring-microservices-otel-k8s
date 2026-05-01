package com.ricsanfre.order.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes order domain events to Kafka.
 *
 * <p>The Kafka producer is configured with {@code JsonSerializer} for the value (see
 * {@code application.yaml}). The message key is the order UUID string, ensuring all events for a
 * given order land on the same partition.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    static final String TOPIC_ORDER_CREATED = "order.created.v1";
    static final String TOPIC_ORDER_CONFIRMED = "order.confirmed.v1";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreatedEvent orderId={} userId={}", event.orderId(), event.userId());
        kafkaTemplate.send(TOPIC_ORDER_CREATED, event.orderId().toString(), event);
    }

    public void publishOrderConfirmed(OrderConfirmedEvent event) {
        log.info("Publishing OrderConfirmedEvent orderId={} userId={}", event.orderId(), event.userId());
        kafkaTemplate.send(TOPIC_ORDER_CONFIRMED, event.orderId().toString(), event);
    }
}
