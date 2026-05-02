package com.ricsanfre.cart.client;

import org.springframework.security.oauth2.client.annotation.ClientRegistrationId;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * HTTP Interface for calling order-service to create a PENDING order
 * during checkout (Phase 1 of two-phase checkout).
 *
 * <p>Uses Client Credentials with scope {@code orders:write}.
 */
@ClientRegistrationId("order-service")
@HttpExchange("/api/v1")
public interface OrderServiceClient {

    @PostExchange("/orders")
    OrderResponse createOrder(@RequestBody CreateOrderRequest request);

    record CreateOrderRequest(List<OrderItemRequest> items) {}

    record OrderItemRequest(String productId, int quantity, double unitPrice) {}

    record OrderResponse(
            UUID id,
            UUID userId,
            String status,
            List<OrderItemResponse> items,
            double totalAmount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    record OrderItemResponse(
            UUID id,
            String productId,
            int quantity,
            double unitPrice) {}
}
