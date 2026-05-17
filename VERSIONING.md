# Versioning and Image Tagging Policy

This document defines the lifecycle, versioning standards, Docker image tagging policies, and automated GitOps deployment flows for both Spring Boot (Maven) and Next.js (Node.js) microservices.

---

## 1. Versioning Policy

All backend modules inherit their version from the root `pom.xml`. The Next.js frontend manages its version via `package.json`. To maintain consistency across the platform, both ecosystems follow synchronized version phases.


| Phase | Maven Format | Next.js Format | Example (Java / Node) | Meaning |
|-------|---------------|----------------|-----------------------|---------|
| **Active development** | `X.Y.Z-SNAPSHOT` | `X.Y.Z-snapshot` | `1.0.0-SNAPSHOT` / `1.0.0-snapshot` | Mutable; rebuilt and republished freely |
| **Release / Production** | `X.Y.Z` | `X.Y.Z` | `1.0.0` / `1.0.0` | Immutable; never overwritten |

### Semantic Versioning (SemVer) Rules
Both applications must strictly follow SemVer principles:
- **PATCH** — Backwards-compatible bug fixes.
- **MINOR** — Backwards-compatible new features.
- **MAJOR** — Breaking API or architectural changes.

### Bumping Versions Locally
To change development versions across all applications at once:

```bash
# Backend (Root Directory)
mvn versions:set -DnewVersion=1.1.0-SNAPSHOT
mvn versions:commit

# Frontend (Next.js App Directory)
npm version 1.1.0-snapshot --no-git-tag-version
```

---

## 2. Docker Image Tags

Images are built using **Jib** for Spring Boot and standard **Dockerfiles** via GitHub Actions for Next.js. Every push to the `main` branch generates three specific tags to support local development, tracking, and immutable GitOps deployments.


| Tag Type | Source Reference | Target Environment / Use Case |
|:---|:---|:---|
| **Development** | `${project.version}` / `package.json` version | Mutable development tag — overwritten on every build. |
| **Commit SHA** | `${{ github.sha }}` | Immutable, pinned to the exact commit — **Mandatory for Kubernetes deployment.** |
| **Latest** | CI runner convention | Floating pointer to the most recent successful `main` build. |

> ⚠️ **CRITICAL SECURITY & STABILITY RULE**
> **Never deploy a `-SNAPSHOT`, `-snapshot`, or `latest` tag to a production or stable staging environment.** Snapshot images change constantly. If a cluster pod restarts, it may pull a different image than the one originally verified. Always pin Kubernetes deployments to the immutable `<git-sha>` tag via Kustomize image transforms.

---

## 3. GitHub Actions CI Automation (Next.js Frontend)

While the Spring Boot backend relies on the Maven Jib plugin to handle tags, the Next.js frontend uses this GitHub Actions workflow block. It dynamically extracts the version string from `package.json` and pushes all three policy tags to GitHub Packages (`ghcr.io`).

The command to extract the version and set it as an output variable is:

```bash
VERSION=$(node -p "require('./frontend/package.json').version")
echo "pkg_version=$VERSION" >> $GITHUB_OUTPUT
```

---

## 4. GitOps & Flux Automation with Mend Renovate

To automate application updates within our Flux CD GitOps workflow without human intervention, Mend Renovate is configured to scan Kustomize manifests. 

To ensure stability, Renovate is explicitly restricted to **only match clean Semantic Versioning (SemVer) tags** and automatically scales to any new image deployed under our organization pathway.

### Renovate Configuration (`renovate.json`)
Place this file in the root of the repository:

```json
{
  "\$schema": "https://renovatebot.com",
  "extends": [
    "config:recommended"
  ],
  "packageRules": [
    {
      "matchPackagePatterns": [
        "^ghcr\\.io/ricsanfre/spring-microservices-otel-k8s/"
      ],
      "versioning": "semver",
      "allowedVersions": "/^[0-9]+\\.[0-9]+\\.[0-9]+\$/",
      "automerge": false
    }
  ],
  "customManagers": [
    {
      "customType": "regex",
      "fileMatch": [
        "^gitops/.*\\.yaml\$"
      ],
      "matchStrings": [
        "-\\s+name:\\s+(?<depName>ghcr\\.io/ricsanfre/spring-microservices-otel-k8s/[a-zA-Z0-9-]+)\\s+newTag:\\s+\"(?<currentValue>[^\"]+)\""
      ],
      "versioningTemplate": "semver"
    }
  ]
}
```

### Automation Behavior Flow
1. **Generic Matching**: The `matchPackagePatterns` regex targets *any* current or future container image inside `ghcr.io/ricsanfre/spring-microservices-otel-k8s/`.
2. **Strict SemVer Filter**: The `allowedVersions` regex filter (`X.Y.Z`) ensures Renovate ignores unstable `-SNAPSHOT`, `-snapshot`, or Git SHA tags.
3. **Kustomize Synchronization**: Custom Regex Managers dynamically discover the target Kustomize `newTag` values inside your `gitops/` manifests and automatically open Pull Requests when a new official release is published to GHCR.

---

## 5. Promoting a Release

Follow these steps to transition from development snapshots to an immutable production release.

### Step 1: Strip Development Suffixes
Remove the snapshot indicators from all project configurations:
```bash
# Backend
mvn versions:set -DnewVersion=1.0.0 && mvn versions:commit

# Frontend
cd frontend
npm version 1.0.0 --no-git-tag-version
```

### Step 2: Commit, Tag, and Push
Commit the release configurations and push a Git tag. This action triggers the CI release pipeline to generate the production-ready `:1.0.0` images.
```bash
git add .
git commit -m "chore: release version 1.0.0"
git tag -a v1.0.0 -m "Release version 1.0.0"
git push origin main --tags
```

### Step 3: Update GitOps Overlays (Manual or Renovate-Driven)
Update the respective staging or production overlays using Kustomize image transformers to lock down the exact release tag:

```yaml
# gitops/apps/user-service/overlays/staging/kustomization.yaml
images:
  - name: ghcr.io/ricsanfre/spring-microservices-otel-k8s/user-service
    newTag: "1.0.0"
```

```yaml
# gitops/apps/nextjs-frontend/overlays/staging/kustomization.yaml
images:
  - name: ghcr.io/ricsanfre/spring-microservices-otel-k8s/nextjs-frontend
    newTag: "1.0.0"
```

### Step 4: Open the Next Development Cycle
Immediately bump the master/main branch configurations to the next planned iteration:
```bash
# Backend
mvn versions:set -DnewVersion=1.1.0-SNAPSHOT && mvn versions:commit

# Frontend
cd frontend
npm version 1.1.0-snapshot --no-git-tag-version

# Commit back to main
git add .
git commit -m "chore: open development cycle 1.1.0-SNAPSHOT"
git push origin main
```
