# Development Guidelines — E-Commerce Microservice Platform

> **Stack:** Java 25 · Spring Boot 4.0.x · Spring Cloud 2025.x · Maven · MVC (not Webflux)  
> These guidelines govern all microservices in this project. Follow them consistently across every service to ensure interoperability, maintainability, and observability.

---

## Table of Contents

1. [Project Structure (Maven Multi-Module)](#1-project-structure-maven-multi-module)
2. [Core Dependencies](#2-core-dependencies)
3. [API Design — API-First with OpenAPI](#3-api-design--api-first-with-openapi)
4. [REST API Versioning](#4-rest-api-versioning)
5. [HTTP Clients — Spring HTTP Interfaces](#5-http-clients--spring-http-interfaces)
6. [Resilience Patterns — Resilience4j](#6-resilience-patterns--resilience4j)
7. [Error Handling — RFC 7807 Problem Details](#7-error-handling--rfc-7807-problem-details)
8. [Data Access](#8-data-access)
9. [Security — OAuth2 Resource Server](#9-security--oauth2-resource-server)
10. [Kafka Messaging](#10-kafka-messaging)
11. [OpenTelemetry Observability](#11-opentelemetry-observability)
12. [Docker Images — Jib](#12-docker-images--jib)
13. [Code Style — Lombok](#13-code-style--lombok)
14. [Testing Strategy](#14-testing-strategy)
15. [Virtual Threads](#15-virtual-threads)
16. [Caching — Valkey / Spring Data Redis](#16-caching--valkey--spring-data-redis)
17. [Local Development Workflow (Docker Compose + Makefile)](#17-local-development-workflow-docker-compose--makefile)
18. [Kubernetes Deployment](#18-kubernetes-deployment)
19. [CI/CD — GitHub Actions](#19-cicd--github-actions)

---

## 1. Project Structure (Maven Multi-Module)

### Root POM layout

```
e-commerce/
├── pom.xml                        ← root (BOM + plugin management)
├── product-service/
├── order-service/
├── reviews-service/
├── notification-service/
├── user-service/
├── k8s/                           ← Kubernetes manifests
└── common/                        ← shared DTOs, exceptions, security helpers
```

### Root `pom.xml` responsibilities

- Declare `spring-boot-starter-parent` 4.0.x as parent
- Set `<java.version>25</java.version>`
- Manage **all** dependency versions in `<dependencyManagement>` — child modules inherit without specifying versions
- Manage **all** plugin versions in `<pluginManagement>`
- Never include runtime dependencies directly in the root POM

### Child module `pom.xml` responsibilities

- Declare parent as root POM (not `spring-boot-starter-parent` directly)
- List only the dependencies and plugins needed by that module, without versions
- Do **not** repeat plugin configuration already defined in root `<pluginManagement>`

### `common` module

Shared library (not a Spring Boot app — no `@SpringBootApplication`). Contains:
- Response/error DTOs (`ApiError`, `ProblemDetailUtil`)
- Security utilities (JWT claim extractors, `UserContext`)
- Base `GlobalExceptionHandler` that all services can extend or include
- OpenAPI model classes shared across services (when applicable)

---

## 2. Core Dependencies

### Every business service must include

```xml
<!-- Web (MVC — NOT Webflux) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>

<!-- Validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- Actuator (health, metrics endpoints) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-actuator</artifactId>
</dependency>

<!-- Security — OAuth2 Resource Server -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>

<!-- OpenTelemetry -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.21.0-alpha</version>
</dependency>

<!-- Lombok -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- Service discovery + load balancer (Kubernetes) -->
<!-- Removed: use plain Kubernetes Service DNS (http://service-name:port) instead.
     See design/adr-002-plain-kubernetes-dns-service-calls.md -->

<!-- Resilience4j -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
</dependency>

<!-- Swagger UI -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>
```

### PostgreSQL services additionally include

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<!-- spring-boot-starter-flyway provides spring-boot-flyway (autoconfigure module)
     + flyway-core. In Spring Boot 4, flyway-core alone does NOT register
     FlywayAutoConfiguration — the starter is required. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

### MongoDB services additionally include

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

### Kafka services additionally include

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

---

## 3. API Design — API-First with OpenAPI

Every service defines its REST API in an **OpenAPI 3.1 specification first**. Code is generated from the spec; the spec is never generated from code.

### File location

```
src/main/resources/openapi/
└── {service-name}-api.yaml      ← OpenAPI 3.1 spec
```

### Code generation with `openapi-generator-maven-plugin`

Add to the service's `pom.xml` inside `<build><plugins>`:

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>generate-api</id>
            <goals><goal>generate</goal></goals>
            <configuration>
                <inputSpec>${project.basedir}/src/main/resources/openapi/${project.artifactId}-api.yaml</inputSpec>
                <generatorName>spring</generatorName>
                <apiPackage>com.ricsanfre.${service}.api</apiPackage>
                <modelPackage>com.ricsanfre.${service}.api.model</modelPackage>
                <configOptions>
                    <useSpringBoot3>true</useSpringBoot3>
                    <interfaceOnly>true</interfaceOnly>
                    <useTags>true</useTags>
                    <dateLibrary>java8</dateLibrary>
                    <useBeanValidation>true</useBeanValidation>
                    <useJakartaEe>true</useJakartaEe>
                    <openApiNullable>false</openApiNullable>
                    <additionalModelTypeAnnotations>@lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor</additionalModelTypeAnnotations>
                </configOptions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Controller implementation pattern

The generated interface is implemented by the controller. **Never modify generated files.**

```java
// Generated (do not touch):
public interface ProductsApi {
    ResponseEntity<ProductResponse> getProductById(@PathVariable UUID id);
    // ...
}

// Hand-written controller:
@RestController
@RequiredArgsConstructor
public class ProductController implements ProductsApi {

    private final ProductService productService;

    @Override
    public ResponseEntity<ProductResponse> getProductById(UUID id) {
        return ResponseEntity.ok(productService.findById(id));
    }
}
```

### `servers.url` is NOT applied automatically by the generator

The `openapi-generator-maven-plugin` with `interfaceOnly: true` generates **method-level** `@RequestMapping` annotations only (e.g. `@RequestMapping(value = "/products/{id}", ...)`). It intentionally ignores the `servers[0].url` base path (`/api/v1`) so the same interface contract can be deployed behind an API gateway that re-strips the prefix.

**You must apply the base path yourself** by adding a class-level `@RequestMapping` on the concrete controller:

```java
@RestController
@RequestMapping("/api/v1")   // ← mirrors servers[0].url in the OpenAPI spec
@RequiredArgsConstructor
public class ProductController implements ProductsApi {
    // Spring MVC merges class-level + method-level mappings:
    //   /api/v1  +  /products/{id}  →  GET /api/v1/products/{id}
}
```

> **Do NOT use `server.servlet.context-path`** in `application.yaml` for this purpose — it shifts the entire servlet container (actuator, Swagger UI, etc.) and prevents multiple API versions from coexisting.

> **MockMvc tests** do not include the servlet context path, so integration tests use bare paths (`/products/{id}`) regardless of the `@RequestMapping` prefix on the controller — tests do not need to change.

### Swagger UI

Configure in `application.yaml`:

```yaml
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
```

---

## 4. REST API Versioning

Use **URL path versioning**: `/api/v{N}/resource`.

- All endpoints start with `/api/v1/`
- Breaking changes bump the version: `/api/v2/`
- Old versions remain live and deprecated for at least one release cycle before removal
- Mark deprecated paths in the OpenAPI spec with `deprecated: true`
- The API Gateway routes by prefix: `/api/v1/**` and `/api/v2/**` are separate routes

### Version naming in OpenAPI spec

```yaml
info:
  title: Product Service API
  version: "1.0.0"
servers:
  - url: /api/v1
```

---

## 5. HTTP Clients — Spring HTTP Interfaces

Use Spring 6 **HTTP Interfaces** (`@HttpExchange`) for all synchronous service-to-service calls. Do **not** use OpenFeign.

### Interface definition

```java
@HttpExchange("/api/v1")
public interface OrderServiceClient {

    @GetExchange("/orders/{orderId}")
    OrderResponse getOrderById(@PathVariable UUID orderId);

    @GetExchange("/orders/user/{userId}")
    List<OrderResponse> getOrdersByUserId(@PathVariable UUID userId);
}
```

### Bean configuration (with plain K8s DNS + OAuth2 token)

```java
@Configuration
public class HttpClientConfig {

    @Bean
    public OrderServiceClient orderServiceClient(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager authorizedClientManager) {

        RestClient restClient = builder
            .baseUrl("http://order-service:8082")
            .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(authorizedClientManager))
            .build();

        HttpServiceProxyFactory factory = HttpServiceProxyFactory
            .builderFor(RestClientAdapter.create(restClient))
            .build();

        return factory.createClient(OrderServiceClient.class);
    }
}
```

Key rules:
- Use **plain Kubernetes Service DNS** `http://service-name:port` — kube-proxy handles server-side load balancing. No `spring-cloud-starter-kubernetes-client-loadbalancer`, no Eureka, no RBAC permissions to the Kubernetes API. See [adr-002-plain-kubernetes-dns-service-calls.md](adr-002-plain-kubernetes-dns-service-calls.md).
- Inject an `OAuth2ClientHttpRequestInterceptor` to attach a Client Credentials token on every request
- Use `RestClient` (not `RestTemplate` or `WebClient`) — it is the preferred synchronous client in Spring Boot 4
- For local development, set the base URL via an environment-specific property (e.g., `services.order-service.url: http://localhost:8082`)

---

## 6. Resilience Patterns — Resilience4j

All outgoing HTTP calls must be wrapped with resilience patterns.

### Standard `application.yaml` resilience config

```yaml
resilience4j:
  circuitbreaker:
    instances:
      order-service:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true
  retry:
    instances:
      order-service:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - java.io.IOException
          - org.springframework.web.client.ResourceAccessException
  timelimiter:
    instances:
      order-service:
        timeout-duration: 3s
```

Define one named instance per downstream service the caller depends on.

### Usage in service layer

```java
@Service
@RequiredArgsConstructor
public class ReviewValidationService {

    private final OrderServiceClient orderServiceClient;
    private final CircuitBreakerFactory cbFactory;

    public boolean hasDeliveredOrder(UUID userId, String productId) {
        return cbFactory.create("order-service").run(
            () -> orderServiceClient.getOrdersByUserId(userId)
                .stream()
                .anyMatch(o -> o.getStatus() == OrderStatus.DELIVERED
                    && o.containsProduct(productId)),
            throwable -> false    // fallback: deny review if order-service is down
        );
    }
}
```

---

## 7. Error Handling — RFC 7807 Problem Details

Use Spring Boot's native **RFC 7807 `ProblemDetail`** support. Never return plain strings or custom error envelopes for HTTP errors.

### Enable in `application.yaml`

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true
```

### Global exception handler (every service)

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // 404 — resource not found
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    // 400 — validation failures (@Valid, @Validated)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Validation Error");
        problem.setProperty("violations", ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
            .toList());
        return problem;
    }

    // 403 — access denied
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Access Denied");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    // 409 — business rule / conflict
    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Business Rule Violation");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    // 500 — catch-all (never expose internal details)
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("An unexpected error occurred. Please try again later.");
        return problem;
    }
}
```

### Standard exception classes (in `common` module)

```java
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resourceType, Object id) {
        super(resourceType + " not found with id: " + id);
    }
}

public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) { super(message); }
}
```

### RFC 7807 response shape

```json
{
  "type":     "about:blank",
  "title":    "Validation Error",
  "status":   400,
  "detail":   "Request validation failed",
  "instance": "/api/v1/reviews",
  "violations": [
    { "field": "rating", "message": "must be between 1 and 5" }
  ]
}
```

---

## 8. Data Access

### PostgreSQL — JPA + Flyway

#### Entity conventions

```java
@Entity
@Table(name = "orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

Enable JPA auditing on the application class:

```java
@SpringBootApplication
@EnableJpaAuditing
public class OrderServiceApplication { ... }
```

> **Do not use `@Data` on JPA entities** — `@ToString` and `@EqualsAndHashCode` cause infinite recursion on bidirectional relationships. Use `@Getter @Setter` instead, plus explicit `@ToString(exclude = "...")` where needed.

#### Flyway migration conventions

- Location: `src/main/resources/db/migration/`
- Naming: `V{version}__{underscore_description}.sql`
  - `V1__create_orders_table.sql`
  - `V2__add_order_items_table.sql`
  - `V3__add_index_user_id.sql`
- **Never modify an existing migration** — always add a new one
- Each migration file is idempotent where possible (`CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`)
- `CREATE TABLE` and its indexes belong in the same migration version

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
    validate-on-migrate: true
```

### MongoDB — Spring Data MongoDB

#### `application.yaml`

```yaml
spring:
  mongodb:
    uri: ${MONGODB_URI:mongodb://localhost:27017/products}
```

> **Spring Boot 4 migration note:** The MongoDB connection URI property was renamed in Spring Boot 4.
> Use `spring.mongodb.uri` (and `spring.mongodb.host`, `spring.mongodb.port`, etc.).
> The old `spring.data.mongodb.uri` is no longer recognised — the service will silently connect to the
> default `test` database and all queries return empty results.
> Properties that require Spring Data MongoDB (e.g. `spring.data.mongodb.auto-index-creation`,
> `spring.data.mongodb.repositories.type`) are unchanged.

#### Document conventions

```java
@Document(collection = "products")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    private String id;               // MongoDB ObjectId as String

    @Indexed(unique = true)
    private String sku;

    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private int stockQty;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
```

Enable MongoDB auditing on the application class:

```java
@SpringBootApplication
@EnableMongoAuditing
public class ProductServiceApplication { ... }
```

> `@Data` is safe on MongoDB documents — there are no JPA-style bidirectional relationship proxies.

---

## 9. Security — OAuth2 Resource Server

Every business service validates incoming JWTs issued by Keycloak.

### `application.yaml`

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://keycloak:8180/realms/e-commerce/protocol/openid-connect/certs
          issuer-uri: http://keycloak:8180/realms/e-commerce
```

### Security configuration

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().hasAuthority("SCOPE_<service-scope>:read")
            )
            // Spring Security default converter reads 'scope'/'scp' claim → SCOPE_ prefix.
            // No custom JwtAuthenticationConverter is needed.
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

Replace `<service-scope>:read` with the primary read scope for the service (e.g. `products:read`, `orders:read`, `users:read`).

### Scope-based method-level authorization

Use `@PreAuthorize` with `hasAuthority('SCOPE_<scope>')` on endpoints that require specific scopes beyond the baseline:

```java
// Endpoint accessible to regular users
@Override
@PreAuthorize("hasAuthority('SCOPE_users:read')")
public ResponseEntity<UserResponse> getMyProfile(Authentication auth) { ... }

// Internal-only endpoint — requires the machine-to-machine scope
@Override
@PreAuthorize("hasAuthority('SCOPE_users:resolve')")
public ResponseEntity<UserResponse> resolveUser(String idpSubject) { ... }
```

### Service-to-service Client Credentials config

For services that call other services (e.g., `reviews-service` calling `order-service`):

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          order-service:
            client-id: reviews-service
            client-secret: ${REVIEWS_SERVICE_CLIENT_SECRET}
            authorization-grant-type: client_credentials
            scope: reviews:read   # grant only the scopes needed for this M2M call
        provider:
          order-service:
            token-uri: http://keycloak:8180/realms/e-commerce/protocol/openid-connect/token
```

### Extracting the current user from a JWT — per-service lazy resolution (ADR-004)

Never accept `userId` from the request body for user-scoped operations. Always resolve from the JWT.
Never store the IAM `sub` as a data key — always resolve to the internal `users.id` UUID first
(see [ADR-004](adr-004-iam-portability-user-service-isolation.md) and [iam-portability.md](iam-portability.md)).

Resolution is a two-step process:
1. Extract the `sub` from the JWT
2. Call `user-service` (`GET /api/v1/users/resolve?idp_subject={sub}`) to get the internal UUID, cached locally with a 10-min Caffeine TTL

The pattern below is the reference implementation from `cart-service` and should be replicated in every service that needs to associate data with a user.

#### Step 1 — HTTP interface for user-service

Create a `client/UserServiceClient.java` HTTP Interface (Spring 6 `@HttpExchange`):

```java
@HttpExchange("/api/v1")
public interface UserServiceClient {

    @GetExchange("/users/resolve")
    UserResolveResponse resolveUser(@RequestParam("idp_subject") String idpSubject);

    record UserResolveResponse(UUID id) {}
}
```

#### Step 2 — Caching resolver service

Create a `service/UserIdResolverService.java` that wraps the HTTP call with a Caffeine cache
and a Resilience4j circuit breaker:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserIdResolverService {

    private final UserServiceClient userServiceClient;

    @Cacheable(value = "userIdBySubject", key = "#idpSubject")
    @CircuitBreaker(name = "user-service", fallbackMethod = "resolveInternalIdFallback")
    public UUID resolveInternalId(String idpSubject) {
        log.debug("Cache miss — resolving idp_subject={} via user-service", idpSubject);
        try {
            return userServiceClient.resolveUser(idpSubject).id();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("User", idpSubject);
        }
    }

    @CacheEvict(value = "userIdBySubject", key = "#idpSubject")
    public void evict(String idpSubject) {}

    private UUID resolveInternalIdFallback(String idpSubject, Throwable t) {
        log.warn("user-service circuit open — cannot resolve idp_subject={}", idpSubject);
        throw new IllegalStateException("User identity service unavailable. Retry in a moment.", t);
    }
}
```

#### Step 3 — Wire the RestClient + OAuth2 token + Caffeine cache

Create a `config/HttpClientConfig.java` (annotated `@EnableCaching` here so each service only needs it in one place):

```java
@Configuration
@EnableCaching
public class HttpClientConfig {

    @Bean
    public UserServiceClient userServiceClient(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager authorizedClientManager,
            @Value("${services.user-service.url:http://localhost:8085}") String userServiceUrl) {

        RestClient restClient = builder
                .baseUrl(userServiceUrl)
                .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(authorizedClientManager))
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(UserServiceClient.class);
    }

    @Bean
    public CacheManager cacheManager(
            @Value("${cart.user-resolver.cache-ttl-minutes:10}") long ttlMinutes) {
        CaffeineCacheManager manager = new CaffeineCacheManager("userIdBySubject");
        manager.setCaffeine(
                Caffeine.newBuilder().expireAfterWrite(ttlMinutes, TimeUnit.MINUTES));
        return manager;
    }
}
```

> **Rename the `@Value` key** to match the service name (e.g. `orders.user-resolver.cache-ttl-minutes`).

#### Step 4 — `application.yaml` additions

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          user-service:
            client-id: e-commerce-service
            client-secret: ${E_COMMERCE_SERVICE_CLIENT_SECRET:e-commerce-service-secret}
            authorization-grant-type: client_credentials
            scope: users:resolve
        provider:
          user-service:
            token-uri: ${KEYCLOAK_URL:http://localhost:8180}/realms/e-commerce/protocol/openid-connect/token

# Downstream service URLs (override per env)
services:
  user-service:
    url: ${USER_SERVICE_URL:http://localhost:8085}

# Resilience4j — circuit breaker / retry / timeout for user-service
resilience4j:
  circuitbreaker:
    instances:
      user-service:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true
  retry:
    instances:
      user-service:
        max-attempts: 3
        wait-duration: 300ms
        retry-exceptions:
          - java.io.IOException
          - org.springframework.web.client.ResourceAccessException
  timelimiter:
    instances:
      user-service:
        timeout-duration: 3s

# Caffeine TTL for sub → internal userId cache (minutes)
<service-name>:
  user-resolver:
    cache-ttl-minutes: 10
```

#### Step 5 — pom.xml additions

```xml
<!-- OAuth2 Client — Client Credentials for calling user-service -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
<!-- Resilience4j -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
</dependency>
<!-- Caffeine in-process cache -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

#### Usage in controller

```java
@RestController
@RequiredArgsConstructor
public class OrderController implements OrderApi {

    private final OrderService orderService;
    private final UserIdResolverService userIdResolverService;

    @Override
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    public ResponseEntity<OrderResponse> placeOrder(PlaceOrderRequest request) {
        String idpSubject = JwtUtils.getSubject(
                SecurityContextHolder.getContext().getAuthentication());
        UUID userId = userIdResolverService.resolveInternalId(idpSubject);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.place(userId, request));
    }
}
```

---

## 10. Kafka Messaging

Kafka is used for asynchronous, event-driven communication between services. The canonical example is `order-service`, which publishes an `OrderCreatedEvent` after successfully persisting a new order.

### When to use Kafka

Publish a Kafka event when a state change in one service must trigger downstream workflows in other services (e.g., `notification-service` sending a confirmation email after an order is created). Use synchronous HTTP (`@HttpExchange`) for request-response calls where the caller needs an immediate result.

### Dependencies

For **producers** (services that publish events):

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

For **consumers** (services that subscribe to events), add the same `spring-kafka` dependency. Add `spring-kafka-test` and `testcontainers-kafka` as `test`-scope dependencies (see [Testing Strategy](#14-testing-strategy)).

### Topic naming convention

`<domain>.<event>.<version>` — all lowercase, dots as separators, versioned from `v1`.

Examples: `order.created.v1`, `notification.sent.v1`.

### Event Record

Define events as Java `record` types in a `kafka/` sub-package. Use only serializable types (`UUID`, `String`, primitives, `Instant`). Never include JPA entities.

```java
// order-service: kafka/OrderCreatedEvent.java
public record OrderCreatedEvent(
        UUID orderId,
        UUID userId,
        double totalAmount,
        int itemCount,
        Instant createdAt) {}
```

**Important:** Use the internal service UUID for `userId` (ADR-004). Never expose the Keycloak `sub` in Kafka events.

### Producer Configuration (`application.yaml`)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

Use `StringSerializer` for keys and `JsonSerializer` for values. The `JsonSerializer` serializes the event record to JSON automatically — no extra configuration needed.

### Producer Component

Wrap `KafkaTemplate` in a dedicated `@Component` in the `kafka/` package. Use the entity UUID as the message key to guarantee all events for a given entity land on the same partition.

```java
// order-service: kafka/OrderEventPublisher.java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    static final String TOPIC_ORDER_CREATED = "order.created.v1";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreatedEvent orderId={} userId={}",
                event.orderId(), event.userId());
        kafkaTemplate.send(TOPIC_ORDER_CREATED, event.orderId().toString(), event);
    }
}
```

Declare `KafkaTemplate<String, Object>` (not a specific event type) so the same template can publish multiple event types from one service.

### Consumer Configuration (`application.yaml`)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: ${spring.application.name}
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.ricsanfre.*"
        spring.json.value.default.type: com.ricsanfre.<service>.kafka.<EventClass>
```

Set `spring.json.trusted.packages` to allow deserialization of event types from other services. Set `spring.json.value.default.type` to the consumer's local copy of the event record (a record with matching field names).

### Consumer Component

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCreatedEventConsumer {

    @KafkaListener(topics = "order.created.v1", groupId = "${spring.application.name}")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent orderId={}", event.orderId());
        // handle event ...
    }
}
```

### Testing

For `@WebMvcTest` or `@SpringBootTest` slices that do **not** test Kafka, mock the publisher bean so no broker connection is attempted at startup:

```java
@MockitoBean
OrderEventPublisher orderEventPublisher;
```

For full integration tests with a real broker, use the Testcontainers Kafka module (see [Testing Strategy](#14-testing-strategy)):

```java
@Container
@ServiceConnection
static final KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));
```

`@ServiceConnection` auto-configures `spring.kafka.bootstrap-servers` — no manual property override needed.

Add `spring-kafka-test` and `testcontainers-kafka` to the service `pom.xml` under `<scope>test</scope>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 11. OpenTelemetry Observability

### Dependencies (all services)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.21.0-alpha</version>
</dependency>
```

### `application.yaml` OTEL config

```yaml
spring:
  application:
    name: product-service          # set per service

management:
  tracing:
    sampling:
      probability: 1.0             # 100% in dev; use 0.1 in prod
  otlp:
    metrics:
      export:
        url: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
    logging:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/logs
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
```

### `logback-spring.xml` (every service)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/base.xml"/>
    <appender name="OTEL"
              class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender"/>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OTEL"/>
    </root>
</configuration>
```

### OpenTelemetry appender installer (every service)

```java
@Component
public class InstallOpenTelemetryAppender implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    public InstallOpenTelemetryAppender(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
```

Use the existing [otel-demo](../otel-demo) module as the reference implementation for this wiring.

### Automatically instrumented (zero extra code)

- All inbound HTTP requests (latency, status codes, trace context propagation)
- Outbound `RestClient` calls
- Kafka producer/consumer spans
- JPA/JDBC query spans
- Spring `@Scheduled` methods

---

## 12. Docker Images — Jib

Use **Google Jib** to build container images without a Dockerfile. Jib produces layered, reproducible images without requiring a Docker daemon at build time.

### Plugin configuration (root `pom.xml` `<pluginManagement>`)

```xml
<plugin>
    <groupId>com.google.cloud.tools</groupId>
    <artifactId>jib-maven-plugin</artifactId>
    <version>3.4.4</version>
    <configuration>
        <from>
            <image>eclipse-temurin:25-jre-alpine</image>
        </from>
        <to>
            <image>${docker.registry}/${project.artifactId}:${project.version}</image>
            <tags>
                <tag>latest</tag>
            </tags>
        </to>
        <container>
            <jvmFlags>
                <jvmFlag>-Dspring.threads.virtual.enabled=true</jvmFlag>
                <jvmFlag>-XX:MaxRAMPercentage=75.0</jvmFlag>
                <jvmFlag>-XX:+UseContainerSupport</jvmFlag>
            </jvmFlags>
            <ports>
                <port>8080</port>
            </ports>
            <labels>
                <maintainer>ricsanfre</maintainer>
            </labels>
            <creationTime>USE_CURRENT_TIMESTAMP</creationTime>
        </container>
    </configuration>
</plugin>
```

### Build commands

```bash
# Build and push to registry
mvn compile jib:build -Ddocker.registry=docker.io/ricsanfre

# Build to local Docker daemon (dev only)
mvn compile jib:dockerBuild

# Build to a local tarball (CI without Docker daemon)
mvn compile jib:buildTar
```

### Image layering (automatic)

Jib splits the image into layers from most-to-least stable:

1. JRE base (`eclipse-temurin:25-jre-alpine`)
2. Dependencies (changes rarely)
3. Snapshot dependencies
4. Resources (`application.yaml`, static files)
5. Classes (changes most often)

This maximises layer cache reuse in CI and container registries.

---

## 13. Code Style — Lombok

### Approved annotations

| Annotation | Where to use |
|---|---|
| `@Getter` / `@Setter` | JPA entities |
| `@Data` | Simple DTOs, MongoDB documents, non-entity POJOs |
| `@Value` | Immutable DTOs (all fields final) |
| `@Builder` | Any class where fluent construction is useful |
| `@RequiredArgsConstructor` | All Spring components (`@Service`, `@RestController`, `@Component`) — constructor injection |
| `@NoArgsConstructor` + `@AllArgsConstructor` | JPA entities (required by JPA spec + builder pattern) |
| `@Slf4j` | Any class that logs |

### Hard rules

- **Always use constructor injection** via `@RequiredArgsConstructor` — never `@Autowired` field injection
- **Never use `@Data` on JPA entities** with bidirectional relationships — causes `@ToString`/`@EqualsAndHashCode` infinite recursion; use `@Getter @Setter` + explicit `@ToString(exclude = "...")`
- **Never use `@SneakyThrows`** — handle checked exceptions explicitly
- Always configure Lombok in the Maven compiler annotation processor path (see [otel-demo/pom.xml](../otel-demo/pom.xml) as reference)

### Maven compiler config (per module)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

---

## 14. Testing Strategy

### Pyramid

```
         /\
        /  \   E2E  (minimal — API Gateway smoke tests only)
       /----\
      /      \  Integration  (TestContainers — per service)
     /--------\
    /          \ Unit  (Mockito — service layer, full isolation)
   /------------\
```

### Unit tests

- Test the **service layer** in complete isolation — mock all repositories and HTTP clients
- Use JUnit 5 (`@ExtendWith(MockitoExtension.class)`) and Mockito
- Name pattern: `methodName_givenCondition_thenExpectedResult()`
- Target > 80% branch coverage on service classes

```java
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock ProductRepository productRepository;
    @InjectMocks ProductService productService;

    @Test
    void findById_givenNonExistentId_throwsResourceNotFoundException() {
        when(productRepository.findById(any())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
            () -> productService.findById(UUID.randomUUID()));
    }
}
```

### Integration tests — TestContainers + `@SpringBootTest`

Use Spring Boot's `@ServiceConnection` to auto-wire container ports into the application context — no manual property overrides needed.

#### PostgreSQL service

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate restTemplate;

    @Test
    void placeOrder_givenValidRequest_returns201() {
        // Flyway runs migrations automatically against the container
        var response = restTemplate.postForEntity("/api/v1/orders", buildRequest(), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
```

#### MongoDB service

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProductServiceIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired TestRestTemplate restTemplate;

    @Test
    void getProduct_givenExistingId_returns200() { ... }
}
```

#### Kafka integration

```java
@SpringBootTest
@Testcontainers
class OrderEventPublisherIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"));

    @Autowired OrderEventPublisher publisher;

    @Test
    void publishOrder_givenValidOrder_sendsMessageToTopic() throws Exception {
        var latch = new CountDownLatch(1);
        // set up a test consumer to count down the latch, then assert
    }
}
```

### Test dependencies (every service)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<!-- Add the specific module for this service's DB / broker: -->
<!-- org.testcontainers:testcontainers-postgresql  OR  testcontainers-mongodb  OR  testcontainers-kafka -->

<!-- MockMvc support (Spring Boot 4 split this out of spring-boot-test): -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

### Mock security in tests

Use the `jwt()` MockMvc post-processor from `spring-security-test` to inject pre-built JWT tokens without a real Keycloak instance:

```java
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

mockMvc.perform(get("/users/me").with(jwt()
        .jwt(j -> j
            .subject("test-sub-1")
            .claim("email", "user@example.com")
            .claim("preferred_username", "testuser")
            .claim("given_name", "Test")
            .claim("family_name", "User")
            .claim("scope", "openid profile email users:read"))
        .authorities(new SimpleGrantedAuthority("SCOPE_users:read"))))
    .andExpect(status().isOk());
```

For endpoints that require specific scopes (e.g. M2M-only `users:resolve`):

```java
// Service account with users:resolve scope
mockMvc.perform(get("/users/resolve").with(jwt()
        .jwt(j -> j.subject("svc-sub").claim("scope", "users:resolve"))
        .authorities(new SimpleGrantedAuthority("SCOPE_users:resolve"))))
    .andExpect(status().isOk());

// Regular user missing users:resolve → 403
mockMvc.perform(get("/users/resolve").with(jwt()
        .jwt(j -> j.subject("test-sub-1").claim("scope", "openid profile email users:read"))
        .authorities(new SimpleGrantedAuthority("SCOPE_users:read"))))
    .andExpect(status().isForbidden());
```

Also mock the `JwtDecoder` bean to prevent Spring Boot from fetching the JWK Set URI at context startup:

```java
@MockitoBean
JwtDecoder jwtDecoder;
```

### Maven plugin configuration — Surefire & Failsafe

Tests are split across two Maven plugins to allow a fast unit-only gate and a full integration gate:

| Plugin | Phase | Runs | Command |
|---|---|---|---|
| `maven-surefire-plugin` | `test` | `*Test.java` only | `mvn test` |
| `maven-failsafe-plugin` | `integration-test` / `verify` | `*IT.java` only | `mvn verify` |

> See [ADR-012](adr-012-surefire-failsafe-test-separation.md) for rationale.

#### Naming conventions

| Class suffix | Type | Example |
|---|---|---|
| `*Test.java` | Unit test or `@WebMvcTest` slice — no infrastructure | `ProductServiceTest`, `ProductControllerTest` |
| `*IT.java` | Integration test — starts real containers via TestContainers | `ProductControllerIT`, `UserControllerIT` |

#### Root POM configuration

Both plugins are configured in `<pluginManagement>` so the configuration is inherited by all child modules without repetition:

```xml
<!-- Surefire — excludes *IT.java -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <excludes>
            <exclude>**/*IT.java</exclude>
        </excludes>
    </configuration>
</plugin>

<!-- Failsafe — includes only *IT.java, binds to integration-test + verify goals -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*IT.java</include>
        </includes>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

#### Service module activation

Each service module that contains `*IT.java` tests must declare `maven-failsafe-plugin` in its `<build><plugins>` block (no additional configuration needed — it inherits everything from `pluginManagement`):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
</plugin>
```

#### CI pipeline usage

```bash
# PR check — fast, no containers (~seconds)
mvn test

# Merge / deployment gate — full stack with TestContainers (~minutes)
mvn verify
```

---

### Spring Boot 4 — key testing changes

| Concern | Spring Boot 3 | Spring Boot 4 |
|---|---|---|
| `@AutoConfigureMockMvc` package | `org.springframework.boot.test.autoconfigure.web.servlet` | `org.springframework.boot.webmvc.test.autoconfigure` |
| MockMvc dependency | included in `spring-boot-test` | separate `spring-boot-webmvc-test` artifact |
| `ObjectMapper` bean type | `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` (Jackson 3.x, groupId `tools.jackson.core`) |
| Flyway autoconfiguration | `flyway-core` triggers `FlywayAutoConfiguration` | requires `spring-boot-starter-flyway` (brings `spring-boot-flyway` module); `flyway-core` alone does **not** register the auto-configuration |

---

## 15. Virtual Threads

Enable Project Loom virtual threads in every service to maximise throughput with standard MVC blocking I/O:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

This routes all incoming HTTP request threads through JVM-managed virtual threads. Works seamlessly with JPA blocking calls, `RestClient`, and Kafka consumers — no code changes required.

Also set the corresponding JVM flag in the Jib container config (`-Dspring.threads.virtual.enabled=true`) so the setting is applied even if `application.yaml` is overridden by environment variables at runtime.

---

## 16. Caching — Valkey / Spring Data Redis

`cart-service` uses **Valkey** as its primary data store (not a secondary cache layer). Valkey is Redis-compatible, so `spring-boot-starter-data-redis` works without changes. The same guidelines apply to any future service that uses Valkey or Redis.

### Dependency

Add to the service's `pom.xml` (no version needed — managed by the root BOM):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### `application.yaml` connection config

```yaml
spring:
  data:
    redis:
      host: ${VALKEY_HOST:localhost}
      port: ${VALKEY_PORT:6379}
      # password: ${VALKEY_PASSWORD:}    # uncomment if auth is enabled
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
          max-wait: -1ms          # wait indefinitely for a connection (adjust per SLA)
```

> **Local dev defaults** point at `localhost:6379` (the Docker Compose Valkey container on the `infra` profile).
> In Kubernetes, `VALKEY_HOST` is overridden via the service's `ConfigMap` to `valkey.valkey.svc.cluster.local`.

### Three caching tiers

This project uses three distinct caching mechanisms. Choose the right one for the job:

| Tier | Technology | When to use | Example |
|------|-----------|-------------|--------|
| **Primary data store** | Valkey `StringRedisTemplate` + `ObjectMapper` | Service's canonical persistence is Valkey (cart, session) | `cart-service` |
| **Secondary read-through cache** | Valkey `@Cacheable` + Redis `CacheManager` | Speed up expensive relational/document lookups | product catalog caching |
| **Local in-process cache** | Caffeine + Spring `@Cacheable` | Short-lived, per-instance memoisation (sub → userId mapping) | ADR-004 lazy resolution |

> Keep these tiers separate. Do **not** route the `CacheManager` used for ADR-004 resolution through Valkey — that would create a circular dependency between `cart-service`'s own data store and its identity lookup cache.

### Valkey as primary data store — `StringRedisTemplate` pattern

When Valkey is the **canonical persistence layer** (not a cache in front of another store), use
`StringRedisTemplate` with manual `ObjectMapper` serialisation. This gives full control over the
stored JSON format and avoids the type-metadata overhead of `GenericJackson2JsonRedisSerializer`:

```java
@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate stores keys and values as plain UTF-8 strings.
     * JSON serialisation is handled manually in the repository via ObjectMapper.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Shared ObjectMapper with Java time support (Instant → ISO-8601).
     * Mark @Primary so Spring Boot's autoconfigured ObjectMapper is replaced.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
```

The repository serialises/deserialises the domain object explicitly:

```java
@Repository
@RequiredArgsConstructor
@Slf4j
public class CartRepository {

    static final Duration CART_TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "cart:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<Cart> findByUserId(String userId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, Cart.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialise cart for user {}", userId, e);
            return Optional.empty();
        }
    }

    public Cart save(Cart cart) {
        cart.setExpiresAt(Instant.now().plus(CART_TTL));
        try {
            String json = objectMapper.writeValueAsString(cart);
            redisTemplate.opsForValue().set(KEY_PREFIX + cart.getUserId(), json, CART_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialise cart for user " + cart.getUserId(), e);
        }
        return cart;
    }

    public void deleteByUserId(String userId) {
        redisTemplate.delete(KEY_PREFIX + userId);
    }
}
```

> **Do not use `JdkSerializationRedisSerializer`** — it produces unreadable binary blobs and ties stored data to Java class internals. Always use string-based keys and human-readable JSON values.

### Valkey as secondary read-through cache — `@Cacheable` + Redis `CacheManager`

For services that want a secondary cache in front of a relational or document store, use the
Spring Cache abstraction backed by Valkey:

```yaml
# application.yaml
spring:
  cache:
    type: redis
    redis:
      time-to-live: 300000       # 5 minutes (milliseconds)
      cache-null-values: false   # do not cache null (avoids masking 404s)
```

```java
@Cacheable(value = "products", key = "#id")
public ProductResponse findById(String id) { ... }

@CacheEvict(value = "products", key = "#id")
public void deleteById(String id) { ... }

@CachePut(value = "products", key = "#result.id")
public ProductResponse update(String id, UpdateProductRequest request) { ... }
```

### Key naming convention

Keys **must** follow the pattern `{service}:{entity}:{id}` to prevent collisions when multiple services share a single Valkey instance:

| Service | Entity | Key pattern | Example |
|---------|--------|-------------|----------|
| `cart-service` | shopping cart | `cart:{userId}` | `cart:550e8400-e29b-41d4-a716-446655440001` |

Define key prefixes as `private static final String` constants inside the repository class, never as inline strings. Always set a TTL on every write — **never store data without an expiry** to prevent unbounded memory growth.

> Use `@Cacheable` only for **idempotent, read-heavy** data. Avoid caching mutable data that requires complex cache invalidation logic — the complexity outweighs the benefit.

### Preventing cache stampede

When multiple threads miss the cache simultaneously (thundering herd), `@Cacheable` can issue redundant DB queries. Mitigate by:

1. **Jittering TTLs** — add random offset: `Duration.ofMinutes(5).plus(Duration.ofSeconds(new Random().nextInt(60)))`
2. **Keeping TTLs short** for high-cardinality data (user-specific caches)
3. **Avoiding `@Cacheable`** on write-heavy or personalized data; use `RedisTemplate` directly with explicit TTL control

### Resilience — graceful degradation on cache failure

Valkey is a supporting infrastructure component. A cache outage must **never** take down the business service. Wrap cache operations with a circuit breaker or catch `RedisException` and fall through to the data store:

```java
public Optional<Cart> findByUserId(String userId) {
    try {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (json == null) return Optional.empty();
        return Optional.of(objectMapper.readValue(json, Cart.class));
    } catch (RedisException | JsonProcessingException ex) {
        log.warn("Valkey unavailable, serving empty cart for user {}", userId, ex);
        return Optional.empty();   // caller treats missing cart as empty
    }
}
```

For services using the Spring Cache abstraction, configure a fallback via `CacheErrorHandler`:

```java
@Configuration
public class CacheConfig extends CachingConfigurerSupport {

    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler();  // logs + ignores cache errors
    }
}
```

### Integration testing with TestContainers

Use the `valkey/valkey` Docker image in TestContainers. Spring Boot's `@ServiceConnection` auto-wires the container port:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CartServiceIntegrationTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> valkey =
        new GenericContainer<>("valkey/valkey:8-alpine")
            .withExposedPorts(6379);

    @Autowired TestRestTemplate restTemplate;

    @Test
    void addItem_givenValidRequest_returnsUpdatedCart() {
        // Spring Boot connects to the mapped port automatically via @ServiceConnection
        var response = restTemplate.exchange(
            "/api/v1/cart/items/some-product-id",
            HttpMethod.PUT,
            buildRequest(),
            CartResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

> **`@ServiceConnection` with Valkey:** Spring Boot's built-in `@ServiceConnection` support covers `RedisContainer` from `org.testcontainers:testcontainers-redis`. For `valkey/valkey`, use `GenericContainer` with `withExposedPorts(6379)` and manually set `spring.data.redis.host` and `spring.data.redis.port` via `@DynamicPropertySource`:
>
> ```java
> @DynamicPropertySource
> static void redisProperties(DynamicPropertyRegistry registry) {
>     registry.add("spring.data.redis.host", valkey::getHost);
>     registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
> }
> ```

When adding a new microservice, ensure all of the following are in place before merging:

### API & Contracts
- [ ] `src/main/resources/openapi/{service}-api.yaml` spec defined and reviewed
- [ ] API code generated via `openapi-generator-maven-plugin` (`interfaceOnly: true`)
- [ ] Controller implements generated interface — no `@RequestMapping` on the controller class itself
- [ ] Swagger UI accessible at `/swagger-ui.html`

### Error Handling
- [ ] `GlobalExceptionHandler` present in `exception` package
- [ ] `spring.mvc.problemdetails.enabled: true` in `application.yaml`
- [ ] No raw `RuntimeException` thrown from controllers — only typed domain exceptions

### Data
- [ ] Flyway migrations in `db/migration/V{n}__{desc}.sql` (PostgreSQL services)
- [ ] `@EnableJpaAuditing` or `@EnableMongoAuditing` on application class
- [ ] No `@Data` on JPA entities with relationships

### Observability
- [ ] `logback-spring.xml` with CONSOLE + `OpenTelemetryAppender`
- [ ] `InstallOpenTelemetryAppender` bean present
- [ ] `spring.application.name` set to the service name
- [ ] OTEL OTLP endpoints configured (defaulting to `localhost:4318`)
- [ ] `management.tracing.sampling.probability: 1.0` (dev)

### Build & Container
- [ ] Jib plugin configured in `pom.xml`
- [ ] `spring.threads.virtual.enabled: true` in `application.yaml`

### Caching (Valkey / Redis services only)
- [ ] `spring-boot-starter-data-redis` in `pom.xml`
- [ ] `VALKEY_HOST` / `VALKEY_PORT` environment variables in `ConfigMap` and `application.yaml` defaults
- [ ] `StringRedisTemplate` bean (primary store) or `spring.cache.type: redis` (secondary cache) — never mix both patterns in one service without explicit naming
- [ ] JSON serialisation via `StringRedisTemplate` + `ObjectMapper` (primary store) or `GenericJackson2JsonRedisSerializer` (secondary cache)
- [ ] Key naming follows `{service}:{entity}:{id}` pattern; KEY_PREFIX constants defined inline or in a constants class
- [ ] Every write sets an explicit TTL — no unbounded keys
- [ ] Cache errors caught and degraded gracefully (never propagate `RedisException` to callers)
- [ ] Integration test uses `GenericContainer<>("valkey/valkey:8-alpine")` + `@DynamicPropertySource`

### Security — per-service lazy resolution (ADR-004)
- [ ] `spring-boot-starter-oauth2-client`, `spring-cloud-starter-circuitbreaker-resilience4j`, `spring-boot-starter-cache`, `caffeine` in `pom.xml`
- [ ] `UserServiceClient` HTTP Interface in `client/` package
- [ ] `UserIdResolverService` with `@Cacheable("userIdBySubject")` + `@CircuitBreaker(name = "user-service")` in `service/` package
- [ ] `HttpClientConfig` with `@EnableCaching`, `UserServiceClient` bean (RestClient + `OAuth2ClientHttpRequestInterceptor`), `CaffeineCacheManager` for `userIdBySubject`
- [ ] `spring.security.oauth2.client.registration.user-service` with `grant-type: client_credentials`, `scope: users:resolve`
- [ ] `services.user-service.url` property (default `http://localhost:8085`)
- [ ] Resilience4j `user-service` circuit breaker / retry / timelimiter in `application.yaml`
- [ ] Controller calls `userIdResolverService.resolveInternalId(JwtUtils.getSubject(auth))` — never uses JWT `sub` as a storage key directly

### Infrastructure
- [ ] `ServiceAccount` defined in `k8s/{service}/serviceaccount.yaml`
- [ ] Service-to-service base URLs configured via environment-specific properties (default `http://localhost:{port}` for local dev)
- [ ] No `spring-cloud-starter-kubernetes-client-loadbalancer` or `lb://` URIs — use plain Kubernetes Service DNS

### Security
- [ ] OAuth2 Resource Server configured with Keycloak JWKS URI
- [ ] `SecurityFilterChain` bean with stateless session + `permitAll` for actuator/swagger
- [ ] Service-to-service calls use Client Credentials grant via `OAuth2ClientHttpRequestInterceptor`
- [ ] User identity always resolved from JWT — never from request body

### Resilience
- [ ] Resilience4j circuit breaker configured for every downstream HTTP dependency
- [ ] Fallback defined for every `circuitBreaker.run(...)` call

### Testing
- [ ] Unit tests for all service-layer methods (> 80% branch coverage)
- [ ] Integration test with TestContainers `@ServiceConnection` for primary DB / broker
- [ ] Security role tests using `jwt()` MockMvc post-processor + `@MockitoBean JwtDecoder` for at least one protected endpoint (see §13)
- [ ] `spring-boot-webmvc-test` and `spring-security-test` in test scope dependencies

---

## 17. Local Development Workflow (Docker Compose + Makefile)

The root `Makefile` and `compose.yaml` replace manual Docker Compose and Keycloak setup steps. Follow the pattern below when implementing each new microservice.

### Docker Compose profiles

`compose.yaml` uses Docker Compose **profiles** so that starting one service's infrastructure does not pull in unrelated containers. Three profiles are shared across all services; no per-service profile is needed.

```
compose.yaml
  profiles:
    infra             → postgres (all service DBs, port 5432) + mongo (port 27017)
    auth              → keycloak (port 8180) — realm e-commerce auto-imported
    observability     → grafana/otel-lgtm (Grafana, Loki, Tempo, Prometheus)
```

Future additions to `infra` as services are implemented:

```
    infra             → + kafka (port 9092)                           [planned]
```

> **Database-per-service within a shared instance:** `postgres` hosts one database per service (`users`, `orders`, …). `mongo` hosts one database per service (`products`, `reviews`, …). Each service connects with its own user credentials to its own named database. The `docker/postgres/init-databases.sh` script creates all databases and users on first container start. This preserves full isolation at the database level without multiple container instances.

### Keycloak realm auto-import

Keycloak is started with `start-dev --import-realm`. The realm JSON file is volume-mounted at `/opt/keycloak/data/import/`. On first startup Keycloak automatically creates the realm with all clients, roles, test users, and JWT protocol mappers. **No manual Admin Console configuration is required.**

Realm files live in `docker/keycloak/`. Add or update realm clients in the JSON file as new services are implemented.

### Makefile pattern (per service)


#### Common infrastructure targets

These targets manage the shared Docker Compose infrastructure for all services:

| Target         | Description                                         |
|----------------|-----------------------------------------------------|
| `infra-up`     | Start infra containers with healthcheck wait (`--wait`) |
| `infra-down`   | Stop containers, keep named volumes                 |
| `infra-clean`  | Stop containers **and** delete data volumes         |
| `infra-logs`   | Tail infra container logs                           |
| `infra-ps`     | Show container status                               |

#### Per-service targets

Makefile targets follow the prefix convention `<service-abbrev>-<action>`:

| Target         | Description                                         |
|----------------|-----------------------------------------------------|
| `us-build`     | Compile + package JAR (`-DskipTests`)               |
| `us-test`      | Run unit tests only (fast, no containers)           |
| `us-verify`    | Run unit + integration tests (Testcontainers)       |
| `us-image`     | Build container image to local Docker daemon via Jib|
| `us-run`       | Build JAR then run with Spring profile `local`      |
| `us-dev`       | `infra-up` + `us-run` in sequence                   |
| `us-token`     | Fetch user access token via password grant (needs `jq`) |
| `us-token-sa`  | Fetch service-account token via client credentials (needs `jq`) |

Add analogous targets (`ps-*`, `os-*`, …) for each new service following the same structure.

### Environment variable defaults

Each service's `application.yaml` defaults to values that match the compose network:

| Env var | Default value | Compose service |
|---------|---------------|-----------------|
| `DB_HOST` | `localhost` | `postgres` on host |
| `DB_PORT` | `5432` | standard PostgreSQL port |
| `DB_NAME` | service-specific | e.g. `users` for user-service |
| `DB_USER` | service-specific | e.g. `users` for user-service |
| `KEYCLOAK_URL` | `http://localhost:8180` | `keycloak` on host |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | `grafana-lgtm` on host |

When running the service JAR locally with `make us-run`, these defaults point at the mapped host ports. When running the service as a Docker container inside the compose network, override them with the Docker service names (`DB_HOST=postgres`, `KEYCLOAK_URL=http://keycloak:8080`, etc.).

### Complete local dev session

```bash
# Start everything and run user-service
make us-dev

# In another terminal — get a token and test an endpoint
TOKEN=$(make -s us-token)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/v1/users/me | jq .

# Tear down (preserves postgres data)
make infra-down

# Hard reset (destroys all data)
make infra-clean
```

---

## 18. Kubernetes Deployment

### Local Kubernetes environment — k3d

[k3d](https://k3d.io) runs k3s (a lightweight Kubernetes distribution) inside Docker. It is the recommended local Kubernetes environment for this project.

```bash
# Create cluster with Envoy Gateway port (80) and Keycloak port (8180) exposed,
# plus a built-in local image registry on port 5000
k3d cluster create e-commerce \
  --port "80:80@loadbalancer" \
  --port "8180:8180@loadbalancer" \
  --registry-create e-commerce-registry:0.0.0.0:5000
```

### Install Envoy Gateway

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.3.0 \
  --namespace envoy-gateway-system \
  --create-namespace

kubectl wait --namespace envoy-gateway-system \
  --for=condition=Available deployment/envoy-gateway \
  --timeout=90s
```

### Manifests structure

```
k8s/
├── namespace.yaml
├── envoy-gateway/
│   ├── gateway.yaml            ← GatewayClass + Gateway
│   ├── httproutes.yaml         ← HTTPRoute per business service
│   └── security-policy.yaml   ← JWT SecurityPolicy (Keycloak JWKS)
├── {service-name}/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   └── serviceaccount.yaml
└── infra/
    ├── keycloak/
    ├── kafka/
    ├── mongodb/
    └── postgres/
```

### Build and import images with Jib

```bash
# Build all service images and push to the k3d local registry
mvn compile jib:build -Ddocker.registry=localhost:5000

# Import images into k3d cluster nodes
for svc in product-service order-service reviews-service notification-service user-service; do
  k3d image import localhost:5000/${svc}:latest -c e-commerce
done
```

In `pom.xml` (root `<pluginManagement>`), set `docker.registry` default to `localhost:5000` for k3d:

```xml
<properties>
    <docker.registry>localhost:5000</docker.registry>
</properties>
```

### Envoy Gateway — GatewayClass and Gateway

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: eg
spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: eg
  namespace: envoy-gateway-system
spec:
  gatewayClassName: eg
  listeners:
    - name: http
      port: 80
      protocol: HTTP
      allowedRoutes:
        namespaces:
          from: All
```

### Envoy Gateway — JWT SecurityPolicy

Apply once per gateway. Validates all JWTs using the Keycloak JWKS endpoint before routing.

```yaml
apiVersion: gateway.envoyproxy.io/v1alpha1
kind: SecurityPolicy
metadata:
  name: jwt-authn
  namespace: envoy-gateway-system
spec:
  targetRef:
    group: gateway.networking.k8s.io
    kind: Gateway
    name: eg
    namespace: envoy-gateway-system
  jwt:
    providers:
      - name: keycloak
        issuer: http://keycloak.e-commerce-infra.svc.cluster.local:8180/realms/e-commerce
        remoteJWKS:
          uri: http://keycloak.e-commerce-infra.svc.cluster.local:8180/realms/e-commerce/protocol/openid-connect/certs
```

### Envoy Gateway — HTTPRoute example

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: product-service
  namespace: e-commerce
spec:
  parentRefs:
    - name: eg
      namespace: envoy-gateway-system
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /api/v1/products
      backendRefs:
        - name: product-service
          port: 8081
```

Repeat for each business service (`/api/v1/orders`, `/api/v1/reviews`, `/api/v1/users`).

### Spring Cloud Kubernetes DiscoveryClient — RBAC

Each service deployment must reference a `ServiceAccount` that has permission to list Kubernetes services and endpoints:

```yaml
# serviceaccount.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: product-service
  namespace: e-commerce
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: product-service-discovery
  namespace: e-commerce
rules:
  - apiGroups: [""]
    resources: ["services", "endpoints", "pods"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: product-service-discovery
  namespace: e-commerce
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: product-service-discovery
subjects:
  - kind: ServiceAccount
    name: product-service
    namespace: e-commerce
```

```yaml
# deployment.yaml (excerpt)
spec:
  template:
    spec:
      serviceAccountName: product-service
```

### Local dev profile — disabling Kubernetes discovery

When running services locally with Docker Compose (no cluster), disable Kubernetes discovery in `src/main/resources/application-local.yaml`:

```yaml
spring:
  cloud:
    kubernetes:
      enabled: false
    discovery:
      client:
        simple:
          instances:
            order-service:
              - uri: http://localhost:8082
            product-service:
              - uri: http://localhost:8081
            user-service:
              - uri: http://localhost:8085
```

Start services with `-Dspring-boot.run.profiles=local` to activate this profile.

---

## 19. CI/CD — GitHub Actions

### Pipeline overview

| Workflow | File | Trigger | Purpose |
|---|---|---|---|
| CI | `.github/workflows/ci.yaml` | Push to `main`, Pull Requests | Change detection, unit tests, Jib image publish to `ghcr.io` |
| CD | `.github/workflows/cd.yaml` | CI completes on `main` | Deploy to k3d staging cluster; curl smoke tests |

**No extra secrets required** — Jib authenticates to `ghcr.io` using the auto-provided `GITHUB_TOKEN`.

### Change detection — only build what changed

Use `dorny/paths-filter` to detect which service directories changed. The matrix job runs only for changed services, keeping CI fast for single-service PRs. Changes to `common/` or the root `pom.xml` mark **all** services as changed (conservative).

```yaml
- uses: dorny/paths-filter@v3
  id: filter
  with:
    filters: |
      product-service:
        - 'product-service/**'
        - 'common/**'
        - 'pom.xml'
      order-service:
        - 'order-service/**'
        - 'common/**'
        - 'pom.xml'
      reviews-service:
        - 'reviews-service/**'
        - 'common/**'
        - 'pom.xml'
      notification-service:
        - 'notification-service/**'
        - 'common/**'
        - 'pom.xml'
      user-service:
        - 'user-service/**'
        - 'common/**'
        - 'pom.xml'
```

### CI workflow — `.github/workflows/ci.yaml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  detect-changes:
    runs-on: ubuntu-latest
    outputs:
      services: ${{ steps.filter.outputs.changes }}
    steps:
      - uses: actions/checkout@v4
      - uses: dorny/paths-filter@v3
        id: filter
        with:
          filters: |
            product-service:
              - 'product-service/**'
              - 'common/**'
              - 'pom.xml'
            order-service:
              - 'order-service/**'
              - 'common/**'
              - 'pom.xml'
            reviews-service:
              - 'reviews-service/**'
              - 'common/**'
              - 'pom.xml'
            notification-service:
              - 'notification-service/**'
              - 'common/**'
              - 'pom.xml'
            user-service:
              - 'user-service/**'
              - 'common/**'
              - 'pom.xml'

  build:
    needs: detect-changes
    if: ${{ needs.detect-changes.outputs.services != '[]' }}
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: ${{ fromJson(needs.detect-changes.outputs.services) }}
      fail-fast: false
    permissions:
      contents: read
      packages: write       # required to push to ghcr.io
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
          cache: maven

      - name: Run unit tests
        run: mvn -pl ${{ matrix.service }} -am verify --no-transfer-progress

      - name: Build and push image to ghcr.io
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        run: |
          mvn -pl ${{ matrix.service }} compile jib:build \
            --no-transfer-progress \
            -Ddocker.registry=ghcr.io/${{ github.repository_owner }} \
            -Djib.to.tags=${{ github.sha }},latest \
            -Djib.to.auth.username=${{ github.actor }} \
            -Djib.to.auth.password=${{ secrets.GITHUB_TOKEN }}
```

> The `jib:build` step runs only on `push` to `main` — not on pull requests — to avoid publishing images from unreviewed code. Unit tests still run on PRs via the `verify` goal.

### CD workflow — `.github/workflows/cd.yaml`

Deploys the newly published images to a **k3d cluster running inside the GitHub Actions runner**. The cluster is ephemeral (destroyed at the end of the run) and functions as a staging smoke-test environment.

```yaml
name: CD — Staging

on:
  workflow_run:
    workflows: [CI]
    types: [completed]
    branches: [main]

jobs:
  deploy-staging:
    runs-on: ubuntu-latest
    if: ${{ github.event.workflow_run.conclusion == 'success' }}
    steps:
      - uses: actions/checkout@v4

      - name: Install k3d
        run: curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | bash

      - name: Install Helm
        uses: azure/setup-helm@v4

      - name: Create k3d cluster
        run: |
          k3d cluster create e-commerce \
            --port "80:80@loadbalancer" \
            --port "8180:8180@loadbalancer" \
            --wait

      - name: Install Envoy Gateway
        run: |
          helm install eg oci://docker.io/envoyproxy/gateway-helm \
            --version v1.3.0 \
            --namespace envoy-gateway-system \
            --create-namespace \
            --wait

      - name: Deploy infrastructure
        run: kubectl apply -f k8s/infra/

      - name: Wait for infrastructure
        run: |
          kubectl rollout status deployment/keycloak -n e-commerce-infra --timeout=120s
          kubectl rollout status deployment/kafka     -n e-commerce-infra --timeout=120s
          kubectl rollout status deployment/mongodb   -n e-commerce-infra --timeout=60s

      - name: Deploy services
        env:
          IMAGE_TAG: ${{ github.event.workflow_run.head_sha }}
          REGISTRY: ghcr.io/${{ github.repository_owner }}
        run: |
          for svc in product-service order-service reviews-service notification-service user-service; do
            export IMAGE="${REGISTRY}/${svc}:${IMAGE_TAG}"
            envsubst < k8s/${svc}/deployment.yaml | kubectl apply -f -
            kubectl apply -f k8s/${svc}/service.yaml
            kubectl apply -f k8s/${svc}/configmap.yaml
            kubectl apply -f k8s/${svc}/serviceaccount.yaml
          done
          kubectl apply -f k8s/envoy-gateway/

      - name: Wait for service rollouts
        run: |
          for svc in product-service order-service reviews-service user-service; do
            kubectl rollout status deployment/${svc} -n e-commerce --timeout=120s
          done

      - name: Smoke tests
        run: |
          sleep 10
          curl -sf http://localhost/api/v1/products -w "\nHTTP %{http_code}\n"
          curl -sf http://localhost/api/v1/users/me  -w "\nHTTP %{http_code}\n" || true
```

> **Image tag patching:** `k8s/{service}/deployment.yaml` uses `image: ${IMAGE}` as a placeholder. `envsubst` substitutes the `$IMAGE` environment variable before applying, pinning each deployment to the exact commit SHA built by CI.

### Deployment YAML image placeholder convention

In every `k8s/{service}/deployment.yaml`, use the `${IMAGE}` shell variable as the image reference so `envsubst` can substitute it in CI:

```yaml
# k8s/product-service/deployment.yaml (excerpt)
spec:
  template:
    spec:
      containers:
        - name: product-service
          image: ${IMAGE}             # substituted by envsubst in CD workflow
          ports:
            - containerPort: 8081
```

### Secrets and permissions

| Secret / Permission | Source | Required for |
|---|---|---|
| `secrets.GITHUB_TOKEN` | Auto-provided by GitHub Actions | Authenticating Jib to push to `ghcr.io` |
| `permissions.packages: write` | Declared in CI `build` job | Authorising `GITHUB_TOKEN` to publish packages |

No manually configured repository secrets are needed for the standard CI/CD pipeline.

### Branch protection (recommended)

In **Settings → Branches → Branch protection rules** for `main`:

- ✅ Require status checks to pass — add `build` (CI job) as required check
- ✅ Require branches to be up to date before merging
- ✅ Require at least 1 pull request review before merging
- ✅ Do not allow bypassing the above settings
