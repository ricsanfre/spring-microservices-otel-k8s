package com.ricsanfre.cart.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ricsanfre.cart.domain.Cart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class CartRepository {

    static final Duration CART_TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "cart:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<Cart> findByUserId(String userId) {
        String json = redisTemplate.opsForValue().get(key(userId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, Cart.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialise cart for user {}", userId, e);
            return Optional.empty();
        }
    }

    public Cart save(Cart cart) {
        cart.setExpiresAt(Instant.now().plus(CART_TTL));
        try {
            String json = objectMapper.writeValueAsString(cart);
            redisTemplate.opsForValue().set(key(cart.getUserId()), json, CART_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise cart for user " + cart.getUserId(), e);
        }
        return cart;
    }

    public void deleteByUserId(String userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
