# ADR-010 — Jib for Container Image Builds

**Date:** 2026-04-25  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

Each microservice needs to be packaged as an OCI container image and pushed to a registry (local k3d registry for staging, `ghcr.io` for CI). The build must work:

- On developer laptops (macOS / Linux)
- In GitHub Actions CI runners (no Docker daemon by default in some runners)
- Without requiring Docker Desktop licensing

Three approaches for building container images in a Maven project:

1. **Dockerfile + `docker build`** — traditional approach; requires Docker daemon
2. **Google Jib Maven plugin** — builds and pushes OCI images directly from Maven, without a Docker daemon or Dockerfile
3. **Cloud Native Buildpacks (`mvn spring-boot:build-image`)** — Spring Boot built-in; uses Buildpacks to create an image; requires Docker daemon

---

## Decision

Use **Google Jib** (`com.google.cloud.tools:jib-maven-plugin`) to build and push all service container images.

There is **no Dockerfile** in any service module. Images are built entirely from the Maven plugin configuration in the root POM `<pluginManagement>`.

---

## Rationale

### No Docker daemon required

Jib builds OCI-compliant images directly from the Maven classpath without calling `docker build`. It communicates directly with the registry API to push layers. This means:

- CI runners do not need Docker installed or Docker Desktop licensed
- Developer laptops without Docker running can still produce and push images
- Build times are faster because no Docker layer export/import step occurs

### Reproducible, layered images

Jib automatically splits the image into deterministic layers ordered from most-stable to least-stable:

| Layer | Contents | Changes |
|-------|----------|---------|
| 1 | JRE base image (`eclipse-temurin:25-jre-alpine`) | Only on JRE update |
| 2 | Dependencies (non-SNAPSHOT JARs) | Only on dependency version change |
| 3 | Snapshot dependencies | On snapshot dependency change |
| 4 | Resources (`application.yaml`, static files) | On config change |
| 5 | Classes (compiled bytecode) | On every code change |

In practice, only layers 4–5 change on most commits. Registry pushes push only the changed layers, dramatically reducing push time and registry storage in CI.

### Build targets

```bash
# Push to k3d local registry (developer staging)
mvn compile jib:build -Ddocker.registry=localhost:5000

# Push to GitHub Container Registry (CI/CD)
mvn compile jib:build -Ddocker.registry=ghcr.io/owner

# Build to local Docker daemon (local smoke test)
mvn compile jib:dockerBuild

# Build to tarball (CI without Docker daemon, for k3d image import)
mvn compile jib:buildTar
```

The `docker.registry` property is defined in the root POM with default `localhost:5000` (k3d). CI overrides it via `-D` on the command line — no POM modification needed.

### Base image

```xml
<from>
    <image>eclipse-temurin:25-jre-alpine</image>
</from>
```

`eclipse-temurin:25-jre-alpine` is the official Adoptium OpenJDK 25 JRE on Alpine Linux. Choosing JRE (not JDK) reduces the image size. Alpine minimises the attack surface. Adoptium provides long-term, enterprise-grade JDK builds.

### JVM flags baked in

```xml
<container>
    <jvmFlags>
        <jvmFlag>-Dspring.threads.virtual.enabled=true</jvmFlag>
        <jvmFlag>-XX:MaxRAMPercentage=75.0</jvmFlag>
        <jvmFlag>-XX:+UseContainerSupport</jvmFlag>
    </jvmFlags>
</container>
```

- `spring.threads.virtual.enabled=true` — enables Project Loom virtual threads (see `application.yaml` also)
- `MaxRAMPercentage=75.0` — caps JVM heap at 75% of the container's memory limit; prevents OOM kills
- `UseContainerSupport` — makes the JVM respect `cgroups` memory limits (on by default in JDK 11+, but explicit for clarity)

### Why not Dockerfile

| Concern | Dockerfile | Jib |
|---------|-----------|-----|
| Docker daemon required | Yes | No |
| Layering | Manual (order of `COPY` instructions) | Automatic, deterministic |
| Reproducibility | Build context can vary | Entirely from Maven classpath |
| Maintenance | Separate file per service | Centralised in root POM `<pluginManagement>` |
| Security scanning | Works with any scanner | Works with any OCI-compliant scanner |

A Dockerfile would require every service to maintain its own `Dockerfile` and every developer to have Docker running. Jib centralises the image configuration in the root POM and eliminates the Docker daemon dependency.

### Why not Buildpacks (`spring-boot:build-image`)

Spring Boot Buildpacks are excellent for zero-configuration image builds but:
- **Requires a Docker daemon** at build time (uses `docker` CLI internally)
- **Slower** — Buildpacks detect the runtime environment, install layers, and run a full lifecycle; Jib directly assembles the image from known Maven artifacts
- **Less control** over layer structure, base image, and JVM flags without buildpack customisation
- For this project, the Jib configuration is well-understood, minimal, and centralised — there is no benefit to Buildpacks' higher-level abstraction

---

## Consequences

### Positive

- **No Docker daemon required** — builds work in any Maven environment, including CI runners without Docker
- **Fast incremental pushes** — only changed layers are pushed; most commits push only the classes layer
- **Centralised image config** — all services share one Jib configuration in the root POM `<pluginManagement>`; per-service POMs add the plugin with no additional config
- **No Dockerfile maintenance** — no per-service Dockerfile to keep in sync with the application

### Neutral

- `mvn compile jib:build` must be run before `mvn package` completes — the image is built from compiled classes, not from the final JAR. This is by design; Jib does not need the fat JAR.
- Image tags follow `${project.version}` + `latest`. CI pipelines should override with a commit SHA: `-Ddocker.tag=$(git rev-parse --short HEAD)` for traceability.

### Negative / Trade-offs

- **No `docker build` caching** — Jib bypasses the Docker layer cache. On the first build in a fresh environment, all layers must be pushed. Subsequent builds benefit from registry-side layer deduplication.
- **Requires registry credentials** — `jib:build` pushes to a registry and requires authentication. CI uses `GITHUB_TOKEN` for `ghcr.io`; local builds use the k3d registry which accepts unauthenticated pushes.

---

## Implementation Notes

- Plugin: `com.google.cloud.tools:jib-maven-plugin:3.4.4`
- Configured in root POM `<pluginManagement>`; activated in each service `<build><plugins>` with no additional config
- `docker.registry` property (root POM): default `localhost:5000`; override with `-Ddocker.registry=...`
- Makefile target `k8s-us-image`: `mvn compile jib:build -pl user-service -Ddocker.registry=localhost:5000`
- See `development-guidelines.md` Section 11 for full plugin configuration XML
