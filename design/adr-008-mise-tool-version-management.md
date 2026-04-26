# ADR-008 — mise for Developer Tool Version Management

**Date:** 2026-04-26  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

This project requires several external tools across two development scenarios:

| Scenario | Required tools |
|----------|---------------|
| Option A — Local dev (Docker Compose) | Docker, Java 25, Maven 3.9, Node.js 22, `curl`, `jq` |
| Option B — Staging (k3d) | Everything above + `kubectl`, `helm`, `kustomize`, `k3d` |

Without a version manager, contributors must install each tool independently using their OS package manager (apt, brew, winget), curl scripts, or official installers. This leads to:

- **Version drift** — different contributors running subtly different versions (e.g., kubectl 1.28 vs 1.31, helm 3.12 vs 3.15, Java 21 vs 25)
- **OS-specific install friction** — no single install command works across Linux, macOS, and Windows
- **Undocumented upgrade paths** — when minimum versions change, each contributor must find and re-run the correct installer
- **CI/CD divergence** — CI workflows pin different versions than local dev without a shared source of truth

The project already uses multiple existing tool managers for specific runtimes (e.g., SDKMAN for Java, nvm for Node.js), but they are language-specific and cannot manage Kubernetes CLI tools, `jq`, or cross-language build tools in a unified way.

---

## Decision

Use **[mise](https://mise.jdx.dev)** (mise-en-place) as the single tool version manager for all developer prerequisites. Pin all required tool versions in a root-level `.mise.toml` file committed to the repository.

```toml
[tools]
java      = "temurin-25"
maven     = "3.9"
node      = "22"
kubectl   = "latest"
helm      = "latest"
k3d       = "latest"
kustomize = "latest"
jq        = "latest"
```

Running `mise install` from the repository root installs every tool at the declared version into `~/.local/share/mise/installs/` — isolated from system packages, no `sudo` required.

---

## Rationale

### Single tool, polyglot registry

mise manages language runtimes (Java, Node.js, Python, Go, Ruby) **and** DevOps/CLI tools (kubectl, helm, k3d, kustomize, jq, terraform, …) from a single registry backed by [aqua](https://mise.jdx.dev/dev-tools/backends/aqua.html), [asdf plugins](https://mise.jdx.dev/dev-tools/backends/asdf.html), and GitHub Releases. Every tool listed in `.mise.toml` is available in the mise registry — no custom plugin authoring required.

### Comparison with alternatives

| Concern | Manual install scripts | SDKMAN + nvm | mise |
|---------|----------------------|--------------|------|
| Languages (Java, Node) | ✗ Per-tool install | ✓ Separate managers | ✓ Unified |
| DevOps CLIs (kubectl, helm…) | curl scripts | ✗ | ✓ |
| Version pinned in repo | ✗ | ✗ | ✓ `.mise.toml` |
| Per-directory version switching | ✗ | Partial | ✓ |
| One-command install | ✗ | ✗ | ✓ `mise install` |
| No `sudo` / no root | ✗ | ✓ | ✓ |
| Windows support | ✗ | ✗ | ✓ |
| Rust-based (fast) | — | No | ✓ |
| CI reuse | ✗ | Partial | ✓ `mise install --non-interactive` |

### Per-directory version switching

mise automatically activates the correct tool versions when entering the project directory (via shell hook `eval "$(mise activate bash)"`). This means a developer can have Java 21 globally but Java 25 inside this repo — no manual `export JAVA_HOME` required.

### Docker is excluded

Docker (the daemon) is excluded from `.mise.toml` because:
- mise can manage `docker-cli` and `docker-compose` but **not** the Docker daemon (`dockerd`)
- Installing the daemon requires OS-level integration (systemd unit, kernel namespaces, cgroup v2) that a user-space tool manager cannot provide
- The [official Docker install script](https://get.docker.com) remains the recommended path: `curl -fsSL https://get.docker.com | sh`

---

## Consequences

### Positive

- New contributors can set up a fully working dev environment with three commands:
  ```bash
  curl https://mise.run | sh        # install mise
  echo 'eval "$(mise activate bash)"' >> ~/.bashrc && source ~/.bashrc
  mise install                      # install all tools at pinned versions
  ```
- Version upgrades are a one-line change in `.mise.toml`, visible in git history, reviewable in PRs
- CI workflows can reuse the same `.mise.toml` by running `mise install --non-interactive`
- mise is OS-agnostic; no separate instructions for Linux / macOS / Windows

### Negative / Trade-offs

- mise is an additional tool that developers must install before anything else — a bootstrap step unavoidable with any version manager
- Developers already using SDKMAN or nvm must either switch their workflow or keep both (mise and their existing manager) in parallel; shell `PATH` ordering must be checked to avoid conflicts
- `java = "temurin-25"` requires mise to resolve the Temurin JDK from the Adoptium API; first install requires internet access and takes ~2 minutes to download

---

## Related ADRs

- [ADR-010 — Jib for Container Image Builds](adr-010-jib-for-container-image-builds.md) — Jib requires Java 25; the JDK version is now pinned via mise
- [ADR-007 — Next.js BFF Frontend](adr-007-nextjs-bff-frontend.md) — frontend-service requires Node.js 22; the version is now pinned via mise
