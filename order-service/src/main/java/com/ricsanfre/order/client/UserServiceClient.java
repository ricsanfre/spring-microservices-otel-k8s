package com.ricsanfre.order.client;

import org.springframework.security.oauth2.client.annotation.ClientRegistrationId;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.UUID;

/**
 * HTTP Interface for calling user-service to resolve an IAM sub → internal user UUID.
 * Used for per-service lazy resolution as defined in ADR-004.
 */
@ClientRegistrationId("user-service")
@HttpExchange("/api/v1")
public interface UserServiceClient {

    /**
     * Resolves a Keycloak JWT {@code sub} claim to the internal {@code users.id} UUID.
     *
     * @param idpSubject the JWT {@code sub} value
     * @return the internal user UUID wrapped in a record
     */
    @GetExchange("/users/resolve")
    UserResolveResponse resolveUser(@RequestParam("idp_subject") String idpSubject);

    record UserResolveResponse(UUID id) {}
}
