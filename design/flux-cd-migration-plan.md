# Flux CD Migration Plan

Migration plan for moving the k3d `kubectl`-driven deployment to a GitOps deployment managed by Flux CD, based on the architectural strategy defined in [`flux-deployment-strategy.md`](flux-deployment-strategy.md).

---

## 1. Current State vs. Target State

### 1.1 Repository structure comparison

| Area | Current (`k8s/`) | Target |
|---|---|---|
| Infra components | `k8s/infra/<component>/` — flat, no base/overlay split | `gitops/infrastructure/<component>/base/` + `overlays/{staging,production}/` |
| Operators | Installed imperatively via `make k8s-*-helm` Helm commands | Flux `HelmRelease` + `HelmRepository` CRDs inside each component directory |
| Envoy Gateway | `k8s/envoy-gateway/` flat directory | `gitops/infrastructure/envoy-gateway/base/` + per-environment overlays |
| App resources | `k8s/apps/<service>/base/` + `overlays/staging/` per service | `gitops/apps/<service>/base/` + `overlays/{staging,production}/` per service |
| Cross-cutting app CRs | `k8s/apps/base/` (realm, Kafka topics/users, CNPG databases) | `gitops/apps/core-config/base/` — business-owned CRs that outlive any single service |
| Flux entry points | **None** — no `clusters/` directory, no Flux CRDs | `gitops/clusters/{staging,production}/` with Flux `Kustomization` orchestration files |
| Production environment | **Does not exist** — staging only | Full production overlay set for every infrastructure component and every application |

### 1.2 What is already well-aligned

- `k8s/apps/base/` already separates business-owned CRs (realm import, Kafka topics/users, CNPG databases) from operator infrastructure. This directly matches the strategy's **Application-Owned Business Resources** principle.
- Per-service Kustomize `base/` + `overlays/staging/` already follow the DRY overlay pattern.
- ESO Fake provider (`k8s/infra/eso/cluster-secret-store.yaml`) is already the staging secret backend — maps cleanly to `gitops/infrastructure/eso-stores/overlays/staging/`.
- Keycloak realm import uses `spec.placeholders` with ESO-injected secrets — more secure than the strategy document's example that hardcodes the client secret directly.

---

## 2. Gap Analysis

### Gap 1 — No Flux bootstrap (blocker)

No `clusters/` directory exists. No `flux-system` namespace. No `GitRepository` or `Kustomization` CRDs of any kind. The entire GitOps control loop is absent. Everything is currently driven by `kubectl apply` and `make k8s-*` Makefile targets.

### Gap 2 — Operators installed imperatively, not via HelmRelease

The `k8s/helm/*.yaml` files are raw Helm values passed directly to `helm upgrade --install` in the Makefile. Flux requires `HelmRelease` + `HelmRepository` (or `OCIRepository`) CRDs so the operator lifecycle is reconciled continuously by the Flux Helm controller.

Operators to convert (8 total):

| Operator | Current install target | Chart source |
|---|---|---|
| cert-manager | `k8s-cert-manager-helm` | `jetstack/cert-manager` Helm repo |
| Envoy Gateway | `k8s-envoy-gateway-helm` | OCI `oci://docker.io/envoyproxy/gateway-helm` |
| Strimzi | `k8s-strimzi-operator-helm` | OCI `oci://quay.io/strimzi-helm-charts/strimzi-kafka-operator` |
| CloudNativePG | `k8s-cnpg-operator-helm` | `cnpg/cloudnative-pg` Helm repo |
| MongoDB Community | `k8s-mongodb-operator-helm` | `mongodb/community-operator` Helm repo |
| Keycloak Operator | `k8s-keycloak-operator` | `kubectl apply -k` (no Helm chart — upstream kustomize bundle) |
| OTel Operator | `k8s-otel-operator-helm` | `open-telemetry/opentelemetry-operator` Helm repo |
| ESO | `k8s-eso-helm` | `external-secrets/external-secrets` Helm repo |

### Gap 3 — Infrastructure components have no base/overlay split

Every component in `k8s/infra/` is a single flat layer targeting staging only. There are no `base/` sub-directories and no production overlays anywhere. The operator `HelmRelease` and the live instance CR (e.g. CNPG `Cluster`, Strimzi `Kafka`) are mixed in the same directory.

### Gap 4 — No production environment

The strategy defines distinct HA configurations for production:
- CNPG: 3 instances, 50 Gi storage, scheduled backups
- Kafka: `KafkaNodePool` replicas=3, `offsets.topic.replication.factor: 3`
- MongoDB: 3-node replica set
- Valkey: persistent store with replication (Bitnami Helm chart)
- Applications: 3 replicas per service + `PodDisruptionBudget`
- ESO: HashiCorp Vault `ClusterSecretStore` instead of Fake provider
- Envoy Gateway: HTTPS listeners with cert-manager Let's Encrypt `ClusterIssuer`
- OTel: full Collector pipeline (staging uses embedded `grafana/otel-lgtm`)

None of these exist yet.

### Gap 5 — Image tag management is a staging workaround

Staging overlays pin `:latest` + `imagePullPolicy: Always`. This is incompatible with GitOps immutability requirements. The chosen solution is:

**Renovate Bot (Mend):** Each staging overlay `kustomization.yaml` has an `images:` block with a quoted SemVer `newTag` value. Renovate scans `gitops/**/*.yaml` files, detects new SemVer tags on ghcr.io, and opens Pull Requests to bump `newTag`. The GitOps repo requires only **read** access from Flux — no write credentials in the cluster.

Flux Image Automation (Image Reflector + Image Update Automation controllers, SSH deploy key) was evaluated and rejected in favour of Renovate to preserve the zero-credential, anonymous read-only Flux configuration. See Phase 5 for the implementation.

### Gap 6 — Strategy's single `apps/core-app/` doesn't match microservice structure

The strategy document was written around a single deployment (`core-application`). For this platform, each microservice has its own Deployment, ConfigMap, Service, ServiceAccount, and ExternalSecret. The correct interpretation is:
- `gitops/apps/core-config/base/` → shared business-owned CRs (realm import, Kafka topics, Kafka users, CNPG databases) — equivalent to current `k8s/apps/base/`
- `gitops/apps/<service>/base/` + `overlays/` → per-service Kubernetes resources — equivalent to current `k8s/apps/<service>/`

This is **not a gap** in the current structure; it is a strategic document simplification.

### Gap 7 — `namespaces.yaml` is not Flux-managed

A single `k8s/namespaces.yaml` file creates all namespaces imperatively. Flux `Kustomization` resources support automatic namespace creation via `spec.createNamespace: true` or by including namespace manifests within each component's base. The current flat file should be replaced with a `gitops/infrastructure/namespaces/` component applied as the first item in the infrastructure Kustomization.

