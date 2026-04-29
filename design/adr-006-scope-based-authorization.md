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
| `cart:read` | Read own shopping cart | `e-commerce-web` (optional) |
| `cart:write` | Add, update, and remove items in own shopping cart | `e-commerce-web` (optional) |

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

Scopes are defined as **client scopes** (`clientScopes[]`) at the realm level. Resource scopes are
configured as `optionalClientScopes` on `e-commerce-web` so they are not blindly granted to every
authenticated user.

**Role-to-scope promotion mechanism** — instead of adding resource scopes to `defaultClientScopes`
(which grants them to all users), the realm uses two Keycloak features:

1. **Client roles** on `e-commerce-web` — one atomic role per scope plus composite `customer` and
   `admin` roles that bundle the appropriate atomic roles.
2. **`clientScopeMappings`** — maps each atomic client role to its corresponding client scope object.
   Keycloak automatically promotes matching optional scopes into the token for any user who holds the
   role (directly or via a composite).

```json
// roles.client.e-commerce-web (excerpt)
{ "name": "customer", "composite": true,
  "composites": { "client": { "e-commerce-web": [
    "products:read", "orders:read", "orders:write",
    "reviews:read", "reviews:write", "users:read",
    "cart:read", "cart:write"
  ] } } }

// clientScopeMappings (excerpt)
"e-commerce-web": [
  { "clientScope": "products:read", "roles": ["products:read"] },
  { "clientScope": "orders:read",   "roles": ["orders:read"]   },
  ...
]
```

Users are assigned the composite role in `users[].clientRoles`:
```json
"clientRoles": { "e-commerce-web": ["customer"] }
```

The `e-commerce-service` M2M client keeps `defaultClientScopes: ["users:resolve"]` — for the
Client Credentials grant there is no human user, so role-based promotion is not applicable.

`fullScopeAllowed: false` is set on `e-commerce-web` to ensure Keycloak does not fall back to
including all realm roles in the token.

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
| `docker/keycloak/realm-e-commerce.json` | Removed `"roles"` block + protocol mappers; added `"clientScopes"` + `"roles.client.e-commerce-web"` (atomic + composite roles) + `"clientScopeMappings"` (role → scope promotion) + `optionalClientScopes` on `e-commerce-web` |
| `user-service/.../SecurityConfig.java` | Removed custom `JwtAuthenticationConverter`; changed to `Customizer.withDefaults()` |
| `user-service/.../UserController.java` | `@PreAuthorize("hasRole('service-account')")` → `@PreAuthorize("hasAuthority('SCOPE_users:resolve')")` |
| `user-service/.../UserControllerIntegrationTest.java` | JWT helpers updated to `scope` claim + `SCOPE_` authorities |
| `user-service/.../UserServiceTest.java` | JWT helper updated to `scope` claim |
| `cart-service/.../SecurityConfig.java` | Baseline requires `SCOPE_cart:read`; `@PreAuthorize` on each method uses `SCOPE_cart:read` or `SCOPE_cart:write` |
| `design/development-guidelines.md` | §9 SecurityConfig example updated; §13 mock JWT examples updated |
| `design/keycloak-configuration.md` | Realm roles → Client Scopes + Client Roles + clientScopeMappings; role-to-scope promotion mechanism documented |
| `README.md` | Test accounts table: "Roles" → "Granted Scopes" |

---

## Related Documents

- [ADR-003 — Keycloak as IAM](adr-003-keycloak-as-iam.md) — why Keycloak was chosen
- [ADR-004 — IAM Portability / User Service Isolation](adr-004-iam-portability-user-service-isolation.md) — why IDP coupling is isolated to `user-service`
- [design/keycloak-configuration.md](keycloak-configuration.md) — complete Keycloak realm reference
- [development-guidelines.md §9](development-guidelines.md) — Security configuration pattern for all services
