package com.ricsanfre.product.controller;

import tools.jackson.databind.ObjectMapper;
import com.ricsanfre.common.exception.GlobalExceptionHandler;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.product.api.model.CreateProductRequest;
import com.ricsanfre.product.api.model.ProductPage;
import com.ricsanfre.product.api.model.ProductResponse;
import com.ricsanfre.product.api.model.StockReserveItem;
import com.ricsanfre.product.api.model.StockReserveRequest;
import com.ricsanfre.product.api.model.UpdateProductRequest;
import com.ricsanfre.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for ProductController.
 *
 * <p>Excludes OAuth2 auto-configurations that fail in a slice context and provides a
 * minimal inline security configuration. Scope enforcement is tested at the URL level
 * (no {@code @EnableMethodSecurity}) to avoid the CGLIB proxy conflict in Spring Boot 4 @WebMvcTest.
 */
@WebMvcTest(
        value = ProductController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@Import({ProductControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class ProductControllerTest {

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
                            .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**").permitAll()
                            .anyRequest().hasAuthority("SCOPE_products:write"))
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                    .build();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> null;  // dummy — jwt() post-processor bypasses verification
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ProductService productService;

    // ── GET /api/v1/products (public) ─────────────────────────────────────────

    @Test
    void listProducts_noAuth_returns200() throws Exception {
        when(productService.list(isNull(), any())).thenReturn(
                ProductPage.builder().content(List.of()).page(0).size(20).totalElements(0L).totalPages(0).build());

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listProducts_withCategoryParam_delegatesToService() throws Exception {
        when(productService.list(eq("books"), any())).thenReturn(
                ProductPage.builder().content(List.of()).page(0).size(20).totalElements(0L).totalPages(0).build());

        mockMvc.perform(get("/api/v1/products").param("category", "books"))
                .andExpect(status().isOk());

        verify(productService).list(eq("books"), any());
    }

    // ── GET /api/v1/products/{id} (public) ───────────────────────────────────

    @Test
    void getProductById_existingId_returns200() throws Exception {
        var response = ProductResponse.builder().id("abc123").name("Widget").price(9.99).build();
        when(productService.getById("abc123")).thenReturn(response);

        mockMvc.perform(get("/api/v1/products/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("abc123"))
                .andExpect(jsonPath("$.name").value("Widget"));
    }

    @Test
    void getProductById_nonExistentId_returns404() throws Exception {
        when(productService.getById("missing")).thenThrow(new ResourceNotFoundException("Product", "missing"));

        mockMvc.perform(get("/api/v1/products/missing"))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/products (requires write scope) ──────────────────────────

    @Test
    void createProduct_noAuth_returns401() throws Exception {
        var request = CreateProductRequest.builder().name("New").price(9.99).build();

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createProduct_withWriteScope_returns201() throws Exception {
        var request = CreateProductRequest.builder().name("New Widget").price(9.99).category("toys").stockQty(5).build();
        var created = ProductResponse.builder().id("new-id").name("New Widget").build();
        when(productService.create(any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/products")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_products:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("new-id"))
                .andExpect(jsonPath("$.name").value("New Widget"));
    }

    // ── PUT /api/v1/products/{id} ─────────────────────────────────────────────

    @Test
    void updateProduct_noAuth_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/products/abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProductRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProduct_withWriteScope_returns200() throws Exception {
        var request = UpdateProductRequest.builder().name("Updated").build();
        var updated = ProductResponse.builder().id("abc123").name("Updated").build();
        when(productService.update(eq("abc123"), any())).thenReturn(updated);

        mockMvc.perform(put("/api/v1/products/abc123")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_products:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void updateProduct_notFound_returns404() throws Exception {
        when(productService.update(eq("missing"), any()))
                .thenThrow(new ResourceNotFoundException("Product", "missing"));

        mockMvc.perform(put("/api/v1/products/missing")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_products:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProductRequest())))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/v1/products/{id} ──────────────────────────────────────────

    @Test
    void deleteProduct_noAuth_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/products/abc123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteProduct_withWriteScope_returns204() throws Exception {
        doNothing().when(productService).delete("abc123");

        mockMvc.perform(delete("/api/v1/products/abc123")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_products:write"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteProduct_notFound_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Product", "missing")).when(productService).delete("missing");

        mockMvc.perform(delete("/api/v1/products/missing")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_products:write"))))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/products/stock/reserve ──────────────────────────────────

    @Test
    void reserveStock_withWriteScope_returns200() throws Exception {
        doNothing().when(productService).reserveStock(any());

        StockReserveRequest request = StockReserveRequest.builder()
                .items(List.of(
                        StockReserveItem.builder().productId("prod-1").quantity(3).build()))
                .build();

        mockMvc.perform(post("/api/v1/products/stock/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_products:write"))))
                .andExpect(status().isOk());
    }

    @Test
    void reserveStock_noAuth_returns401() throws Exception {
        StockReserveRequest request = StockReserveRequest.builder()
                .items(List.of(
                        StockReserveItem.builder().productId("prod-1").quantity(3).build()))
                .build();

        mockMvc.perform(post("/api/v1/products/stock/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reserveStock_insufficientStock_returns409() throws Exception {
        doThrow(new BusinessRuleException("Insufficient stock for product prod-1"))
                .when(productService).reserveStock(any());

        StockReserveRequest request = StockReserveRequest.builder()
                .items(List.of(
                        StockReserveItem.builder().productId("prod-1").quantity(99).build()))
                .build();

        mockMvc.perform(post("/api/v1/products/stock/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_products:write"))))
                .andExpect(status().isConflict());
    }
}
