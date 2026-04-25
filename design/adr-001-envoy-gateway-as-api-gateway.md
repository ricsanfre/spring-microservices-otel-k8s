# ADR-001 — Envoy Gateway as API Gateway

**Date:** 2026-04-25  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

An e-commerce microservice platform requires an API gateway to:

- Receive all external HTTP traffic and route it to the appropriate backend service
- Terminate TLS
- Enforce authentication — validate JWTs issued by Keycloak before forwarding requests
- Route requests based on hostname and/or path prefix

Several gateway options exist for Kubernetes-based deployments:

1. **Spring Cloud Gateway** — a Spring Boot application acting as a proxy, configured in Java/YAML
2. **Envoy Gateway** — CNCF project implementing the Kubernetes Gateway API standard using Envoy Proxy as the data plane
3. **Kong** — feature-rich API gateway with plugin ecosystem, runs as a separate deployment
4. **Nginx Ingress Controller** — Kubernetes Ingress controller using Nginx; not Gateway API

---

## Decision

Use **Envoy Gateway** (implementing the [Kubernetes Gateway API](https://gateway-api.sigs.k8s.io/)) as the sole entry point for external traffic.

There is **no Spring Cloud Gateway service** in this architecture. Envoy Gateway handles TLS termination, JWT validation, and HTTPRoute-based routing directly.

---

## Rationale

### Kubernetes Gateway API standard

The Kubernetes Gateway API is the successor to the Ingress API and is now GA. It provides:
- **Role-oriented resources** — `GatewayClass` (infra), `Gateway` (cluster ops), `HTTPRoute` (app dev)
- **Expressive routing** — header matching, path matching, traffic splitting, redirects
- **Portable configuration** — specs are implementation-agnostic; switching data-plane implementations (Envoy → Nginx → Cilium) requires only changing the `GatewayClass`, not rewriting routes

Using a standard API avoids vendor lock-in at the routing configuration level.

### JWT validation without a gateway service

Envoy Gateway's `SecurityPolicy` CRD offloads JWT validation to the Envoy data plane:

```yaml
apiVersion: gateway.envoyproxy.io/v1alpha1
kind: SecurityPolicy
spec:
  jwt:
    providers:
      - name: keycloak
        issuer: https://keycloak.local.test/realms/e-commerce
        remoteJWKS:
          uri: https://keycloak.local.test/realms/e-commerce/protocol/openid-connect/certs
```

Every request that reaches a business service has already been authenticated. Services still perform their own JWT validation as a defence-in-depth measure (they are OAuth2 Resource Servers), but no JWT can arrive at a service without first passing Envoy's check.

This means **no gateway microservice** needs to be written, maintained, or scaled.

### Why not Spring Cloud Gateway

| Concern | Spring Cloud Gateway | Envoy Gateway |
|---------|---------------------|---------------|
| Runtime | JVM process (Spring Boot app) | Envoy Proxy (native binary) |
| JWT validation | Custom `GatewayFilter` in Java | Declarative `SecurityPolicy` CRD |
| Configuration | Java DSL or YAML in application config | Kubernetes-native CRDs (Gateway API) |
| Routing standard | Proprietary Spring routes | Kubernetes Gateway API (standard) |
| Maintenance | Full Spring Boot service to build, test, and deploy | Zero application code |
| TLS termination | Needs keystore config | Native via Kubernetes `Secret` reference in `Gateway` |

Spring Cloud Gateway is well-suited when the gateway itself contains business logic (token relay with transformation, custom request enrichment). In this project the gateway has no business logic — it only routes and validates JWTs. Using a JVM process for this would add unnecessary operational overhead.

### Why not Kong

Kong is a powerful option but:
- Requires a separate database (PostgreSQL) for its configuration store in DB mode
- DB-less mode requires all configuration in a `kong.yaml` file — less Kubernetes-native than CRDs
- Licensing complexity (Kong Enterprise vs Community)
- Higher operational overhead than a declarative CRD-based solution

### TLS termination with cert-manager

Envoy Gateway terminates TLS using a wildcard certificate (`*.local.test`) issued by cert-manager:

```yaml
# Gateway listener references the TLS Secret
listeners:
  - name: https
    port: 443
    protocol: HTTPS
    tls:
      certificateRefs:
        - name: local-test-wildcard-tls
```

cert-manager issues and automatically rotates the certificate. No manual certificate management.

---

## Consequences

### Positive

- **Zero gateway application code** — no Spring Boot gateway service to build, test, or deploy
- **Kubernetes-native** — all routing config is standard Kubernetes Gateway API CRDs, version-controlled alongside the application manifests
- **Declarative JWT validation** — `SecurityPolicy` enforces authentication at the Envoy data plane, before requests reach services
- **Standard API** — HTTPRoute configuration is portable across Gateway API implementations

### Neutral

- Services still run full OAuth2 Resource Server validation (`spring-boot-starter-oauth2-resource-server`) as defence-in-depth. This is intentional — services must not blindly trust that the gateway always validates tokens.
- JWT is forwarded to backend services as-is (the `Authorization: Bearer` header is preserved by default). Services extract the `sub` claim from the JWT themselves.

### Negative / Trade-offs

- **No request enrichment at gateway** — the gateway does not inject `X-User-Id` or other resolved headers. Each service must resolve the JWT `sub` to an internal user ID independently (see [ADR-004](adr-004-iam-portability-user-service-isolation.md)).
- **Envoy Gateway expertise required** — CRD-based configuration has a steeper learning curve than a YAML `application.yaml` file. The trade-off is accepted: the configuration is minimal and well-documented.

---

## Alternatives Considered

### Spring Cloud Gateway

Rejected. It would add a full JVM service with no business logic. All needed functionality (TLS, JWT validation, routing) is available declaratively through Envoy Gateway CRDs.

### Kong

Rejected. Database dependency and licensing complexity outweigh the benefits for this scale of project.

### Nginx Ingress Controller

Rejected. The Nginx Ingress API (`networking.k8s.io/v1 Ingress`) is less expressive than Gateway API and does not natively support JWT validation as a declarative policy.

---

## Implementation Notes

- `k8s/envoy-gateway/gateway.yaml` — `Gateway` resource with HTTP redirect and HTTPS TLS listeners
- `k8s/envoy-gateway/httproutes.yaml` — one `HTTPRoute` per business service + Keycloak + Grafana
- `k8s/envoy-gateway/security-policy.yaml` — `SecurityPolicy` referencing Keycloak JWKS endpoint
- `k8s/infra/cert-manager/wildcard-certificate.yaml` — `*.local.test` wildcard certificate managed by cert-manager
- Envoy Gateway installed via Helm: `oci://docker.io/envoyproxy/gateway-helm`
