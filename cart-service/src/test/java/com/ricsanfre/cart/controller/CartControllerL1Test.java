package com.ricsanfre.cart.controller;

import com.ricsanfre.cart.api.model.CartItemRequest;
import com.ricsanfre.cart.api.model.CartItemResponse;
import com.ricsanfre.cart.api.model.CartResponse;
import com.ricsanfre.cart.client.UserServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Layer 1 (TestContainers) integration tests for CartController.
 *
 * <p>Tests the full HTTP → Controller → Service → Repository stack against a real
 * Valkey (Redis-compatible) container. External dependencies are replaced:
 * <ul>
 *   <li>{@link JwtDecoder} — replaced with a Mockito mock that returns a fixed test JWT</li>
 *   <li>{@link UserServiceClient} — replaced with a Mockito mock that returns a fixed
 *       internal user UUID (avoids OAuth2 client-credentials token fetch)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("L1")
@ActiveProfiles("l1test")
class CartControllerL1Test {

    // ── Container ─────────────────────────────────────────────────────────────

    @Container
    static GenericContainer<?> valkey =
            new GenericContainer<>("valkey/valkey:8")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    // ── Mocked external dependencies ─────────────────────────────────────────

    /** Replaces auto-configured NimbusJwtDecoder; prevents Keycloak OIDC discovery at startup. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** Replaces the Spring @HttpExchange proxy; prevents OAuth2 client-credentials token fetch. */
    @MockitoBean
    private UserServiceClient userServiceClient;

    // ── Injected beans ────────────────────────────────────────────────────────

    @LocalServerPort
    private int port;

    private RestClient http;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    // ── Test constants ────────────────────────────────────────────────────────

    private static final UUID TEST_USER_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String TEST_SUBJECT  = "keycloak-sub-test-123";
    private static final String BEARER_TOKEN  = "l1-test-bearer-token";
    private static final String PRODUCT_ALPHA = "prod-alpha-001";
    private static final String PRODUCT_BETA  = "prod-beta-002";

    // ── Setup / teardown ─────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        // Wipe all Redis keys between tests to ensure isolation
        redisConnectionFactory.getConnection().serverCommands().flushDb();

        // Build RestClient pointing to the random port; disable error-on-4xx/5xx so we can assert status codes
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        // Configure JWT mock — returns a valid Jwt for our test token
        Jwt testJwt = Jwt.withTokenValue(BEARER_TOKEN)
                .header("alg", "none")
                .subject(TEST_SUBJECT)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("scope", "cart:read cart:write")
                .build();
        when(jwtDecoder.decode(BEARER_TOKEN)).thenReturn(testJwt);

        // Configure user-service mock — resolves any subject to the test user UUID
        when(userServiceClient.resolveUser(anyString()))
                .thenReturn(new UserServiceClient.UserResolveResponse(TEST_USER_ID));
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void getCart_returnsEmptyCart_whenNothingAdded() {
        // When
        CartResponse body = http.get().uri("/api/v1/cart")
                .retrieve()
                .body(CartResponse.class);

        // Then
        assertThat(body).isNotNull();
        assertThat(body.getItems()).isEmpty();
        assertThat(body.getTotalItems()).isZero();
        assertThat(body.getGrandTotal()).isZero();
        assertThat(body.getUserId()).isEqualTo(TEST_USER_ID);
    }

    @Test
    void upsertCartItem_addsNewItemAndPersistsInRedis() {
        // Given
        CartItemRequest request = CartItemRequest.builder()
                .productName("Alpha Widget")
                .price(9.99)
                .quantity(2)
                .build();

        // When
        CartResponse upsertBody = http.put().uri("/api/v1/cart/items/" + PRODUCT_ALPHA)
                .body(request)
                .retrieve()
                .body(CartResponse.class);

        // Then — upsert response is correct
        assertThat(upsertBody).isNotNull();
        assertThat(upsertBody.getItems()).hasSize(1);
        CartItemResponse item = upsertBody.getItems().get(0);
        assertThat(item.getProductId()).isEqualTo(PRODUCT_ALPHA);
        assertThat(item.getProductName()).isEqualTo("Alpha Widget");
        assertThat(item.getPrice()).isEqualTo(9.99);
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getLineTotal()).isEqualTo(19.98);
        assertThat(upsertBody.getTotalItems()).isEqualTo(1);
        assertThat(upsertBody.getGrandTotal()).isEqualTo(19.98);