### Gap 8 — Valkey has no operator and no overlay

Valkey (`k8s/infra/valkey/`) is a plain `Deployment` + `Service` with no operator and no overlay. It needs to be included in `gitops/infrastructure/valkey/` with a staging overlay (current sizing) and a production overlay (Bitnami Helm chart with persistence). The strategy document does not mention Valkey — it must be added.

### Gap 9 — CI pushes only SHA and `:latest` tags, no SemVer release tags

CI currently pushes `${{ github.sha }},latest` to ghcr.io on every push to `master`. The chosen image update strategy (Renovate + SemVer) requires a separate tag-triggered release workflow that pushes a clean `X.Y.Z` tag. CI does **not** need `contents: write` \u2014 the GitOps repository is updated via Renovate PRs only. See Phase 5.

### Gap 10 — Strategy document contains placeholder errors

These must **not** be copied verbatim when implementing:

| Location in strategy doc | Issue | Correct value |
|---|---|---|
| `flux-eso-alerts.yaml` | `apiVersion: ://coreos.com` | `apiVersion: monitoring.coreos.com/v1` |
| `otel-instrumentation.yaml` | `endpoint: "http://cluster.local"` | Real Collector DNS, e.g. `http://cluster-collector-collector.monitoring.svc.cluster.local:4318` |
| `configmap.yaml` | `KEYCLOAK_URL: "http://cluster.local"` | `http://keycloak.keycloak.svc.cluster.local:8080` |
| `kafka-topics.yaml` | `strimzi.io/cluster: my-kafka-cluster` | `strimzi.io/cluster: kafka` (matches actual cluster name) |
| `keycloak-realm.yaml` | `apiVersion: k8s.keycloak.org/v2alpha1` | `apiVersion: k8s.keycloak.org/v2beta1` (current operator) |
| `keycloak-realm.yaml` | `secret: "super-secret-client-credential-key"` hardcoded | Use `spec.placeholders` with ESO injection (already done in current impl) |
| `production/kustomization.yaml` | `://stakater.com` annotation key | `reloader.stakater.com/auto: "true"` |

### Gap 11 — Istio Ambient Mesh (production-only, future work)

The strategy lists Istio ztunnel as the production mTLS mechanism. This is not referenced in any current ADR or design document. It should be treated as a separate ADR (`adr-018`) and a distinct workstream. It must not block Phases 1–6.

---

## 3. Issues to Resolve Before Starting

**1. Remove or deprecate imperative secret targets in the Makefile.**  
The `k8s-secrets`, `k8s-postgres-secret`, `k8s-keycloak-secret`, etc. targets create secrets via `kubectl create secret`. These conflict with ESO-managed secrets once Flux is reconciling. The ESO Fake store already covers the same credentials. These Makefile targets should be removed or clearly marked as pre-ESO legacy bootstrappers in the README.

**2. Decide on monorepo layout.**  
Two options:
- **Keep co-located (recommended):** Move `k8s/` to `gitops/` within this repo. CI and GitOps delivery are co-located but clearly separated. Flux `sourceRef` points at the same GitHub repo.
- **Extract to dedicated GitOps repo:** Cleaner separation of concerns but adds cross-repo coordination overhead when a service change requires both an application code PR and a gitops repo update.

Recommendation: keep co-located as `gitops/` — matches common Flux monorepo patterns and avoids the dual-PR problem until team size warrants separation.

**3. Confirm the GitHub branch name.**  
CI currently triggers on `master`. Flux bootstrap uses `--branch=master`. Confirm these match before bootstrapping.

**4. Decide on the production secret backend before Phase 5.**  
HashiCorp Vault requires additional infrastructure. Alternatives that avoid self-managed Vault: AWS Secrets Manager, Azure Key Vault, or Google Secret Manager via the respective ESO providers. This decision gates the production ESO overlay.

---

## 4. Target Directory Structure

