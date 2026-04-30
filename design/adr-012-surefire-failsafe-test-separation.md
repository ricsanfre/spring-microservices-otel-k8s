# ADR-012 — Surefire / Failsafe Split for Unit and Integration Tests

**Date:** 2026-04-30  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

The project has two distinct categories of automated tests:

1. **Unit and slice tests** — pure JVM tests using Mockito (`@ExtendWith(MockitoExtension.class)`)
   and Spring MVC slice tests (`@WebMvcTest`). They have no external dependencies and run in
   seconds.

2. **Integration tests** — full-context tests (`@SpringBootTest`) backed by real infrastructure
   containers (PostgreSQL, MongoDB, Valkey/Redis) managed by TestContainers. They spin up Docker
   containers and can take tens of seconds per test class.

Before this ADR, **all tests ran together under `mvn test`** via the default Maven Surefire plugin.
This meant every `mvn test` invocation — including fast PR feedback loops — also launched Docker
containers, significantly increasing local and CI build times even when only unit-level feedback was
needed.

Additionally, Failsafe provides a stronger guarantee than Surefire for integration tests: it
**always executes `post-integration-test`** (container cleanup) even when a test fails, whereas
Surefire halts on the first failure and may skip cleanup phases.

---

## Decision

Split test execution across two Maven plugins using a **file-naming convention**:

| Convention | Plugin | Maven phase | Command |
|---|---|---|---|
| `*Test.java` | `maven-surefire-plugin` | `test` | `mvn test` |
| `*IT.java` | `maven-failsafe-plugin` | `integration-test` + `verify` | `mvn verify` |

### Configuration location

Both plugins are configured once in `<pluginManagement>` in the root `pom.xml`:

- **Surefire** is configured with `<excludes>**/*IT.java</excludes>` so integration tests never
  leak into the unit phase.
- **Failsafe** is configured with `<includes>**/*IT.java</includes>` and binds the
  `integration-test` and `verify` goals to ensure results are checked and the build fails
  correctly after the integration phase.

Service modules that contain `*IT.java` tests declare `maven-failsafe-plugin` in their own
`<build><plugins>` without repeating any configuration — they inherit everything from
`pluginManagement`.

### Naming convention applied

| Old name | New name | Reason |
|---|---|---|
| `CartControllerL1Test` | `CartControllerIT` | TestContainers (Valkey) |
| `CartServiceApplicationTests` | `CartServiceApplicationIT` | `@SpringBootTest` context |
| `ProductControllerL1Test` | `ProductControllerIT` | TestContainers (MongoDB) |
| `ProductServiceApplicationTests` | `ProductServiceApplicationIT` | `@SpringBootTest` context |
| `UserControllerIntegrationTest` | `UserControllerIT` | TestContainers (PostgreSQL) |
| `UserServiceApplicationTests` | `UserServiceApplicationIT` | TestContainers (PostgreSQL) |

Unit and slice tests (`*ServiceTest`, `*ControllerTest`, `*RepositoryTest`) are unchanged — they
already follow the `*Test.java` convention.

---

## Consequences

### Positive

- **`mvn test` is fast** — only unit and `@WebMvcTest` slice tests run; no Docker containers are
  started. Suitable as a quick PR feedback gate (seconds).
- **`mvn verify` is complete** — Failsafe adds integration tests after the unit phase. Suitable for
  merge gates and deployment pipelines.
- **Failsafe guarantees cleanup** — the `post-integration-test` phase always runs even when a test
  fails, preventing dangling containers.
- **No tagging infrastructure required** — the split is enforced by filename pattern, not by
  `@Tag` annotations or JUnit 5 groups, keeping test code free of build-tooling concerns.
- **Convention is self-documenting** — any developer reading a file named `*IT.java` immediately
  knows it requires infrastructure; `*Test.java` means pure JVM.

### Negative / Trade-offs

- **Two commands instead of one** — developers must remember to run `mvn verify` (not just
  `mvn test`) to execute the full suite locally.
- **Failsafe must be explicitly declared per module** — service modules containing `*IT.java` tests
  must add a `maven-failsafe-plugin` entry to their `pom.xml`. A module that omits this entry will
  silently skip integration tests. This is mitigated by code review and the fact that the IT test
  file itself will be visible in the module's test source tree.

---

## Alternatives Considered

### JUnit 5 `@Tag` filtering

Configure Surefire/Failsafe to include/exclude by JUnit tag (e.g. `@Tag("integration")`). Rejected
because it requires every integration test class to carry the annotation, mixes build-tooling
concerns into test code, and is easier to accidentally omit than a filename suffix.

### Single Surefire run with tag exclusion

Exclude tagged tests from `mvn test` but never run them in a separate phase. Rejected because it
loses the Failsafe cleanup guarantee and does not establish a clear `verify` gate in the Maven
lifecycle.

### Separate Maven profiles

Use `-Pintegration` profiles to gate test execution. Rejected as more complex than necessary and
incompatible with standard CI tooling that relies on lifecycle phases (`test`, `verify`).
