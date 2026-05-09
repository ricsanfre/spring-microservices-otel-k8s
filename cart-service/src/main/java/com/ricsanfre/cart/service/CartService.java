package com.ricsanfre.cart.service;

import com.ricsanfre.cart.api.model.CartItemRequest;
import com.ricsanfre.cart.api.model.CartItemResponse;
import com.ricsanfre.cart.api.model.CartResponse;
import com.ricsanfre.cart.client.OrderServiceClient;
import com.ricsanfre.cart.domain.Cart;
import com.ricsanfre.cart.repository.CartRepository;
import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private final CartRepository cartRepository;
    private final OrderServiceClient orderServiceClient;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public CartResponse getCart(UUID userId) {
        log.debug("getCart userId={}", userId);
        Cart cart = cartRepository.findByUserId(userId.toString())
                .orElseGet(() -> emptyCart(userId));
        return toResponse(cart);
    }

    public CartResponse upsertItem(UUID userId, String productId, CartItemRequest request) {
        log.info("Upsert item productId={} qty={} for user={}", productId, request.getQuantity(), userId);
        Cart cart = cartRepository.findByUserId(userId.toString())
                .orElseGet(() -> emptyCart(userId));

        List<Cart.CartItem> items = new ArrayList<>(cart.getItems());

        if (request.getQuantity() != null && request.getQuantity() == 0) {
            items.removeIf(i -> i.getProductId().equals(productId));
        } else {
            Optional<Cart.CartItem> existing = items.stream()
                    .filter(i -> i.getProductId().equals(productId))
                    .findFirst();
            if (existing.isPresent()) {
                existing.get().setQuantity(request.getQuantity() != null ? request.getQuantity() : existing.get().getQuantity());
                if (request.getPrice() != null)       existing.get().setPrice(request.getPrice());
                if (request.getProductName() != null) existing.get().setProductName(request.getProductName());
            } else {
                items.add(Cart.CartItem.builder()
                        .productId(productId)
                        .productName(request.getProductName())
                        .price(request.getPrice() != null ? request.getPrice() : 0.0)
                        .quantity(request.getQuantity() != null ? request.getQuantity() : 1)
                        .build());
            }
        }

        cart.setItems(items);
        CartResponse response = toResponse(cartRepository.save(cart));
        Counter.builder("cart.items.added").register(meterRegistry).increment();
        log.info("Cart item upserted userId={} productId={} itemCount={}", userId, productId, response.getTotalItems());
        return response;
    }

    public CartResponse removeItem(UUID userId, String productId) {
        log.info("Remove item productId={} for user={}", productId, userId);
        Cart cart = cartRepository.findByUserId(userId.toString())
                .orElseThrow(() -> new ResourceNotFoundException("Cart", userId.toString()));

        boolean removed = cart.getItems().removeIf(i -> i.getProductId().equals(productId));
        if (!removed) {
            throw new ResourceNotFoundException("CartItem", productId);
        }
        CartResponse response = toResponse(cartRepository.save(cart));
        log.info("Cart item removed userId={} productId={} itemCount={}", userId, productId, response.getTotalItems());
        return response;
    }

    public void clearCart(UUID userId) {
        log.info("Clear cart for user={}", userId);
        cartRepository.deleteByUserId(userId.toString());
    }

    public OrderServiceClient.OrderResponse checkout(UUID userId) {
        log.debug("checkout userId={}", userId);
        Span span = tracer.nextSpan().name("cart.checkout").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("user.id", userId.toString());
            MDC.put("user.id", userId.toString());
            try {
                Counter.builder("cart.checkout.initiated").register(meterRegistry).increment();

                Cart cart = cartRepository.findByUserId(userId.toString())
                        .orElseThrow(() -> new BusinessRuleException("Cart is empty for user " + userId));
                if (cart.getItems() == null || cart.getItems().isEmpty()) {
                    throw new BusinessRuleException("Cart is empty for user " + userId);
                }
                List<OrderServiceClient.OrderItemRequest> items = cart.getItems().stream()
                        .map(i -> new OrderServiceClient.OrderItemRequest(
                                i.getProductId(), i.getQuantity(), i.getPrice()))
                        .toList();
                span.tag("cart.item.count", String.valueOf(items.size()));

                log.info("Initiating checkout for userId={} with {} items", userId, items.size());
                try {
                    OrderServiceClient.OrderResponse order = orderServiceClient.createOrder(
                            new OrderServiceClient.CreateOrderRequest(userId, items));
                    span.tag("order.id", order.id().toString());
                    Counter.builder("cart.checkout.confirmed").tag("result", "success").register(meterRegistry).increment();
                    log.info("Checkout completed userId={} orderId={}", userId, order.id());
                    return order;
                } catch (Exception e) {
                    span.error(e);
                    Counter.builder("cart.checkout.confirmed").tag("result", "failure").register(meterRegistry).increment();
                    throw e;
                }
            } finally {
                MDC.remove("user.id");
            }
        } finally {
            span.end();
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private Cart emptyCart(UUID userId) {
        return Cart.builder()
                .userId(userId.toString())
                .items(new ArrayList<>())
                .build();
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(i -> CartItemResponse.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .price(i.getPrice())
                        .quantity(i.getQuantity())
                        .lineTotal(i.getLineTotal())
                        .build())
                .toList();

        return CartResponse.builder()
                .userId(UUID.fromString(cart.getUserId()))
                .items(items)
                .totalItems(cart.getTotalItems())
                .grandTotal(cart.getGrandTotal())
                .expiresAt(cart.getExpiresAt() != null
                        ? OffsetDateTime.ofInstant(cart.getExpiresAt(), ZoneOffset.UTC) : null)
                .build();
    }
}