```
gitops/
├── clusters/
│   ├── staging/
│   │   ├── flux-instance.yaml              ← FluxInstance CR (Flux Operator bootstrap)
│   │   ├── cluster-settings.yaml           ← CLUSTER_DOMAIN, ENVIRONMENT variables
│   │   ├── infra-security.yaml             ← Kustomization: ESO stores (no deps)
│   │   ├── infra-routing.yaml              ← Kustomization: cert-manager + envoy-gateway (no deps)
│   │   ├── infra-monitoring.yaml           ← Kustomization: observability (no deps)
│   │   ├── infra-backends.yaml             ← Kustomization: databases + kafka + keycloak + valkey (dependsOn: infra-security)
│   │   └── core-apps.yaml                  ← Kustomization: all app services (dependsOn: infra-backends, infra-routing, infra-monitoring)
│   └── production/
│       ├── flux-instance.yaml
│       ├── cluster-settings.yaml
│       ├── infra-security.yaml
│       ├── infra-routing.yaml
│       ├── infra-monitoring.yaml
│       ├── infra-backends.yaml
│       └── core-apps.yaml
│
├── infrastructure/
│   ├── environments/
│   │   ├── staging/
│   │   │   ├── routing/
│   │   │   │   └── kustomization.yaml      ← aggregates: namespaces + cert-manager + envoy-gateway
│   │   │   └── backends/
│   │   │       └── kustomization.yaml      ← aggregates: databases + kafka + keycloak + valkey
│   │   └── production/
│   │       ├── routing/
│   │       │   └── kustomization.yaml
│   │       └── backends/
│   │           └── kustomization.yaml
│   │
│   ├── namespaces/
│   │   └── namespaces.yaml                 ← all cluster namespaces (from k8s/namespaces.yaml)
│   │
│   ├── cert-manager/
│   │   ├── base/
│   │   │   ├── helmrepository.yaml         ← jetstack Helm repo
│   │   │   └── helmrelease.yaml            ← cert-manager HelmRelease
│   │   └── overlays/
│   │       ├── staging/
│   │       │   ├── kustomization.yaml
│   │       │   ├── cluster-issuer.yaml     ← self-signed (from k8s/infra/cert-manager/)
│   │       │   └── wildcard-certificate.yaml
│   │       └── production/
│   │           ├── kustomization.yaml
│   │           └── cluster-issuer.yaml     ← letsencrypt-production
│   │
│   ├── envoy-gateway/
│   │   ├── base/
│   │   │   ├── helmrepository.yaml         ← OCI source
│   │   │   ├── helmrelease.yaml
│   │   │   └── gateway-class.yaml          ← from k8s/envoy-gateway/gateway-class.yaml
│   │   └── overlays/
│   │       ├── staging/
│   │       │   ├── kustomization.yaml
│   │       │   ├── gateway.yaml            ← HTTP listener (from k8s/envoy-gateway/)
│   │       │   ├── httproutes.yaml
│   │       │   └── security-policy.yaml
│   │       └── production/
│   │           ├── kustomization.yaml
│   │           ├── gateway.yaml            ← HTTPS listener + cert-manager annotation
│   │           └── httproutes.yaml
│   │
│   ├── eso-stores/
│   │   ├── base/
│   │   │   ├── helmrepository.yaml
│   │   │   └── helmrelease.yaml            ← ESO operator HelmRelease
│   │   └── overlays/
│   │       ├── staging/
│   │       │   ├── kustomization.yaml
│   │       │   └── cluster-secret-store.yaml   ← Fake provider (from k8s/infra/eso/)
│   │       └── production/
│   │           ├── kustomization.yaml
│   │           └── cluster-secret-store.yaml   ← Vault / cloud provider backend
│   │
│   ├── databases/
│   │   ├── base/
│   │   │   ├── cnpg-helmrepository.yaml
│   │   │   ├── cnpg-helmrelease.yaml       ← CNPG operator
│   │   │   ├── mongodb-helmrepository.yaml
│   │   │   └── mongodb-helmrelease.yaml    ← MongoDB Community Operator
│   │   └── overlays/
│   │       ├── staging/
│   │       │   ├── kustomization.yaml
│   │       │   ├── postgres-cluster.yaml   ← 2-instance CNPG (from k8s/infra/postgres/)
│   │       │   ├── postgres-externalsecrets.yaml
│   │       │   ├── mongodb-community.yaml  ← 1-node MongoDB (from k8s/infra/mongodb/)
│   │       │   └── mongodb-externalsecrets.yaml
│   │       └── production/
│   │           ├── kustomization.yaml
│   │           ├── postgres-cluster.yaml   ← 3-instance + backup config
│   │           └── mongodb-community.yaml  ← 3-node replica set
│   │
│   ├── kafka/
│   │   ├── base/
│   │   │   ├── helmrepository.yaml
│   │   │   └── helmrelease.yaml            ← Strimzi operator
│   │   └── overlays/
│   │       ├── staging/
│   │       │   ├── kustomization.yaml
│   │       │   └── cluster.yaml            ← 1-node KRaft (from k8s/infra/kafka/)
│   │       └── production/
│   │           ├── kustomization.yaml
│   │           └── cluster.yaml            ← 3-node KRaft, replication.factor=3
│   │
│   ├── keycloak/
│   │   ├── base/
│   │   │   └── operator.yaml               ← OCIRepository pointing at keycloak-k8s-resources release
│   │   └── overlays/
│   │       ├── staging/
│   │       │   ├── kustomization.yaml
│   │       │   ├── keycloak.yaml           ← Keycloak CR (from k8s/infra/keycloak/)
│   │       │   ├── database.yaml
│   │       │   ├── httproute.yaml
│   │       │   └── external-secrets.yaml
│   │       └── production/
│   │           ├── kustomization.yaml
│   │           └── keycloak.yaml           ← HA Keycloak (replicas=2+, production DB)
│   │
│   ├── valkey/
│   │   ├── base/
│   │   │   ├── deployment.yaml             ← plain Deployment (from k8s/infra/valkey/)
│   │   │   └── service.yaml
│   │   └── overlays/
│   │       ├── staging/
│   │       │   └── kustomization.yaml      ← no changes from base for staging
│   │       └── production/
│   │           ├── kustomization.yaml
│   │           └── helmrelease.yaml        ← Bitnami valkey chart with persistence
│   │
│   └── observability/
│       ├── base/
│       │   ├── prometheus-helmrepository.yaml
│       │   ├── prometheus-helmrelease.yaml ← kube-prometheus-stack
│       │   ├── otel-helmrepository.yaml
│       │   └── otel-helmrelease.yaml       ← OTel Operator
│       └── overlays/
│           ├── staging/
│           │   ├── kustomization.yaml
│           │   ├── external-secrets.yaml   ← Grafana admin secret (from k8s/infra/monitoring/)
│           │   └── otel-collector.yaml     ← single-node collector (from k8s/infra/otel-collector/)
│           └── production/
│               ├── kustomization.yaml
│               ├── otel-collector.yaml     ← full pipeline (Tempo + Loki + Prometheus exporters)
│               └── prometheus-rules.yaml   ← FluxReconciliationFailed + ESOSyncFailed alerts
│
└── apps/
    ├── core-config/
    │   ├── base/
    │   │   ├── kustomization.yaml
    │   │   ├── realm-import.yaml           ← from k8s/apps/base/
    │   │   ├── topics.yaml
    │   │   ├── users.yaml
    │   │   ├── databases.yaml
    │   │   └── external-secrets-kafka.yaml
    │   └── overlays/
    │       ├── staging/
    │       │   └── kustomization.yaml
    │       └── production/
    │           ├── kustomization.yaml
    │           └── topics-ha-patch.yaml    ← replicas=3 for all KafkaTopics
    │
    ├── user-service/                       ← from k8s/apps/user-service/
    │   ├── base/
    │   └── overlays/
    │       ├── staging/
    │       └── production/
    ├── product-service/
    ├── cart-service/
    ├── order-service/
    ├── reviews-service/
    ├── notification-service/
    └── frontend-service/
```

---

## 5. Flux Kustomization Orchestration

Following the **Decoupled Multi-Kustomization Pipeline** architectural principle (see `flux-deployment-strategy.md` §1), the infrastructure delivery is split into five specialized Flux `Kustomization` files in `gitops/clusters/staging/`. This eliminates the monolithic infrastructure aggregator and allows Flux to execute non-dependent groups in **parallel** while enforcing that backend services wait for their ESO security prerequisite.

### Dependency Pipeline

```
cluster-settings (ConfigMap — synced by FluxInstance)
│
├── infra-security   (eso-stores/overlays/staging)          [no deps — starts immediately]
├── infra-routing    (environments/staging/routing/)        [no deps — starts immediately]
├── infra-monitoring (observability/overlays/staging)       [no deps — starts immediately]
│
└── infra-backends   (environments/staging/backends/)
      dependsOn: infra-security  ← ESO ClusterSecretStore must exist before ExternalSecrets evaluate
      │
      └── core-apps  (apps/)
            dependsOn: infra-backends + infra-routing + infra-monitoring
```

### Staging Kustomization files (`gitops/clusters/staging/`)

