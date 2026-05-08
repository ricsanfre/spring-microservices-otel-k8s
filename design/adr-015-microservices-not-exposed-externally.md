# ADR-015 — Microservices Not Exposed via External Gateway

**Date:** 2026-05-08  
**Status:** Accepted  
**Deciders:** Project team  
**Amends:** [ADR-001](adr-001-envoy-gateway-as-api-gateway.md), [ADR-007](adr-007-nextjs-bff-frontend.md)

---

## Context

ADR-001 originally placed all traffic — both browser-facing and API traffic — behind the single
external Envoy Gateway, exposing `api.local.test` with a `SecurityPolicy` that validated JWTs
before forwarding requests to business microservices.

ADR-007 subsequently adopted the **BFF pattern**: the Next.js frontend became the only external
entry point for browsers. The browser never calls `api.local.test` directly — all microservice
calls originate from the Next.js server process, running inside the cluster.

With the BFF in place, `api.local.test` has no remaining external consumer in production. Keeping
microservice `HTTPRoute`s on the external gateway has no benefit, only risk: an external
load-balancer IP continues to route directly to business services even though no legitimate
external client calls them.

Additionally, Istio is planned as the east-west traffic layer in production. Istio provides:
- **mTLS between services** — mutual authentication using SPIFFE identities, no shared secrets
- **`AuthorizationPolicy`** — L7 per-service access control (source service principal, HTTP
  method/path, JWT claims) enforced at the sidecar, not at the application layer
- **Traffic observability** — distributed tracing and metrics without service-level instrumentation
  changes

Defining granular Kubernetes `NetworkPolicy` objects now would duplicate the same intent as
`AuthorizationPolicy` rules that will be written when Istio is introduced.

---

## Decision

1. **Remove all microservice `HTTPRoute`s from the external Envoy Gateway.** Only the following
   routes remain on the external gateway:
   - `app.local.test` → `frontend-service` (Next.js BFF)
   - `keycloak.local.test` → Keycloak
   - `grafana.local.test` → Grafana

2. **Remove the `SecurityPolicy` (JWT validation) from the external gateway.** It is no longer
   meaningful — neither of the remaining external routes (frontend, Keycloak) needs Envoy to
   validate JWTs. Auth.js handles the browser session; Keycloak issues tokens and validates its
   own endpoints.

3. **The Next.js BFF calls microservices directly via Kubernetes Service DNS**
   (`http://service-name.e-commerce.svc.cluster.local:port` or the short form
   `http://service-name:port` within the same namespace). This is consistent with how
   service-to-service calls already work (ADR-002).

4. **NetworkPolicy objects are kept minimal** (namespace-level ingress deny for the `e-commerce`
   namespace) to provide a coarse-grained L4 fence. Granular service-to-service authorization
   policy is deferred to Istio `AuthorizationPolicy`.

---

## Rationale

### Reduced attack surface

Removing `api.local.test` from the load balancer means there is no externally routable path to
business microservices. An attacker who bypasses the BFF cannot reach any service API directly
from the internet, regardless of the JWT they possess.

### No duplicate proxy hop

With the BFF pattern, a browser request to the frontend previously traversed two Envoy hops:

```
Browser → External Gateway (TLS termination) → frontend-service
                                                     │
                                                     ▼
                                    External Gateway (JWT validate) → microservice
```

The second Envoy hop is an intra-cluster round-trip through the gateway pod's data plane — latency
with no corresponding security benefit (the BFF already holds a valid JWT). Removing it makes the
call path:

```
Browser → External Gateway (TLS termination) → frontend-service
                                                     │ direct cluster DNS
                                                     ▼
                                               microservice
```

### JWT enforcement is already at the service layer

Every microservice is an OAuth2 Resource Server and validates the `Authorization: Bearer` JWT
independently. This was already identified in ADR-001 as a defence-in-depth measure. Removing the
gateway-level JWT check leaves the service-layer check as the sole enforcer for intra-cluster
traffic — which is appropriate since the gateway's role was only to protect the external boundary.

### Istio will replace fine-grained east-west policy

When Istio is introduced:
- mTLS between pods is automatic via sidecar injection
- `AuthorizationPolicy` restricts each microservice to accept calls only from the
  `frontend-service` service account (and from other microservices for M2M flows)
- Both checks happen at the Envoy sidecar level, transparent to the Spring Boot application

Writing Kubernetes `NetworkPolicy` objects with the same pod-to-pod topology now would require
maintaining two parallel sets of access-control rules. The minimal namespace-level `NetworkPolicy`
below provides L4 isolation without duplicating Istio's L7 intent:

```yaml
# Allows ingress only from within the e-commerce namespace
# and from envoy-gateway-system (browser → frontend-service)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-external
  namespace: e-commerce
spec:
  podSelector: {}
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: e-commerce
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: envoy-gateway-system
```

---

## Consequences

### Positive

- **Smaller external attack surface** — business APIs are network-unreachable from the internet
- **One fewer proxy hop** for BFF → microservice calls
- **Simpler Envoy Gateway config** — 3 routes instead of 8; no `SecurityPolicy` to maintain
- **Clear separation** — Envoy Gateway owns north-south (browser/external); Istio will own
  east-west (service-to-service); services own their own JWT validation

### Negative / Trade-offs

- **No gateway-level rate limiting or request enrichment** for microservice calls (e.g., injecting
  `x-user-id` from the JWT claim) without adding a second internal gateway. This is accepted:
  request enrichment can be done inside the BFF (Next.js Route Handler sets custom headers) or
  at the service layer, and rate limiting at the API level is not a current requirement.
- **Local development `api.local.test` is removed.** Direct `curl` testing against individual
  services requires port-forwarding (`kubectl port-forward`) or an in-cluster debug pod. The
  `oauth2c`-based Makefile targets remain valid for Docker Compose local development (services
  listen on `localhost:808x` directly).

---

## Alternatives Considered

### Two chained Envoy Gateways (external + internal ClusterIP)

Provides centralized API policy at the internal gateway (rate limiting, claim-to-header
enrichment). Rejected for now: adds operational surface area (second `Gateway`, `EnvoyProxy`
override, separate `HTTPRoute` + `SecurityPolicy` sets) without a concrete policy requirement
that justifies it. Can be adopted later if a non-BFF consumer (mobile app, partner integration)
or centralized rate limiting requirement appears.

### Keep `api.local.test` with additional authentication

Would require adding mutual TLS client certificates or a network-level allowlist to prevent
unintended external access. More complex than simply removing the route.

---

## Implementation

**Files changed:**

- `k8s/envoy-gateway/httproutes.yaml` — removed `user-service`, `product-service`,
  `order-service`, `reviews-service`, `cart-service` HTTPRoutes; retained `https-redirect`,
  `frontend-service`, `keycloak`, `grafana`
- `k8s/envoy-gateway/kustomization.yaml` — removed `security-policy.yaml` from resources

**Planned (when Istio is introduced):**

- `k8s/e-commerce/network-policy.yaml` — namespace-level ingress deny (see above)
- `k8s/e-commerce/istio-authorization-policies.yaml` — per-service `AuthorizationPolicy` CRDs
