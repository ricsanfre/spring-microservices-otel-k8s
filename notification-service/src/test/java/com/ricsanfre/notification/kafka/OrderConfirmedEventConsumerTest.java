package com.ricsanfre.notification.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class OrderConfirmedEventConsumerTest {

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private OrderConfirmedEventConsumer consumer;

    @Test
    void onOrderConfirmed_logsEventWithoutThrowing() {
        OrderConfirmedEvent event = new OrderConfirmedEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                149.98,
                2,
                Instant.now());

        assertThatCode(() -> consumer.onOrderConfirmed(event))
                .doesNotThrowAnyException();
    }
}
