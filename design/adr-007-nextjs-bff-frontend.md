# ADR-007 — Next.js BFF Frontend

**Date:** 2026-04-26  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

The e-commerce platform needs a browser-facing frontend to exercise the microservice APIs.
Several deployment and security constraints shaped the choice:

1. **Envoy Gateway is the sole ingress** — all external traffic must enter through it (TLS
   termination, routing). Adding a dedicated frontend deployment is straightforward via a new
   `HTTPRoute`.

2. **JWT security in the browser is undesirable** — storing an access token in `localStorage` or
   `sessionStorage` exposes it to XSS attacks. The standard mitigation is to keep the token on the
   server and issue the browser only an HttpOnly cookie that it cannot read.

3. **Keycloak client type** — a public client (no secret, PKCE) is fine for a pure SPA but shifts
   the OAuth2 session management responsibility entirely to the browser. A confidential client
   lets the server hold the `client_secret`, enabling a true BFF pattern.

4. **Grafana occupies port 3000** — the local development port for the frontend must be something
   other than 3000.

### Options evaluated

| Option | Description | JWT in browser? | Client type |
|--------|-------------|-----------------|-------------|
| **A — Vite SPA** | Pure React SPA with PKCE in the browser | Yes (memory / storage) | Public |
| **B — Next.js App Router + Auth.js v5** | Server-side OIDC session; Route Handlers proxy API calls | No (HttpOnly cookie) | Confidential |

---

## Decision

Use **Next.js 15 (App Router)** with **Auth.js v5** as the frontend, deployed as a dedicated
`frontend-service` in the `e-commerce` Kubernetes namespace.

The frontend operates as a **Backend-For-Frontend (BFF)**:

- Auth.js manages the full OIDC Authorization Code flow server-side.
- The browser receives only an HttpOnly session cookie — it never sees the JWT.
- Server Components and Route Handlers call the microservice REST APIs server-side, forwarding
  the user's `access_token` from the server-side session as an `Authorization: Bearer` header.

---

## Rationale

### Security — JWT never reaches the browser

```
Browser  ──── HTTPS ────►  Next.js server  ──── Bearer JWT ────►  Envoy Gateway
               (cookie)                           (server-side)           │
                                                                           ▼
                                                                    Microservices
```

The browser's session state is an HttpOnly, Secure, SameSite=Lax cookie issued by Auth.js. The
underlying JWT lives only in the Next.js server process (or an encrypted cookie payload). An XSS
script in the browser cannot exfiltrate the access token.

### CORS elimination

Because all API calls originate server-side (within the cluster), there are no cross-origin API
requests from the browser. Microservices do not need CORS headers configured.

### SSR / SSG

Next.js App Router enables Server-Side Rendering and Static Site Generation, reducing
time-to-first-paint and removing client-side fetch waterfalls for page loads.

### `e-commerce-web` becomes a confidential client

```
clientId:               e-commerce-web
type:                   Confidential (client_secret stored in Next.js server env only)
standardFlowEnabled:    true    ← Authorization Code (no PKCE needed — server holds secret)
serviceAccountsEnabled: false
redirectUris:           http://localhost:3001/api/auth/callback/keycloak  (local dev)
                        https://app.local.test/api/auth/callback/keycloak (staging)
```

The `client_secret` is set as an environment variable (`KEYCLOAK_CLIENT_SECRET`) in the Next.js
deployment — it never appears in any browser-accessible asset.

### Token forwarding strategy

When a Route Handler or Server Component needs to call a microservice, Auth.js provides the stored
`access_token` from the server-side session. The Route Handler forwards it as:

```
Authorization: Bearer <user access_token>
```

This preserves per-user authorization: the downstream microservice sees the real user's JWT, not
a service-account token. The microservice does not need to know about Auth.js or Next.js.

### Envoy Gateway — no `SecurityPolicy` on the frontend `HTTPRoute`

The `app.local.test` HTTPRoute routes to `frontend-service`. **No `SecurityPolicy` is attached.**
Keycloak authentication is handled by Auth.js inside the Next.js server — unauthenticated browser
requests are redirected to the Keycloak login page by Auth.js middleware, not by Envoy.

The existing `api.local.test` and per-service HTTPRoutes retain their `SecurityPolicy` (JWT
validation) unchanged.

---

## Consequences

### New artifacts

| Artifact | Description |
|----------|-------------|
| `frontend-service/` | Next.js 15 application (App Router + Auth.js v5) |
| `k8s/apps/frontend-service/` | Kubernetes Deployment, Service, ConfigMap (KEYCLOAK_CLIENT_SECRET secret ref) |
| `k8s/envoy-gateway/httproutes.yaml` | New `HTTPRoute` for `app.local.test` (no SecurityPolicy) |

### Changed configuration

| Item | Before | After |
|------|--------|-------|
| `e-commerce-web` Keycloak client type | Public — SPA (no secret, PKCE) | Confidential — BFF (client_secret) |
| `redirectUris` | `http://localhost:*` (wildcard for any SPA dev port) | Exact URIs: `http://localhost:3001/api/auth/callback/keycloak` + `https://app.local.test/api/auth/callback/keycloak` |
| `directAccessGrantsEnabled` | `true` (for local curl testing) | Kept `true` for local Makefile token targets |
| `webOrigins` | `+` (all) | `http://localhost:3001`, `https://app.local.test` |

### Local development

| Port | Service |
|------|---------|
| 3000 | Grafana (existing — do not change) |
| 3001 | Next.js dev server (`npm run dev -- --port 3001`) |

Next.js connects to Keycloak at `http://localhost:8180` and to microservices at their local ports
(`http://localhost:808x`).

### Spring Boot microservices — no changes required

Microservices continue to validate JWTs as OAuth2 Resource Servers. They receive the user's
access token forwarded by Next.js Route Handlers and apply the same `@PreAuthorize` rules. No
microservice has any knowledge of the BFF layer.

---

## Related Decisions

- [ADR-001 — Envoy Gateway as API Gateway](adr-001-envoy-gateway-as-api-gateway.md)
- [ADR-003 — Keycloak as IAM](adr-003-keycloak-as-iam.md)
- [ADR-006 — Scope-Based Authorization](adr-006-scope-based-authorization.md)
