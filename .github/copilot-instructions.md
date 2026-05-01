# Copilot Instructions — E-Commerce Microservice Platform

## What This Repository Is

Spring Boot 4 microservice e-commerce platform. **Java 25, Maven multi-module, MVC (never WebFlux).** Seven backend services + Next.js 15 frontend. Each service is an independent Spring Boot application sharing a root Maven BOM.

**Implemented services (have full source + tests):** `common`, `product-service`, `user-service`, `cart-service`  
**In-progress (pom.xml scaffold only, missing `src/` or OpenAPI spec):** `order-service`, `reviews-service`, `notification-service`

**Trust these instructions first.** Only search the codebase if something here is incomplete or appears wrong.

---

## Build & Test Commands

**Prerequisites:** Java 25 (temurin-25), Maven 3.9+, Docker (for integration tests). Managed via `.mise.toml` — run `mise install` from repo root to install all tools.

### Always build with `common` included

Every service depends on the `common` module. Always use `-pl common,<service> -am`.

```bash
# Compile + package (skip tests)
mvn -pl common,<service> -am package -DskipTests --no-transfer-progress

# Unit tests only — fast, no Docker (Surefire runs *Test.java)
mvn -pl common,<service> -am test --no-transfer-progress

# Unit + integration tests — requires Docker (Failsafe runs *IT.java)
mvn -pl common,<service> -am verify --no-transfer-progress

# Full project build — FAILS on order-service/reviews-service/notification-service
# (missing OpenAPI specs). Build only implemented services:
mvn -pl common,product-service,user-service,cart-service -am package -DskipTests --no-transfer-progress
```

**Do NOT run `mvn package` or `mvn test` from root without `-pl` — it will fail** because `order-service`, `reviews-service`, and `notification-service` have `openapi-generator-maven-plugin` configured but their `src/main/resources/openapi/*.yaml` specs do not yet exist.

### Test naming convention (enforced by Maven plugins)

| Suffix | Plugin | Phase | Command |
|--------|--------|-------|---------|
| `*Test.java` | Surefire | `test` | `mvn test` |
| `*IT.java` | Failsafe | `integration-test`/`verify` | `mvn verify` |

**Both plugins are configured in root `pom.xml` `<pluginManagement>` — child modules must not re-specify config, only declare the plugin.**

### Infrastructure (Docker Compose)

```bash
# Start minimal infra (Postgres, MongoDB, Valkey, Keycloak) — needed for integration tests
make infra-min-up       # profiles: infra + auth
# With observability (Grafana LGTM on :3000, OTLP on :4317/:4318)
make infra-up           # profiles: infra + auth + observability
make infra-down         # stop (volumes preserved)
make infra-clean        # stop + delete volumes
```

---

## Project Layout

```
pom.xml                          ← root BOM: Spring Boot 4.0.5 parent, all version pins
.mise.toml                       ← tool versions (java=temurin-25, maven=3.9, node=22)
Makefile                         ← dev targets (infra-up, *-build, *-test, *-verify, k8s-*)
compose.yaml                     ← Docker Compose (profiles: infra, auth, observability)
.github/workflows/ci.yaml        ← CI: change detection, unit tests, integration tests, Jib → ghcr.io
.github/workflows/cd.yaml        ← CD: ephemeral k3d staging cluster + smoke tests
design/development-guidelines.md ← AUTHORITATIVE coding rules — read before implementing
design/adr-*.md                  ← Architecture decision records
docker/postgres/init-databases.sh
docker/mongo/init-products.js
k8s/                             ← Kubernetes manifests (k3d cluster, Helm values)
<service>/
  pom.xml                        ← inherits root POM; lists deps without versions
  src/main/java/com/ricsanfre/<service>/
    <Service>Application.java
    controller/
    service/
    repository/
    domain/
    config/SecurityConfig.java
    config/HttpClientConfig.java  ← (services calling other services only)
    otel/InstallOpenTelemetryAppender.java
    client/UserServiceClient.java ← (services resolving user IDs only)
  src/main/resources/
    openapi/<service>-api.yaml   ← OpenAPI 3.1 spec (API-first, written before code)
    application.yaml
    logback-spring.xml
  src/test/java/com/ricsanfre/<service>/
    service/*Test.java           ← unit tests (Mockito, no containers)
    controller/*Test.java        ← @WebMvcTest slice tests
    controller/*IT.java          ← @SpringBootTest + TestContainers
common/src/main/java/com/ricsanfre/common/
  exception/GlobalExceptionHandler.java
  exception/ResourceNotFoundException.java
  exception/BusinessRuleException.java
  security/JwtUtils.java
```

