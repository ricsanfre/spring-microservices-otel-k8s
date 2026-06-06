package com.ricsanfre.notification.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code order.confirmed.v1} events and dispatches notifications.
 *
 * <p>Current implementation logs the event. In production this would send
 * an email, push notification, or write to a messaging pipeline.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderConfirmedEventConsumer {

    private final MeterRegistry meterRegistry;

    @KafkaListener(topics = "order.confirmed.v1", groupId = "notification-group")
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        log.info(
                "operation=notification.consume_order_confirmed outcome=start orderId={} userId={} totalAmount={} itemCount={} confirmedAt={}",
                event.orderId(),
                event.userId(),
                event.totalAmount(),
                event.itemCount(),
                event.confirmedAt());

        // TODO: replace with real notification dispatch (email, push, webhook)
        sendNotification(event);
    }

    private void sendNotification(OrderConfirmedEvent event) {
        try {
            log.info("operation=notification.send outcome=success type=ORDER_CONFIRMATION orderId={} userId={} totalAmount={}",
                event.orderId(), event.userId(), event.totalAmount());
            Counter.builder("notifications.sent")
                    .tag("type", "ORDER_CONFIRMATION")
                    .tag("status", "success")
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.warn("operation=notification.send outcome=failure type=ORDER_CONFIRMATION orderId={} userId={} reason={}",
                event.orderId(), event.userId(), e.toString());
            Counter.builder("notifications.sent")
                    .tag("type", "ORDER_CONFIRMATION")
                    .tag("status", "failure")
                    .register(meterRegistry)
                    .increment();
            throw e;
        }
    }
}
