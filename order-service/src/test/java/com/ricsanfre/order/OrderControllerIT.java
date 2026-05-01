package com.ricsanfre.order;

import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.order.api.model.CreateOrderRequest;
import com.ricsanfre.order.api.model.OrderItemRequest;
import com.ricsanfre.order.api.model.UpdateOrderStatusRequest;
import com.ricsanfre.order.kafka.OrderEventPublisher;
import com.ricsanfre.order.service.UserIdResolverService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests for {@link com.ricsanfre.order.controller.OrderController}.
 *
 * <p>Uses a real PostgreSQL container via Testcontainers + {@code @ServiceConnection}.
 * {@code UserIdResolverService} is mocked to avoid requiring a live user-service.
 * {@code OrderEventPublisher} is mocked to avoid requiring a live Kafka broker.
 * JWT verification is bypassed with {@code @MockitoBean JwtDecoder}.
 *
 * <p>Tests are ordered to simulate a realistic order lifecycle: create → read → update status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // Prevents Spring Boot from fetching the JWK Set URI at context startup.
    @MockitoBean
    JwtDecoder jwtDecoder;

    // Mock user-service resolution — no live user-service available in tests.
    @MockitoBean
    UserIdResolverService userIdResolverService;

    // Mock Kafka publisher — no live Kafka broker in this test.
    @MockitoBean
    OrderEventPublisher orderEventPublisher;

    // Shared across ordered tests
    static UUID createdOrderId;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUB = "test-sub-1";

    // ── JWT helpers ───────────────────────────────────────────────────────────

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor userJwt() {
        return jwt()
                .jwt(j -> j
                        .subject(SUB)
                        .claim("scope", "orders:read orders:write"))
                .authorities(
                        new SimpleGrantedAuthority("SCOPE_orders:read"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor readOnlyJwt() {
        return jwt()
                .jwt(j -> j.subject(SUB).claim("scope", "orders:read"))
                .authorities(new SimpleGrantedAuthority("SCOPE_orders:read"));
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createOrder_validRequest_returns201AndPersists() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        doNothing().when(orderEventPublisher).publishOrderCreated(any(com.ricsanfre.order.kafka.OrderCreatedEvent.class));

        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder()
                                .productId("60a7c2f8e4b0e1234567890a")
                                .quantity(2)
                                .unitPrice(29.99)
                                .build(),
                        OrderItemRequest.builder()
                                .productId("60a7c2f8e4b0e1234567890b")
                                .quantity(1)
                                .unitPrice(9.99)
                                .build()
                ))
                .build();

        String responseBody = mockMvc.perform(post("/api/v1/orders")
                        .with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(69.97))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // Store order ID for subsequent tests
        createdOrderId = UUID.fromString(
                objectMapper.readTree(responseBody).get("id").asText());
    }

    @Test
    @Order(2)
    void getOrderById_existingOrder_returns200() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);

        mockMvc.perform(get("/api/v1/orders/{id}", createdOrderId)
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdOrderId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    @Order(3)
    void getOrdersByUserId_existingOrders_returns200() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);

        mockMvc.perform(get("/api/v1/orders/user/{userId}", USER_ID)
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].userId").value(USER_ID.toString()));
    }

    @Test
    @Order(4)
    void updateOrderStatus_toConfirmed_returns200() throws Exception {
        UpdateOrderStatusRequest request = UpdateOrderStatusRequest.builder()
                .status(com.ricsanfre.order.api.model.OrderStatus.CONFIRMED)
                .build();

        mockMvc.perform(put("/api/v1/orders/{id}/status", createdOrderId)
                        .with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @Order(5)
    void getOrderById_nonExistent_returns404() throws Exception {
        UUID unknownId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/orders/{id}", unknownId)
                        .with(readOnlyJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(6)
    void getOrderById_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", createdOrderId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(7)
    void createOrder_emptyItems_returns400() throws Exception {
        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of())
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(8)
    void createOrder_readScopeOnly_returns403() throws Exception {
        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder().productId("prod-1").quantity(1).unitPrice(5.0).build()
                ))
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .with(readOnlyJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());
    }

    // ── Mockito helper ────────────────────────────────────────────────────────

    private static <T> T any(Class<T> clazz) {
        return org.mockito.ArgumentMatchers.any(clazz);
    }
}
