package com.ricsanfre.order.service;

import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.common.security.JwtUtils;
import com.ricsanfre.order.api.model.CreateOrderRequest;
import com.ricsanfre.order.api.model.OrderItemResponse;
import com.ricsanfre.order.api.model.OrderResponse;
import com.ricsanfre.order.api.model.UpdateOrderStatusRequest;
import com.ricsanfre.order.client.ProductServiceClient;
import com.ricsanfre.order.domain.Order;
import com.ricsanfre.order.domain.OrderItem;
import com.ricsanfre.order.domain.OrderStatus;
import com.ricsanfre.order.kafka.OrderConfirmedEvent;
import com.ricsanfre.order.kafka.OrderEventPublisher;
import com.ricsanfre.order.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserIdResolverService userIdResolverService;
    private final OrderEventPublisher eventPublisher;
    private final ProductServiceClient productServiceClient;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    // ── Create ────────────────────────────────────────────────────────────────

    public OrderResponse createOrder(CreateOrderRequest request, Authentication auth) {
        log.debug("operation=order.create outcome=start itemCount={}", request.getItems().size());
        UUID userId = resolveUserId(request, auth);

        io.micrometer.tracing.Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) currentSpan.tag("user.id", userId.toString());
        MDC.put("user.id", userId.toString());
        try {
            List<OrderItem> items = request.getItems().stream()
                    .map(i -> OrderItem.builder()
                            .productId(i.getProductId())
                            .quantity(i.getQuantity())
                            .unitPrice(i.getUnitPrice())
                            .build())
                    .toList();

            double totalAmount = items.stream()
                    .mapToDouble(i -> i.getUnitPrice() * i.getQuantity())
                    .sum();

            Order order = Order.builder()
                    .userId(userId)
                    .status(OrderStatus.PENDING)
                    .totalAmount(totalAmount)
                    .build();

            // Establish bidirectional reference before persisting
            items.forEach(item -> item.setOrder(order));
            order.setItems(items);

            Order saved = orderRepository.save(order);

            Counter.builder("orders.created")
                    .tag("status", saved.getStatus().name())
                    .register(meterRegistry)
                    .increment();
            DistributionSummary.builder("order.value.amount")
                    .baseUnit("currency_units")
                    .register(meterRegistry)
                    .record(saved.getTotalAmount());

                log.info("operation=order.create outcome=success orderId={} userId={} itemCount={} totalAmount={} status={}",
                    saved.getId(), saved.getUserId(), saved.getItems().size(), saved.getTotalAmount(), saved.getStatus());
            return toResponse(saved);
        } finally {
            MDC.remove("user.id");
        }
    }

    // ── Confirm ───────────────────────────────────────────────────────────────

    public OrderResponse confirmOrder(UUID id, Authentication auth) {
        log.debug("operation=order.confirm outcome=start orderId={}", id);
        Order order = findOrderById(id);

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessRuleException(
                    "Order " + id + " cannot be confirmed: status is " + order.getStatus());
        }

        checkOwnerOrServiceAccount(order.getUserId(), auth);

        List<ProductServiceClient.StockReserveItem> items = order.getItems().stream()
                .map(i -> new ProductServiceClient.StockReserveItem(i.getProductId(), i.getQuantity()))
                .toList();

        Span confirmSpan = tracer.nextSpan().name("order.confirm").start();
        try (Tracer.SpanInScope confirmScope = tracer.withSpan(confirmSpan)) {
            confirmSpan.tag("order.id", id.toString());
            confirmSpan.tag("user.id", order.getUserId().toString());

            Span reserveSpan = tracer.nextSpan().name("order.confirm.reserve_stock").start();
            try (Tracer.SpanInScope reserveScope = tracer.withSpan(reserveSpan)) {
                reserveSpan.tag("order.id", id.toString());
                productServiceClient.reserveStock(new ProductServiceClient.StockReserveRequest(items));
                reserveSpan.tag("result", "success");
            } catch (HttpClientErrorException.Conflict ex) {
                reserveSpan.error(ex);
                reserveSpan.tag("result", "failure");
                throw new BusinessRuleException("Insufficient stock for one or more items in order " + id);
            } catch (NoFallbackAvailableException ex) {
                reserveSpan.error(ex);
                reserveSpan.tag("result", "failure");
                throw new BusinessRuleException(
                        "Product service is temporarily unavailable — please retry confirming order " + id);
            } finally {
                reserveSpan.end();
            }

            Span persistSpan = tracer.nextSpan().name("order.confirm.persist_status").start();
            Order saved;
            try (Tracer.SpanInScope persistScope = tracer.withSpan(persistSpan)) {
                persistSpan.tag("order.id", id.toString());
                persistSpan.tag("from_status", OrderStatus.PENDING.name());
                persistSpan.tag("to_status", OrderStatus.CONFIRMED.name());
                order.setStatus(OrderStatus.CONFIRMED);
                saved = orderRepository.save(order);
            } catch (RuntimeException e) {
                persistSpan.error(e);
                throw e;
            } finally {
                persistSpan.end();
            }

            Counter.builder("orders.status.changed")
                    .tag("from", OrderStatus.PENDING.name())
                    .tag("to", OrderStatus.CONFIRMED.name())
                    .register(meterRegistry)
                    .increment();

            Span publishSpan = tracer.nextSpan().name("order.confirm.publish_event").start();
            try (Tracer.SpanInScope publishScope = tracer.withSpan(publishSpan)) {
                publishSpan.tag("order.id", saved.getId().toString());
                eventPublisher.publishOrderConfirmed(new OrderConfirmedEvent(
                        saved.getId(),
                        saved.getUserId(),
                        saved.getTotalAmount(),
                        saved.getItems().size(),
                        Instant.now()));
                publishSpan.tag("result", "success");
            } catch (RuntimeException e) {
                publishSpan.error(e);
                publishSpan.tag("result", "failure");
                throw e;
            } finally {
                publishSpan.end();
            }

            log.info("operation=order.confirm outcome=success orderId={} userId={} status={}",
                    saved.getId(), saved.getUserId(), saved.getStatus());

            return toResponse(saved);
        } catch (RuntimeException e) {
            confirmSpan.error(e);
            log.warn("operation=order.confirm outcome=failure orderId={} reason={}", id, e.toString());
            throw e;
        } finally {
            confirmSpan.end();
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrderResponse findById(UUID id, Authentication auth) {
        log.debug("findById orderId={}", id);
        Order order = findOrderById(id);
        checkOwnerOrServiceAccount(order.getUserId(), auth);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findByUserId(UUID userId, Authentication auth) {
        log.debug("findByUserId userId={}", userId);
        checkOwnerOrServiceAccount(userId, auth);
        return orderRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public OrderResponse updateStatus(UUID id, UpdateOrderStatusRequest request) {
        log.debug("operation=order.update_status outcome=start orderId={}", id);
        Order order = findOrderById(id);
        OrderStatus previousStatus = order.getStatus();
        OrderStatus newStatus = OrderStatus.valueOf(request.getStatus().getValue());
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        Counter.builder("orders.status.changed")
                .tag("from", previousStatus.name())
                .tag("to", newStatus.name())
                .register(meterRegistry)
                .increment();
        log.info("operation=order.update_status outcome=success orderId={} from={} to={}", id, previousStatus, newStatus);
        return toResponse(saved);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Order findOrderById(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    /**
     * Resolves the internal user UUID for the order being created.
     *
     * <p>For end-user JWT requests: extracts the {@code sub} claim and resolves via user-service
     * (per ADR-004). For M2M service-account requests (e.g. cart-service using Client Credentials),
     * the sub does not map to a real user, so the caller must supply {@code userId} in the request body.
     */
    private UUID resolveUserId(CreateOrderRequest request, Authentication auth) {
        String idpSubject = JwtUtils.getSubject(auth);
        try {
            return userIdResolverService.resolveInternalId(idpSubject);
        } catch (ResourceNotFoundException e) {
            // Service account token — sub is a machine identity, not a real user.
            // The caller (e.g. cart-service) must provide the userId in the request body.
            UUID userId = request.getUserId();
            if (userId == null) {
                throw new BusinessRuleException(
                        "M2M caller must supply userId in the request body (sub " + idpSubject + " is not a user)");
            }
            log.debug("M2M caller sub={} resolved userId from request body: {}", idpSubject, userId);
            return userId;
        }
    }

    /**
     * Enforces that the caller is either the order owner (their internal userId matches) or a
     * service account (a JWT sub that does not resolve to any user record — e.g. reviews-service
     * performing order validation via Client Credentials).
     */
    private void checkOwnerOrServiceAccount(UUID ownerId, Authentication auth) {
        String sub = JwtUtils.getSubject(auth);
        try {
            UUID callerId = userIdResolverService.resolveInternalId(sub);
            if (!callerId.equals(ownerId)) {
                throw new AccessDeniedException("Access denied: caller is not the order owner");
            }
        } catch (ResourceNotFoundException e) {
            // Sub does not resolve to a user record — treat as a service account and allow access.
            log.debug("Subject {} not found as user; treating as service account, allowing access", sub);
        }
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(i -> OrderItemResponse.builder()
                        .id(i.getId())
                        .productId(i.getProductId())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .build())
                .toList();

        return OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .status(com.ricsanfre.order.api.model.OrderStatus.fromValue(order.getStatus().name()))
                .totalAmount(order.getTotalAmount())
                .items(itemResponses)
                .createdAt(order.getCreatedAt() != null
                        ? OffsetDateTime.ofInstant(order.getCreatedAt(), ZoneOffset.UTC) : null)
                .updatedAt(order.getUpdatedAt() != null
                        ? OffsetDateTime.ofInstant(order.getUpdatedAt(), ZoneOffset.UTC) : null)
                .build();
    }
}
