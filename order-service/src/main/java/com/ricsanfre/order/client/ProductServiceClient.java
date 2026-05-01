package com.ricsanfre.order.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * HTTP Interface for calling product-service to atomically reserve stock
 * during order confirmation (two-phase checkout, Phase 2).
 *
 * <p>Uses Client Credentials with scope {@code products:write}.
 * On insufficient stock, product-service responds 409 which is mapped to
 * {@link com.ricsanfre.common.exception.BusinessRuleException} by the caller.
 */
@HttpExchange("/api/v1")
public interface ProductServiceClient {

    @PostExchange("/products/stock/reserve")
    void reserveStock(@RequestBody StockReserveRequest request);

    record StockReserveRequest(List<StockReserveItem> items) {}

    record StockReserveItem(String productId, int quantity) {}
}