        // And — data persists: a subsequent GET returns the same cart
        CartResponse getBody = http.get().uri("/api/v1/cart")
                .retrieve()
                .body(CartResponse.class);
        assertThat(getBody).isNotNull();
        assertThat(getBody.getItems()).hasSize(1);
        assertThat(getBody.getItems().get(0).getProductId()).isEqualTo(PRODUCT_ALPHA);
    }

    @Test
    void upsertCartItem_updatesExistingItem_whenProductAlreadyInCart() {
        // Given — add item
        http.put().uri("/api/v1/cart/items/" + PRODUCT_ALPHA)
                .body(CartItemRequest.builder().productName("Alpha").price(5.0).quantity(1).build())
                .retrieve()
                .toBodilessEntity();

        // When — update quantity and price
        CartResponse body = http.put().uri("/api/v1/cart/items/" + PRODUCT_ALPHA)
                .body(CartItemRequest.builder().price(7.50).quantity(3).build())
                .retrieve()
                .body(CartResponse.class);

        // Then — only one item with updated values
        assertThat(body).isNotNull();
        assertThat(body.getItems()).hasSize(1);
        CartItemResponse item = body.getItems().get(0);
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getPrice()).isEqualTo(7.50);
        assertThat(item.getLineTotal()).isEqualTo(22.50);
    }

    @Test
    void removeCartItem_removesItemFromCart() {
        // Given — add two items
        http.put().uri("/api/v1/cart/items/" + PRODUCT_ALPHA)
                .body(CartItemRequest.builder().productName("Alpha").price(1.0).quantity(1).build())
                .retrieve().toBodilessEntity();
        http.put().uri("/api/v1/cart/items/" + PRODUCT_BETA)
                .body(CartItemRequest.builder().productName("Beta").price(2.0).quantity(1).build())
                .retrieve().toBodilessEntity();

        // When — remove PRODUCT_ALPHA
        CartResponse body = http.delete().uri("/api/v1/cart/items/" + PRODUCT_ALPHA)
                .retrieve()
                .body(CartResponse.class);

        // Then — only PRODUCT_BETA remains
        assertThat(body).isNotNull();
        assertThat(body.getItems()).hasSize(1);
        assertThat(body.getItems().get(0).getProductId()).isEqualTo(PRODUCT_BETA);
    }

    @Test
    void removeCartItem_returns404_whenProductNotInCart() {
        // When / Then — RestClient throws HttpClientErrorException.NotFound on 4xx
        assertThatThrownBy(() ->
                http.delete().uri("/api/v1/cart/items/non-existent-product")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void clearCart_removesAllItemsAndReturns204() {
        // Given — add items
        http.put().uri("/api/v1/cart/items/" + PRODUCT_ALPHA)
                .body(CartItemRequest.builder().productName("Alpha").price(1.0).quantity(1).build())
                .retrieve().toBodilessEntity();
        http.put().uri("/api/v1/cart/items/" + PRODUCT_BETA)
                .body(CartItemRequest.builder().productName("Beta").price(2.0).quantity(1).build())
                .retrieve().toBodilessEntity();

        // When
        http.delete().uri("/api/v1/cart").retrieve().toBodilessEntity();

        // Then — cart is empty on subsequent GET
        CartResponse getBody = http.get().uri("/api/v1/cart")
                .retrieve()
                .body(CartResponse.class);
        assertThat(getBody).isNotNull();
        assertThat(getBody.getItems()).isEmpty();
    }

    @Test
    void getCart_returns401_whenNoAuthorizationHeader() {
        // RestClient without auth header
        RestClient noAuthHttp = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        // When / Then
        assertThatThrownBy(() ->
                noAuthHttp.get().uri("/api/v1/cart").retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void upsertCartItem_returns401_whenNoAuthorizationHeader() {
        // RestClient without auth header
        RestClient noAuthHttp = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        // When / Then
        assertThatThrownBy(() ->
                noAuthHttp.put().uri("/api/v1/cart/items/" + PRODUCT_ALPHA)
                        .body(CartItemRequest.builder().productName("X").price(1.0).quantity(1).build())
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    // (RestClient default headers are set in setUp())
}

