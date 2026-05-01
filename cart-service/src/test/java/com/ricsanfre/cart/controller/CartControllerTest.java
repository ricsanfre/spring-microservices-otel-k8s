package com.ricsanfre.cart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ricsanfre.cart.api.model.CartItemRequest;
import com.ricsanfre.cart.api.model.CartItemResponse;
import com.ricsanfre.cart.api.model.CartResponse;
import com.ricsanfre.cart.client.OrderServiceClient;
import com.ricsanfre.cart.service.CartService;
import com.ricsanfre.cart.service.UserIdResolverService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for CartController.
 *
 * <p>We exclude the OAuth2 auto-configurations that create their own {@code SecurityFilterChain}
 * (they fail in a slice context where {@code HttpSecurity} is not yet available) and provide a
 * minimal test-local security configuration instead. Scope enforcement is tested at the
 * {@code authorizeHttpRequests} URL level so that {@code @EnableMethodSecurity} is not needed —
 * avoiding the CGLIB proxy / handler-mapping conflict present in {@code @WebMvcTest} in Spring Boot 4.
 */
@WebMvcTest(
        value = CartController.class,
        excludeAutoConfiguration = {
                OAuth2ClientWebSecurityAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class
        })
@Import({CartControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class CartControllerTest {

    // ── Test-local security config ────────────────────────────────────────────

    /**
     * Minimal security configuration for the slice test.
     * Uses URL-based scope enforcement (no {@code @EnableMethodSecurity}) to avoid
     * the CGLIB proxy conflict with {@code RequestMappingHandlerMapping} in Spring Boot 4.
     */
    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/cart").hasAuthority("SCOPE_cart:read")
                            .requestMatchers(HttpMethod.POST, "/api/v1/cart/checkout").hasAuthority("SCOPE_cart:read")
                            .requestMatchers(HttpMethod.PUT, "/api/v1/cart/items/**").hasAuthority("SCOPE_cart:write")
                            .requestMatchers(HttpMethod.DELETE, "/api/v1/cart/items/**").hasAuthority("SCOPE_cart:write")
                            .requestMatchers(HttpMethod.DELETE, "/api/v1/cart").hasAuthority("SCOPE_cart:write")
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                    .build();
        }
    }

    // ── MockMvc & mocks ───────────────────────────────────────────────────────

    @Autowired
    MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    CartService cartService;

    @MockitoBean
    UserIdResolverService userIdResolverService;

    // Prevents Spring Boot from fetching the JWK Set URI at context startup.
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SUB = "keycloak-sub-123";

    // ── JWT helpers ──────────────────────────────────────────────────────────

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor readJwt() {
        return jwt().jwt(j -> j.subject(SUB).claim("scope", "cart:read"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor writeJwt() {
        return jwt().jwt(j -> j.subject(SUB).claim("scope", "cart:read cart:write"));
    }

    private CartResponse emptyCartResponse() {
        return CartResponse.builder()
                .userId(USER_ID)
                .items(List.of())
                .totalItems(0)
                .grandTotal(0.0)
                .build();
    }

    private CartResponse cartWithItemResponse() {
        CartItemResponse item = CartItemResponse.builder()
                .productId("prod-1")
                .productName("Widget")
                .price(9.99)
                .quantity(2)
                .lineTotal(19.98)
                .build();
        return CartResponse.builder()
                .userId(USER_ID)
                .items(List.of(item))
                .totalItems(1)
                .grandTotal(19.98)
                .build();
    }

    // ── GET /api/v1/cart ─────────────────────────────────────────────────────

    @Test
    void getCart_withReadScope_returns200AndCart() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        when(cartService.getCart(USER_ID)).thenReturn(cartWithItemResponse());

        mockMvc.perform(get("/api/v1/cart").with(readJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.items[0].productId").value("prod-1"))
                .andExpect(jsonPath("$.grandTotal").value(19.98));
    }

    @Test
    void getCart_withNoToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCart_withWriteScopeOnly_returns403() throws Exception {
        // jwt with only cart:write but not cart:read
        mockMvc.perform(get("/api/v1/cart")
                        .with(jwt().jwt(j -> j.subject(SUB).claim("scope", "cart:write"))))
                .andExpect(status().isForbidden());
    }

    // ── PUT /api/v1/cart/items/{productId} ───────────────────────────────────

    @Test
    void upsertCartItem_withWriteScope_returns200() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        when(cartService.upsertItem(eq(USER_ID), eq("prod-1"), any(CartItemRequest.class)))
                .thenReturn(cartWithItemResponse());

        CartItemRequest request = CartItemRequest.builder()
                .productName("Widget")
                .price(9.99)
                .quantity(2)
                .build();

        mockMvc.perform(put("/api/v1/cart/items/prod-1")
                        .with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value("prod-1"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void upsertCartItem_withReadScopeOnly_returns403() throws Exception {
        CartItemRequest request = CartItemRequest.builder().quantity(1).build();

        mockMvc.perform(put("/api/v1/cart/items/prod-1")
                        .with(readJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /api/v1/cart/items/{productId} ────────────────────────────────

    @Test
    void removeCartItem_withWriteScope_returns200() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        when(cartService.removeItem(USER_ID, "prod-1")).thenReturn(emptyCartResponse());

        mockMvc.perform(delete("/api/v1/cart/items/prod-1").with(writeJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void removeCartItem_productNotFound_returns404() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        when(cartService.removeItem(USER_ID, "missing-prod"))
                .thenThrow(new ResourceNotFoundException("CartItem", "missing-prod"));

        mockMvc.perform(delete("/api/v1/cart/items/missing-prod").with(writeJwt()))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/v1/cart ──────────────────────────────────────────────────

    @Test
    void clearCart_withWriteScope_returns204() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        doNothing().when(cartService).clearCart(USER_ID);

        mockMvc.perform(delete("/api/v1/cart").with(writeJwt()))
                .andExpect(status().isNoContent());
    }

    @Test
    void clearCart_withNoToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/cart"))
                .andExpect(status().isUnauthorized());
    }

    // ── user-service unavailable ─────────────────────────────────────────────

    @Test
    void getCart_userServiceUnavailable_returns503() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB))
                .thenThrow(new IllegalStateException("User identity service is unavailable. Please retry in a moment."));

        mockMvc.perform(get("/api/v1/cart").with(readJwt()))
                .andExpect(status().isInternalServerError());
    }

    // ── POST /api/v1/cart/checkout ──────────────────────────────────────

    @Test
    void checkout_withReadScope_returns201() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);

        OrderServiceClient.OrderResponse fakeOrder = new OrderServiceClient.OrderResponse(
                java.util.UUID.randomUUID(), USER_ID, "PENDING", List.of(), 19.98, null, null);
        when(cartService.checkout(USER_ID)).thenReturn(fakeOrder);

        mockMvc.perform(post("/api/v1/cart/checkout").with(readJwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void checkout_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/cart/checkout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkout_writeOnlyScope_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/cart/checkout")
                        .with(jwt().jwt(j -> j.subject(SUB).claim("scope", "cart:write"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void checkout_emptyCart_returns409() throws Exception {
        when(userIdResolverService.resolveInternalId(SUB)).thenReturn(USER_ID);
        when(cartService.checkout(USER_ID))
                .thenThrow(new BusinessRuleException("Cart is empty for user " + USER_ID));

        mockMvc.perform(post("/api/v1/cart/checkout").with(readJwt()))
                .andExpect(status().isConflict());
    }
}
