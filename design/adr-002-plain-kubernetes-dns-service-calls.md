# ADR-002 — Use Plain Kubernetes Service DNS for Service-to-Service Calls

**Date:** 2026-04-25  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

Microservices in this platform make synchronous HTTP calls to each other (e.g., `reviews-service` → `order-service`, `reviews-service` → `product-service`) using Spring HTTP Interfaces (`@HttpExchange`) backed by `RestClient`.

Two approaches exist for resolving the target URL at call time:

1. **Spring Cloud Kubernetes DiscoveryClient** (`lb://service-name`) — client-side service discovery backed by the Kubernetes API server. Spring Cloud LoadBalancer reads `Service` and `Endpoints` resources and performs client-side round-robin load balancing. Requires `spring-cloud-starter-kubernetes-client-loadbalancer` and RBAC permissions for each pod's `ServiceAccount`.

2. **Plain Kubernetes Service DNS** (`http://service-name:port`) — standard Kubernetes DNS resolution via CoreDNS. The `ClusterIP` `Service` acts as a stable virtual IP; kube-proxy implements server-side load balancing (IPVS or iptables) across all healthy pod replicas.

The original implementation used approach (1).

---

## Decision

Use **plain Kubernetes Service DNS** (`http://service-name:port`) for all service-to-service HTTP calls.

`RestClient` base URLs are set to the Kubernetes internal DNS name of the target service (e.g., `http://order-service:8082`). No Spring Cloud Kubernetes dependency is used.

---

## Rationale

### What DiscoveryClient adds and what it costs

| Concern | DiscoveryClient | Plain K8s DNS |
|---------|----------------|---------------|
| URL resolution | `lb://service-name` resolved via K8s API | `http://service-name:port` resolved by CoreDNS |
| Load balancing | Client-side round-robin (Spring Cloud LB) | Server-side (kube-proxy: IPVS / iptables) |
| RBAC required | Yes — `get/list/watch` on `services`, `endpoints`, `pods` | No |
| K8s API load | Yes — watch streams per pod | No |
| Extra dependency | `spring-cloud-starter-kubernetes-client-loadbalancer` | None |
| Local dev complexity | Requires disabling discovery + static instance config | Direct URL override per service |
| Resilience4j compatibility | Full | Full (circuit breaker operates at the call level) |

### Why DiscoveryClient is unnecessary here

Kubernetes already provides exactly what DiscoveryClient adds:

- **CoreDNS** resolves `order-service` to the `ClusterIP` stable virtual IP.
- **kube-proxy** (IPVS mode) load-balances across all healthy pod endpoints at the kernel level, invisible to the client.
- **Readiness probes** ensure kube-proxy only routes to pods that are ready; unhealthy pods are removed from the endpoint slice automatically.

Client-side load balancing (DiscoveryClient) would only provide a meaningful advantage over kube-proxy in rare scenarios:
- **Zone-aware routing** (pin calls to pods in the same AZ to reduce cross-zone latency)
- **Weighted routing** (canary deployments at the call level)
- **Custom load-balancing algorithms** beyond round-robin

None of these are required for this project.

### Resilience4j remains fully functional

Resilience patterns (circuit breaker, retry, rate limiter, bulkhead, time limiter) operate at the HTTP client call level — they wrap the `RestClient` execution, not the URL resolution step. Removing DiscoveryClient has zero effect on Resilience4j behaviour.

### Simpler local development

Without DiscoveryClient, the `local` Spring profile no longer needs to:
- Set `spring.cloud.kubernetes.enabled: false`
- Configure `spring.cloud.discovery.client.simple.instances` with static URLs

Instead, services configure the target URL via a standard Spring property with a `localhost` default, which works transparently in both local and Kubernetes environments:

```yaml
# application.yaml — works locally and in-cluster
services:
  order-service:
    url: ${ORDER_SERVICE_URL:http://localhost:8082}
```

---

## Consequences

### Positive

- **Simpler dependency tree** — `spring-cloud-starter-kubernetes-client-loadbalancer` removed from every service POM.
- **No RBAC overhead** — `ServiceAccount` no longer needs a `Role` granting access to the Kubernetes API; `rbac.yaml` removed from all Kustomize bases.
- **No K8s API load** — services no longer open watch streams against the API server on startup.
- **Idiomatic Kubernetes** — the pattern used by the vast majority of Kubernetes workloads, well-understood by operators and SRE teams.
- **Local dev simplification** — `application-local.yaml` no longer needed for service-discovery overrides.

### Neutral

- **Spring Cloud BOM retained** — `spring-cloud-dependencies` BOM remains in the root POM because `spring-cloud-starter-circuitbreaker-resilience4j` still uses it for version management.
- **Load balancing behaviour unchanged** — kube-proxy already did the same work as the Spring Cloud LoadBalancer for standard round-robin across pods.

### Negative / Trade-offs

- **No client-side zone awareness** — if future requirements demand zone-aware routing (e.g., to minimise cross-AZ traffic costs), the DiscoveryClient approach would need to be re-introduced or replaced with a service mesh (Istio, Linkerd).
- **No weighted routing at client level** — canary deployments must be implemented at the Kubernetes `Service` / `HTTPRoute` level rather than per calling service.

---

## Alternatives Considered

### Keep Spring Cloud Kubernetes DiscoveryClient

Rejected. The added complexity (RBAC, K8s API watches, extra JAR, profile-based local overrides) is not justified for the current requirements. kube-proxy provides equivalent load balancing without any application-level configuration.

### Migrate to Eureka

Rejected. Eureka is a cloud-agnostic service registry that makes sense when services run outside Kubernetes (e.g., on bare metal or mixed environments). This project targets Kubernetes exclusively; using Eureka would add a stateful server component for no benefit over CoreDNS.

### Introduce a Service Mesh (Istio / Linkerd)

Out of scope for current phase. A service mesh would provide zone-aware routing, mTLS, and advanced traffic management — but also significant operational overhead. Revisit if zone-aware routing becomes a hard requirement.

---

## Implementation Notes

- `spring-cloud-starter-kubernetes-client-loadbalancer` removed from `user-service/pom.xml` (and will be absent from all future service POMs).
- `spring.cloud.kubernetes.discovery.enabled` removed from `application.yaml`.
- `rbac.yaml` deleted from `k8s/apps/user-service/base/`; `kustomization.yaml` updated accordingly.
- `application-local.yaml` (which only disabled Kubernetes discovery) deleted.
- `RestClient` base URLs set directly to `http://service-name:port` in `HttpClientConfig`.
- `development-guidelines.md` Section 5 updated to reflect the new pattern.