```yaml
# gitops/clusters/staging/infra-security.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: infra-security
  namespace: flux-system
spec:
  interval: 10m
  path: ./gitops/infrastructure/eso-stores/overlays/staging
  sourceRef:
    kind: GitRepository
    name: flux-system
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
---
# gitops/clusters/staging/infra-routing.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: infra-routing
  namespace: flux-system
spec:
  interval: 10m
  path: ./gitops/infrastructure/environments/staging/routing
  sourceRef:
    kind: GitRepository
    name: flux-system
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
---
# gitops/clusters/staging/infra-monitoring.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: infra-monitoring
  namespace: flux-system
spec:
  interval: 10m
  path: ./gitops/infrastructure/observability/overlays/staging
  sourceRef:
    kind: GitRepository
    name: flux-system
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
---
# gitops/clusters/staging/infra-backends.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: infra-backends
  namespace: flux-system
spec:
  dependsOn:
    - name: infra-security
  interval: 10m
  path: ./gitops/infrastructure/environments/staging/backends
  sourceRef:
    kind: GitRepository
    name: flux-system
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
  healthChecks:
    - apiVersion: postgresql.cnpg.io/v1
      kind: Cluster
      name: postgres
      namespace: postgres
    - apiVersion: kafka.strimzi.io/v1beta2
      kind: Kafka
      name: kafka
      namespace: kafka
    - apiVersion: k8s.keycloak.org/v2beta1
      kind: Keycloak
      name: keycloak
      namespace: keycloak
---
# gitops/clusters/staging/core-apps.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: core-apps
  namespace: flux-system
spec:
  dependsOn:
    - name: infra-backends
    - name: infra-routing
    - name: infra-monitoring
  interval: 5m
  path: ./gitops/apps
  sourceRef:
    kind: GitRepository
    name: flux-system
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
```

### Routing group aggregator (`gitops/infrastructure/environments/staging/routing/kustomization.yaml`)

This thin Kustomize overlay is the single `path` target for `infra-routing`. It bundles namespace declarations, cert-manager, and envoy-gateway so they are applied as one atomic unit.

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../../namespaces
  - ../../../cert-manager/overlays/staging
  - ../../../envoy-gateway/overlays/staging
```

### Backends group aggregator (`gitops/infrastructure/environments/staging/backends/kustomization.yaml`)

This thin Kustomize overlay is the single `path` target for `infra-backends`. Because its Flux Kustomization carries `dependsOn: infra-security`, the ESO `ClusterSecretStore` is guaranteed to exist before any `ExternalSecret` in these components is evaluated.

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../../databases/overlays/staging
  - ../../../kafka/overlays/staging
  - ../../../keycloak/overlays/staging
  - ../../../valkey/overlays/staging
```

> **Why replace the monolithic aggregator?** The old `environments/staging/kustomization.yaml` covered all nine components under a single Flux `Kustomization` CRD — no parallelism, no partial health gating, and a single failure blocked all infrastructure. The decoupled pipeline lets monitoring and routing converge independently while backend health checks gate only the components that need them.

### Apps aggregator (`gitops/apps/kustomization.yaml`)

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - core-config/overlays/staging
  - user-service/overlays/staging
  - product-service/overlays/staging
  - cart-service/overlays/staging
  - order-service/overlays/staging
  - reviews-service/overlays/staging
  - notification-service/overlays/staging
  - frontend-service/overlays/staging
```

> For production, the `core-apps` Flux `Kustomization` will target `./gitops/apps` with a production-specific aggregator overlay (to be created in Phase 6).

---

## 5.5. Global Cluster Parameterization

Following the **Global Cluster Parameterization** architectural principle (see `flux-deployment-strategy.md` §1), each cluster entry point directory contains a `cluster-settings` ConfigMap. Flux injects these variables into all reconciled manifests via its `postBuild.substituteFrom` engine, eliminating per-environment duplication of hostnames, domain names, and environment labels.

### Cluster Settings ConfigMap (`gitops/clusters/staging/cluster-settings.yaml`)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: cluster-settings
  namespace: flux-system
data:
  CLUSTER_DOMAIN: "local.test"   # staging: *.local.test (k3d dev cluster)
  ENVIRONMENT: "staging"
```

> For production: `CLUSTER_DOMAIN: "<your-domain>"`, `ENVIRONMENT: "production"`.

### Variable reference

| Variable | Staging value | Production value (Phase 6) | Typical usage |
|----------|--------------|----------------------------|---------------|
| `CLUSTER_DOMAIN` | `local.test` | `<production domain>` | Envoy Gateway `HTTPRoute` hostnames, cert-manager `Certificate` `dnsNames`, app ConfigMap external URLs, Keycloak `redirectUris` |
| `ENVIRONMENT` | `staging` | `production` | Log labels, resource naming, distinguishing alert rules |

### Using variables in manifests

Reference any defined variable with `${VARIABLE_NAME}` anywhere in a Kustomize-managed manifest. Flux substitutes the value during the post-build phase, before applying the result to the cluster:

```yaml
# Envoy Gateway HTTPRoute
spec:
  hostnames:
    - "app.${CLUSTER_DOMAIN}"        # → app.local.test (staging)
    - "keycloak.${CLUSTER_DOMAIN}"   # → keycloak.local.test (staging)
```

> **Scope:** Internal service-to-service DNS (`<service>.<namespace>.svc.cluster.local`) is cluster-agnostic and must remain hardcoded. Only externally-visible domain references (Gateway listeners/HTTPRoutes, cert-manager `Certificate` `dnsNames`, Keycloak `redirectUris`, external URLs in app ConfigMaps) benefit from substitution. See Phase 4.5 for the audit and replacement task.

### How `postBuild` is wired

All five Flux `Kustomization` files in `gitops/clusters/staging/` carry a `postBuild.substituteFrom` block referencing the `cluster-settings` ConfigMap. The ConfigMap is applied to the cluster as part of the same cluster-path sync (it lives in `gitops/clusters/staging/cluster-settings.yaml`), so it is always present before any downstream Kustomization reconciles.

---

## 6. Image Update Strategy (replacing `:latest`)

**Decision: Renovate Bot (Mend), not Flux Image Automation.**

Flux Image Automation was evaluated (Image Reflector + Image Update Automation controllers, SSH deploy key, write-back commits to `master`). It was rejected because:
1. It requires the Flux cluster to have **write access** to the GitOps repository, breaking the zero-credential anonymous HTTPS configuration.
2. It adds two extra controllers to the cluster increasing operational surface.
3. It produces unreviewed direct commits to `master`; Renovate produces reviewable Pull Requests.

