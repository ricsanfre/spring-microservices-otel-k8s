package com.ricsanfre.order.service;

import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.common.security.JwtUtils;
import com.ricsanfre.order.api.model.CreateOrderRequest;
import com.ricsanfre.order.api.model.OrderItemResponse;
import com.ricsanfre.order.api.model.OrderResponse;
import com.ricsanfre.order.api.model.UpdateOrderStatusRequest;
import com.ricsanfre.order.domain.Order;
import com.ricsanfre.order.domain.OrderItem;
import com.ricsanfre.order.domain.OrderStatus;
import com.ricsanfre.order.kafka.OrderCreatedEvent;
import com.ricsanfre.order.kafka.OrderEventPublisher;
import com.ricsanfre.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    // ── Create ────────────────────────────────────────────────────────────────

    public OrderResponse createOrder(CreateOrderRequest request, Authentication auth) {
        String idpSubject = JwtUtils.getSubject(auth);
        UUID userId = userIdResolverService.resolveInternalId(idpSubject);

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
        log.info("Created order id={} for userId={} with {} items, total={}",
                saved.getId(), saved.getUserId(), saved.getItems().size(), saved.getTotalAmount());

        eventPublisher.publishOrderCreated(new OrderCreatedEvent(
                saved.getId(),
                saved.getUserId(),
                saved.getTotalAmount(),
                saved.getItems().size(),
                saved.getCreatedAt()));

        return toResponse(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrderResponse findById(UUID id, Authentication auth) {
        Order order = findOrderById(id);
        checkOwnerOrServiceAccount(order.getUserId(), auth);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findByUserId(UUID userId, Authentication auth) {
        checkOwnerOrServiceAccount(userId, auth);
        return orderRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public OrderResponse updateStatus(UUID id, UpdateOrderStatusRequest request) {
        Order order = findOrderById(id);
        OrderStatus newStatus = OrderStatus.valueOf(request.getStatus().getValue());
        log.info("Updating order id={} status: {} → {}", id, order.getStatus(), newStatus);
        order.setStatus(newStatus);
        return toResponse(orderRepository.save(order));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Order findOrderById(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
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
