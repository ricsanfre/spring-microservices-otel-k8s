# ADR-009 — API-First Design with OpenAPI Generator

**Date:** 2026-04-25  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

Each microservice exposes a REST API. Two approaches exist for maintaining the API contract:

1. **Code-first** — write controllers; generate OpenAPI spec from annotations (`springdoc-openapi`)
2. **API-first** — write the OpenAPI spec; generate controller interfaces and model classes from it

In a code-first approach, the spec is a byproduct of the implementation. In an API-first approach, the spec is the single source of truth — it is defined before any implementation code exists.

---

## Decision

Use **API-first design**: every service defines its REST API as an **OpenAPI 3.1 specification** in `src/main/resources/openapi/{service}-api.yaml` before writing any implementation code. The `openapi-generator-maven-plugin` generates controller interfaces and model classes from the spec at compile time.

**The spec is never generated from code. Generated files are never manually edited.**

---

## Rationale

### Contract-first enables parallel work

When the spec is defined first, frontend teams, integration partners, and consumer services can:
- Mock the API (e.g., Prism, WireMock) before the implementation is ready
- Write integration tests against the spec
- Generate client SDKs in any language from the same spec

In a code-first approach, the spec is only available after the controller is implemented.

### `interfaceOnly: true` — clean separation of contract and implementation

The generator produces a Java interface annotated with Spring MVC annotations (`@RequestMapping`, `@PathVariable`, etc.):

```java
// Generated — never touch
public interface OrdersApi {
    @PostMapping("/api/v1/orders")
    ResponseEntity<OrderResponse> createOrder(@RequestBody @Valid CreateOrderRequest request);

    @GetMapping("/api/v1/orders/{id}")
    ResponseEntity<OrderResponse> getOrderById(@PathVariable UUID id);
}
```

The controller implements the interface:

```java
// Hand-written
@RestController
@RequiredArgsConstructor
public class OrderController implements OrdersApi {

    private final OrderService orderService;

    @Override
    public ResponseEntity<OrderResponse> createOrder(CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(orderService.create(request));
    }
}
```

This pattern enforces that:
- The controller **cannot drift** from the spec — any breaking change in the spec causes a compile error
- There is no `@RequestMapping` on the controller class; all routing annotations live in the generated interface
- Business logic in the service layer is completely independent of the HTTP contract

### Bean validation from spec

Setting `useBeanValidation: true` in the generator config causes the generator to emit JSR-303/380 annotations directly on model fields from OpenAPI `minLength`, `maxLength`, `pattern`, `minimum`, `maximum` constraints. Validation rules are defined once (in the spec) and applied everywhere.

### Lombok annotations on generated models

The generator config includes:

```xml
<additionalModelTypeAnnotations>
    @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
</additionalModelTypeAnnotations>
```

This makes every generated model class a builder-enabled immutable POJO — usable directly in test code without constructor telescoping.

### Swagger UI from the spec

`springdoc-openapi-starter-webmvc-ui` serves the OpenAPI spec and renders the Swagger UI from the same spec file. No annotation scanning needed — the spec is the documentation.

### Why not code-first (springdoc annotation scanning)

| Concern | Code-first | API-first |
|---------|-----------|----------|
| Source of truth | Controller annotations | OpenAPI YAML spec |
| Contract availability | After implementation | Before implementation |
| Drift prevention | None — spec reflects whatever is coded | Compile error on drift |
| Cross-team workflow | Sequential (implement → share spec) | Parallel (share spec → implement) |
| Spec quality | Reflects implementation; no guarantee of consistency | Explicitly designed contract |
| Bean validation source | Repeated in annotations AND spec | Spec only (generated to annotations) |

Code-first is convenient for rapid prototyping. For a multi-service platform where API contracts are shared between services, the risks of silent drift and sequential workflows outweigh the convenience.

---

## Consequences

### Positive

- **No contract drift** — compile-time enforcement that the controller matches the spec
- **Parallel development** — spec drives mock servers and client generation before implementation
- **Single source of validation rules** — constraints defined in the spec, generated to Java annotations
- **Clean controller code** — no routing annotations in controller classes; only business logic

### Neutral

- Generated sources land in `target/generated-sources/openapi/` — never committed to git, always regenerated on `mvn compile`
- The generator adds a `mvn generate-sources` phase; first build after a spec change regenerates all model and interface files

### Negative / Trade-offs

- **Spec maintenance** — changes require editing the YAML spec explicitly. For quick iterations this can feel slower than adding an annotation. Accepted trade-off: the spec is the contract, not the code.
- **Generator version lock-in** — model generation behaviour can subtly change between `openapi-generator` versions. Pin the version in the root POM `<pluginManagement>`.
- **Custom annotations on generated models** — the `additionalModelTypeAnnotations` approach works but means every model gets `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` regardless of whether they're needed. Acceptable for the scale of this project.

---

## Implementation Notes

- Generator plugin: `org.openapitools:openapi-generator-maven-plugin:7.12.0`
- Generator: `spring` (produces Spring MVC interfaces + model classes)
- Config: `interfaceOnly: true`, `useTags: true`, `useBeanValidation: true`, `useJakartaEe: true`, `openApiNullable: false`
- Spec location: `src/main/resources/openapi/{service}-api.yaml`
- Plugin configured in root POM `<pluginManagement>`; activated in each service `<build><plugins>`
- See `development-guidelines.md` Section 3 for full plugin configuration
