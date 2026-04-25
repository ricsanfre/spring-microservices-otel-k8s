# ADR-006 — Scope-Based Authorization (OAuth2 Resource Scopes)

**Status:** Accepted  
**Date:** 2025-07-18  
**Deciders:** ricsanfre  
**Supersedes:** none  
**Related:** [ADR-003](adr-003-keycloak-as-iam.md), [ADR-004](adr-004-iam-portability-user-service-isolation.md)

---

## Context

The initial authorization design used Keycloak **realm roles** (`user`, `service-account`) injected
into JWTs via a custom protocol mapper (`oidc-usermodel-realm-role-mapper`) as a flat `"roles"` array
claim. Spring Security was configured with a custom `JwtGrantedAuthoritiesConverter` that read this
claim and mapped it to `ROLE_` prefixed authorities.

This approach has several drawbacks:

1. **Keycloak-specific coupling.** The `"roles"` claim is produced by a Keycloak-proprietary protocol
   mapper. No other OAuth2 provider (Auth0, Azure AD, Okta, AWS Cognito, Google) emits this claim
   shape by default. Migrating to a different IAM provider would require changing Spring Security
   configuration in every service.

2. **Coarse granularity.** A single `user` role gates all endpoints. As the platform adds more
   services, every service has access to everything a `user` is allowed to do. There is no per-resource
   or per-operation control boundary without inventing a new role per permission.

3. **Non-standard Spring Security config.** The custom `JwtAuthenticationConverter` deviates from
   Spring Security defaults, adding boilerplate that every service must copy and maintain.

4. **`service-account` role is also non-standard.** Using a realm role to distinguish M2M service
   accounts from human users is a workaround. OAuth2 already has a standard mechanism for this:
   the `scope` claim and the Client Credentials grant.

Three options were evaluated:

| Option | Mechanism | Spring changes | IAM portability |
|--------|-----------|---------------|-----------------|
| A — Coarse scopes | `users`, `products`, `orders` — one per service | Default Spring converter | High |
| **B — Fine-grained scopes** | `users:read`, `users:resolve`, `orders:write`, … | Default Spring converter | High |
| C — OIDC groups claim | `groups` claim in JWT | Custom converter | Medium (still custom claim) |

---

## Decision

Use **Option B — fine-grained resource scopes** as the authorization model.

Authorization decisions are expressed as standard OAuth2 `scope` values (RFC 6749 §3.3) carried in the
`scope` claim of every JWT. Each scope follows the pattern `<resource>:<action>`.

### Scope Catalogue

| Scope | Description | Default client |
|-------|-------------|---------------|
| `products:read` | Read product catalog | `e-commerce-web` |
| `products:write` | Create/update products (admin) | `e-commerce-web` (optional) |
| `orders:read` | Read own orders | `e-commerce-web` |
| `orders:write` | Place and update orders | `e-commerce-web` |
| `reviews:read` | Read product reviews | `e-commerce-web` |
| `reviews:write` | Submit and edit reviews | `e-commerce-web` |
| `users:read` | Read own user profile | `e-commerce-web` |
| `users:resolve` | Resolve IDP subject → internal user ID (M2M only) | `e-commerce-service` |
| `notifications:receive` | Receive notification events | `e-commerce-web` (optional) |

### Spring Security

Spring Security's default `JwtGrantedAuthoritiesConverter` reads the `scope` or `scp` claim
(space-separated) from the JWT and creates one `SCOPE_<name>` `GrantedAuthority` per token:

```java
// SecurityConfig.java — no custom JwtAuthenticationConverter needed
.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```

Endpoint protection uses `hasAuthority` with the `SCOPE_` prefix:

```java
// Method security
@PreAuthorize("hasAuthority('SCOPE_users:resolve')")
public ResponseEntity<UserResponse> resolveUser(String idpSubject) { ... }
```

Global baseline in `SecurityFilterChain`:

```java
.anyRequest().hasAuthority("SCOPE_users:read")   // require at minimum read scope
```

### Keycloak Implementation

Scopes are defined as **client scopes** (`clientScopes[]`) at the realm level:

