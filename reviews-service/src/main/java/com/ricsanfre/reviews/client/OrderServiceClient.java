package com.ricsanfre.reviews.client;

import org.springframework.security.oauth2.client.annotation.ClientRegistrationId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * HTTP Interface for fetching order details from order-service.
 * Used to validate that the reviewer has a DELIVERED order containing the product.
 * Uses Client Credentials with scope {@code orders:read}.
 */
@ClientRegistrationId("order-service")
@HttpExchange("/api/v1")
public interface OrderServiceClient {

    @GetExchange("/orders/{id}")
    OrderResponse getOrder(@PathVariable("id") UUID orderId);

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
