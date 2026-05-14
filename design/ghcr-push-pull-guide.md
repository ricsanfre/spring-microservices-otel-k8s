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

By default, packages pushed by `GITHUB_TOKEN` are **private**. To make them public:

1. Go to **GitHub → Your profile → Packages → \<package-name\>**
2. **Package settings → Change visibility → Public**

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
