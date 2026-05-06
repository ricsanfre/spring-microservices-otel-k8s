# ADR-014 — Per-Resource-Server Client Role Distribution

**Date:** 2026-05-06  
**Status:** Accepted  
**Deciders:** Project team  
**Supersedes:** partial supersession of [ADR-006](adr-006-scope-based-authorization.md) (role placement only — scope-based authorization model unchanged)  
**Related:** [ADR-003](adr-003-keycloak-as-iam.md), [ADR-006](adr-006-scope-based-authorization.md)

---

## Context

[ADR-006](adr-006-scope-based-authorization.md) introduced fine-grained OAuth2 scope-based authorization
(`products:read`, `orders:write`, etc.) and defined all atomic client roles on the **`e-commerce-web`**
Keycloak client. The `clientScopeMappings` configuration, which links a client role to a client scope
so the scope is promoted into the JWT, was therefore keyed on `e-commerce-web` as well.

```json
// Previous structure (ADR-006)
"roles": {
  "client": {
    "e-commerce-web": [
      { "name": "products:read" },
      { "name": "products:write" },
      { "name": "orders:write" },
      ...
    ]
  }
},
"clientScopeMappings": {
  "e-commerce-web": [
    { "clientScope": "products:read", "roles": ["products:read"] },
    ...
  ]
}
```

Service account users (`service-account-cart-service`, etc.) were also assigned roles on
`e-commerce-web`:

```json
"clientRoles": { "e-commerce-web": ["orders:write"] }
```

This concentrates all role ownership in the frontend client, which has two drawbacks:

1. **Semantic mismatch.** The `products:read` role controls access to `product-service`'s API, yet
   it lives on the `e-commerce-web` client. A new external system (mobile app, partner API, CI tool)
   that wants to call `product-service` directly would still have to receive roles on a frontend-only
   client with no conceptual connection to it.

2. **M2M extensibility is blocked.** When a new external client needs M2M access to individual
   services, role assignments pointing at `e-commerce-web` are conceptually wrong — that client
   represents a browser-facing application, not a resource authority. Managing external system
   permissions via `e-commerce-web` roles would conflate access control for very different principals.

Three options were evaluated:

| Option | Role ownership | M2M extensibility | Config complexity |
|--------|---------------|-------------------|-------------------|
| A — Keep all roles on `e-commerce-web` | Frontend client | Poor — external clients use frontend roles | Low |
| **B — Roles on owning resource server** | Each backend service client | Good — clear per-service boundary | Medium |
| C — Realm roles | Realm-level | Good | High — requires custom JWT converter in every service |

Option C was excluded because realm roles require a custom `JwtAuthenticationConverter` in every
service, reintroducing Keycloak coupling that ADR-006 deliberately removed.

---

## Decision

Move all atomic client roles to the **Keycloak client that owns the corresponding resource**:

| Role(s) | Keycloak client (owner) |
|---------|------------------------|
| `products:read`, `products:write` | `product-service` |
| `orders:read`, `orders:write` | `order-service` |
| `reviews:read`, `reviews:write` | `reviews-service` |
| `users:read`, `users:resolve` | `user-service` |
| `notifications:receive` | `notification-service` |
| `cart:read`, `cart:write` | `cart-service` |
| `customer` (composite), `admin` (composite) | `e-commerce-web` |

The `clientScopeMappings` configuration is updated so each key references the owning resource server
client instead of `e-commerce-web`.

Service account role assignments in `users[].clientRoles` are updated to reference the correct
resource server client (e.g., `service-account-cart-service` holds `orders:write` on `order-service`,
not on `e-commerce-web`).

The composite roles `customer` and `admin` remain on `e-commerce-web` because they represent
user-facing permission bundles that aggregate permissions across multiple services — they have no
single owning resource server. Their composites now cross-reference roles on the owning service
clients.

`users:resolve` — previously it had a client scope defined but no backing role, so it was
ungated. It now has an explicit role on `user-service`, consistent with all other scopes.

---

## Consequences

### Positive

- **Semantic correctness.** A role on `product-service` expresses "permission to act on
  product-service resources", not "permission granted by the frontend client". The mapping between
  role and protected resource is unambiguous.

- **M2M extensibility.** An external system (mobile app, partner integration, CI pipeline) that needs
  access to individual services receives roles directly on those service clients. There is no coupling
  to the frontend client.

- **Independent access control per service.** Permissions for `order-service` can be managed by a
  different team or toolchain than permissions for `product-service`. Role administration follows
  the service ownership model.

- **Keycloak Authorization Services compatibility.** If Authorization Services are enabled in the
  future, resource server clients are the correct attachment point for resources and policies. The
  per-service role structure is already aligned with that model.