```json
{
  "name": "users:resolve",
  "description": "Resolve IDP subject to internal user ID (M2M only)",
  "protocol": "openid-connect",
  "attributes": { "include.in.token.scope": "true", "display.on.consent.screen": "false" }
}
```

Each client declares which scopes it can obtain via `defaultClientScopes` and
`optionalClientScopes` — **no protocol mappers or realm roles are needed**:

```json
{
  "clientId": "e-commerce-web",
  "defaultClientScopes": ["openid", "profile", "email", "products:read", "orders:read", "orders:write", "reviews:read", "reviews:write", "users:read"],
  "optionalClientScopes": ["products:write"]
}
```

```json
{
  "clientId": "e-commerce-service",
  "defaultClientScopes": ["users:resolve"]
}
```

The resulting JWT `scope` claim is a space-separated string (RFC 6749):

```json
{ "scope": "openid profile email products:read orders:read orders:write reviews:read reviews:write users:read" }
```

---

## Consequences

### Positive

- **IAM portability.** The `scope` claim is the same on Auth0, Azure AD B2C, Okta, Cognito, and any
  other OAuth2-compliant provider. Migrating away from Keycloak requires no changes to Spring
  Security configuration.

- **No custom Spring Security code.** Removing the `JwtGrantedAuthoritiesConverter` bean reduces
  boilerplate in every service and removes a class of Keycloak-specific coupling from application code.

- **Fine-grained, future-proof permissions.** Individual service operations can be controlled
  independently. A future admin scope (`products:write`) can be introduced without changing the
  existing permission model for regular users.

- **Clearer M2M distinction.** Service-to-service calls use the Client Credentials grant and receive a
  token that only contains `users:resolve` — it is impossible for a service account token to pass
  `SCOPE_users:read` checks on user-facing endpoints, and vice versa.

- **Standard `scope` claim in tokens.** No Keycloak-proprietary `realm_access.roles` or custom flat
  `roles` array needed in the JWT.

### Negative / Trade-offs

- **More Keycloak config.** Client scopes must be declared explicitly for each service and each
  action. The realm JSON grows slightly compared to a single `user` role.

- **Scope proliferation risk.** Fine-grained scopes require discipline — without governance, every
  endpoint could end up with its own scope. The `<resource>:<action>` convention above defines the
  allowed actions (`read`, `write`, `resolve`, `receive`) to limit this.

- **Re-import required on scope changes.** Adding a new scope to `realm-e-commerce.json` requires
  destroying and re-importing the Keycloak realm in dev (`docker compose down -v && docker compose up`).

---

## Implementation Notes

The following files were updated as part of this ADR:

| File | Change |
|------|--------|
| `docker/keycloak/realm-e-commerce.json` | Removed `"roles"` block + protocol mappers; added `"clientScopes"` + `defaultClientScopes` on clients |
| `user-service/.../SecurityConfig.java` | Removed custom `JwtAuthenticationConverter`; changed to `Customizer.withDefaults()` |
| `user-service/.../UserController.java` | `@PreAuthorize("hasRole('service-account')")` → `@PreAuthorize("hasAuthority('SCOPE_users:resolve')")` |
| `user-service/.../UserControllerIntegrationTest.java` | JWT helpers updated to `scope` claim + `SCOPE_` authorities |
| `user-service/.../UserServiceTest.java` | JWT helper updated to `scope` claim |
| `design/development-guidelines.md` | §9 SecurityConfig example updated; §13 mock JWT examples updated |
| `design/keycloak-configuration.md` | Realm roles → Client Scopes; protocol mappers section removed |
| `README.md` | Test accounts table: "Roles" → "Granted Scopes" |

---

## Related Documents

- [ADR-003 — Keycloak as IAM](adr-003-keycloak-as-iam.md) — why Keycloak was chosen
- [ADR-004 — IAM Portability / User Service Isolation](adr-004-iam-portability-user-service-isolation.md) — why IDP coupling is isolated to `user-service`
- [design/keycloak-configuration.md](keycloak-configuration.md) — complete Keycloak realm reference
- [development-guidelines.md §9](development-guidelines.md) — Security configuration pattern for all services