**Chosen approach:**
- A tag-triggered `release.yaml` CI workflow pushes images with **SemVer tags** (e.g., `1.0.0`) when a `v*.*.*` git tag is pushed.
- Staging overlays declare explicit `images:` blocks with a quoted `newTag: "x.y.z"` value.
- Renovate scans `gitops/**/*.yaml` via a `customManagers` regex, detects new SemVer tags on ghcr.io, and opens structured PRs.
- A PR merge to `master` is the deployment trigger; Flux applies the change on its next reconcile poll.
- `flux-instance.yaml` remains **anonymous read-only HTTPS** — no credentials stored in the cluster.

Full `renovate.json` configuration and release promotion steps are in `VERSIONING.md §4` and `§5`. Implementation steps are in Phase 5.

---

## 7. Migration Phases

### Phase 0 — Pre-flight decisions (~0.5 day) ✅

- [x] **Monorepo layout:** `gitops/` sub-directory within this repo.
- [x] **Branch name:** `master` — confirmed matches Flux bootstrap target.
- [x] **Production secret backend:** HashiCorp Vault (self-managed).
- [x] **Flux CLI version:** `2.8.1` (latest stable; mise tool is `flux2`) — pinned in `.mise.toml`.
- [x] **`.gitignore`:** No changes needed — the Flux Operator approach requires no auto-generated commits to the repository.

### Phase 1 — Restructure `k8s/` → `gitops/` (~1 day)

Pure directory rename and reorganisation. No manifest content changes. Existing `make k8s-*` targets continue to work by updating paths.

1. Create `gitops/` root directory.
2. Move `k8s/infra/<component>/` → `gitops/infrastructure/<component>/base/` (rename flat dir to `base/`).
3. Create `gitops/infrastructure/<component>/overlays/staging/kustomization.yaml` pointing at `../../base` for each component.
4. Move `k8s/envoy-gateway/` → `gitops/infrastructure/envoy-gateway/base/`.
5. Move `k8s/helm/` values files — each becomes the `values:` section of its respective `HelmRelease` (Phase 2). Keep them in `gitops/infrastructure/<component>/base/` temporarily.
6. Move `k8s/apps/<service>/` → `gitops/apps/<service>/` (structure unchanged).
7. Move `k8s/apps/base/` → `gitops/apps/core-config/base/`.
8. Move `k8s/namespaces.yaml` → `gitops/infrastructure/namespaces/namespaces.yaml`.
9. Create `gitops/infrastructure/environments/staging/kustomization.yaml` monolithic aggregator (all nine components). ⚠️ This aggregator is replaced by the `routing/` and `backends/` group aggregators in Phase 3b.
10. Update Makefile `k8s-*` paths to `gitops/`.

### Phase 2 — Convert Helm installs to HelmRelease CRDs (~1 day) ✅

For each operator:

1. Create `HelmRepository` (or `OCIRepository` for OCI-only charts) in the component `base/`.
2. Convert `k8s/helm/<operator>-values.yaml` content into a `HelmRelease` `spec.values` block.
3. Remove the corresponding `helm upgrade --install` block from the Makefile (or mark as deprecated).
4. Verified Kustomize builds cleanly against the monolithic aggregator: `kustomize build gitops/infrastructure/environments/staging | kubectl apply --dry-run=client -f -`. ⚠️ Once Phase 3b splits this into `routing/` and `backends/`, re-verify with both group paths.

**Keycloak Operator special case:** No Helm chart exists. Use a Flux `Kustomization` source (`OCIRepository` or `GitRepository`) pointing at the upstream `keycloak/keycloak-k8s-resources` release at the pinned version:

```yaml
apiVersion: source.toolkit.fluxcd.io/v1beta2
kind: OCIRepository
metadata:
  name: keycloak-operator
  namespace: flux-system
spec:
  interval: 12h
  url: oci://quay.io/keycloak/keycloak-k8s-resources
  ref:
    tag: "26.6.1"
```

### Phase 3 — Flux Bootstrap on staging (~0.5 day) ✅

