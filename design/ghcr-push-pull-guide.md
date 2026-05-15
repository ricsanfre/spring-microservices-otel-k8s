# GitHub Container Registry (ghcr.io) — Push & Pull Guide

## Authentication

### Local CLI

Create a Personal Access Token (PAT) at **GitHub → Settings → Developer settings → Personal access tokens (classic)** with scopes:
- `read:packages` — pull
- `write:packages` — push
- `delete:packages` — delete (optional)

```bash
# Login once — credentials are stored in ~/.docker/config.json
echo "<YOUR_PAT>" | docker login ghcr.io -u <your-github-username> --password-stdin
```

### GitHub Actions

`GITHUB_TOKEN` is auto-provided — no manual secrets needed. The workflow already does this correctly:
```yaml
-Djib.to.auth.username=${{ github.actor }}
-Djib.to.auth.password=${{ secrets.GITHUB_TOKEN }}
```

---

## Pushing Images

### Local — Java services (Jib, no Docker daemon)

```bash
# Push a single service
mvn -pl common,user-service -am compile jib:build \
  --no-transfer-progress \
  -Ddocker.registry=ghcr.io/<your-github-username> \
  -Djib.to.tags=local-test,latest \
  -Djib.to.auth.username=<your-github-username> \
  -Djib.to.auth.password=<YOUR_PAT>
```

Or set credentials in `~/.m2/settings.xml` to avoid passing them on the command line:
```xml
<servers>
  <server>
    <id>ghcr.io</id>
    <username>your-github-username</username>
    <password>your-pat-here</password>
  </server>
</servers>
```
Then omit the `-Djib.to.auth.*` flags.

### Local — Frontend service (Docker)

```bash
cd frontend-service
docker build -t ghcr.io/<your-github-username>/frontend-service:local-test .
docker push ghcr.io/<your-github-username>/frontend-service:local-test
```

### GitHub Actions (already configured)

The `build-java` job in [ci.yaml](../.github/workflows/ci.yaml) pushes on every `push` to `main`:
```yaml
- name: Build and push image to ghcr.io (Jib)
  if: github.event_name == 'push' && github.ref == 'refs/heads/master'
  run: |
    mvn -pl ${{ matrix.service }} compile jib:build \
      -Ddocker.registry=ghcr.io/${{ github.repository_owner }} \
      -Djib.to.tags=${{ github.sha }},latest \
      -Djib.to.auth.username=${{ github.actor }} \
      -Djib.to.auth.password=${{ secrets.GITHUB_TOKEN }}
```

Two tags are always pushed: a pinned `<commit-sha>` and a floating `latest`.

---

## Pulling Images

### Local

```bash
# Pull latest
docker pull ghcr.io/<your-github-username>/user-service:latest

# Pull a specific commit
docker pull ghcr.io/<your-github-username>/user-service:<git-sha>
```

If the package is private, you must be logged in first (see Authentication above).

### GitHub Actions — pulling in a job

```yaml
- name: Log in to ghcr.io
  uses: docker/login-action@v3
  with:
    registry: ghcr.io
    username: ${{ github.actor }}
    password: ${{ secrets.GITHUB_TOKEN }}

- name: Pull image
  run: docker pull ghcr.io/${{ github.repository_owner }}/user-service:${{ github.sha }}
```

The CD workflow uses this pattern when deploying to the k3d cluster — it patches the image tag to `${{ github.sha }}` so the exact commit image is deployed.

---

## Package Visibility

By default, packages pushed by `GITHUB_TOKEN` are **private**, even in a public repository. A private package causes a `403 Forbidden` when Kubernetes (or anyone else) tries to pull without credentials.

### Making an image public (UI)

1. Go to `https://github.com/ricsanfre?tab=packages`
2. Click the package (e.g. `micro-sp4-otel/user-service`)
3. Click **Package settings** (bottom-right of the package page)
4. Scroll to **Danger Zone → Change visibility → Public**
5. Repeat for each service image

### Making an image public (API)

```bash
# Requires a PAT with write:packages
curl -X PATCH \
  -H "Authorization: Bearer <YOUR_PAT>" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/user/packages/container/<package-name> \
  -d '{"visibility":"public"}'
```

### Inherit visibility from the repository

Once a package is linked to the repository (which the Jib CI push does automatically via `GITHUB_TOKEN`), you can configure all packages in one place:

> **github.com/ricsanfre/micro-sp4-otel** → Settings → Packages → **Inherit access from source repository**

This makes every linked package follow the repo's public/private setting — useful so newly pushed service images don't need manual visibility changes.

Or via the API:
```bash
# Make a package public (requires a PAT with write:packages)
curl -X PATCH \
  -H "Authorization: Bearer <YOUR_PAT>" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/user/packages/container/<package-name> \
  -d '{"visibility":"public"}'
```

For k8s deployments pulling from a private registry, an `imagePullSecret` is needed — the CD workflow creates one with:
```yaml
kubectl create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=${{ github.actor }} \
  --docker-password=${{ secrets.GITHUB_TOKEN }}
```

---

## Permissions Required in the Workflow

The `build-java` and `build-frontend` jobs already have `packages: write`. Any job that only pulls needs at minimum `packages: read` (the default `contents: read` top-level permission does **not** grant package access):

```yaml
permissions:
  contents: read
  packages: read   # for pull
  # packages: write  # for push
```

---

## Listing / Inspecting Images

```bash
# List tags for a package
curl -H "Authorization: Bearer <YOUR_PAT>" \
  "https://api.github.com/user/packages/container/user-service/versions" \
  | jq '.[].metadata.container.tags'

# Inspect a manifest without pulling
docker manifest inspect ghcr.io/<your-github-username>/user-service:latest
```
