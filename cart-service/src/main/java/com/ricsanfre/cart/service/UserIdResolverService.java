package com.ricsanfre.cart.service;

import com.ricsanfre.cart.client.UserServiceClient;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;

/**
 * Resolves Keycloak JWT {@code sub} → internal {@code users.id} UUID.
 *
 * <p>Per ADR-004 (IAM Portability via user-service Isolation), the cart-service must never
 * use the IAM {@code sub} directly as a storage key. This service performs a lazy lookup via
 * {@code user-service} on first encounter for a given {@code sub} and caches the result locally
 * with a 10-minute TTL (configured in {@code application.yaml}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserIdResolverService {

    private final UserServiceClient userServiceClient;

    /**
     * Returns the internal {@code users.id} UUID for the given IAM {@code idpSubject}.
     *
     * <p>The result is cached in the {@code userIdBySubject} Caffeine cache (TTL: 10 min).
     * On a 404 response from {@code user-service}, the entry is evicted and a
     * {@link ResourceNotFoundException} is thrown.
     *
     * @param idpSubject the JWT {@code sub} claim (Keycloak UUID string)
     * @return the internal user UUID
     * @throws ResourceNotFoundException if no user exists for the given subject
     */
    @Cacheable(value = "userIdBySubject", key = "#idpSubject")
    @CircuitBreaker(name = "user-service", fallbackMethod = "resolveInternalIdFallback")
    public UUID resolveInternalId(String idpSubject) {
        log.debug("Cache miss — resolving idp_subject={} via user-service", idpSubject);
        try {
            UUID id = userServiceClient.resolveUser(idpSubject).id();
            log.debug("Resolved idp_subject={} → internalId={}", idpSubject, id);
            return id;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("User", idpSubject);
        }
    }

    /**
     * Evicts the cached mapping for the given subject, e.g. when a 404 is received from
     * user-service on a subsequent call (user deleted).
     */
    @CacheEvict(value = "userIdBySubject", key = "#idpSubject")
    public void evict(String idpSubject) {
        log.debug("Evicted cached userId for idp_subject={}", idpSubject);
    }

    @SuppressWarnings("unused")
    private UUID resolveInternalIdFallback(String idpSubject, Throwable t) {
        log.warn("user-service circuit open — cannot resolve idp_subject={}: {}", idpSubject, t.getMessage());
        throw new IllegalStateException(
                "User identity service is unavailable. Please retry in a moment.", t);
    }
}
