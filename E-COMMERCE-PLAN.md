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
| Service discovery | Netflix Eureka |
| API Gateway | Spring Cloud Gateway |
| Authentication / Authorization | OAuth2.0 + Keycloak (OIDC) |
| Observability | OpenTelemetry → Grafana LGTM (traces, metrics, logs) |

---

## Full Services Table

| Service | Port | Database | Communication |
|---------|------|----------|---------------|
| `api-gateway` | 8080 | — | Routes all external HTTP traffic; JWT validation and token relay |
| `eureka-server` | 8761 | — | Service registration and discovery (Netflix Eureka) |
| `keycloak` | 8180 | H2 (dev) / PostgreSQL (prod) | OAuth2 / OIDC IAM — token issuance |
| `product-service` | 8081 | MongoDB | Product catalog CRUD |
| `order-service` | 8082 | PostgreSQL | Order lifecycle; Kafka producer |
| `reviews-service` | 8083 | MongoDB | Product reviews; validates via order + product services |
| `notification-service` | 8084 | stateless | Kafka consumer; sends notifications |
| `user-service` | 8085 | PostgreSQL | User profile management; delegates identity to Keycloak |

---

## Documentation Phases

### Phase 1 — Overview & Services Table

1. Write intro paragraph: app purpose and full tech stack
2. Add microservices overview table (all 8 services)

---

### Phase 2 — Architecture Diagram (Mermaid)

Produce a `flowchart TD` diagram covering:

- External client → Keycloak (get JWT) → API Gateway (Bearer token)
- API Gateway validates JWT via Keycloak JWKS endpoint, then routes + relays token downstream
- All services register with Eureka; synchronous inter-service calls use load-balanced Discovery Client
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

- **Entity:** `User { id, username, email, firstName, lastName, keycloak_id, createdAt, updatedAt }`
- `keycloak_id` = the `sub` UUID from Keycloak's JWT — links profile to Keycloak identity
- **No password storage** — Keycloak owns credentials
- **REST API:**

  | Method | Path | Required Role |
  |--------|------|---------------|
  | `GET` | `/api/v1/users/me` | Any authenticated user (resolved from JWT `sub`) |
  | `GET` | `/api/v1/users/{id}` | Any authenticated user |
  | `PUT` | `/api/v1/users/{id}` | Owner |
  | `POST` | `/api/v1/users` | Service account (post-registration hook) |

- **Profile registration flow:** After first Keycloak login, client (or Keycloak event listener) calls `POST /api/v1/users` to create the local profile, linked via `keycloak_id`

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
| `api-gateway` | Authorization Code + PKCE | Public-facing API Gateway |
| `product-service` | Client Credentials | Resource server + service account |
| `order-service` | Client Credentials | Resource server + service account |
| `reviews-service` | Client Credentials | Resource server + service account |
| `user-service` | Client Credentials | Resource server + service account |
| `notification-service` | Client Credentials | Resource server |

#### Token Flow 1 — User Authentication (Authorization Code + PKCE)

```
Client App → Keycloak (login) → Authorization Code
           → Keycloak (token exchange) → JWT access_token + refresh_token
           → API Gateway (Bearer JWT)
           → Keycloak JWKS (validate signature) — cached
           → Microservice (Bearer JWT forwarded via TokenRelay filter)
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
| Token Relay (API Gateway) | `spring-cloud-starter-gateway` | `TokenRelay` filter on all routes |

---

### Phase 4 — Infrastructure Services

| Service | Port | Role |
|---------|------|------|
| **API Gateway** | 8080 | Spring Cloud Gateway; JWT validation + token relay to all business services |
| **Eureka Server** | 8761 | Netflix Eureka; all services register on startup; `lb://` URI for load-balanced calls |
| **Keycloak** | 8180 | OAuth2.0 / OIDC IAM; JWKS endpoint cached by all resource servers |

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

Table: `users` (with `keycloak_id` as external identity reference)

#### MongoDB — products collection

Fields: `_id`, `name`, `description`, `price`, `category`, `imageUrl`, `stockQty`, `createdAt`, `updatedAt`

#### MongoDB — reviews collection

Fields: `_id`, `productId` (ref products), `orderId` (ref orders), `userId` (Keycloak `sub`), `rating`, `comment`, `createdAt`

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

### Phase 8 — Docker Compose Infrastructure

| Container | Image | Ports | Notes |
|-----------|-------|-------|-------|
| `keycloak` | `quay.io/keycloak/keycloak:latest` | 8180 | OAuth2 IAM; add dedicated PostgreSQL for prod |
| `kafka` | `apache/kafka:latest` | 9092 | KRaft mode (no Zookeeper required) |
| `mongodb` | `mongo:7` | 27017 | Shared MongoDB for products + reviews |
| `db-orders` | `postgres:16` | 5432 | PostgreSQL exclusive to order-service |
| `db-users` | `postgres:16` | 5433 | PostgreSQL exclusive to user-service |
| `grafana-lgtm` | `grafana/otel-lgtm:latest` | 3000, 4317, 4318 | OTEL + Loki + Tempo + Prometheus + Grafana |

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
- [ ] `keycloak_id` linkage documented in both User Service and security sections

---

## Key Design Notes

1. **User Service vs Keycloak separation:** Keycloak owns credentials/authentication. User Service stores enriched profile data (username, email, names). The `keycloak_id` field is the authoritative link between the two systems.

2. **Token relay vs re-fetch:** API Gateway uses Spring Cloud Gateway's `TokenRelay` filter to forward the original user JWT downstream — services don't re-fetch tokens for user-context calls. Only service-to-service background calls (Reviews → Order validation) use Client Credentials.

3. **Reviews user identity:** Reviews Service reads the `sub` claim from the JWT to identify the reviewer — no `userId` is passed in the request body. This prevents spoofing.

4. **Two PostgreSQL instances:** `db-orders` and `db-users` are independent containers, each owned exclusively by one service — a core microservice isolation principle (database-per-service pattern).

5. **Keycloak production:** Default dev mode uses embedded H2. For production, provision a dedicated `db-keycloak` PostgreSQL container and run Keycloak in production mode.

---

## Further Improvements (Out of Scope)

| Area | Idea |
|------|------|
| Stock management | Order Service calls Product Service to decrement `stockQty` synchronously, or via a `stock.reserved` Kafka event |
| Review coupling | Reviews Service keeps a local `completed-orders` projection (from `order.completed.v1` Kafka event) instead of synchronous calls to Order Service |
| Keycloak event listener | Automatically create user-service profile on first Keycloak login (SPI extension), removing the need for a manual `POST /api/v1/users` call |
| Kafka security | Add SASL/SCRAM authentication for Kafka in production |
| Container orchestration | Kubernetes with Helm charts for each service |
| CI/CD | GitHub Actions pipeline — build, test, publish Docker images |
