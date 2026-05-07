package com.ricsanfre.reviews.controller;

import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.common.exception.GlobalExceptionHandler;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.reviews.api.model.CreateReviewRequest;
import com.ricsanfre.reviews.api.model.ReviewResponse;
import com.ricsanfre.reviews.service.ReviewService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for ReviewController.
 *
 * <p>Excludes OAuth2 auto-configurations and provides a minimal test-local security config.
 * URL-based scope enforcement avoids the CGLIB proxy conflict with @EnableMethodSecurity
 * in Spring Boot 4's @WebMvcTest.
 */
@WebMvcTest(
        value = ReviewController.class,
        excludeAutoConfiguration = {
                OAuth2ClientWebSecurityAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class
        })
@Import({ReviewControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class ReviewControllerTest {

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/reviews/**").hasAuthority("SCOPE_reviews:read")
                            .requestMatchers(HttpMethod.POST, "/api/v1/reviews").hasAuthority("SCOPE_reviews:write")
                            .requestMatchers(HttpMethod.DELETE, "/api/v1/reviews/**").hasAuthority("SCOPE_reviews:write")
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                    .build();
        }
    }

    @Autowired
    MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    ReviewService reviewService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String PRODUCT_ID = "mongo-product-id-abc";
    private static final String REVIEW_ID = "mongo-review-id-xyz";
    private static final String SUB = "keycloak-sub-123";

    // ── JWT helpers ──────────────────────────────────────────────────────────

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor readJwt() {
        return jwt().jwt(j -> j.subject(SUB).claim("scope", "reviews:read"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor writeJwt() {
        return jwt().jwt(j -> j.subject(SUB).claim("scope", "reviews:read reviews:write"));
    }

    private ReviewResponse sampleReview() {
        return ReviewResponse.builder()
                .id(REVIEW_ID)
                .productId(PRODUCT_ID)
                .orderId(ORDER_ID)
                .userId(USER_ID)
                .rating(5)
                .comment("Excellent!")
                .build();
    }

    // ── GET /api/v1/reviews/product/{productId} ───────────────────────────────

    @Test
    void getReviewsByProduct_withReadScope_returns200() throws Exception {
        when(reviewService.getByProduct(PRODUCT_ID)).thenReturn(List.of(sampleReview()));

        mockMvc.perform(get("/api/v1/reviews/product/{productId}", PRODUCT_ID).with(readJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(REVIEW_ID))
                .andExpect(jsonPath("$[0].rating").value(5));
    }

    @Test
    void getReviewsByProduct_withNoToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/product/{productId}", PRODUCT_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/reviews ─────────────────────────────────────────────────

    @Test
    void createReview_withWriteScope_returns201() throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID)
                .orderId(ORDER_ID)
                .rating(5)
                .comment("Excellent!")
                .build();
        when(reviewService.createReview(eq(SUB), any(CreateReviewRequest.class)))
                .thenReturn(sampleReview());

        mockMvc.perform(post("/api/v1/reviews")
                        .with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(REVIEW_ID));
    }

    @Test
    void createReview_withReadScopeOnly_returns403() throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();

        mockMvc.perform(post("/api/v1/reviews")
                        .with(readJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createReview_businessRuleViolation_returns409() throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();
        when(reviewService.createReview(eq(SUB), any(CreateReviewRequest.class)))
                .thenThrow(new BusinessRuleException("Order must be in DELIVERED status"));

        mockMvc.perform(post("/api/v1/reviews")
                        .with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void createReview_productNotFound_returns404() throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();
        when(reviewService.createReview(eq(SUB), any(CreateReviewRequest.class)))
                .thenThrow(new ResourceNotFoundException("Product", PRODUCT_ID));

        mockMvc.perform(post("/api/v1/reviews")
                        .with(writeJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/v1/reviews/{id} ──────────────────────────────────────────

    @Test
    void deleteReview_withWriteScope_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/{id}", REVIEW_ID).with(writeJwt()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReview_withReadScopeOnly_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/{id}", REVIEW_ID).with(readJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteReview_notOwner_returns409() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessRuleException("not the owner"))
                .when(reviewService).deleteReview(REVIEW_ID, SUB);

        mockMvc.perform(delete("/api/v1/reviews/{id}", REVIEW_ID).with(writeJwt()))
                .andExpect(status().isConflict());
    }
}
