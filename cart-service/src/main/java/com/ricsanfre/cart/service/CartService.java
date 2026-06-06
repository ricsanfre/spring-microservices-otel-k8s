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
        log.info("operation=cart.upsert_item outcome=start userId={} productId={} quantity={}",
            userId, productId, request.getQuantity());
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
        log.info("operation=cart.upsert_item outcome=success userId={} productId={} itemCount={}",
            userId, productId, response.getTotalItems());
        return response;
    }

    public CartResponse removeItem(UUID userId, String productId) {
        log.info("operation=cart.remove_item outcome=start userId={} productId={}", userId, productId);
        Cart cart = cartRepository.findByUserId(userId.toString())
                .orElseThrow(() -> new ResourceNotFoundException("Cart", userId.toString()));

        boolean removed = cart.getItems().removeIf(i -> i.getProductId().equals(productId));
        if (!removed) {
            throw new ResourceNotFoundException("CartItem", productId);
        }
        CartResponse response = toResponse(cartRepository.save(cart));
        log.info("operation=cart.remove_item outcome=success userId={} productId={} itemCount={}",
                userId, productId, response.getTotalItems());
        return response;
    }

    public void clearCart(UUID userId) {
        log.info("operation=cart.clear outcome=start userId={}", userId);
        cartRepository.deleteByUserId(userId.toString());
        log.info("operation=cart.clear outcome=success userId={}", userId);
    }

    public OrderServiceClient.OrderResponse checkout(UUID userId) {
        log.debug("checkout userId={}", userId);
        Span checkoutSpan = tracer.nextSpan().name("cart.checkout").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(checkoutSpan)) {
            checkoutSpan.tag("user.id", userId.toString());
            MDC.put("user.id", userId.toString());
            try {
                Counter.builder("cart.checkout.initiated").register(meterRegistry).increment();

                List<OrderServiceClient.OrderItemRequest> items;
                Span validateSpan = tracer.nextSpan().name("checkout.validate").start();
                try (Tracer.SpanInScope validateScope = tracer.withSpan(validateSpan)) {
                    Cart cart = cartRepository.findByUserId(userId.toString())
                            .orElseThrow(() -> new BusinessRuleException("Cart is empty for user " + userId));
                    if (cart.getItems() == null || cart.getItems().isEmpty()) {
                        throw new BusinessRuleException("Cart is empty for user " + userId);
                    }
                    items = cart.getItems().stream()
                            .map(i -> new OrderServiceClient.OrderItemRequest(
                                    i.getProductId(), i.getQuantity(), i.getPrice()))
                            .toList();
                    validateSpan.tag("cart.item.count", String.valueOf(items.size()));
                } catch (RuntimeException e) {
                    validateSpan.error(e);
                    throw e;
                } finally {
                    validateSpan.end();
                }

                log.info("operation=cart.checkout outcome=start userId={} itemCount={}", userId, items.size());

                OrderServiceClient.OrderResponse order;
                Span reserveSpan = tracer.nextSpan().name("checkout.reserve").start();
                try (Tracer.SpanInScope reserveScope = tracer.withSpan(reserveSpan)) {
                    order = orderServiceClient.createOrder(
                            new OrderServiceClient.CreateOrderRequest(userId, items));
                    reserveSpan.tag("order.id", order.id().toString());
                } catch (Exception e) {
                    reserveSpan.error(e);
                    throw e;
                } finally {
                    reserveSpan.end();
                }

                Span confirmSpan = tracer.nextSpan().name("checkout.confirm").start();
                try (Tracer.SpanInScope confirmScope = tracer.withSpan(confirmSpan)) {
                    confirmSpan.tag("order.id", order.id().toString());
                    confirmSpan.tag("result", "success");
                    Counter.builder("cart.checkout.confirmed").tag("result", "success").register(meterRegistry).increment();
                    log.info("operation=cart.checkout outcome=success userId={} orderId={}", userId, order.id());
                    return order;
                } catch (Exception e) {
                    confirmSpan.error(e);
                    confirmSpan.tag("result", "failure");
                    throw e;
                } finally {
                    confirmSpan.end();
                }
            } catch (Exception e) {
                checkoutSpan.error(e);
                Counter.builder("cart.checkout.confirmed").tag("result", "failure").register(meterRegistry).increment();
                log.warn("operation=cart.checkout outcome=failure userId={} reason={}", userId, e.toString());
                throw e;
            } finally {
                MDC.remove("user.id");
            }
        } finally {
            checkoutSpan.end();
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

