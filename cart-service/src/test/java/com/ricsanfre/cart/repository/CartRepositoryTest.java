package com.ricsanfre.cart.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ricsanfre.cart.domain.Cart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private CartRepository cartRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String KEY = "cart:" + USER_ID;

    @BeforeEach
    void setUp() {
        cartRepository = new CartRepository(redisTemplate, objectMapper);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Cart buildCart() {
        Cart.CartItem item = Cart.CartItem.builder()
                .productId("prod-1")
                .productName("Widget")
                .price(10.0)
                .quantity(2)
                .build();
        return Cart.builder()
                .userId(USER_ID)
                .items(new ArrayList<>(List.of(item)))
                .expiresAt(Instant.ofEpochSecond(1_700_000_000))
                .build();
    }

    // ── findByUserId ─────────────────────────────────────────────────────────

    @Test
    void findByUserId_keyPresent_deserializesAndReturnsCart() throws Exception {
        stubOpsForValue();
        Cart stored = buildCart();
        String json = objectMapper.writeValueAsString(stored);
        when(valueOps.get(KEY)).thenReturn(json);

        Optional<Cart> result = cartRepository.findByUserId(USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(USER_ID);
        assertThat(result.get().getItems()).hasSize(1);
        assertThat(result.get().getItems().get(0).getProductId()).isEqualTo("prod-1");
    }

    @Test
    void findByUserId_keyAbsent_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY)).thenReturn(null);

        Optional<Cart> result = cartRepository.findByUserId(USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findByUserId_malformedJson_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY)).thenReturn("not-valid-json{{{");

        Optional<Cart> result = cartRepository.findByUserId(USER_ID);

        assertThat(result).isEmpty();
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test
    void save_writesJsonToRedisWithTtl() throws Exception {
        stubOpsForValue();
        Cart cart = buildCart();

        Cart saved = cartRepository.save(cart);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(valueOps).set(keyCaptor.capture(), jsonCaptor.capture(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo(KEY);
        assertThat(ttlCaptor.getValue()).isEqualTo(CartRepository.CART_TTL);

        // JSON must be parseable back to a Cart with same content
        Cart roundTripped = objectMapper.readValue(jsonCaptor.getValue(), Cart.class);
        assertThat(roundTripped.getUserId()).isEqualTo(USER_ID);
        assertThat(roundTripped.getItems().get(0).getProductId()).isEqualTo("prod-1");
    }

    @Test
    void save_setsExpiresAtBeforeWriting() {
        stubOpsForValue();
        Cart cart = buildCart();
        Instant before = Instant.now();

        cartRepository.save(cart);

        assertThat(cart.getExpiresAt()).isAfterOrEqualTo(before);
    }

    @Test
    void save_returnsTheSameCartInstance() {
        stubOpsForValue();
        Cart cart = buildCart();

        Cart result = cartRepository.save(cart);

        assertThat(result).isSameAs(cart);
    }

    // ── deleteByUserId ───────────────────────────────────────────────────────

    @Test
    void deleteByUserId_deletesCorrectKey() {
        cartRepository.deleteByUserId(USER_ID);

        verify(redisTemplate).delete(KEY);
    }
}
