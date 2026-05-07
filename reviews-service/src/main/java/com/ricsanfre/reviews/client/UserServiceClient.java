package com.ricsanfre.reviews.client;

import org.springframework.security.oauth2.client.annotation.ClientRegistrationId;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.UUID;

/**
 * HTTP Interface for resolving an IAM sub → internal user UUID via user-service.
 * Per ADR-004 (IAM Portability via user-service Isolation).
 */
@ClientRegistrationId("user-service")
@HttpExchange("/api/v1")
public interface UserServiceClient {

    @GetExchange("/users/resolve")
    UserResolveResponse resolveUser(@RequestParam("idp_subject") String idpSubject);

    record UserResolveResponse(UUID id) {}
}
