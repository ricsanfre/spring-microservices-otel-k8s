# Plan: E-Commerce Microservice Architecture

> **Reference:** https://github.com/micaellobo/e-commerce-store  
> **Output:** Architecture documentation in `README.md`  
> **Stack:** Spring Boot 4 · Java 25 · Spring Cloud · Apache Kafka · MongoDB · PostgreSQL · Keycloak · OpenTelemetry

---

## Confirmed Design Decisions

| Decision | Choice |
|----------|--------|
| Product catalog database | MongoDB |
| Order management database | PostgreSQL |
| User reviews database | MongoDB |
| User profiles database | PostgreSQL |
| Notification service storage | Stateless (no DB) |
| Async messaging | Apache Kafka — topic `order.created.v1` |
| Service discovery | Plain Kubernetes Service DNS — `http://service-name:port` via CoreDNS + kube-proxy |
| Entry point / API Gateway | Envoy Gateway (Kubernetes Gateway API) |
| Authentication / Authorization | OAuth2.0 + Keycloak (OIDC) |
| Observability | OpenTelemetry → Grafana LGTM (traces, metrics, logs) |
| Deployment environment | k3d (k3s in Docker) |

---

## Full Services Table

| Service | Port | Database | Communication |
|---------|------|----------|---------------|
| `keycloak` | 8180 | H2 (dev) / PostgreSQL (prod) | OAuth2 / OIDC IAM — token issuance |
| `product-service` | 8081 | MongoDB | Product catalog CRUD |
| `order-service` | 8082 | PostgreSQL | Order lifecycle; Kafka producer |
| `reviews-service` | 8083 | MongoDB | Product reviews; validates via order + product services |
| `notification-service` | 8084 | stateless | Kafka consumer; sends notifications |
| `user-service` | 8085 | PostgreSQL | User profile management; delegates identity to Keycloak |

> **Entry point:** All external traffic enters via **Envoy Gateway** (Kubernetes Gateway API). There is no `api-gateway` Spring service — Envoy handles JWT validation (`SecurityPolicy` → Keycloak JWKS) and routes directly to Kubernetes services.

---

## Documentation Phases

### Phase 1 — Overview & Services Table

1. Write intro paragraph: app purpose and full tech stack
2. Add microservices overview table (all 8 services)

---

### Phase 2 — Architecture Diagram (Mermaid)

Produce a `flowchart TD` diagram covering:

- External client → Keycloak (get JWT) → Envoy Gateway (Bearer token)
- Envoy Gateway validates JWT via Keycloak JWKS `SecurityPolicy`, then routes directly to Kubernetes services via `HTTPRoute`
- All business services use plain Kubernetes Service DNS (`http://service-name:port`) for synchronous inter-service calls; kube-proxy handles server-side load balancing
- Order Service publishes to Kafka `order.created.v1`; Notification Service consumes
- Reviews Service calls Order Service + Product Service via Client Credentials (sync validation)
- Database associations:
  - `product-service` → MongoDB
  - `reviews-service` → MongoDB
  - `order-service` → PostgreSQL (orders DB)
  - `user-service` → PostgreSQL (users DB)

---

### Phase 3 — Service-by-Service Details

#### product-service · port 8081 · MongoDB

- **Entity:** `Product { id, name, description, price, category, imageUrl, stockQty, createdAt, updatedAt }`
- **REST API:**

  | Method | Path | Required Role |
  |--------|------|---------------|
  | `GET` | `/api/v1/products` | Any authenticated user |
  | `GET` | `/api/v1/products/{id}` | Any authenticated user |
  | `POST` | `/api/v1/products` | `ADMIN` |
  | `PUT` | `/api/v1/products/{id}` | `ADMIN` |
  | `DELETE` | `/api/v1/products/{id}` | `ADMIN` |

---

#### order-service · port 8082 · PostgreSQL

- **Entities:**
  - `Order { id, userId, status, totalAmount, createdAt, updatedAt }`
  - `OrderItem { id, orderId (FK), productId, quantity, unitPrice }`
