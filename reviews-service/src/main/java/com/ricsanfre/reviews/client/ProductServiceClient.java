package com.ricsanfre.reviews.client;

import org.springframework.security.oauth2.client.annotation.ClientRegistrationId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * HTTP Interface for verifying product existence via product-service.
 * Uses Client Credentials with scope {@code products:read}.
 */
@ClientRegistrationId("product-service")
@HttpExchange("/api/v1")
public interface ProductServiceClient {

    @GetExchange("/products/{id}")
    ProductResponse getProduct(@PathVariable("id") String productId);

    record ProductResponse(String id, String name) {}
}
