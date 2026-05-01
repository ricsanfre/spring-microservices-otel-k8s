package com.ricsanfre.cart.kafka;

import com.ricsanfre.cart.service.CartService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderConfirmedEventConsumerTest {

    @Mock
    private CartService cartService;

    @InjectMocks
    private OrderConfirmedEventConsumer consumer;

    @Test
    void onOrderConfirmed_callsClearCartWithUserId() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrderConfirmedEvent event = new OrderConfirmedEvent(orderId, userId, 29.99, 2, Instant.now());

        consumer.onOrderConfirmed(event);

        verify(cartService).clearCart(userId);
    }
}