- **Status lifecycle:** `PENDING` → `CONFIRMED` → `SHIPPED` → `DELIVERED` | `CANCELLED`
- **REST API:**

  | Method | Path | Required Role |
  |--------|------|---------------|
  | `POST` | `/api/v1/orders` | Any authenticated user |
  | `GET` | `/api/v1/orders/{id}` | Owner or `ADMIN` |
  | `GET` | `/api/v1/orders/user/{userId}` | Owner or `ADMIN` |
  | `PUT` | `/api/v1/orders/{id}/status` | `ADMIN` |

- **Kafka:** publishes `OrderCreatedEvent` to `order.created.v1` on every new order

---

#### reviews-service · port 8083 · MongoDB

- **Entity:** `Review { id, productId, orderId, userId, rating (1–5), comment, createdAt }`
- **Business rule:** Review only allowed if the authenticated user (`sub` from JWT) has a `DELIVERED` order containing the reviewed `productId`
- **REST API:**

  | Method | Path | Required Role |
  |--------|------|---------------|
  | `GET` | `/api/v1/reviews/product/{productId}` | Any authenticated user |
  | `POST` | `/api/v1/reviews` | Any authenticated user |
  | `DELETE` | `/api/v1/reviews/{id}` | Owner |

- **Sync validation calls** (Client Credentials grant):
  1. `product-service` → verify product exists
  2. `order-service` → verify user has a `DELIVERED` order with the product

---

#### notification-service · port 8084 · stateless

- No REST API exposed
- No database
- Kafka consumer group: `notification-group`, topic: `order.created.v1`
- Action on event: send email / push notification / write to observability pipeline

---

#### user-service · port 8085 · PostgreSQL

- **Entity:** `User { id, idp_subject, username, email, firstName, lastName, createdAt, updatedAt }`
- `idp_subject` = the `sub` UUID from the IAM provider's JWT — IAM-agnostic name; only used inside this service
- **No password storage** — Keycloak owns credentials
- **IAM portability:** This is the **only** service that stores the IAM provider's `sub`. All other services use the internal `id` (UUID). On IAM migration, only this service's `idp_subject` column needs updating. See `design/iam-portability.md`.
- **REST API:**

  | Method | Path | Required Role |
  |--------|------|---------------|
  | `GET` | `/api/v1/users/me` | Any authenticated user (resolved from JWT `sub`) |
  | `GET` | `/api/v1/users/{id}` | Any authenticated user |
  | `GET` | `/api/v1/users/resolve?idp_subject={sub}` | Service account only |
  | `PUT` | `/api/v1/users/{id}` | Owner |
  | `POST` | `/api/v1/users` | Any authenticated user (lazy registration) |

- **Lazy registration flow:** On first `GET /api/v1/users/me`, if no profile exists for the JWT `sub`, `user-service` auto-creates it from JWT claims (`email`, `given_name`, `family_name`, `preferred_username`)
- **Per-service lazy resolution:** Other services call `GET /api/v1/users/resolve?idp_subject={sub}` on first encounter with a user, cache the result (TTL 5–15 min), and use the internal `users.id` UUID for all data storage

---

### Phase 3b — Security Architecture (OAuth2 + Keycloak)

#### Keycloak Realm

| Setting | Value |
|---------|-------|
| Realm name | `e-commerce` |
| JWKS endpoint | `http://keycloak:8180/realms/e-commerce/protocol/openid-connect/certs` |
| Token endpoint | `http://keycloak:8180/realms/e-commerce/protocol/openid-connect/token` |

One confidential client per service (service accounts enabled):

| Keycloak Client ID | Grant Types | Used By |
|--------------------|-------------|---------|
| `product-service` | Client Credentials | Resource server + service account |
| `order-service` | Client Credentials | Resource server + service account |
| `reviews-service` | Client Credentials | Resource server + service account |
| `user-service` | Client Credentials | Resource server + service account |
| `notification-service` | Client Credentials | Resource server |

> **No `api-gateway` Keycloak client** — the frontend SPA performs the Authorization Code + PKCE flow directly with Keycloak. Envoy Gateway validates JWTs via JWKS without holding a client secret.

#### Token Flow 1 — User Authentication (Authorization Code + PKCE)

```
Client SPA → Keycloak (login) → Authorization Code
           → Keycloak (token exchange) → JWT access_token + refresh_token
           → Envoy Gateway (Bearer JWT)
           → SecurityPolicy → Keycloak JWKS (validate signature) — cached
           → Microservice (Bearer JWT forwarded in Authorization header)
           → Microservice validates JWT as OAuth2 Resource Server
```

