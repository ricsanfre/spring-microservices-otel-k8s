# Observability Improvement Plan - OpenTelemetry + Grafana Correlation

**Last updated:** 2026-06-03  
**Status:** Active plan (AS-IS revalidated against current codebase)

---

## Table of Contents

1. [Historical Gap Analysis (Original Plan, Status Updated)](#1-historical-gap-analysis-original-plan-status-updated)
2. [AS-IS - Current State (Revalidated)](#2-as-is---current-state-revalidated)
3. [TO-BE - Target State (Trace<->Logs Focus)](#3-to-be---target-state-tracelogs-focus)
4. [Remaining Gaps (Current)](#4-remaining-gaps-current)
5. [Implementation Plan (Phased)](#5-implementation-plan-phased)
6. [Acceptance Criteria](#6-acceptance-criteria)
7. [Operational Queries and Debug Workflow](#7-operational-queries-and-debug-workflow)

---

## 1. Historical Gap Analysis (Original Plan, Status Updated)

This section keeps the initial gap list from the previous version of this document and updates each item with current execution status and evidence of how it was implemented.

| Original gap (from initial plan) | Current status | How it has been done |
|---|---|---|
| Missing MongoDB observation config in reviews-service | Done | Added manual MongoDB observation wiring via `MongoObservationConfig` and enabled Mongo command observations with `management.observations.enable.spring.data.mongodb.command: true` in reviews-service and product-service. |
| No business metrics | Done | Added `MeterRegistry`-based counters/histograms in service layer across services (orders, cart checkout funnel, product creation/search, reviews, users, notifications). |
| No user identity on spans/logs | Done (major business flows) | Added user context propagation using `Tracer` span tags (for example `user.id`) and MDC population/removal (`MDC.put` + `finally MDC.remove`) in key service methods. |
| No Caffeine cache metrics for user resolver cache | Done | Replaced generic cache manager usage with native Caffeine caches + `recordStats` + `CaffeineCacheMetrics.monitor(..., "user.id.resolution")` in cart/order/reviews HTTP client config. |
| No custom spans for checkout | Done | Implemented phase-level spans in `CartService.checkout`: `checkout.validate`, `checkout.reserve`, `checkout.confirm` (plus parent `cart.checkout`) with tags for user/order/result context. |
| No custom span for sub->userId resolution | Done | Added explicit `user.resolve.idp_subject` spans in resolver services using `Tracer.nextSpan()`. |
| Unstructured log conventions | In progress | Standardized `operation=<...> outcome=<...>` logs are implemented in cart/order/reviews/user/notification write paths; remaining work is to complete and enforce the same convention in all remaining business paths (notably product-service and cross-cutting error paths). |

## 2. AS-IS - Current State (Revalidated)

### 2.1 Infrastructure and signal pipeline

The platform exports all three OTel signals (traces, metrics, logs):

- **Local Docker Compose:** `grafana/otel-lgtm` (`:3000`, `:4317`, `:4318`)
- **Kubernetes staging:** OTel Collector fan-out to Tempo + Loki + Prometheus/Grafana

### 2.2 Baseline instrumentation status

The following baseline is present in all six backend services (`product`, `reviews`, `order`, `cart`, `user`, `notification`):

- `spring-boot-starter-opentelemetry`
- `opentelemetry-logback-appender-1.0`
- `InstallOpenTelemetryAppender`
- `logback-spring.xml` with `CONSOLE` + `OTEL` appenders
- OTLP endpoints configured for traces, metrics, and logs
- `management.tracing.sampling.probability: 1.0`

### 2.3 Automatic instrumentation status

| Signal | Status | Notes |
|---|---|---|
| HTTP server spans | Implemented | Spring MVC auto-observation |
| HTTP client spans | Implemented | RestClient calls instrumented |
| PostgreSQL spans | Implemented where applicable | user-service and order-service |
| MongoDB command spans | Implemented | product-service and reviews-service use `MongoObservationConfig` + `management.observations.enable.spring.data.mongodb.command: true` |
| Kafka producer spans | Implemented | order-service (`template.observation-enabled: true`) |
| Kafka consumer spans | Implemented | cart-service and notification-service (`listener.observation-enabled: true`) |

### 2.4 Business metrics and custom observability

Most items previously listed as gaps are now implemented.

| Capability | Current status |
|---|---|
| Business counters/histograms | Implemented across services (`orders.created`, `order.value.amount`, `cart.items.added`, `cart.checkout.*`, `products.created`, `product.search.results`, `reviews.submitted`, `review.rating`, `users.registered`, `notifications.sent`) |
| Caffeine cache metrics for user resolution | Implemented in cart-service, order-service, reviews-service (`user.id.resolution`) |
| User ID on span + MDC | Implemented in major user-facing flows (order/cart/reviews/user services) |
| Custom spans | Implemented for checkout and order confirmation critical paths (`cart.checkout`, `checkout.validate`, `checkout.reserve`, `checkout.confirm`, `order.confirm`, `order.confirm.reserve_stock`, `order.confirm.persist_status`, `order.confirm.publish_event`, `user.resolve.idp_subject`) |

### 2.5 Grafana trace-to-logs and logs-to-traces status

For Kubernetes monitoring values, Grafana datasources are provisioned with correlation wiring:

- Tempo `tracesToLogsV2` query uses `trace_id`
- Loki `derivedFields` maps `trace_id` to Tempo datasource

This means trace<->logs linking is configured in staging manifests. For local Compose, linkage depends on `grafana/otel-lgtm` built-in defaults and should be explicitly validated in runtime checks.

### 2.6 Remaining AS-IS weaknesses

Current weaknesses are mostly about **consistency and operability**, not missing base plumbing:

1. Structured log conventions are partially standardized; broad coverage exists in cart/order/reviews/user/notification write paths, but full platform-wide consistency is still pending.
2. Custom spans are implemented for checkout and order confirmation; remaining work is to extend similar phase-level spans to additional high-value flows beyond these two critical paths.
3. Correlation behavior is not continuously verified by CI smoke tests.
4. Local (Compose) Grafana correlation behavior is not declared as code in this repository.

### 2.7 Current Custom Span Catalog

| Span name | Service | Trigger point | Key tags |
|---|---|---|---|
| `cart.checkout` | cart-service | `CartService.checkout` parent flow span | `user.id` |
| `checkout.validate` | cart-service | cart validation + item mapping before order call | `cart.item.count` |
| `checkout.reserve` | cart-service | order creation call to order-service | `order.id` |
| `checkout.confirm` | cart-service | checkout completion/failure branch | `order.id`, `result` |
| `order.confirm` | order-service | `OrderService.confirmOrder` parent flow span | `order.id`, `user.id` |
| `order.confirm.reserve_stock` | order-service | stock reservation call to product-service | `order.id`, `result` |
| `order.confirm.persist_status` | order-service | status transition persist (`PENDING` -> `CONFIRMED`) | `order.id`, `from_status`, `to_status` |
| `order.confirm.publish_event` | order-service | Kafka publish `order.confirmed.v1` | `order.id`, `result` |
| `user.resolve.idp_subject` | cart/order/reviews services | internal user resolution from JWT `sub` | `cache.hit`, `user.id` |

---

## 3. TO-BE - Target State (Trace<->Logs Focus)

### 3.1 Main objective

Make Grafana debugging reliable and fast by ensuring any incident can be navigated in both directions:

- **Trace -> Logs:** from a Tempo span to only the relevant Loki log lines.
- **Logs -> Trace:** from an error log line to the exact Tempo trace.

### 3.2 Correlation contract (platform-wide)

Define and enforce a single logging and tracing contract:

#### Required log fields (all services, all key INFO/WARN/ERROR events)

- `trace_id`
- `span_id`
- `service.name`
- `operation`
- `outcome` (`success` / `failure`)
- `error.type` (when applicable)
- `error.message` (when applicable)

#### Required domain context fields (when known)

- `user.id`
- `orderId`
- `cartId`
- `productId`
- `reviewId`

### 3.3 Labeling policy for Loki

- Keep low-cardinality fields as labels (for filtering performance).
- Keep high-cardinality identifiers (`user.id`, `orderId`, etc.) as structured fields, not labels.

### 3.4 Span model target

Refine custom spans for critical workflows:

- `checkout.validate`
- `checkout.reserve`
- `checkout.confirm`
- keep `user.resolve.idp_subject` with clear tags (`cache.hit`, `user.id`)

### 3.5 Operations target

- Saved Grafana Explore queries for common debugging paths.
- Correlation dashboard for error triage (errors, slow traces, direct links).
- CI smoke check for trace-log linkage regression.

---

## 4. Remaining Gaps (Current)

| Gap | Severity | Scope | Comment |
|---|---|---|---|
| Inconsistent structured log field naming | Medium | Remaining non-standardized paths | Main remaining blocker for uniform Loki filtering |
| No CI validation for trace<->logs linkage | High | Platform-wide | Correlation can silently regress |
| Local Grafana datasource linkage not codified in repo | Medium | Local dev | Drift risk across environments |
| Uneven MDC/trace field coverage outside key flows | Medium | Some service paths | Improves completeness for edge/debug cases |

---

## 5. Implementation Plan (Phased)

### Phase 0 - Baseline verification and inventory (1 day)

1. Run a standard synthetic flow: browse product -> add to cart -> checkout -> review.
2. Capture one known `trace_id` and verify:
   - Tempo -> Loki navigation works.
   - Loki -> Tempo navigation works.
3. Record current false positives/empty-result cases.

### Phase 1 - Correlation contract rollout (2-4 days)

1. Standardize key log field names and message pattern in service-layer mutating operations.
2. Ensure all important failure paths emit `outcome=failure` and exception context.
3. Keep MDC lifecycle strict (`put` + `finally remove`) where user context is set.

### Phase 2 - Span granularity upgrade (1-2 days)

1. Split cart checkout into phase spans:
   - `checkout.validate`
   - `checkout.reserve`
   - `checkout.confirm`
2. Ensure each span carries useful tags (`user.id`, `order.id`, `result`).

### Phase 3 - Grafana provisioning hardening (1-2 days)

1. Keep Kubernetes datasource settings as the source of truth.
2. Add explicit validation for local Compose behavior and document the expected datasource/link configuration.
3. Ensure Tempo `tracesToLogsV2` query and Loki `derivedFields` stay aligned on `trace_id`.
4. Run runtime datasource smoke check against Grafana API: `make obs-correlation-runtime-check`.

### Phase 4 - Quality gates and runbook (1-2 days)

1. Add CI smoke validation that asserts one request produces correlated traces and logs.
2. Add a short runbook for on-call flow:
   - Start from error log -> open trace -> pivot back to logs by tags.
3. Add a compact Grafana dashboard for correlation-first debugging.
4. Run end-to-end synthetic correlation check (when user-service is running): `make obs-correlation-e2e-check`.

---

## 6. Acceptance Criteria

The plan is complete when all criteria below pass:

1. For a synthetic request, both navigation paths work reliably:
   - Tempo span -> Loki logs
   - Loki log (`trace_id`) -> Tempo trace
2. Runtime Grafana datasource validation passes locally (`make obs-correlation-runtime-check`).
3. At least 95% of ERROR logs include `trace_id` and `span_id`.
4. Key mutating operations across services emit standardized operation/outcome fields.
5. Checkout traces show phase-level spans (`validate/reserve/confirm`) with meaningful tags.
6. CI catches broken correlation wiring before merge.
7. End-to-end synthetic check can be executed successfully in local dev (`make obs-correlation-e2e-check`) with required services running.

---

## 7. Operational Queries and Debug Workflow

### 7.1 Recommended Loki queries

```logql
{service_name="order-service"} | trace_id="<trace_id>"
```

```logql
{service_name="cart-service"} | user.id="<user_uuid>" | outcome="failure"
```

### 7.2 Recommended Tempo search filters

- `service.name = cart-service`
- `span.user.id = <user_uuid>`
- `status = error`

### 7.3 Standard incident path

1. Start from an ERROR log in Loki.
2. Open linked trace in Tempo.
3. Inspect slow/error child spans.
4. Pivot back to logs for that `trace_id` and service.
5. Confirm root cause with domain identifiers (`orderId`, `productId`, `user.id`).