**GroupId:** `com.ricsanfre` — package structure follows `com.ricsanfre.<service-short-name>`.

---

## Key Architectural Rules (from `design/development-guidelines.md`)

### API-First (mandatory)
- Write `src/main/resources/openapi/<service>-api.yaml` (OpenAPI 3.1) **before** any controller code.
- `openapi-generator-maven-plugin` generates interface + model at compile time (`interfaceOnly: true`, `useSpringBoot3: true`).
- Controller implements the generated interface. **Never edit generated files.**
- Add `@RequestMapping("/api/v1")` on the **controller class** — the generator ignores `servers[0].url`. Do NOT use `server.servlet.context-path`.

### Spring Boot 4 Breaking Changes — Must Know
- **Jackson 3.x:** groupId changed to `tools.jackson.core`. Use `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson.databind.ObjectMapper`.
- **MockMvc:** `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure`. Requires separate `spring-boot-webmvc-test` artifact (already in child poms).
- **Flyway:** `flyway-core` alone does NOT register `FlywayAutoConfiguration`. Must use `spring-boot-starter-flyway` + `flyway-database-postgresql`.
- **MongoDB URI:** Use `spring.mongodb.uri` (NOT `spring.data.mongodb.uri` — silently connects to `test` DB).

### @WebMvcTest Security Setup
Exclude OAuth2 auto-configs and provide a `@TestConfiguration` with `@EnableWebSecurity` (no `@EnableMethodSecurity`). Always add `@MockitoBean JwtDecoder jwtDecoder` to prevent JWK URI fetch at startup. See `cart-service` tests for the exact pattern.

### HTTP Clients (service-to-service)
Use Spring 6 HTTP Interfaces (`@HttpExchange`) + `RestClient` with `OAuth2ClientHttpRequestInterceptor`. Plain Kubernetes DNS `http://service-name:port`. Never Feign, never Spring Cloud LoadBalancer, never Eureka.

### User Identity (ADR-004)
Never use the Keycloak `sub` as a data key. Extract `sub` from JWT → call `GET /api/v1/users/resolve?idp_subject={sub}` on `user-service` → cache result with Caffeine (10-min TTL). Field name is `idp_subject` (not `keycloak_id`).

### Lombok Rules
- `@RequiredArgsConstructor` for constructor injection everywhere.
- **Never `@Data` on JPA entities** — use `@Getter @Setter` + explicit `@ToString(exclude)`.
- `@Data` is safe on MongoDB documents and plain DTOs.

### Persistence
- **PostgreSQL:** Spring Data JPA + Flyway. Migrations in `src/main/resources/db/migration/V{n}__{desc}.sql`. Enable with `@EnableJpaAuditing`.
- **MongoDB:** Spring Data MongoDB, URI via `spring.mongodb.uri`. Enable with `@EnableMongoAuditing`.
- **Valkey/Redis (cart-service):** Primary store via `StringRedisTemplate` + manual `ObjectMapper`. Caffeine is a separate in-process L1 cache for user-ID resolution only.

### Security Pattern (every service)
JWT RS256 from Keycloak. JWKS: `http://keycloak:8180/realms/e-commerce/protocol/openid-connect/certs`. Scopes: `products:read`, `orders:read/write`, `reviews:read/write`, `users:read`, `users:resolve`, `cart:read/write`. Default Spring Security converter maps `scope` claim → `SCOPE_` prefix authorities. No custom converter needed.

### Observability (every service)
`spring-boot-starter-opentelemetry` + `opentelemetry-logback-appender-1.0:2.21.0-alpha`. Add `InstallOpenTelemetryAppender implements InitializingBean` + `logback-spring.xml` with `CONSOLE` + `OTEL` appenders. OTLP HTTP endpoint: `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}`.

---

## CI/CD — GitHub Actions

Two workflows are implemented in `.github/workflows/`.

### CI workflow (`.github/workflows/ci.yaml`)

Triggers on `push` and `pull_request` to `main`.