#### Token Flow 2 — Service-to-Service (Client Credentials Grant)

```
reviews-service → Keycloak (client_id + client_secret) → service account JWT
               → order-service (Bearer service JWT)
               → order-service validates JWT as OAuth2 Resource Server
               → order-service returns order details
```

#### Spring Boot Configuration Roles

| Service Role | Spring Boot Dependency | Key Config |
|---|---|---|
| Resource Server (all services) | `spring-boot-starter-oauth2-resource-server` | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` |
| OAuth2 Client (service accounts) | `spring-boot-starter-oauth2-client` | `grant-type: client_credentials` per registration |

---

### Phase 4 — Infrastructure Services

| Component | Type | Role |
|-----------|------|------|
| **Envoy Gateway** | Kubernetes resource (CRD) | External entry point; JWT validation via `SecurityPolicy`; HTTPRoute routing to all business services |
| **Keycloak** | Container / K8s Deployment | OAuth2.0 / OIDC IAM; JWKS endpoint cached by Envoy Gateway and all resource servers |

---

### Phase 5 — Kafka Events

| Topic | Producer | Consumer | Description |
|-------|----------|----------|-------------|
| `order.created.v1` | `order-service` | `notification-service` | Fired on every new order placement |

**`OrderCreatedEvent` payload:**

```json
{
  "orderId":     "550e8400-e29b-41d4-a716-446655440000",
  "userId":      "550e8400-e29b-41d4-a716-446655440001",
  "totalAmount": 149.98,
  "itemCount":   2,
  "createdAt":   "2026-04-23T10:00:00Z"
}
```

---

### Phase 6 — Data Models

#### PostgreSQL — orders DB

Tables: `orders`, `order_items` (FK `order_id → orders.id`)

#### PostgreSQL — users DB

Table: `users` columns: `id` (PK, internal UUID), `idp_subject` (IAM provider `sub`, indexed), `email` (UNIQUE), `username`, `first_name`, `last_name`, `created_at`, `updated_at`

> `idp_subject` replaces the former `keycloak_id` name. The IAM-agnostic name signals that this column belongs to the external identity provider, not to the application's own data model.

#### MongoDB — products collection

Fields: `_id`, `name`, `description`, `price`, `category`, `imageUrl`, `stockQty`, `createdAt`, `updatedAt`

#### MongoDB — reviews collection

Fields: `_id`, `productId` (ref products), `orderId` (ref orders), `userId` (internal `users.id` UUID — resolved via user-service lazy resolution), `rating`, `comment`, `createdAt`

---

### Phase 7 — Observability Stack

All services export via **OTLP** to `grafana/otel-lgtm` (already defined in `compose.yaml`):

| Signal | Backend | Integration |
|--------|---------|-------------|
| **Traces** | Grafana Tempo | `spring-boot-starter-opentelemetry` — W3C TraceContext propagation |
| **Logs** | Grafana Loki | Logback `OpenTelemetryAppender` — log/trace correlation |
| **Metrics** | Prometheus | Micrometer via OTLP — JVM, HTTP, Kafka consumer lag |

OTLP endpoints (HTTP): `:4318` — gRPC: `:4317` — Grafana UI: `:3000`

---

### Phase 8 — Local Development Infrastructure (Docker Compose)

| Container | Image | Ports | Notes |
|-----------|-------|-------|-------|
| `keycloak` | `quay.io/keycloak/keycloak:latest` | 8180 | OAuth2 IAM; add dedicated PostgreSQL for prod |
| `kafka` | `apache/kafka:latest` | 9092 | KRaft mode (no Zookeeper required) |
| `mongodb` | `mongo:7` | 27017 | Shared MongoDB for products + reviews |
| `db-orders` | `postgres:16` | 5432 | PostgreSQL exclusive to order-service |
| `db-users` | `postgres:16` | 5433 | PostgreSQL exclusive to user-service |
| `grafana-lgtm` | `grafana/otel-lgtm:latest` | 3000, 4317, 4318 | OTEL + Loki + Tempo + Prometheus + Grafana |

---

### Phase 9 — Kubernetes Deployment (k3d)

#### Cluster creation

```bash
k3d cluster create e-commerce \
  --port "80:80@loadbalancer" \
  --port "8180:8180@loadbalancer" \
  --registry-create e-commerce-registry:0.0.0.0:5000
