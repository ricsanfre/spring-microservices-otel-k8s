# ADR-004 — IAM Portability via user-service Isolation

**Date:** 2026-04-25  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

When Keycloak issues a JWT, the `sub` claim contains a Keycloak-internal UUID:

```
sub: "f47ac10b-58cc-4372-a567-0e02b2c3d479"
```

Services need a stable user identifier to associate data (orders, reviews, etc.) with a user. The question is: **which UUID should be used as the cross-service user key**?

Three options were considered:

1. **Use the IAM `sub` directly** — store `sub` as `user_id` in every service's database
2. **API Gateway enrichment** — the gateway resolves `sub` → internal `userId` and injects an `X-User-Id` header before forwarding requests
3. **Per-service lazy resolution** — each service resolves `sub` → internal `userId` on first encounter, then caches it locally; `user-service` is the single source of truth for the mapping

> See [iam-portability.md](iam-portability.md) for the full detailed analysis including sequence diagrams for each option.

---

## Decision

Use **Option 3 — Per-service lazy resolution**.

`user-service` is the **only** service that stores the IAM provider's `sub` (in the `idp_subject` column). All other services store and reference the internal `users.id` UUID. On first encounter of a `sub`, a service calls `GET /api/v1/users/resolve?idp_subject={sub}` to obtain the internal UUID, then caches it locally (TTL: 5–15 min).

---

## Rationale

### The cost of using `sub` as the cross-service key

The naive approach stores `sub` everywhere:

```
order-service:    orders.user_id = "f47ac10b-..."  (Keycloak sub)
reviews-service:  reviews.userId = "f47ac10b-..."  (Keycloak sub)
user-service:     users.id       = "f47ac10b-..."  (Keycloak sub)
```

If the IAM provider changes (Keycloak → Auth0, Okta, AWS Cognito), the new provider issues **different `sub` values** for the same users. Every service that stored the old `sub` has stale, unmatchable references. The migration blast radius spans every service and every table.

### The internal UUID as the stable key

With per-service lazy resolution, only one column in one service ever holds the IAM provider's `sub`:

```
user-service:     users.idp_subject = "f47ac10b-..."  (Keycloak sub — indexed, internal only)
                  users.id           = "9a3e2c1d-..."  (internal UUID — stable across IAM migrations)

order-service:    orders.user_id     = "9a3e2c1d-..."  (internal UUID)
reviews-service:  reviews.userId     = "9a3e2c1d-..."  (internal UUID)
```

On an IAM migration:
- The new provider maps the same physical user to a new `sub`
- Only `users.idp_subject` in `user-service` needs updating (one column, one table)
- All cross-service references (`orders.user_id`, `reviews.userId`) remain valid — they point to the stable internal UUID

### Why not Option 1 (use `sub` directly)

Rejected. Creates tight coupling between every service's data model and the IAM provider's identity space. IAM migration requires a coordinated data update across every service and every database — a high-risk, expensive operation.

### Why not Option 2 (gateway enrichment)

Option 2 is architecturally clean but introduces risks in this specific setup:

- **Envoy Gateway is the gateway** — and Envoy Gateway does not natively support arbitrary request enrichment (calling an upstream service and injecting a header). Implementing this would require a custom Lua filter or a sidecar proxy, adding significant complexity.
- **Trust boundary** — `X-User-Id` headers can be spoofed by any process inside the cluster unless mTLS or network policies strictly prevent it. JWT-based identity (verified cryptographically by each service) is more secure.
- **Gateway becomes stateful** — the gateway would need a cache with TTL management, complicating its operational profile.

Given that the gateway is Envoy Gateway (see [ADR-001](adr-001-envoy-gateway-as-api-gateway.md)), Option 2 is not feasible without significant infrastructure additions.

### Lazy registration

`user-service` exposes a `/api/v1/users/me` endpoint that triggers **lazy registration**: if no profile exists for the JWT `sub`, `user-service` creates one using JWT claims (`email`, `given_name`, `family_name`, `preferred_username`). No explicit registration step is required.

This means the first call any authenticated user makes automatically provisions their profile.

### Per-service resolution and caching

When `order-service` or `reviews-service` needs the internal `userId`:

```
1. Extract `sub` from the JWT
2. Check local Caffeine cache: sub → userId?
   └─ HIT:  use cached userId
   └─ MISS: GET /api/v1/users/resolve?idp_subject={sub}
              (authenticated with Client Credentials token)
            Cache the result (TTL: 5–15 min)
3. Use internal userId for all database operations
```

This approach keeps the resolution logic at the boundary of each service, with `user-service` as the single source of truth. The JWKS validation still happens per-service (no trust in the gateway's resolved header), maintaining a strong security posture.

---

## Consequences

### Positive

- **IAM portability** — migrating the IAM provider requires updating only `users.idp_subject` in `user-service`. All cross-service data references (`orders.user_id`, `reviews.userId`) remain valid.
- **No gateway complexity** — resolution logic is in the application layer, not in the infrastructure layer. No custom Envoy filters required.
- **Security** — each service validates the JWT cryptographically. The internal `userId` is resolved from a trusted, authenticated call to `user-service`, not from an easily-spoofable HTTP header.
- **Local caching** — Caffeine in-process cache keeps the resolution overhead negligible after the first encounter per user.

### Neutral

- **`user-service` as a soft dependency** — services that call the resolve endpoint are indirectly dependent on `user-service` being available for first-encounter resolution. Subsequent calls for the same user use the local cache. A `user-service` outage degrades new-user scenarios only, not existing-user flows (while cache is warm).
- **Cache invalidation** — if a user's IAM mapping is updated (edge case), services will use the cached `userId` until TTL expiry. Acceptable for this domain.

### Negative / Trade-offs

- **One extra HTTP call per new user** — the first request from a user triggers `GET /users/resolve`. This is bounded by the cache TTL and is an inherent cost of the isolation approach.
- **Resolve endpoint must be protected** — `GET /api/v1/users/resolve` must only be accessible to service accounts (authenticated via Client Credentials), never to regular users. Enforced via `@PreAuthorize` with a service-account role check.

---

## Implementation Notes

- `user-service` `users` table: `id UUID PK` (internal), `idp_subject VARCHAR UNIQUE INDEXED` (IAM `sub`)
- `GET /api/v1/users/resolve?idp_subject={sub}` — service-account-only endpoint; returns internal user profile
- `GET /api/v1/users/me` — triggers lazy registration on first call
- Resolution caching: Caffeine, TTL 5–15 min, per-service (each service caches independently)
- See [iam-portability.md](iam-portability.md) for full sequence diagrams and migration walkthrough
