package com.ricsanfre.order.service;

import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.order.api.model.CreateOrderRequest;
import com.ricsanfre.order.api.model.OrderItemRequest;
import com.ricsanfre.order.api.model.OrderResponse;
import com.ricsanfre.order.api.model.UpdateOrderStatusRequest;
import com.ricsanfre.order.domain.Order;
import com.ricsanfre.order.domain.OrderItem;
import com.ricsanfre.order.domain.OrderStatus;
import com.ricsanfre.order.kafka.OrderCreatedEvent;
import com.ricsanfre.order.kafka.OrderEventPublisher;
import com.ricsanfre.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserIdResolverService userIdResolverService;

    @Mock
    private OrderEventPublisher eventPublisher;

    @InjectMocks
    private OrderService orderService;

    private static final String SUB = "keycloak-sub-owner";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private Authentication ownerAuth;
    private Authentication otherAuth;

    @BeforeEach
    void setUp() {
        ownerAuth = jwtAuth(SUB);
        otherAuth = jwtAuth("other-sub");
    }

    // ── createOrder ───────────────────────────────────────────────────────────

    @Test
    void createOrder_happyPath_savesOrderAndPublishesEvent() {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(ORDER_ID);
            o.setCreatedAt(Instant.now());
            return o;
        });

        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder().productId("prod-1").quantity(2).unitPrice(9.99).build()
                ))
                .build();

        OrderResponse response = orderService.createOrder(request, ownerAuth);

        assertThat(response.getId()).isEqualTo(ORDER_ID);
        assertThat(response.getUserId()).isEqualTo(USER_ID);
        assertThat(response.getStatus()).isEqualTo(com.ricsanfre.order.api.model.OrderStatus.PENDING);
        assertThat(response.getTotalAmount()).isEqualTo(19.98);
        assertThat(response.getItems()).hasSize(1);
        verify(orderRepository).save(any(Order.class));
        verify(eventPublisher).publishOrderCreated(any(OrderCreatedEvent.class));
    }

    @Test
    void createOrder_userNotFound_throwsResourceNotFoundException() {
        when(userIdResolverService.resolveInternalId(SUB))
                .thenThrow(new ResourceNotFoundException("User", SUB));

        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder().productId("prod-1").quantity(1).unitPrice(5.0).build()
                ))
                .build();

        assertThatThrownBy(() -> orderService.createOrder(request, ownerAuth))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishOrderCreated(any());
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    void findById_ownerCalling_returnsOrder() {
        Order order = buildOrder(ORDER_ID, USER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);

        OrderResponse response = orderService.findById(ORDER_ID, ownerAuth);

        assertThat(response.getId()).isEqualTo(ORDER_ID);
        assertThat(response.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void findById_differentUserCalling_throwsAccessDeniedException() {
        Order order = buildOrder(ORDER_ID, USER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(userIdResolverService.resolveInternalId("other-sub")).thenReturn(OTHER_USER_ID);

        assertThatThrownBy(() -> orderService.findById(ORDER_ID, otherAuth))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findById_serviceAccountCalling_allowsAccess() {
        Order order = buildOrder(ORDER_ID, USER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        // Service account sub does not resolve to a user
        when(userIdResolverService.resolveInternalId("service-sub"))
                .thenThrow(new ResourceNotFoundException("User", "service-sub"));

        Authentication serviceAuth = jwtAuth("service-sub");
        OrderResponse response = orderService.findById(ORDER_ID, serviceAuth);

        assertThat(response.getId()).isEqualTo(ORDER_ID);
    }

    @Test
    void findById_orderNotFound_throwsResourceNotFoundException() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(ORDER_ID, ownerAuth))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── findByUserId ──────────────────────────────────────────────────────────

    @Test
    void findByUserId_ownerCalling_returnsOrderList() {
        Order order = buildOrder(ORDER_ID, USER_ID);
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        when(orderRepository.findByUserId(USER_ID)).thenReturn(List.of(order));

        List<OrderResponse> responses = orderService.findByUserId(USER_ID, ownerAuth);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void findByUserId_emptyList_returnsEmptyList() {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        when(orderRepository.findByUserId(USER_ID)).thenReturn(Collections.emptyList());

        List<OrderResponse> responses = orderService.findByUserId(USER_ID, ownerAuth);

        assertThat(responses).isEmpty();
    }

    // ── updateStatus ──────────────────────────────────────────────────────────

    @Test
    void updateStatus_validTransition_returnsUpdatedOrder() {
        Order order = buildOrder(ORDER_ID, USER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        UpdateOrderStatusRequest request = UpdateOrderStatusRequest.builder()
                .status(com.ricsanfre.order.api.model.OrderStatus.CONFIRMED)
                .build();

        OrderResponse response = orderService.updateStatus(ORDER_ID, request);

        assertThat(response.getStatus()).isEqualTo(com.ricsanfre.order.api.model.OrderStatus.CONFIRMED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void updateStatus_orderNotFound_throwsResourceNotFoundException() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        UpdateOrderStatusRequest request = UpdateOrderStatusRequest.builder()
                .status(com.ricsanfre.order.api.model.OrderStatus.CONFIRMED)
                .build();

        assertThatThrownBy(() -> orderService.updateStatus(ORDER_ID, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Authentication jwtAuth(String sub) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(sub)
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private static Order buildOrder(UUID orderId, UUID userId) {
        OrderItem item = OrderItem.builder()
                .id(UUID.randomUUID())
                .productId("prod-1")
                .quantity(2)
                .unitPrice(9.99)
                .build();
        Order order = Order.builder()
                .id(orderId)
                .userId(userId)
                .status(OrderStatus.PENDING)
                .totalAmount(19.98)
                .items(new java.util.ArrayList<>(List.of(item)))
                .createdAt(Instant.now())
                .build();
        item.setOrder(order);
        return order;
    }
}