```

#### Namespace layout

| Namespace | Contents |
|-----------|----------|
| `e-commerce` | All business microservices |
| `e-commerce-infra` | Keycloak, Kafka, MongoDB, PostgreSQL |
| `envoy-gateway-system` | Envoy Gateway controller |
| `monitoring` | Grafana LGTM stack |

#### Envoy Gateway installation

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.3.0 \
  --namespace envoy-gateway-system \
  --create-namespace
```

#### Kubernetes manifests structure

```
k8s/
├── namespace.yaml
├── envoy-gateway/
│   ├── gateway.yaml            ← GatewayClass + Gateway resource
│   ├── httproutes.yaml         ← HTTPRoute per business service
│   └── security-policy.yaml   ← JWT SecurityPolicy (Keycloak JWKS)
├── product-service/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   └── serviceaccount.yaml
├── order-service/           ← (same files)
├── reviews-service/
├── notification-service/
├── user-service/
└── infra/
    ├── keycloak/
    ├── kafka/
    ├── mongodb/
    └── postgres/
```

#### Image build and import

```bash
# Build and push to k3d local registry
mvn compile jib:build -Ddocker.registry=localhost:5000

# Import into k3d cluster nodes
for svc in product-service order-service reviews-service notification-service user-service; do
  k3d image import localhost:5000/${svc}:latest -c e-commerce
done
```

#### Deploy

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/infra/
kubectl apply -f k8s/product-service/
kubectl apply -f k8s/order-service/
kubectl apply -f k8s/reviews-service/
kubectl apply -f k8s/notification-service/
kubectl apply -f k8s/user-service/
kubectl apply -f k8s/envoy-gateway/
```

#### Service-to-Service Calls

Services call each other using plain Kubernetes Service DNS (`http://service-name:port`). kube-proxy handles server-side load balancing. No `spring-cloud-starter-kubernetes-client-loadbalancer` or RBAC API access required. See [design/adr-002-plain-kubernetes-dns-service-calls.md](design/adr-002-plain-kubernetes-dns-service-calls.md).

---

### Phase 10 — CI/CD with GitHub Actions

#### Strategy

| Decision | Choice |
|---|---|
| Registry | `ghcr.io` (GitHub Container Registry) — `GITHUB_TOKEN`, no extra secrets |
| Scope | CI (build + test + publish) + CD (ephemeral k3d staging cluster in runner) |
| Triggers | Push to `main` + Pull Requests |
| Build strategy | `dorny/paths-filter` change detection + matrix per changed service |

#### Workflow files

```
.github/
└── workflows/
    ├── ci.yaml   ← detect changes → matrix build → unit tests → jib:build → ghcr.io
    └── cd.yaml   ← workflow_run on CI success (main only) → k3d deploy → smoke tests
```

#### CI job flow (per changed service)

1. `dorny/paths-filter` — detect which service directories (or `common/` / root `pom.xml`) changed
2. Matrix over changed services (`fromJson(outputs.changes)`)
3. `actions/setup-java@v4` — temurin, Java 25, Maven cache
4. `mvn -pl {service} -am verify` — compile + unit tests (runs on PRs and `main`)
5. `mvn -pl {service} compile jib:build` — push `ghcr.io/{owner}/{service}:{sha}` + `latest` tag (main only)
6. Authenticate via `GITHUB_TOKEN` + `permissions.packages: write` — no Docker daemon needed

#### CD job flow (main only, after CI succeeds)

1. Install k3d + kubectl + Helm
2. `k3d cluster create e-commerce --port 80:80@loadbalancer --port 8180:8180@loadbalancer --wait`
3. `helm install eg oci://docker.io/envoyproxy/gateway-helm --version v1.3.0 --namespace envoy-gateway-system --wait`
4. `kubectl apply -f k8s/infra/` + wait for rollouts
5. For each service: `envsubst < k8s/{svc}/deployment.yaml | kubectl apply -f -` (substitutes `${IMAGE}` = `ghcr.io/{owner}/{svc}:{sha}`)
6. `kubectl apply -f k8s/envoy-gateway/`
7. `kubectl rollout status` per service
8. `curl` smoke tests against `http://localhost/api/v1/products` etc.

