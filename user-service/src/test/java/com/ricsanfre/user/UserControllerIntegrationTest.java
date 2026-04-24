package com.ricsanfre.user;

import com.ricsanfre.user.api.model.UpdateUserRequest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests for {@link com.ricsanfre.user.controller.UserController}.
 *
 * <p>Uses a real PostgreSQL container via Testcontainers + {@code @ServiceConnection}.
 * JWT verification is bypassed with {@code @MockitoBean JwtDecoder} — Spring Boot will
 * not attempt to fetch the JWK Set URI. The {@code jwt()} MockMvc post-processor
 * injects a pre-built {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken}
 * directly into the SecurityContext.
 *
 * <p>Tests are ordered to simulate a realistic user lifecycle: first call creates the
 * profile (lazy registration), subsequent calls read/update it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // Prevents Spring Boot from fetching the JWK Set URI at context startup.
    @MockitoBean
    JwtDecoder jwtDecoder;

    // Shared across ordered tests — set when the profile is first created.
    static UUID createdUserId;

    // ── JWT helpers ──────────────────────────────────────────────────────────

    /** JWT for a regular user with sub = "test-sub-1". */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor userJwt() {
        return jwt()
                .jwt(j -> j
                        .subject("test-sub-1")
                        .claim("email", "testuser@example.com")
                        .claim("preferred_username", "testuser")
                        .claim("given_name", "Test")
                        .claim("family_name", "User")
                        .claim("roles", List.of("user")))
                .authorities(new SimpleGrantedAuthority("ROLE_user"));
    }

    /** JWT for a second user with sub = "test-sub-2". */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor otherUserJwt() {
        return jwt()
                .jwt(j -> j
                        .subject("test-sub-2")
                        .claim("email", "other@example.com")
                        .claim("preferred_username", "otheruser")
                        .claim("given_name", "Other")
                        .claim("family_name", "Person")
                        .claim("roles", List.of("user")))
                .authorities(new SimpleGrantedAuthority("ROLE_user"));
    }

    /** JWT for an internal service account carrying the service-account role. */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor serviceAccountJwt() {
        return jwt()
                .jwt(j -> j
                        .subject("service-account-sub")
                        .claim("email", "service@internal.local")
                        .claim("preferred_username", "service-account")
                        .claim("roles", List.of("service-account")))
                .authorities(new SimpleGrantedAuthority("ROLE_service-account"));
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void getCurrentUser_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void getCurrentUser_firstCall_lazyRegistersAndReturnsFullProfile() throws Exception {
        MvcResult result = mockMvc.perform(get("/users/me").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.email").value("testuser@example.com"))
                .andExpect(jsonPath("$.firstName").value("Test"))
                .andExpect(jsonPath("$.lastName").value("User"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andReturn();

        createdUserId = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("id").asText());
    }

    @Test
    @Order(3)
    void getCurrentUser_secondCall_returnsSameProfile() throws Exception {
        mockMvc.perform(get("/users/me").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdUserId.toString()));
    }

    @Test
    @Order(4)
    void getUserById_existingId_returns200WithProfile() throws Exception {
        mockMvc.perform(get("/users/{id}", createdUserId).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdUserId.toString()))
                .andExpect(jsonPath("$.email").value("testuser@example.com"));
    }

    @Test
    @Order(5)
    void getUserById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/users/{id}", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(6)
    void updateUser_ownProfile_returns200WithUpdatedFields() throws Exception {
        var request = UpdateUserRequest.builder()
                .firstName("Updated")
                .lastName("Name")
                .build();

        mockMvc.perform(put("/users/{id}", createdUserId).with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Updated"))
                .andExpect(jsonPath("$.lastName").value("Name"))
                .andExpect(jsonPath("$.email").value("testuser@example.com"));  // unchanged
    }

    @Test
    @Order(7)
    void updateUser_otherUsersProfile_returns403() throws Exception {
        // First, register the second user
        MvcResult r = mockMvc.perform(get("/users/me").with(otherUserJwt()))
                .andExpect(status().isOk())
                .andReturn();
        UUID otherId = UUID.fromString(
                objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

        // Try to update the other user's profile using the first user's JWT
        mockMvc.perform(put("/users/{id}", otherId).with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(8)
    void updateUser_unknownId_returns404() throws Exception {
        mockMvc.perform(put("/users/{id}", UUID.randomUUID()).with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(9)
    void resolveUser_withServiceAccountRole_returns200() throws Exception {
        mockMvc.perform(get("/users/resolve").with(serviceAccountJwt())
                        .param("idp_subject", "test-sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdUserId.toString()));
    }

    @Test
    @Order(10)
    void resolveUser_withRegularUserRole_returns403() throws Exception {
        mockMvc.perform(get("/users/resolve").with(userJwt())
                        .param("idp_subject", "test-sub-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(11)
    void resolveUser_unknownSubject_returns404() throws Exception {
        mockMvc.perform(get("/users/resolve").with(serviceAccountJwt())
                        .param("idp_subject", "non-existent-sub"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(12)
    void resolveUser_missingParam_returns400() throws Exception {
        mockMvc.perform(get("/users/resolve").with(serviceAccountJwt()))
                .andExpect(status().isBadRequest());
    }
}
