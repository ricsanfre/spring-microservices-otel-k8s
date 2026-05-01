package com.ricsanfre.order.controller;

import com.ricsanfre.order.api.model.CreateOrderRequest;
import com.ricsanfre.order.api.model.OrderItemRequest;
import com.ricsanfre.order.api.model.OrderItemResponse;
import com.ricsanfre.order.api.model.OrderResponse;
import com.ricsanfre.order.api.model.UpdateOrderStatusRequest;
import com.ricsanfre.order.service.OrderService;
import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.common.exception.GlobalExceptionHandler;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link OrderController}.
 *
 * <p>Excludes the OAuth2 auto-configurations that expect a fully assembled Spring context and
 * provides a minimal test-local {@link SecurityFilterChain}. Scope enforcement is exercised via
 * {@code authorizeHttpRequests} URL rules (no {@code @EnableMethodSecurity}) to avoid the
 * CGLIB proxy / handler-mapping conflict in {@code @WebMvcTest} in Spring Boot 4.
 */
@WebMvcTest(
        value = OrderController.class,
        excludeAutoConfiguration = {
                OAuth2ClientWebSecurityAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class
        })
@Import({OrderControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class OrderControllerTest {

    // ── Test-local security config ────────────────────────────────────────────

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAuthority("SCOPE_orders:write")
                            .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/confirm").hasAuthority("SCOPE_orders:write")
                            .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAuthority("SCOPE_orders:read")
                            .requestMatchers(HttpMethod.PUT, "/api/v1/orders/*/status").hasAuthority("SCOPE_orders:write")
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                    .build();
        }
    }

    // ── MockMvc & mocks ───────────────────────────────────────────────────────

    @Autowired
    MockMvc mockMvc;

    // Use new ObjectMapper directly — JacksonAutoConfiguration may not register
    // the auto-configured bean in this slice context.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    OrderService orderService;

    // Prevents Spring Boot from fetching the JWK Set URI at context startup.
    @MockitoBean
    JwtDecoder jwtDecoder;

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private static final UUID ORDER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static OrderResponse sampleResponse() {
        return OrderResponse.builder()
                .id(ORDER_ID)
                .userId(USER_ID)
                .status(com.ricsanfre.order.api.model.OrderStatus.PENDING)
                .totalAmount(19.98)
                .items(List.of(OrderItemResponse.builder()
                        .id(UUID.randomUUID())
                        .productId("prod-1")
                        .quantity(2)
                        .unitPrice(9.99)
                        .build()))
                .createdAt(OffsetDateTime.now())
                .build();
    }

    // ── POST /api/v1/orders ───────────────────────────────────────────────────

    @Test
    void createOrder_validRequestWithWriteScope_returns201() throws Exception {
        when(orderService.createOrder(any(), any())).thenReturn(sampleResponse());

        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder().productId("prod-1").quantity(2).unitPrice(9.99).build()
                ))
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:write"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createOrder_noAuthentication_returns401() throws Exception {
        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder().productId("prod-1").quantity(1).unitPrice(5.0).build()
                ))
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createOrder_readScopeOnly_returns403() throws Exception {
        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder().productId("prod-1").quantity(1).unitPrice(5.0).build()
                ))
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:read"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createOrder_emptyItems_returns400() throws Exception {
        // Empty items list violates @Size(min=1) on the OpenAPI-generated model
        CreateOrderRequest request = CreateOrderRequest.builder()
                .items(List.of())
                .build();

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:write"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/v1/orders/{id} ───────────────────────────────────────────────

    @Test
    void getOrderById_validRequestWithReadScope_returns200() throws Exception {
        when(orderService.findById(eq(ORDER_ID), any())).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/orders/{id}", ORDER_ID)
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:read"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
    }

    @Test
    void getOrderById_noAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", ORDER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOrderById_orderNotFound_returns404() throws Exception {
        when(orderService.findById(eq(ORDER_ID), any()))
                .thenThrow(new ResourceNotFoundException("Order", ORDER_ID));

        mockMvc.perform(get("/api/v1/orders/{id}", ORDER_ID)
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:read"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:read"))))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/v1/orders/user/{userId} ──────────────────────────────────────

    @Test
    void getOrdersByUserId_validRequest_returns200() throws Exception {
        when(orderService.findByUserId(eq(USER_ID), any())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/orders/user/{userId}", USER_ID)
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:read"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ORDER_ID.toString()));
    }

    // ── PUT /api/v1/orders/{id}/status ────────────────────────────────────────

    @Test
    void updateOrderStatus_validRequest_returns200() throws Exception {
        OrderResponse updated = OrderResponse.builder()
                .id(ORDER_ID)
                .userId(USER_ID)
                .status(com.ricsanfre.order.api.model.OrderStatus.CONFIRMED)
                .totalAmount(19.98)
                .items(List.of())
                .createdAt(OffsetDateTime.now())
                .build();
        when(orderService.updateStatus(eq(ORDER_ID), any())).thenReturn(updated);

        UpdateOrderStatusRequest request = UpdateOrderStatusRequest.builder()
                .status(com.ricsanfre.order.api.model.OrderStatus.CONFIRMED)
                .build();

        mockMvc.perform(put("/api/v1/orders/{id}/status", ORDER_ID)
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:write"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    // ── POST /api/v1/orders/{id}/confirm ────────────────────────────────

    @Test
    void confirmOrder_withWriteScope_returns200() throws Exception {
        OrderResponse confirmed = OrderResponse.builder()
                .id(ORDER_ID)
                .userId(USER_ID)
                .status(com.ricsanfre.order.api.model.OrderStatus.CONFIRMED)
                .totalAmount(19.98)
                .items(List.of())
                .createdAt(OffsetDateTime.now())
                .build();
        when(orderService.confirmOrder(eq(ORDER_ID), any())).thenReturn(confirmed);

        mockMvc.perform(post("/api/v1/orders/{id}/confirm", ORDER_ID)
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:write"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:write"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirmOrder_noAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{id}/confirm", ORDER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void confirmOrder_readScopeOnly_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{id}/confirm", ORDER_ID)
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:read"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmOrder_orderNotFound_returns404() throws Exception {
        when(orderService.confirmOrder(eq(ORDER_ID), any()))
                .thenThrow(new ResourceNotFoundException("Order", ORDER_ID.toString()));

        mockMvc.perform(post("/api/v1/orders/{id}/confirm", ORDER_ID)
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:write"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:write"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirmOrder_insufficientStock_returns409() throws Exception {
        when(orderService.confirmOrder(eq(ORDER_ID), any()))
                .thenThrow(new BusinessRuleException("Insufficient stock for one or more items"));

        mockMvc.perform(post("/api/v1/orders/{id}/confirm", ORDER_ID)
                        .with(jwt().jwt(j -> j.subject("sub-1").claim("scope", "orders:write"))
                                .authorities(org.springframework.security.core.authority.AuthorityUtils
                                        .createAuthorityList("SCOPE_orders:write"))))
                .andExpect(status().isConflict());
    }
}