> See [design/development-guidelines.md](design/development-guidelines.md) Section 17 for full YAML workflow examples.

---

## Relevant Workspace Files

| File | Purpose |
|------|---------|
| `README.md` | Architecture documentation (output file) |
| `compose.yaml` | Existing infrastructure (grafana-lgtm on 3000/4317/4318) |
| `otel-demo/src/main/resources/application.yaml` | OTEL config patterns reference |

---

## Verification Checklist

- [ ] Mermaid diagrams render correctly (VS Code Markdown preview + GitHub)
- [ ] All port assignments consistent throughout README
- [ ] All service names consistent (kebab-case throughout)
- [ ] DB assignments consistent (Products/Reviews → MongoDB, Orders/Users → PostgreSQL)
- [ ] Kafka topic name `order.created.v1` used consistently
- [ ] OAuth2 token flows cover both user-facing and service-to-service scenarios
- [ ] `idp_subject` column documented in User Service; all other services reference internal `users.id`
- [ ] Per-service lazy resolution documented (resolve endpoint + local cache)
- [ ] No references to Eureka or Spring Cloud Gateway in documentation
- [ ] k3d cluster creation command includes port mappings for Envoy Gateway and Keycloak
- [ ] Envoy Gateway `SecurityPolicy` references Keycloak JWKS endpoint
- [ ] Service-to-service calls use plain Kubernetes Service DNS (`http://service-name:port`) — no DiscoveryClient, no RBAC
- [ ] CI workflow uses `dorny/paths-filter` change detection with matrix build
- [ ] `jib:build` step authenticates to `ghcr.io` via `GITHUB_TOKEN` (no extra secrets)
- [ ] CD workflow creates k3d cluster + installs Envoy Gateway + deploys via `envsubst`
- [ ] `k8s/{service}/deployment.yaml` uses `${IMAGE}` placeholder for tag substitution

---

## Key Design Notes

1. **User Service vs Keycloak separation:** Keycloak owns credentials/authentication. User Service stores enriched profile data (username, email, names). The `idp_subject` field links the local profile to the IAM provider identity — but this link stays inside `user-service` only.

2. **IAM portability via internal UUID:** All services store the internal `users.id` UUID, NOT the IAM provider's `sub`. On an IAM migration, only `user-service`'s `idp_subject` column needs updating. See `design/iam-portability.md`.

3. **Per-service lazy resolution:** When a service first encounters a JWT `sub` it hasn't seen, it calls `GET /api/v1/users/resolve?idp_subject={sub}` and caches the returned internal UUID locally (Caffeine / Spring `@Cacheable`, TTL 5–15 min).

4. **JWT forwarding:** Envoy Gateway forwards the original `Authorization: Bearer` header downstream after JWT validation. Services receive the full JWT and validate it independently as OAuth2 Resource Servers. Only service-to-service background calls (Reviews → Order validation) use Client Credentials.

5. **Reviews user identity:** Reviews Service resolves the reviewer's internal `userId` from the JWT `sub` via lazy resolution — no `userId` is passed in the request body. This prevents spoofing.

4. **Two PostgreSQL instances:** `db-orders` and `db-users` are independent containers, each owned exclusively by one service — a core microservice isolation principle (database-per-service pattern).

5. **Keycloak production:** Default dev mode uses embedded H2. For production, provision a dedicated `db-keycloak` PostgreSQL container and run Keycloak in production mode.

---

## Further Improvements (Out of Scope)

| Area | Idea |
|------|------|
| Stock management | Order Service calls Product Service to decrement `stockQty` synchronously, or via a `stock.reserved` Kafka event |
| Review coupling | Reviews Service keeps a local `completed-orders` projection (from `order.completed.v1` Kafka event) instead of synchronous calls to Order Service |
| Keycloak event listener | Replace lazy registration (Option A) with a Keycloak SPI EventListenerProvider (Option C) to guarantee profile existence before first API call |
| Kafka security | Add SASL/SCRAM authentication for Kafka in production |
| Container orchestration | Kubernetes with Helm charts for each service |
| Kafka security | Add SASL/SCRAM authentication for Kafka in production |
