# E-Commerce Microservice Application

This repository contains a demo E-commerce application, a microservice-based distributed system intended to illustrate the implementation in a near real-world environment. The application is not a full-featured production-ready system — it focuses on core architectural patterns and best practices rather than exhaustive business features.

Key architectural highlights include:

- **Microservices** — 5 backend services (product, order, reviews, user, cart) + Next.js BFF frontend
- **Database-per-service** — each service has its own database schema (MongoDB or PostgreSQL)
- **API gateway** — Envoy Gateway for routing, load balancing, and security at the edge
- **Domain-driven design** — clear service boundaries and aggregate roots (e.g. Order, Product)
- **Asynchronous messaging** — Kafka for decoupled communication between order-service, cart-service, and notification-service
- Authentication and authorization with **OAuth2 / OIDC** — Keycloak as the identity provider for both user authentication and service-to-service communication
- **Observability** — OpenTelemetry instrumentation with Grafana LGTM stack for monitoring and troubleshooting


For architecture and design details see [ARCHITECTURE.md](ARCHITECTURE.md).  
For coding standards and implementation patterns see [design/development-guidelines.md](design/development-guidelines.md).

## Technology Stack

| Layer | Technology |
|-------|-----------|
| **Backend language / runtime** | Java 25 (Temurin) |
| **Backend framework** | Spring Boot 4.0.6 |
| **Build** | Maven 3.9 (multi-module) |
| **Frontend** | Next.js 16, React 19, TypeScript 6 |
| **Frontend auth** | Auth.js v5 (next-auth) — OIDC / Authorization Code + PKCE |
| **IAM** | Keycloak 26.6 — OAuth2 / OIDC, JWT RS256 |
| **API gateway** | Envoy Gateway (Kubernetes) |
| **Relational database** | PostgreSQL 18 (CNPG operator in k8s) |
| **Document database** | MongoDB 8 (Community operator in k8s) |
| **Cache / cart store** | Valkey 8 (Redis-compatible) |
| **Messaging** | Apache Kafka 4.2 (Strimzi operator in k8s) |
| **Observability** | OpenTelemetry SDK + Grafana LGTM (Loki · Tempo · Mimir · Grafana) |
| **Container images** | Jib (Maven plugin) → `ghcr.io` |
| **Kubernetes distribution** | k3d (k3s in Docker) for local staging |
| **CI/CD** | GitHub Actions — Surefire / Failsafe / Jib |

## Table of Contents

