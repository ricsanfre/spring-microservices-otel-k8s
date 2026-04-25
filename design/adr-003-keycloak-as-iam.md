# ADR-003 — Keycloak as IAM (OAuth2 / OIDC)

**Date:** 2026-04-25  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

The platform requires a centralised Identity and Access Management (IAM) solution that:

- Issues and validates JSON Web Tokens (JWTs) for user authentication
- Manages user credentials — services must **not** store passwords
- Supports the OAuth2 Authorization Code + PKCE flow for browser clients
- Supports the OAuth2 Client Credentials flow for machine-to-machine (service-to-service) calls
- Provides role-based access control claims in tokens
- Can run locally (Docker Compose) and in Kubernetes (k3d staging)

Alternatives considered:

1. **Keycloak** — open-source, self-hosted OIDC/OAuth2 server
2. **Auth0** — cloud-hosted identity platform (SaaS)
3. **Spring Authorization Server** — Spring-managed OAuth2 Authorization Server, embedded in the application stack
4. **Custom JWT issuance** — hand-rolled JWT signing in a dedicated auth service

---

## Decision

Use **Keycloak** as the sole IAM provider for the platform.

No service stores passwords. Every inbound HTTP request — whether from an external browser client or from another service — carries a Keycloak-issued JWT that each recipient validates independently using Keycloak's JWKS public keys.

---

## Rationale

### Self-hosted and free

Keycloak is open-source (Apache 2.0) and self-hosted. There are no per-user fees, no API rate limits, and no vendor dependency on a cloud SaaS provider. The full IAM stack runs locally inside Docker Compose and in the k3d staging cluster.

### First-class OIDC + OAuth2 support

Keycloak provides out-of-the-box support for all flows required by this project:

| Flow | Used by | Purpose |
|------|---------|---------|
| Authorization Code + PKCE | Browser / SPA | User login — redirects to Keycloak login page, returns `access_token` + `refresh_token` |
| Client Credentials | Microservices | Service-to-service calls — each service authenticates with its own `client_id` / `client_secret` |

### One Keycloak client per service

Each microservice has its own Keycloak client (confidential, with service accounts enabled):

| Keycloak Client | Grant Types | Purpose |
|----------------|-------------|---------|
| `product-service` | Client Credentials | Resource server + outbound service account |
| `order-service` | Client Credentials | Resource server + outbound service account |
| `reviews-service` | Client Credentials | Resource server + outbound service account |
| `user-service` | Client Credentials | Resource server + outbound service account |
| `notification-service` | Client Credentials | Resource server (Kafka consumer, no outbound calls) |

> **No `api-gateway` client** — the frontend SPA handles the Authorization Code + PKCE flow directly with Keycloak. Envoy Gateway validates the resulting JWT via the JWKS endpoint (no client secret needed on the gateway).

### Spring Boot integration is trivial

Spring Boot's `spring-boot-starter-oauth2-resource-server` auto-configures JWT validation from a single JWKS URI:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: https://keycloak.local.test/realms/e-commerce/protocol/openid-connect/certs
          issuer-uri: https://keycloak.local.test/realms/e-commerce
```

For services that make outbound calls, `spring-boot-starter-oauth2-client` handles token acquisition and caching for the Client Credentials flow:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          order-service:
            client-id: reviews-service
            client-secret: ${ORDER_SERVICE_CLIENT_SECRET}
            authorization-grant-type: client_credentials
```

### Realm auto-import for reproducible environments

The Keycloak realm (`e-commerce`) is defined as a JSON export (`docker/keycloak/realm-e-commerce.json`) and auto-imported on container start. This ensures:
- Every developer starts with the same realm configuration (roles, clients, test users, JWT mappers)
- No manual Admin Console setup steps
- The realm config is version-controlled alongside application code

### Why not Auth0

| Concern | Auth0 | Keycloak |
|---------|-------|---------|
| Cost | Per-MAU pricing; free tier limited | Free and open-source |
| Hosting | Cloud SaaS (vendor dependency) | Self-hosted (full control) |
| Offline development | Requires internet connection | Runs entirely locally |
| Custom protocol mappers | Limited on free tier | Full customisation |
| Data residency | Data in Auth0's cloud | Data in your own infrastructure |

Auth0 is an excellent choice for production SaaS products where operational burden must be minimised. For a self-hosted platform where offline development is a requirement, Keycloak is preferable.

### Why not Spring Authorization Server

Spring Authorization Server is the right choice when the auth server needs to be embedded in the application stack (e.g., a monorepo where all auth logic is Spring-managed). For this project:
- Keycloak provides a production-ready admin UI, user management, social login, MFA, and audit logging with zero code
- Spring Authorization Server would require implementing all admin features from scratch
- The Keycloak Operator manages Keycloak as a Kubernetes-native resource via `Keycloak` and `KeycloakRealmImport` CRDs

### Why not custom JWT issuance

Rejected immediately. Writing a JWT issuance service is a security-critical task requiring careful key management, token revocation, and refresh token handling. Keycloak solves all of these problems with battle-tested code.

---

## Consequences

### Positive

- **Zero password storage** — no service ever touches a credential. Keycloak owns the full identity lifecycle.
- **Standard JWT** — all tokens are standard OIDC JWTs; any service can validate them with public keys from the JWKS endpoint, with no Keycloak SDK required.
- **Service isolation** — each service has its own Keycloak client; a compromised client secret affects only that service's outbound calls, not user authentication.
- **Reproducible** — realm JSON in version control means any developer can run a fully configured IAM in seconds with `docker compose up`.

### Neutral

- **Keycloak Operator** — in Kubernetes, Keycloak is managed by the [Keycloak Operator](https://www.keycloak.org/operator/installation) (no Helm chart available as of Keycloak 26). Installation uses `kubectl apply -k` with a remote Kustomize URL. See `k8s/infra/keycloak/`.
- **JWKS caching** — each service fetches the JWKS on first startup and caches it. Keycloak key rotation will require a cache refresh window. Spring Security handles this transparently.

### Negative / Trade-offs

- **Operational dependency** — if Keycloak is unavailable, no new tokens can be issued and existing tokens cannot be validated against a fresh JWKS. Mitigated by JWKS caching in services and Envoy Gateway.
- **IAM provider coupling** — the `sub` UUID in JWTs is Keycloak-specific. Migrating to another IAM provider issues different `sub` values. This is addressed by [ADR-004](adr-004-iam-portability-user-service-isolation.md) — only `user-service` stores the `sub`; all other services use the internal `users.id` UUID.

---

## Implementation Notes

- `docker/keycloak/realm-e-commerce.json` — realm definition auto-imported on container start
- `k8s/infra/keycloak/keycloak.yaml` — `Keycloak` CR (Kubernetes deployment)
- `k8s/infra/keycloak/realm-import.yaml` — `KeycloakRealmImport` CR
- `k8s/helm/keycloak-operator-values.yaml` — Keycloak Operator install notes
- Realm: `e-commerce` · JWKS: `https://keycloak.local.test/realms/e-commerce/protocol/openid-connect/certs`
