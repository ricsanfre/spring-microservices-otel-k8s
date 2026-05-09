# Observability Improvement Plan — OpenTelemetry

**Date:** 2026-05-09  
**Status:** Approved — implementation in progress  
**Scope:** All six backend services (product-service, user-service, cart-service, order-service, reviews-service, notification-service)

---

## Table of Contents

1. [AS-IS — Current State](#1-as-is--current-state)
2. [TO-BE — Target State](#2-to-be--target-state)
3. [Gap Analysis](#3-gap-analysis)
4. [Implementation Plan](#4-implementation-plan)
5. [Signal Reference — Metrics Catalogue](#5-signal-reference--metrics-catalogue)
6. [Signal Reference — Custom Spans Catalogue](#6-signal-reference--custom-spans-catalogue)

---

## 1. AS-IS — Current State

### 1.1 Infrastructure

All six services export to a unified **Grafana LGTM** stack running in Docker Compose under the `observability` profile:

| Component | Port | Role |
|-----------|------|------|
| Grafana | 3000 | Unified UI (dashboards, explore) |
| Loki | — | Log aggregation |
| Tempo | — | Distributed tracing |
| Prometheus | — | Metrics scraping |
| OTLP collector | 4317 (gRPC) / 4318 (HTTP) | Ingest endpoint |

### 1.2 Per-Service Baseline Setup

Every service has **identical** boilerplate:

| Component | Location | Status |
|-----------|----------|--------|
| `spring-boot-starter-opentelemetry` | `pom.xml` | ✅ All 6 services |
| `opentelemetry-logback-appender-1.0:2.21.0-alpha` | `pom.xml` | ✅ All 6 services |
| `InstallOpenTelemetryAppender` | `otel/InstallOpenTelemetryAppender.java` | ✅ All 6 services |
| `logback-spring.xml` (CONSOLE + OTEL appenders) | `src/main/resources/` | ✅ All 6 services |
| `management.tracing.sampling.probability: 1.0` | `application.yaml` | ✅ All 6 services |
| OTLP metrics + traces + logs export | `application.yaml` | ✅ All 6 services |
| Actuator endpoints exposed (health, info, metrics, prometheus) | `application.yaml` | ✅ All 6 services |

### 1.3 Automatic (Zero-Code) Instrumentation in Place

The following signals are collected with no custom code:

| Signal | Mechanism | Services |
|--------|-----------|----------|
| Inbound HTTP request latency + status | Spring MVC auto-observation | All 6 |
| Outbound HTTP call latency + status | `RestClient` auto-observation | cart, order, reviews (user-service calls) |
| Redis / Valkey command latency | Lettuce Micrometer observation | cart |
| PostgreSQL query spans | `datasource-micrometer-spring-boot` — activates automatically when jar + `ObservationRegistry` are present; no `enabled` flag needed | user, order |
| MongoDB command spans | `MongoObservationCommandListener` — requires explicit `management.observations.mongodb.enabled: true` in YAML | product only |
| Kafka producer spans | `spring.kafka.template.observation-enabled: true` | order |
| Kafka consumer spans | `spring.kafka.listener.observation-enabled: true` | notification, cart |
| Distributed trace context propagation (W3C TraceContext) | OTLP auto | All cross-service calls |

> **JDBC properties note:** `datasource-micrometer-spring-boot` uses the prefix `jdbc` (not `management.*`).
> The properties that matter are `jdbc.includes` (which trace types: `QUERY`, `CONNECTION`, `FETCH`, `KEYS` — all enabled by default)
> and `jdbc.datasource-proxy.include-parameter-values` (bind parameter values in spans — **off by default**, keep off in production due to PII risk;
> enable only on a `local` profile for debugging).

### 1.4 What Is NOT Collected Today

- ❌ **No business metrics** — no counters for orders created, reviews submitted, cart checkouts, etc.
- ❌ **No custom spans** — all traces stop at the controller boundary; internal service method durations are invisible.
- ❌ **No user identity on spans/logs** — traces in Tempo cannot be filtered by the end-user who triggered them.
- ❌ **No Caffeine cache metrics** — hit/miss ratio for the sub→userId resolver cache is invisible.
- ❌ **No structured log field conventions** — log messages use ad-hoc string formats; Loki cannot filter by `order.id`, `user.id`, etc.
- ❌ **reviews-service MongoDB observation** — the `management.observations.mongodb.enabled: true` property is absent (present in product-service but missing in reviews-service).

---

## 2. TO-BE — Target State

### 2.1 Three-Signal Completeness

Every service emits all three OTel signal types with **both automatic and business-level data**:

```
Traces  → Tempo    : HTTP spans + DB spans + custom domain spans + user.id attribute
Metrics → Prometheus: JVM + HTTP + DB + Kafka + business counters/histograms
Logs    → Loki     : Structured fields (order.id, user.id, product.id) + OTel correlation IDs
```

### 2.2 Business Metrics (TO-BE per service)

| Service | Key Metrics |
|---------|-------------|
| **order-service** | `orders.created` (counter, tag: status), `orders.status.changed` (counter, tags: from/to), `order.value.amount` (histogram) |
| **cart-service** | `cart.items.added` (counter), `cart.checkout.initiated` (counter), `cart.checkout.confirmed` (counter, tag: result), `user.id.resolution.cache.*` (Caffeine) |
| **product-service** | `products.created` (counter, tag: category), `product.search.results` (distribution summary) |
| **reviews-service** | `reviews.submitted` (counter), `review.rating` (distribution summary 1–5) |
| **user-service** | `users.registered` (counter), `users.idp.resolved` (counter, tag: cache=hit/miss) |
| **notification-service** | `notifications.sent` (counter, tags: type, status) |

### 2.3 Custom Spans (TO-BE per service)

Critical multi-step business flows gain child spans so Tempo shows per-step latency:

| Service | Custom Span Name | Parent |
|---------|-----------------|--------|
| **cart-service** | `checkout.validate` | `POST /api/v1/checkout/initiate` |
| **cart-service** | `checkout.reserve` | `POST /api/v1/checkout/initiate` |
| **cart-service** | `checkout.confirm` | `POST /api/v1/checkout/confirm` |
| **order-service** | `order.persist` | Kafka consumer span |
| **user-service** | `user.resolve.idp_subject` (tag: cache.hit) | HTTP span |

### 2.4 User Identity Propagation (TO-BE)

After the sub→userId resolution, inject the internal UUID into:
1. **Span attribute** `user.id` — enables per-user trace search in Tempo.
2. **MDC field** `user.id` — forwarded by the OTel logback appender as a log record attribute; Loki can then filter all logs for a given user.

### 2.5 Caffeine Cache Metrics (TO-BE)

The sub→userId resolution cache in cart-service (and any future service using ADR-004) exposes:
- `cache.gets{cache=user.id.resolution, result=hit}`
- `cache.gets{cache=user.id.resolution, result=miss}`
- `cache.evictions{cache=user.id.resolution}`

### 2.6 MongoDB Observation Coverage (TO-BE)

reviews-service gains `management.observations.mongodb.enabled: true`, matching product-service. Every MongoDB-backed service then emits DB command spans visible in Tempo.

### 2.7 Structured Log Conventions (TO-BE)

All `@Slf4j` log calls at `INFO`/`WARN`/`ERROR` for key domain events use consistent parameterised fields:

| Field | Type | Example value |
|-------|------|---------------|
| `order.id` | UUID string | `"3fa85f64-5717-4562-b3fc-2c963f66afa6"` |
| `user.id` | UUID string | `"550e8400-e29b-41d4-a716-446655440001"` |
| `product.id` | UUID/String | `"prod-abc123"` |
| `cart.id` | UUID string | same as user.id |
| `notification.type` | string | `"ORDER_CONFIRMATION"` |
| `failure.reason` | string | exception message or code |

---

## 3. Gap Analysis

| Gap | Severity | Affected Service(s) | Fix Size |
|-----|----------|---------------------|----------|
| Missing MongoDB observation config | 🔴 High | reviews-service | 1 line YAML |
| No business metrics | 🔴 High | All 6 | Medium — MeterRegistry injection per service |
| No user.id span attribute | 🟠 Medium | cart, order, reviews, user | Small — HandlerInterceptor or ObservationHandler |
| No Caffeine cache metrics | 🟡 Low | cart (+ future services) | 2 lines YAML + 1 line Java |
| No custom spans for checkout | 🟡 Low | cart-service | Small — Tracer injection in service layer |
| No custom spans for order pipeline | 🟡 Low | order-service | Small — Tracer injection in consumer |
| Unstructured log messages | 🟡 Low | All 6 | Editorial — applied incrementally |
| No custom span for sub→userId resolution | 🟡 Low | user (+ callers) | Small |

---

## 4. Implementation Plan

Ordered by business value / risk:

---

### Step 1 — Fix reviews-service MongoDB Observation Gap

**File:** `reviews-service/src/main/resources/application.yaml`

Add under the `management` block:

```yaml
management:
  observations:
    mongodb:
      enabled: true
```

**Validates:** Tempo shows `db.mongodb.find`, `db.mongodb.insert`, etc. spans for reviews-service queries.

---

### Step 2 — Caffeine Cache Metrics (cart-service)

**File:** `cart-service/src/main/resources/application.yaml`

Enable stats recording in the Caffeine spec:

```yaml
spring:
  cache:
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=10m,recordStats
```

**File:** `cart-service/src/main/java/com/ricsanfre/cart/config/HttpClientConfig.java`

Register the cache with Micrometer after it is created:

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import com.github.benmanes.caffeine.cache.Cache;

// Inject MeterRegistry and the Cache bean, then:
CaffeineCacheMetrics.monitor(meterRegistry, cache, "user.id.resolution");
```

**Validates:** `cache.gets{cache=user.id.resolution, result=hit|miss}` appears in Prometheus.

---

### Step 3 — Business Metrics — order-service

**File:** `order-service/src/main/java/com/ricsanfre/order/service/OrderService.java`

Inject `MeterRegistry` via constructor. After a successful `order.save()`:

```java
// Counter
Counter.builder("orders.created")
    .tag("status", order.getStatus().name())
    .register(meterRegistry)
    .increment();

// Histogram (revenue tracking)
DistributionSummary.builder("order.value.amount")
    .baseUnit("currency_units")
    .register(meterRegistry)
    .record(order.getTotalAmount().doubleValue());
```

After a status transition:

```java
Counter.builder("orders.status.changed")
    .tag("from", previousStatus.name())
    .tag("to", newStatus.name())
    .register(meterRegistry)
    .increment();
```

**Validates:** Grafana can render revenue over time (`rate(order_value_amount_sum[5m]) / rate(order_value_amount_count[5m])`).

---

### Step 4 — Business Metrics — cart-service

**File:** `cart-service/src/main/java/com/ricsanfre/cart/service/CartService.java`

```java
Counter.builder("cart.items.added")
    .register(meterRegistry).increment();

Counter.builder("cart.checkout.initiated")
    .register(meterRegistry).increment();

// In confirm flow:
Counter.builder("cart.checkout.confirmed")
    .tag("result", success ? "success" : "failure")
    .register(meterRegistry).increment();
```

**Validates:** Checkout funnel is visible in Grafana: `add → initiate → confirm` conversion rate.

---

### Step 5 — Business Metrics — product-service, reviews-service, user-service, notification-service

Apply the same `MeterRegistry` injection pattern to each service's relevant service-layer class:

| Service | Class | Metric |
|---------|-------|--------|
| product-service | `ProductService` | `products.created{category}`, `product.search.results` |
| reviews-service | `ReviewService` | `reviews.submitted`, `review.rating` (DistributionSummary 1–5) |
| user-service | `UserService` | `users.registered`, `users.idp.resolved{cache=hit|miss}` |
| notification-service | `NotificationService` | `notifications.sent{type, status}` |

---

### Step 6 — User Identity on Spans and Logs

Create a shared `HandlerInterceptor` (or Spring MVC `ObservationConvention`) that fires after the `UserIdResolverService` resolves the internal UUID. Add to each service that performs user resolution (cart, order, reviews, user):

**In the service layer, after resolution:**

```java
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;

// Inject Tracer via @RequiredArgsConstructor
Span currentSpan = tracer.currentSpan();
if (currentSpan != null) {
    currentSpan.tag("user.id", resolvedUserId.toString());
}
MDC.put("user.id", resolvedUserId.toString());
// ... proceed with business logic ...
MDC.remove("user.id"); // clean up in finally block
```

Use `io.micrometer.tracing.Tracer` (Micrometer Tracing) — already on the classpath via `spring-boot-starter-opentelemetry`. Do **not** import the raw OTel SDK `Tracer`.

**Validates:** In Tempo, querying `{span.user.id="<uuid>"}` returns all spans for that user's session. In Loki, `{user.id="<uuid>"}` returns all log lines.

---

### Step 7 — Custom Spans — cart-service Checkout Pipeline

Inject `io.micrometer.tracing.Tracer` into `CartCheckoutService` and wrap the two phases:

```java
// Initiate phase
Span validateSpan = tracer.nextSpan().name("checkout.validate").start();
try (Tracer.SpanInScope ws = tracer.withSpan(validateSpan)) {
    // validate cart, resolve user, call order-service
} finally {
    validateSpan.end();
}

// Confirm phase
Span confirmSpan = tracer.nextSpan().name("checkout.confirm").start();
try (Tracer.SpanInScope ws = tracer.withSpan(confirmSpan)) {
    // confirm with order-service, clear cart
} finally {
    confirmSpan.end();
}
```

**Validates:** Tempo flame graph for a checkout request shows `checkout.validate` and `checkout.confirm` sub-spans with their individual durations.

---

### Step 8 — Custom Span — user-service sub→userId Resolution

Inject `Tracer` into `UserIdResolverService`. Wrap the cache-miss DB lookup:

```java
Span span = tracer.nextSpan().name("user.resolve.idp_subject").start();
try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
    span.tag("cache.hit", "false");
    UUID id = userRepository.findByIdpSubject(idpSubject)
        .orElseThrow(() -> new ResourceNotFoundException("User", idpSubject))
        .getId();
    span.tag("user.id", id.toString());
    return id;
} finally {
    span.end();
}
```

For cache hits, add only the `span.tag("cache.hit", "true")` call (no DB span is emitted).

---

### Step 9 — Structured Log Conventions (incremental)

As each service's service-layer classes are touched during the steps above, replace ad-hoc log strings with parameterised key=value style:

```java
// Before
log.info("Order created for user {}", userId);

// After
log.info("Order created orderId={} userId={} totalAmount={} itemCount={}",
    order.getId(), userId, order.getTotalAmount(), order.getItemCount());
```

Use the field names defined in [Section 2.7](#27-structured-log-conventions-to-be) consistently across all services so Loki label queries work uniformly.

---

## 5. Signal Reference — Metrics Catalogue

| Metric Name | Type | Tags | Unit | Emitting Service |
|-------------|------|------|------|-----------------|
| `orders.created` | Counter | `status` | — | order-service |
| `orders.status.changed` | Counter | `from`, `to` | — | order-service |
| `order.value.amount` | DistributionSummary | — | currency units | order-service |
| `cart.items.added` | Counter | — | — | cart-service |
| `cart.checkout.initiated` | Counter | — | — | cart-service |
| `cart.checkout.confirmed` | Counter | `result` (success/failure) | — | cart-service |
| `cache.gets` | Counter | `cache`, `result` (hit/miss) | — | cart-service (Caffeine) |
| `cache.evictions` | Counter | `cache` | — | cart-service (Caffeine) |
| `products.created` | Counter | `category` | — | product-service |
| `product.search.results` | DistributionSummary | — | items | product-service |
| `reviews.submitted` | Counter | — | — | reviews-service |
| `review.rating` | DistributionSummary | — | rating (1–5) | reviews-service |
| `users.registered` | Counter | — | — | user-service |
| `users.idp.resolved` | Counter | `cache` (hit/miss) | — | user-service |
| `notifications.sent` | Counter | `type`, `status` | — | notification-service |

All standard JVM, HTTP, DB, and Kafka metrics continue to be emitted automatically.

---

## 6. Signal Reference — Custom Spans Catalogue

| Span Name | Service | Parent Span | Key Tags |
|-----------|---------|-------------|----------|
| `checkout.validate` | cart-service | `POST /api/v1/checkout/initiate` | `user.id` |
| `checkout.reserve` | cart-service | `POST /api/v1/checkout/initiate` | `order.id` |
| `checkout.confirm` | cart-service | `POST /api/v1/checkout/confirm` | `order.id`, `result` |
| `order.persist` | order-service | Kafka `order.created.v1` consumer | `order.id`, `user.id` |
| `user.resolve.idp_subject` | user-service | any HTTP span | `cache.hit`, `user.id` |

All standard HTTP, DB (JDBC + MongoDB), and Kafka spans continue to be emitted automatically.