**Jobs:**
1. `detect-changes` — `dorny/paths-filter@v3` produces a JSON array `java-services` (changed services) and a boolean `frontend`. Changes to `common/` or root `pom.xml` mark **all** Java services as changed (conservative).
2. `build-java` — matrix over `java-services`. For each service:
   - `actions/setup-java@v4` (temurin, java-version: `'25'`, cache: maven)
   - `mvn -pl <service> -am test` — fast gate (Surefire, no Docker)
   - `mvn -pl <service> -am verify` — full gate (Failsafe + Testcontainers, Docker available)
   - On `push` to `main` only: `mvn -pl <service> compile jib:build` pushes to `ghcr.io/<owner>/<service>:<sha>` and `:latest`
3. `build-frontend` — Node 22, `npm ci`, `npm run build`, then `docker/build-push-action@v6` to ghcr.io.

**Secrets:** only `GITHUB_TOKEN` (auto-provided). No manually configured secrets.

**Permissions:** top-level `contents: read`. Jobs that push images add `packages: write`.

**Jib image naming:** `-Ddocker.registry=ghcr.io/<owner>` overrides the default `localhost:5000` from the root POM `<docker.registry>` property.

### CD workflow (`.github/workflows/cd.yaml`)

Triggers via `workflow_run` on CI completing successfully on `main`.

**Job `deploy-staging`:**
1. Installs k3d, Helm, kustomize on the runner.
2. Creates ephemeral k3d cluster with ports `80:80` and `443:443`, Traefik disabled.
3. Applies `k8s/namespaces.yaml` (all namespaces, matching production).
4. Installs operators via Helm/Kustomize — same commands as `make k8s-operators`, scoped to current services:
   - `cert-manager` (jetstack/cert-manager Helm v1.16.2)
   - `Envoy Gateway` (envoyproxy/gateway-helm OCI Helm v1.4.1)
   - `CloudNativePG` (cnpg/cloudnative-pg Helm)
   - `Keycloak Operator` (`kustomize build k8s/infra/keycloak/operator | sed version | kubectl apply`)
5. Creates all infra secrets in the relevant namespaces with CI-only dummy values **before** kustomize applies (so CNPG managed.roles and Keycloak use consistent passwords from first reconciliation).
6. Deploys infrastructure using the same `kubectl apply -k k8s/infra/...` commands as `make k8s-infra`:
   - `k8s/infra/cert-manager` → wait for `local-test-ca-issuer` ClusterIssuer Ready
   - `k8s/infra/postgres` → wait for CNPG Cluster + `users-db` + `keycloak-db` Database CRs Ready
   - `k8s/infra/valkey` → wait for rollout
   - `k8s/infra/keycloak` → wait for Keycloak CR Ready
   - `k8s/envoy-gateway`
7. Creates app secrets (`user-service-db-secret`, `ghcr-pull-secret`) in `e-commerce` namespace.
8. For each service: `kustomize edit set image localhost:5000/<svc>=ghcr.io/<owner>/<svc>:<sha>` + patches `imagePullSecrets` via `kustomize edit add patch`, then `kubectl apply -k`.
9. Smoke tests: `kubectl port-forward` → `curl /actuator/health` → `grep '"status":"UP"'`. No JWT required (Spring Boot JWKS fetch is lazy).
10. `k3d cluster delete e-commerce || true` runs on `always()` to clean up.

**Services in CD:** `user-service` (PostgreSQL) and `cart-service` (Valkey). Others added as their K8s manifests are completed.

### Adding a new service to CI/CD

- **CI:** add the service name to the `java-filter` in `detect-changes` (`.github/workflows/ci.yaml`).
- **CD:** add a deploy step following the Kustomize patch pattern and a smoke-test `curl` call.
- Both require a complete `k8s/apps/<service>/overlays/staging/kustomization.yaml`.

When creating new services, add `maven-failsafe-plugin` to the service `pom.xml` only if it has `*IT.java` tests.

---

## Adding a New Service — Checklist

1. Add module to root `pom.xml` `<modules>`.
2. Create `<service>/pom.xml` — parent = root POM, no version on any dep or plugin.
3. Write `src/main/resources/openapi/<service>-api.yaml` **first**.
4. Generate: `mvn -pl common,<service> -am generate-sources`.
5. Implement controller extending generated interface; add `@RequestMapping("/api/v1")` on class.
6. Add `SecurityConfig`, `GlobalExceptionHandler` (extend from `common`), `InstallOpenTelemetryAppender`, `logback-spring.xml`.
7. For services calling other services: add `HttpClientConfig` with `UserServiceClient` + Caffeine cache.
8. Name unit tests `*Test.java`, integration tests `*IT.java`.
9. Build/test: `mvn -pl common,<service> -am verify --no-transfer-progress`.
