package com.ricsanfre.cart.kafka;

import com.ricsanfre.cart.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code order.confirmed.v1} events and clears the user's cart.
 *
 * <p>Cart clearing happens asynchronously after order confirmation so that the
 * checkout flow (cart-service → order-service) has no circular dependency.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderConfirmedEventConsumer {

    private final CartService cartService;

    @KafkaListener(topics = "order.confirmed.v1", groupId = "cart-service-group")
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        log.info("Received OrderConfirmedEvent orderId={} userId={} — clearing cart",
                event.orderId(), event.userId());
        cartService.clearCart(event.userId());
    }
}