- **No Spring Security changes.** Services still check `SCOPE_` authorities from the JWT `scope`
  claim. The role placement in Keycloak is invisible to Spring Security — only the presence of the
  scope in the token matters.

### Neutral

- **Composite roles remain on `e-commerce-web`.** This is correct: `customer` and `admin` are
  user-facing concepts defined by the frontend application. Their composites now span multiple
  resource server clients, which is the intended cross-service aggregation behaviour.

- **Re-import required.** The realm JSON change takes effect only after `make infra-clean && make infra-min-up`.

### Negative / Trade-offs

- **Slightly more Keycloak configuration.** `clientScopeMappings` now has six top-level keys instead
  of one. This is a minor trade-off for the semantic clarity gained.

---

## Updated Realm Structures

### `roles.client`

```json
{
  "product-service":     [{ "name": "products:read" }, { "name": "products:write" }],
  "order-service":       [{ "name": "orders:read" },   { "name": "orders:write" }],
  "reviews-service":     [{ "name": "reviews:read" },  { "name": "reviews:write" }],
  "user-service":        [{ "name": "users:read" },    { "name": "users:resolve" }],
  "notification-service":[{ "name": "notifications:receive" }],
  "cart-service":        [{ "name": "cart:read" },     { "name": "cart:write" }],
  "e-commerce-web": [
    { "name": "customer", "composite": true,
      "composites": { "client": {
        "product-service":  ["products:read"],
        "order-service":    ["orders:read", "orders:write"],
        "reviews-service":  ["reviews:read", "reviews:write"],
        "user-service":     ["users:read"],
        "cart-service":     ["cart:read", "cart:write"]
      }}
    },
    { "name": "admin", "composite": true,
      "composites": { "client": {
        "product-service":     ["products:read", "products:write"],
        "order-service":       ["orders:read", "orders:write"],
        "reviews-service":     ["reviews:read", "reviews:write"],
        "user-service":        ["users:read"],
        "notification-service":["notifications:receive"],
        "cart-service":        ["cart:read", "cart:write"]
      }}
    }
  ]
}
```

### `clientScopeMappings`

```json
{
  "product-service":     [{ "clientScope": "products:read",        "roles": ["products:read"] },
                          { "clientScope": "products:write",       "roles": ["products:write"] }],
  "order-service":       [{ "clientScope": "orders:read",          "roles": ["orders:read"] },
                          { "clientScope": "orders:write",         "roles": ["orders:write"] }],
  "reviews-service":     [{ "clientScope": "reviews:read",         "roles": ["reviews:read"] },
                          { "clientScope": "reviews:write",        "roles": ["reviews:write"] }],
  "user-service":        [{ "clientScope": "users:read",           "roles": ["users:read"] },
                          { "clientScope": "users:resolve",        "roles": ["users:resolve"] }],
  "notification-service":[{ "clientScope": "notifications:receive","roles": ["notifications:receive"] }],
  "cart-service":        [{ "clientScope": "cart:read",            "roles": ["cart:read"] },
                          { "clientScope": "cart:write",           "roles": ["cart:write"] }]
}
```

### Service account `clientRoles`

```json
{ "username": "service-account-cart-service",
  "clientRoles": { "order-service": ["orders:write"], "user-service": ["users:resolve"] } },
{ "username": "service-account-order-service",
  "clientRoles": { "product-service": ["products:write"], "user-service": ["users:resolve"] } },
{ "username": "service-account-reviews-service",
  "clientRoles": { "product-service": ["products:read"], "order-service": ["orders:read"],
                   "user-service": ["users:resolve"] } }
```

---

## Files Changed

| File | Change |
|------|--------|
| `docker/keycloak/realm-e-commerce.json` | `roles.client`: atomic roles moved to resource server clients; composite roles updated to reference cross-client roles. `clientScopeMappings`: keys changed from `e-commerce-web` to owning service clients. `users`: service account `clientRoles` updated; `service-account-reviews-service` added. |
| `design/keycloak-configuration.md` | Diagram, client roles table, per-service client table, service account role assignments section, and checklist updated to reflect per-resource-server role ownership. |
| `design/adr-006-scope-based-authorization.md` | Code snippets updated. |
| `design/development-guidelines.md` | `clientScopeMappings` service account table updated. |
| `design/adr-013-two-phase-checkout-flow.md` | Keycloak scope configuration table updated. |

---

## Related Documents

- [ADR-003 — Keycloak as IAM](adr-003-keycloak-as-iam.md)
- [ADR-006 — Scope-Based Authorization](adr-006-scope-based-authorization.md)
- [design/keycloak-configuration.md](keycloak-configuration.md)
