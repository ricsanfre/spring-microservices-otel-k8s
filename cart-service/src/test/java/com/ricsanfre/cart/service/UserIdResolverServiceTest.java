package com.ricsanfre.cart.service;

import com.ricsanfre.cart.client.UserServiceClient;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserIdResolverService}.
 *
 * <p>Note: Spring Cache (@Cacheable) and Resilience4j (@CircuitBreaker) are proxy-based
 * and do not activate in plain Mockito tests. These tests verify the core resolution
 * and fallback logic. Cache/circuit-breaker behaviour is covered by integration tests.
 */
@ExtendWith(MockitoExtension.class)
class UserIdResolverServiceTest {

    @Mock
    private UserServiceClient userServiceClient;

    @InjectMocks
    private UserIdResolverService userIdResolverService;

    private static final String IDP_SUBJECT = "keycloak-sub-abc123";
    private static final UUID INTERNAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    // ── resolveInternalId ────────────────────────────────────────────────────

    @Test
    void resolveInternalId_userExists_returnsInternalUuid() {
        when(userServiceClient.resolveUser(IDP_SUBJECT))
                .thenReturn(new UserServiceClient.UserResolveResponse(INTERNAL_ID));

        UUID result = userIdResolverService.resolveInternalId(IDP_SUBJECT);

        assertThat(result).isEqualTo(INTERNAL_ID);
    }

    @Test
    void resolveInternalId_userNotFound_throwsResourceNotFoundException() {
        when(userServiceClient.resolveUser(IDP_SUBJECT))
                .thenThrow(HttpClientErrorException.NotFound.class);

        assertThatThrownBy(() -> userIdResolverService.resolveInternalId(IDP_SUBJECT))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── resolveInternalIdFallback ─────────────────────────────────────────────

    @Test
    void fallback_throwsIllegalStateException() {
        RuntimeException cause = new RuntimeException("circuit open");

        // invoke the fallback method directly (it is package-private via reflection in tests)
        assertThatThrownBy(() -> invokePrivateFallback(IDP_SUBJECT, cause))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable")
                .hasCause(cause);
    }

    // ── evict ─────────────────────────────────────────────────────────────────

    @Test
    void evict_doesNotThrow() {
        // evict() only removes from cache (no-op in unit test, just verify no exception)
        userIdResolverService.evict(IDP_SUBJECT);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void invokePrivateFallback(String idpSubject, Throwable t) throws Throwable {
        var method = UserIdResolverService.class.getDeclaredMethod(
                "resolveInternalIdFallback", String.class, Throwable.class);
        method.setAccessible(true);
        try {
            method.invoke(userIdResolverService, idpSubject, t);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