Bootstrap uses the **Flux Operator** instead of the `flux bootstrap` CLI. Because the GitOps repository is **public**, Flux can pull manifests anonymously over HTTPS — no GitHub PAT, no SSH deploy key, and no auto-generated commit to the repository is required. See [Section 6 of flux-deployment-strategy.md](flux-deployment-strategy.md#6-bootstrapping-flux-cd-using-flux-operator-with-a-public-github-repository) for the full rationale.

#### Step 1 — Commit the cluster entry point files

Create the following files and commit + push to `master` **before** installing Flux, so the operator picks them up on first sync:

- `gitops/clusters/staging/cluster-settings.yaml` — `cluster-settings` ConfigMap (see Section 5.5)
- `gitops/clusters/staging/01-infrastructure.yaml` — monolithic Flux `Kustomization` for all infrastructure (see Section 5)
- `gitops/clusters/staging/02-apps.yaml` — Flux `Kustomization` for apps (see Section 5)
- `gitops/clusters/staging/flux-instance.yaml` — the `FluxInstance` CR below

> ⚠️ `01-infrastructure.yaml` and `02-apps.yaml` are the initial monolithic implementation. They are superseded by the five-file decoupled pipeline created in Phase 3b. Delete them as part of Phase 3b.

```yaml
# gitops/clusters/staging/flux-instance.yaml
apiVersion: fluxcd.controlplane.io/v1
kind: FluxInstance
metadata:
  name: flux-system
  namespace: flux-system
spec:
  distribution:
    version: "2.x"
  sync:
    # Public HTTPS URL — no secretRef needed
    url: "https://github.com/ricsanfre/spring-microservices-otel-k8s"
    branch: "master"
    path: "./gitops/clusters/staging"
    # Mitigate GitHub anonymous rate limits — use webhook for instant sync
    interval: "10m"
```

> The FluxInstance is named `flux-system` so the `GitRepository` it creates in the `flux-system` namespace also gets name `flux-system`, matching the `sourceRef.name: flux-system` used in all five Kustomization CRDs (Section 5).

#### Step 2 — Install the Flux Operator

```bash
helm install flux-operator oci://ghcr.io/controlplaneio-fluxcd/charts/flux-operator \
  --namespace flux-system \
  --create-namespace
```

#### Step 3 — Apply the `FluxInstance`

```bash
kubectl apply -f gitops/clusters/staging/flux-instance.yaml
```

The Flux Operator deploys all required controllers (`source-controller`, `kustomize-controller`, `helm-controller`, `notification-controller`) and immediately starts reconciling `gitops/clusters/staging`.

#### Step 4 — Verify reconciliation

```bash
# Watch Kustomizations converge
flux get kustomizations --watch

# Verify all HelmReleases are deployed
flux get helmreleases --all-namespaces
```

#### Makefile targets to add

```makefile
flux-operator-install: ## Install Flux Operator via Helm (prerequisite for flux-bootstrap)
	helm install flux-operator oci://ghcr.io/controlplaneio-fluxcd/charts/flux-operator \
	  --namespace flux-system --create-namespace

flux-bootstrap: ## Apply FluxInstance to start GitOps reconciliation (run after flux-operator-install)
	kubectl apply -f gitops/clusters/staging/flux-instance.yaml

flux-status: ## Show status of all Flux Kustomizations and HelmReleases
	flux get kustomizations --all-namespaces
	flux get helmreleases --all-namespaces
```

### Phase 3b — Decoupled Multi-Kustomization Pipeline (~0.5 day) ✅

Replaces the two monolithic Kustomization files (`01-infrastructure.yaml` and `02-apps.yaml`) created in Phase 3 with five specialized Flux `Kustomization` files, and splits the monolithic `environments/staging/kustomization.yaml` aggregator into two thin group aggregators. Implements the **Decoupled Multi-Kustomization Pipeline** architectural principle from `flux-deployment-strategy.md` §1 (see Section 5 for the full YAML and rationale).

1. Delete `gitops/clusters/staging/01-infrastructure.yaml` and `gitops/clusters/staging/02-apps.yaml`.
2. Create five Kustomization CRD files in `gitops/clusters/staging/` (Section 5):
   - `infra-security.yaml` — ESO stores, no deps
   - `infra-routing.yaml` — cert-manager + envoy-gateway, no deps
   - `infra-monitoring.yaml` — observability, no deps
   - `infra-backends.yaml` — databases + kafka + keycloak + valkey, `dependsOn: infra-security`
   - `core-apps.yaml` — all microservices, `dependsOn: infra-backends + infra-routing + infra-monitoring`
3. Delete `gitops/infrastructure/environments/staging/kustomization.yaml` (old monolithic aggregator).
4. Create `gitops/infrastructure/environments/staging/routing/kustomization.yaml` — namespaces + cert-manager + envoy-gateway.
5. Create `gitops/infrastructure/environments/staging/backends/kustomization.yaml` — databases + kafka + keycloak + valkey.
6. Verify both group aggregators build cleanly:
   ```bash
   kustomize build gitops/infrastructure/environments/staging/routing
   kustomize build gitops/infrastructure/environments/staging/backends
   ```
7. Commit and push to `master`. Flux picks up the changes on next sync, or force-reconcile:
   ```bash
   flux reconcile ks flux-system --with-source
   ```

### ✅ Phase 4 — Documentation & ADR (~0.5 day)

Record the architectural decision formally and update all existing documentation to reflect the completed GitOps migration.

#### ✅ 4.1 — Create ADR-017

Created `design/adr-017-gitops-fluxcd.md` covering:
- Move from imperative `kubectl apply` / `helm upgrade --install` to declarative, continuously reconciled GitOps
- Flux CD v2 selected over ArgoCD
- Flux Operator chosen over `flux bootstrap` CLI — eliminates PAT requirement and auto-generated commits for a public repository
- `gitops/` directory structure rationale

#### ✅ 4.2 — Update `ARCHITECTURE.md`

- Renamed the `k8s/ Directory Layout` section to `gitops/ Directory Layout` and updated the directory tree
- Updated path references: `k8s/infra/postgres/*` → `gitops/infrastructure/databases/overlays/staging/`, `k8s/apps/user-service/base/configmap.yaml` → `gitops/apps/user-service/base/configmap.yaml`
- Updated cert-manager and security-policy path/hostname references

#### ✅ 4.3 — Update `README.md`

- Added `flux2` to the tool versions table
- Replaced the staging deployment quickstart (`make k8s-infra` block) with the Flux bootstrap sequence (`make flux-operator-install` + `make flux-bootstrap` + `make flux-status`)
- Updated all `k8s/` path references to `gitops/`

#### ✅ 4.4 — Update `design/development-guidelines.md`

- Updated the `k8s/` directory reference in the project layout section to `gitops/`
- Updated Kafka Strimzi CR paths and staging overlay kustomization comment path
- Replaced the old `k8s/` manifests structure block with the `gitops/` layout + GitOps deployment note

#### ✅ 4.5 — Parameterize hardcoded domain values

Replaced all `local.test` literals in Flux-reconciled manifests with `${CLUSTER_DOMAIN}` (18 files: cert-manager, envoy-gateway, keycloak, observability, and all app ConfigMaps). Flux substitutes the value from `cluster-settings.yaml` at reconcile time. Also escaped all non-Flux `${KC_*}` and `${KC_USER_*}` Keycloak placeholder variables in `realm-import.yaml` to `$${...}` to prevent Flux from consuming them before the Keycloak Operator can process them.

---

### Phase 5 — SemVer Release Pipeline & Renovate-Driven Image Updates (~1 day) ✅

**Architecture decision:** Flux Image Automation (Image Reflector + Image Update Automation controllers, SSH deploy key, write-back commits) is **not used**. Instead:

- The Flux `FluxInstance` remains **anonymous read-only HTTPS** — no `secretRef`, no SSH key, no write access required. `flux-instance.yaml` is unchanged.
- Image tags inside `kustomization.yaml` overlays are **pinned SemVer strings** (e.g., `"1.0.0"`).
- **Renovate Bot (Mend)** scans GitOps YAML files via a `regexManager`, detects new SemVer tags on ghcr.io, and opens structured Pull Requests to bump `newTag` values.
- A PR merge to `master` is the deployment trigger — Flux reconciles on next poll.

#### 5.1 — Add Project-Version Tag to Existing CI Workflow

No separate release workflow is needed. The existing `ci.yaml` already runs on every push to `master`; it only needs to emit the Maven/npm project version as a third tag alongside `github.sha` and `latest`.

**Why not a separate tag-triggered workflow?** VERSIONING.md §5 describes pushing a git tag (`v1.0.0`) as a provenance marker and for GitHub Releases. The image publishing happens on the `master` push that strips the `-SNAPSHOT` suffix from `pom.xml` — by that point CI already runs with the clean version. The git tag is informational, not a CI trigger.

**Java services (`build-java` job):** Add a step that extracts the root POM version and passes it to Jib:

```yaml
# Add before the Jib publish step in .github/workflows/ci.yaml
- name: Extract project version
  id: proj_ver
  if: github.event_name == 'push' && github.ref == 'refs/heads/master'
  run: |
    echo "VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout --no-transfer-progress)" >> $GITHUB_OUTPUT

- name: Build and push image to ghcr.io (Jib)
  if: github.event_name == 'push' && github.ref == 'refs/heads/master'
  run: |
    mvn -N install -DskipTests --no-transfer-progress
    mvn -pl common install -DskipTests --no-transfer-progress
    mvn -pl ${{ matrix.service }} compile jib:build \
      --no-transfer-progress \
      -Ddocker.registry=ghcr.io/${{ github.repository }} \
      -Djib.to.tags=${{ github.sha }},latest,${{ steps.proj_ver.outputs.VERSION }} \
      -Djib.to.auth.username=${{ github.actor }} \
      -Djib.to.auth.password=${{ secrets.GITHUB_TOKEN }}
```

**Frontend (`build-frontend` job):** The `package.json` version step already exists but has a path bug — fix it:

```yaml
# Current (broken):
VERSION=$(node -p "require('./frontend/package.json').version")
# Fix:
VERSION=$(node -p "require('./frontend-service/package.json').version")
```

**Behaviour after this change:**

| Commit type | `pom.xml` version | Tags pushed to ghcr.io | Renovate action |
|---|---|---|---|
| Development push | `1.0.0-SNAPSHOT` | `<sha>`, `latest`, `1.0.0-SNAPSHOT` | Ignores (filtered by `allowedVersions`) |
| Release commit | `1.0.0` | `<sha>`, `latest`, `1.0.0` | Opens PR: `newTag: "1.0.0"` |
| Next cycle push | `1.1.0-SNAPSHOT` | `<sha>`, `latest`, `1.1.0-SNAPSHOT` | Ignores |

#### 5.2 — Update `renovate.json`

The `renovate.json` must cover **all** versioned resources in `gitops/`, not just application image `newTag` values. The following table maps each version type to the Renovate manager that handles it:

| Resource / location | Version type | Manager | Notes |
|---|---|---|---|
| `gitops/apps/*/overlays/*/kustomization.yaml` `images.newTag` | App image SemVer | `customManagers` regex | Source of truth for this project's app updates |
| `gitops/infrastructure/*/base/helmrelease.yaml` pinned `version:` | Helm chart SemVer | `flux` built-in | Covers `cert-manager v1.16.2`, `external-secrets 0.14.0` |
| `gitops/infrastructure/*/base/helmrelease.yaml` range `version: >=x.y.z` | Helm range | `flux` (disabled via rule) | Flux HelmController resolves these natively — Renovate must not touch them |
| `gitops/infrastructure/kafka/base/ocirepository.yaml` `tag:` | OCI Helm bundle tag | `flux` built-in | Strimzi operator bundle on quay.io |
| `gitops/infrastructure/envoy-gateway/base/ocirepository.yaml` `tag:` | OCI Helm bundle tag | `flux` built-in | Envoy Gateway bundle on docker.io |
| `gitops/infrastructure/keycloak/base/operator/kustomization.yaml` GitHub raw URL version | GitHub release | `customManagers` regex + `github-releases` datasource | Version `26.6.1` appears 3× in URL path |
| `gitops/infrastructure/valkey/base/deployment.yaml` `image:` | Docker Hub image tag | `kubernetes` built-in | `valkey/valkey:8-alpine` |
| `gitops/infrastructure/observability/overlays/staging/collector.yaml` `image:` | OCI image tag | `kubernetes` built-in | `opentelemetry-collector-contrib:0.123.0` |
| `pom.xml` dependencies | Maven SemVer | `maven` built-in | Included via `config:recommended` |
| `frontend-service/package.json` dependencies | npm SemVer | `npm` built-in | Included via `config:recommended` |
| `.github/workflows/*.yaml` `uses:` | GitHub Actions | `github-actions` built-in | Included via `config:recommended` |
| `gitops/infrastructure/*/overlays/staging/*.yaml` `version:` CR fields (MongoDB `8.0.4`, Kafka `4.2.0`) | Operator-managed software versions | — | **Not tracked by Renovate** — the MongoDB Community Operator and Strimzi manage these versions independently from their Helm chart versions; update manually |
| `gitops/clusters/staging/k3d-cluster.yaml` `rancher/k3s:` | k3d cluster image | — | **Disabled** — cluster version is managed manually |

The `flux` and `kubernetes` built-in managers are not active by default for arbitrary directories — they require explicit `fileMatch` configuration. The full `renovate.json` (already updated in the repo root) is:

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["config:recommended"],

  "flux": {
    "fileMatch": ["^gitops/.*\\.yaml$"]
  },

  "kubernetes": {
    "fileMatch": ["^gitops/infrastructure/.*\\.yaml$"]
  },

  "packageRules": [
    {
      "description": "App images — allow only clean SemVer releases; filter out -SNAPSHOT, SHA, etc.",
      "matchPackagePatterns": ["^ghcr\\.io/ricsanfre/spring-microservices-otel-k8s/"],
      "versioning": "semver",
      "allowedVersions": "/^[0-9]+\\.[0-9]+\\.[0-9]+$/",
      "automerge": false
    },
    {
      "description": "Skip HelmRelease range constraints — Flux HelmController resolves these natively",
      "matchManagers": ["flux"],
      "matchCurrentVersion": "/^>=/",
      "enabled": false
    },
    {
      "description": "k3d cluster image — manually managed",
      "matchPackageNames": ["rancher/k3s"],
      "enabled": false
    },
    {
      "description": "Infrastructure Helm charts, OCI source tags, and inline images — no automerge",
      "matchManagers": ["flux", "kubernetes"],
      "automerge": false
    }
  ],

  "customManagers": [
    {
      "description": "App image newTag in Kustomize staging overlays",
      "customType": "regex",
      "fileMatch": ["^gitops/apps/.+/overlays/.+/kustomization\\.yaml$"],
      "matchStrings": [
        "-\\s+name:\\s+(?<depName>ghcr\\.io/ricsanfre/spring-microservices-otel-k8s/[a-zA-Z0-9-]+)\\s+newTag:\\s+\"(?<currentValue>[^\"]+)\""
      ],
      "versioningTemplate": "semver"
    },
    {
      "description": "Keycloak Operator version embedded in GitHub raw manifest URLs",
      "customType": "regex",
      "fileMatch": ["^gitops/infrastructure/keycloak/base/operator/kustomization\\.yaml$"],
      "matchStrings": [
        "keycloak-k8s-resources/(?<currentValue>\\d+\\.\\d+\\.\\d+)/"
      ],
      "depNameTemplate": "keycloak/keycloak-k8s-resources",
      "datasourceTemplate": "github-releases",
      "versioningTemplate": "semver"
    }
  ]
}
```

> **Behavior:** When a new `v1.0.1` git tag is pushed, the CI emits `ghcr.io/.../user-service:1.0.1`. Renovate polls ghcr.io, finds the new SemVer tag, and opens a PR that changes `newTag: "1.0.0"` → `newTag: "1.0.1"` in the overlay `kustomization.yaml`. Once the PR is merged to `master`, Flux reconciles and rolls out the new image. The same flow applies to infrastructure: a new `cert-manager` Helm chart release on the `jetstack` repo triggers a Renovate PR updating `version: "v1.16.2"` in the HelmRelease.

#### 5.3 — Add `images:` Blocks to Staging Overlays

Every `overlays/staging/kustomization.yaml` currently lacks an `images:` block (they rely on `imagePullPolicy: Always` + `:latest` from the base `Deployment`). Replace the `imagePullPolicy: Always` patch with a pinned `images:` block. The `newTag` value **must be quoted** for the Renovate regex to match:

```yaml
# gitops/apps/user-service/overlays/staging/kustomization.yaml  (example)
resources:
  - ../../base