- [Technology Stack](#technology-stack)
- [Microservices Overview](#microservices-overview)
- [Prerequisites — mise](#prerequisites--mise)
- [Local Development — Docker Compose](#local-development--docker-compose)
- [Kubernetes Staging — k3d](#kubernetes-staging--k3d)
- [CI/CD](#cicd)

---

## Microservices Overview

| Service | Database | Responsibility |
|---------|----------|----------------|
| `frontend-service` | stateless | Next.js 16 BFF — server-side OIDC session (Auth.js v5); proxies API calls to microservices |
| `product-service` | MongoDB | Product catalog — CRUD and inventory quantities |
| `order-service` | PostgreSQL | Order lifecycle management; Kafka producer |
| `reviews-service` | MongoDB | Product reviews and ratings — validated against order history |
| `notification-service` | stateless | Order event notifications — Kafka consumer |
| `user-service` | PostgreSQL | User profile management; delegates identity to Keycloak |
| `cart-service` | Valkey | Shopping cart management; initiates checkout to order-service; clears cart on order confirmation via Kafka |

---

## Prerequisites — mise

All developer tools are version-pinned in [`.mise.toml`](.mise.toml) at the repository root (see [ADR-008](design/adr-008-mise-tool-version-management.md)). Install [mise](https://mise.jdx.dev) once, then run `mise install` to get every tool at the exact declared version — no `sudo`, no OS-specific steps.

```bash
# 1. Install mise (one-time, per machine)
curl https://mise.run | sh
echo 'eval "$(mise activate bash)"' >> ~/.bashrc
source ~/.bashrc

# 2. Install all project tools (re-run after pulling changes to .mise.toml)
mise install

# 3. Verify
mise doctor
```

**Tools installed by `.mise.toml`:**

| Tool | Pinned version | Used by |
|------|---------------|---------|
| `java` (Temurin) | 25 | All Spring Boot services |
| `maven` | 3.9 | Building / running services |
| `node` | 22 | frontend-service dev server + image build |
| `kubectl` | latest | Kubernetes staging |
| `helm` | latest | Operator install |
| `k3d` | latest | Cluster management |
| `kustomize` | latest | k8s manifests |
| `jq` | latest | Makefile token helpers |

> **Docker** is the only prerequisite not managed by mise — the Docker daemon requires OS-level integration that a user-space tool manager cannot provide. Install it separately:
> ```bash
> curl -fsSL https://get.docker.com | sh
> sudo usermod -aG docker $USER   # re-login after this
> ```

---

## Local Development — Docker Compose

### Infrastructure

For local development all infrastructure runs via Docker Compose. Services run directly with `mvn spring-boot:run` (via `make`). `compose.yaml` uses **profiles** so each microservice activates only the containers it needs.

| Container | Image | Host Port | Compose Profile | Description |
|-----------|-------|-----------|-----------------|-------------|
| `grafana-lgtm` | `grafana/otel-lgtm:latest` | 3000, 4317, 4318 | `observability` | Observability stack (Loki, Tempo, Prometheus, Grafana) |
| `postgres` | `postgres:18-alpine` | 5432 | `infra` | Single PostgreSQL instance — one database per service |
| `mongo` | `mongo:8` | 27017 | `infra` | Single MongoDB instance — one database per service |
| `valkey` | `valkey/valkey:9-alpine` | 6379 | `infra` | Valkey cache — shopping carts (TTL 7 days) |
| `kafka` | `apache/kafka:4.2.0` | 9092 | `infra` | Apache Kafka — order events (`order.confirmed.v1`) |
| `keycloak` | `quay.io/keycloak/keycloak:26.6` | 8180 | `auth` | OAuth2 / OIDC IAM — realm `e-commerce` auto-imported |

> **Profiles:** `infra` starts the shared databases (PostgreSQL + MongoDB + Valkey + Kafka); `auth` starts Keycloak; `observability` starts the Grafana LGTM stack. All three are started together via `make infra-up`.

> **Keycloak realm auto-import:** `docker/keycloak/realm-e-commerce.json` is volume-mounted into Keycloak's import directory. On first start Keycloak creates realm `e-commerce` with client scopes, clients, test users, and service-account role assignments automatically — no manual Admin Console steps required. **Important:** changes to the realm JSON only take effect after `make infra-clean && make infra-min-up` — the import is skipped when the data volume already exists.

> **Database-per-service isolation:** Each service connects to its own named database within the shared PostgreSQL (or MongoDB) instance using dedicated credentials. The `docker/postgres/init-databases.sh` init script creates all databases and users on first container start. This preserves the database-per-service isolation principle while avoiding the overhead of multiple container instances.

### Running Services

The root `Makefile` provides per-service targets. Run `make help` to see all targets.

#### Prerequisites
- Docker & Docker Compose v2 (see above)
- All other tools via `mise install` (Java 25, Maven 3.9, Node.js 22, `jq`)
- `curl` (standard on Linux/macOS)

#### Backend services

Start infrastructure first, then run the desired service. All services require at minimum the `infra` + `auth` profiles (`make infra-min-up`); add `observability` for Grafana traces (`make infra-up`).

| Service | Build & run | Dev shortcut (infra + run) | Local port |
|---------|-------------|---------------------------|------------|
| `user-service` | `make us-run` | `make us-dev` | 8085 |
| `product-service` | `make ps-run` | `make ps-dev` | 8081 |
| `order-service` | `make os-run` | `make os-dev` | 8082 |
| `reviews-service` | `make rvs-run` | `make rvs-dev` | 8083 |
| `notification-service` | `make ns-run` | — (run `make infra-min-up` first) | 8084 |
| `cart-service` | `make cs-run` | `make cs-dev` | 8086 |

The dev shortcut builds the JAR, starts infra, and launches the service in one command:

```bash
make infra-up   # start all infrastructure (postgres, mongo, valkey, kafka, keycloak, grafana)
make us-run     # build + run user-service (or any other *-run target above)
```

Each service's Swagger UI is available at `http://localhost:<port>/swagger-ui.html` once running.

#### frontend-service

```bash
# 1. Copy the example env file and fill in required values
cd frontend-service
cp .env.local.example .env.local
# Edit .env.local:
#   AUTH_SECRET=<openssl rand -base64 32>
#   AUTH_KEYCLOAK_SECRET=<e-commerce-web client secret from Keycloak>

# 2. Install dependencies
npm install

# 3. Start the dev server on http://localhost:3001
npm run dev
```

Make sure Keycloak is running (`make infra-up`) before starting the frontend — Auth.js needs to reach the Keycloak OIDC discovery endpoint at `http://localhost:8180/realms/e-commerce`.

See [design/frontend-design.md — Source Layout](design/frontend-design.md#12-source-layout) for the full annotated directory tree.

#### Seeding the product catalog

The MongoDB container auto-seeds on first start via `docker/mongo/init-products.js`. If the data volume already exists, re-run the seed explicitly:

```bash
make ps-seed   # idempotent — skips silently if catalog already populated
```

To wipe and re-seed from scratch:

```bash
make infra-clean   # destroy volumes
make infra-up      # re-create containers → init script runs automatically
```

#### Getting tokens for API testing

```bash
# User token — Authorization Code flow (opens browser for Keycloak login)
# Requires: oauth2c installed (via `mise install`)
TOKEN=$(make -s us-token)

# Service-account token — Client Credentials (no browser required)
SA_TOKEN=$(make -s us-token-sa)
```

> `make us-token` uses [oauth2c](https://github.com/cloudentity/oauth2c) to perform the
> Authorization Code flow. A browser window opens to the Keycloak login page; after login,
> the token is captured automatically. See [ADR-011](design/adr-011-oauth2c-local-api-testing.md)
> for rationale (password grant is disabled on the BFF client).

Use the token with any service:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/v1/users/me
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/products | jq .
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/v1/orders/user/me | jq .

# Service-account call (resolve endpoint — M2M only)
curl -s -H "Authorization: Bearer $SA_TOKEN" \
     "http://localhost:8085/api/v1/users/resolve?idp_subject=<sub>"
```

---

### Access Points

| URL | Description |
|-----|-------------|
| `http://localhost:3001` | frontend-service (Next.js dev server) |
| `http://localhost:8081/swagger-ui.html` | product-service Swagger UI |
| `http://localhost:8082/swagger-ui.html` | order-service Swagger UI |
| `http://localhost:8086/swagger-ui.html` | cart-service Swagger UI |
| `http://localhost:8180` | Keycloak Admin Console |
| `http://localhost:3000` | Grafana Dashboards |

### Keycloak Test Accounts (auto-configured via realm import)

| Username | Password | Client Role | Granted Scopes |
|----------|----------|-------------|----------------|
| `testuser` | `password` | `customer` on `e-commerce-web` | `openid profile email products:read orders:read orders:write reviews:read reviews:write users:read cart:read cart:write` |
| `otheruser` | `password` | `customer` on `e-commerce-web` | `openid profile email products:read orders:read orders:write reviews:read reviews:write users:read cart:read cart:write` |
| `adminuser` | `password` | `admin` on `e-commerce-web` | `openid profile email products:read products:write orders:read orders:write reviews:read reviews:write users:read notifications:receive` (no cart scopes — admins don't shop) |

> **Admin discriminator:** `products:write` is present only in admin tokens. The frontend checks
> `session?.scope?.split(" ").includes("products:write")` to toggle admin-only UI (dashboard,
> all-orders/all-users tables, product creation). Cart icon and Profile link are hidden for admins.

### Service Account Clients (M2M — Client Credentials)

| Client ID | Secret | Scopes |
|-----------|--------|--------|
| `cart-service` | `cart-service-secret` | `users:resolve orders:write` |
| `order-service` | `order-service-secret` | `users:resolve products:write` |
| `reviews-service` | `reviews-service-secret` | `users:resolve products:read orders:read` |

### Stopping Infrastructure

```bash
make infra-down    # stop containers, keep data volumes
make infra-clean   # stop containers AND delete data volumes
```

---

## Kubernetes Staging — k3d (GitOps / Flux)

The staging environment runs a full **k3d** cluster (k3s inside Docker) on your laptop. All Kubernetes manifests live in `gitops/` and are continuously reconciled by **Flux CD**. The cluster definition lives in `gitops/clusters/staging/k3d-cluster.yaml`. All services are exposed via the `.local.test` domain, which resolves automatically on the local machine.

See [ARCHITECTURE.md — Kubernetes Deployment Architecture](ARCHITECTURE.md#kubernetes-deployment-architecture) for the cluster diagram and `gitops/` directory layout.

### Prerequisites

All tools except Docker are installed via `mise install` (see [Prerequisites — mise](#prerequisites--mise)).

| Tool | Pinned in `.mise.toml` |
|------|------------------------|
| Docker 24+ | manual — `get.docker.com` |
| k3d | `latest` |
| kubectl | `latest` |
| helm | `latest` |
| kustomize | `latest` |
| flux2 | `latest` |
| Java 25 (Temurin) | `temurin-25` |
| Maven 3.9 | `3.9` |
| Node.js 22 | `22` |

### 1. Create the Cluster

```bash
make k3d-create
```

This creates cluster `e-commerce` (1 control-plane + 2 worker nodes) with:
- Ports 80/443 mapped to the k3d load-balancer (Envoy Gateway)
- Traefik disabled
- Host aliases for `app.local.test`, `keycloak.local.test`, `grafana.local.test`

### 2. Bootstrap Flux

Flux CD is managed by the **Flux Operator**. Bootstrap installs the operator via Helm and then applies a `FluxInstance` CR that points Flux at this repository.

```bash
make flux-operator-install   # helm install Flux Operator into flux-system
make flux-bootstrap          # kubectl apply FluxInstance — Flux takes over
make flux-status             # watch reconciliation progress
```

Flux will reconcile all infrastructure and application resources in dependency order:

```
cluster-settings (ConfigMap)
├── infra-security   (ESO stores)
├── infra-routing    (cert-manager + Envoy Gateway)
├── infra-monitoring (observability stack)
└── infra-backends   (databases + Kafka + Keycloak + Valkey)
      └── core-apps  (all business microservices)
```

### 3. Build and Push Service Images

Images are pulled from `ghcr.io` — CI publishes them automatically on every push to `master`.
For manual pushes (e.g. testing a local branch), log in to ghcr.io first and use the Jib Maven targets:

```bash
# Log in once (PAT requires write:packages scope)
echo "<YOUR_PAT>" | docker login ghcr.io -u <your-github-username> --password-stdin

# Build and push a service
mvn -pl common,user-service -am compile jib:build -Ddocker.registry=ghcr.io/<your-github-username>
mvn -pl common,cart-service -am compile jib:build -Ddocker.registry=ghcr.io/<your-github-username>
```

### One-Shot Full Setup

```bash
make k3d-create && make flux-operator-install && make flux-bootstrap
```

### Access Points (Staging)

| URL | Description |
|-----|-------------|
| `https://app.local.test` | frontend-service (Next.js BFF) |
| `https://api.local.test/api/v1/users` | user-service via Envoy Gateway |
| `https://api.local.test/api/v1/cart` | cart-service via Envoy Gateway |
| `https://keycloak.local.test` | Keycloak Admin Console |
| `https://grafana.local.test` | Grafana Dashboards |

> TLS is terminated at the Envoy Gateway using a self-signed `*.local.test` wildcard certificate issued by cert-manager. Add the CA to your browser trust store to avoid certificate warnings (see `gitops/infrastructure/cert-manager/overlays/staging/cluster-issuer.yaml`).

---

## CI

| Workflow | File | Trigger | Purpose |
|---|---|---|---|
| CI | [`.github/workflows/ci.yaml`](.github/workflows/ci.yaml) | Push to `master`, Pull Requests | Change detection → unit tests → integration tests → Jib image publish to `ghcr.io` |

**No extra secrets required.** Jib authenticates to `ghcr.io` using the auto-provided `GITHUB_TOKEN`.

See [ARCHITECTURE.md — CI Pipeline Details](ARCHITECTURE.md#ci-pipeline-details) for change-detection flow, test gates, and image naming.

---

*Built with Java 25 · Spring Boot 4 · Next.js 16 · Auth.js v5 · Apache Kafka · MongoDB · PostgreSQL · Valkey · Keycloak · Envoy Gateway · OpenTelemetry · k3d*
