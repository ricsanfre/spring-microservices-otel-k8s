package com.ricsanfre.reviews.service;

import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.reviews.client.UserServiceClient;
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
 * <p>Per ADR-004: other services never use the IAM sub as a storage key. This service
 * performs a lazy lookup via {@code user-service} on first encounter and caches the result
 * with a configurable TTL (default 10 min).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserIdResolverService {

    private final UserServiceClient userServiceClient;

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