images:
  - name: ghcr.io/ricsanfre/spring-microservices-otel-k8s/user-service
    newName: ghcr.io/ricsanfre/spring-microservices-otel-k8s/user-service
    newTag: "1.0.0"   # Renovate keeps this field current via PRs

patches:
  # Scale to 1 replica on laptop
  - patch: |
      - op: replace
        path: /spec/replicas
        value: 1
    target:
      kind: Deployment
      name: user-service
  # imagePullPolicy: Always is removed — pinned SemVer tags are immutable
```

Repeat for all 8 services. Set `newTag` to the current release version (e.g., `"1.0.0"` for the first release; `"0.0.0"` is a valid placeholder if no release exists yet and a real one will be created immediately after).

#### 5.4 — Enable Renovate on the Repository

Renovate Bot (Mend) must be authorized to open PRs on the repository:

1. Install the [Renovate GitHub App](https://github.com/apps/renovate) and grant it access to the repository.
2. No additional Secrets are needed — Renovate uses its own GitHub App token for PR creation.
3. Trigger an initial scan: push a trivial commit to `master` or use the Renovate Dashboard.
4. Confirm Renovate can find the `gitops/**/*.yaml` patterns by checking the Dependency Dashboard issue Renovate creates in GitHub Issues.

#### 5.5 — `flux-instance.yaml` — No Changes Required

The `FluxInstance` remains anonymous read-only HTTPS. No `spec.distribution.components` additions, no `secretRef`, no URL change. Flux's role is purely to apply whatever tag is currently committed in the overlay — Renovate is responsible for updating that tag via PR.


### Phase 6 — Production Overlays (incremental, ~3–5 days)

Implement production overlays one infrastructure component at a time, in dependency order:

1. `eso-stores/overlays/production/` — Vault or cloud provider `ClusterSecretStore`
2. `cert-manager/overlays/production/` — Let's Encrypt `ClusterIssuer`
3. `databases/overlays/production/` — CNPG 3-node, MongoDB 3-node
4. `kafka/overlays/production/` — 3-node KRaft cluster
5. `keycloak/overlays/production/` — multi-replica Keycloak
6. `valkey/overlays/production/` — Bitnami Helm chart with persistence
7. `envoy-gateway/overlays/production/` — HTTPS gateway + cert-manager annotation
8. `observability/overlays/production/` — full OTel Collector pipeline + PrometheusRules
9. Per-service `apps/<service>/overlays/production/` — replicas=3, resource limits, PodDisruptionBudget
10. `core-config/overlays/production/` — KafkaTopic replicas=3 patch
11. Create `gitops/clusters/production/` Flux Kustomizations
12. Bootstrap Flux on the production cluster

### Phase 7 — Istio Ambient Mesh (separate workstream, future)

This phase requires a dedicated ADR (`adr-018-istio-ambient-mesh.md`) covering:
- Istio version selection and Ambient vs Sidecar trade-offs
- Integration with Envoy Gateway (Waypoint proxy configuration)
- Impact on existing Spring Boot service-to-service HTTP client calls
- mTLS vs JWT dual-enforcement strategy (Istio `AuthorizationPolicy` + Spring Security)

Do not block Phases 1–6 on this decision.

---

## 8. Makefile Migration Strategy

During the transition, the Makefile `k8s-*` targets serve as a documented fallback. The recommended approach:

1. **Phase 1–2:** Update all `k8s/` paths to `gitops/` in the Makefile.
2. **Phase 3:** Add a `flux-bootstrap` target and a `flux-status` target.
3. **Phase 3+:** Mark `k8s-infra-*` and `k8s-apps-deploy` targets as `[DEPRECATED — use Flux]` in their help strings but keep them functional.
4. **Post-Phase 5:** Remove `k8s-*-secret` targets (superseded by ESO reconciliation).
5. **Long-term:** The Makefile retains only build, test, local-run, and cluster lifecycle (`k3d-*`) targets. Deployment is fully managed by Flux.

---

## 9. RBAC Considerations for Flux

Flux's service account (`flux-system/kustomize-controller`) needs cross-namespace create/update/delete permissions because:
- `apps/core-config/base/realm-import.yaml` creates a `KeycloakRealmImport` in the `keycloak` namespace
- `apps/core-config/base/topics.yaml` and `users.yaml` create resources in the `kafka` namespace
- `apps/core-config/base/databases.yaml` creates `Database` CRs in the `postgres` namespace

Each Flux `Kustomization` can specify a `spec.serviceAccountName` referencing a dedicated service account with a `ClusterRole` granting the required resource verbs. Alternatively, use Flux's default cluster-admin binding (acceptable for a private staging cluster, not for production).

---

## 10. Known Risks

| Risk | Mitigation |
|---|---|
| Flux `prune: true` deletes resources not in Git on first sync | Dry-run with `flux diff kustomization infrastructure` before enabling prune |
| ESO Fake store has plain-text passwords in Git | Acceptable for staging; rotate all `changeme` placeholders before any shared usage |
| Keycloak Operator has no Helm chart — upstream kustomize bundle format may change between releases | Pin the `OCIRepository` tag; subscribe to Keycloak release notes |
| Renovate PR for a new image version is merged without adequate testing | Set `automerge: false` (default in the config) and require at least the CI `test` job to pass as a PR status check before merge; consider a staging smoke-test workflow on PR merge |  
| Renovate is not installed / scans the wrong file patterns | After enabling the GitHub App, verify the Dependency Dashboard issue lists all 8 service images; if missing, check the `fileMatch` regex in `renovate.json` against actual overlay paths |  
| A git tag is pushed before the release images are fully published to ghcr.io | The release workflow must complete (all matrix jobs succeed) before Renovate can detect new tags; add branch protection requiring the release workflow to succeed before tag is considered stable |
| Production DB migration (Flyway) must complete before app Deployments become ready | `readinessProbe` on `/actuator/health/readiness` handles this; Flux healthChecks on Deployments will wait |
