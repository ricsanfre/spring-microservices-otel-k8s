# ADR-017 — GitOps Deployment with Flux CD

**Date:** 2026-05-16  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

The platform's Kubernetes deployment was initially managed imperatively: operators were
installed via `helm upgrade --install` wrapped in Makefile targets, and application manifests
were applied with `kubectl apply -k`. This approach has several weaknesses:

1. **No continuous reconciliation.** Manual drift between the cluster's live state and the
   repository's manifests is invisible until the next deployment run.
2. **Cluster state undocumented in Git.** Applying CRDs, Namespaces, or operators out of
   order produces inconsistent clusters that cannot be reproduced reliably.
3. **Ordering responsibility lies with the operator.** Deploying the full stack requires
   running a sequence of Makefile targets in the correct order; there is no self-healing if
   a resource is accidentally deleted or mutated.
4. **Operator upgrades are manual.** Each `helm upgrade` call must be driven by a human;
   chart version drift is not detected automatically.

A GitOps tool continuously watches a Git repository and reconciles the cluster's live state
with the declared state. Two leading CNCF-graduated tools are evaluated:

| Concern | Flux CD v2 | ArgoCD |
|---------|-----------|--------|
| Kubernetes-native | Flux controllers are Kubernetes operators; no UI server required | ArgoCD ships a UI + API server (extra attack surface to secure) |
| Helm integration | `HelmRelease` CRD with per-chart upgrade/rollback semantics; patchable via Kustomize | Managed via Application CRD; less granular lifecycle control |
| Image automation | Built-in `ImageRepository` + `ImagePolicy` + `ImageUpdateAutomation` | Requires separate Argo CD Image Updater project |
| Bootstrap method | Flux Operator (fully declarative) or `flux bootstrap` CLI | `argocd-install.yaml` applied manually |
| Public repo credentials | None required with Flux Operator + anonymous HTTPS | Repository credential entry required even for public repos |
| Multi-tenancy | Multiple `Kustomization` CRDs, each with their own `serviceAccountName` | Applications and Projects with RBAC in ArgoCD config |

Within Flux CD, two bootstrap approaches exist:

| Approach | `flux bootstrap` CLI | Flux Operator |
|----------|---------------------|---------------|
| How Flux is installed | CLI writes manifests to `clusters/` and commits them to the repo automatically | Helm installs the operator; a `FluxInstance` CR drives the rest |
| Credentials required at bootstrap | GitHub PAT with `repo` scope | None for a public repository |
| Auto-generated commits | Yes — `flux-system/` manifests committed by the CLI | No — all manifests are committed manually and intentionally |
| Long-term self-management | Flux manages its own `flux-system` Kustomization | Flux Operator manages the full `FluxInstance` lifecycle |
| Controller upgrades | Re-run `flux bootstrap` or edit the generated manifest | Edit `spec.distribution.version` in the `FluxInstance` and `kubectl apply` |

---

## Decision

Adopt **Flux CD v2** as the GitOps engine, bootstrapped via the **Flux Operator**
(not the `flux bootstrap` CLI).

All Kubernetes manifests are stored under `gitops/` in this repository. The Flux Operator
is installed once via Helm (`flux-operator` chart from
`oci://ghcr.io/controlplaneio-fluxcd/charts/flux-operator`). Thereafter, a single
`FluxInstance` CR (`gitops/clusters/staging/flux-instance.yaml`) drives the full
reconciliation loop. Infrastructure operators are managed as Flux `HelmRelease` CRDs;
application workloads are managed as Kustomize overlays reconciled by Flux `Kustomization`
CRDs.

---

## Rationale

### Flux CD over ArgoCD

Flux fits the existing architecture more naturally:

- **`HelmRelease` CRDs** provide per-chart upgrade strategies, remediation retries, and
  `spec.values` that can be patched via Kustomize overlays without forking the chart — a
  capability that ArgoCD's Application-based Helm management exposes less cleanly.
- **Built-in image automation** (`ImageUpdateAutomation`) will handle the requirement of
  replacing `:latest` image tags with SHA-pinned tags written back to Git without adding a
  separate tool.
- This project has no requirement for a UI dashboard or multi-cluster single-pane-of-glass
  view; the operational overhead of ArgoCD's application server is not justified.

### Flux Operator over `flux bootstrap`

- The repository is **public**. `flux bootstrap github` still requires a GitHub PAT with
  `repo` scope to write back the auto-generated `flux-system/` manifests. The Flux Operator
  requires **zero credentials** for an anonymous HTTPS pull from a public repository.
- `flux bootstrap` auto-commits a `flux-system/` directory containing a managed
  `Kustomization` and `GitRepository`. These auto-generated files must be carefully preserved
  to avoid conflicts during re-bootstrap. With the Flux Operator, the `FluxInstance` manifest
  is a manually authored file committed to `gitops/clusters/staging/` alongside all other
  cluster manifests — treated identically to any other Kubernetes resource in the repository.
- Upgrading Flux controllers with the Operator is a declarative change
  (`spec.distribution.version: "2.x"` → specific tag, then `kubectl apply`) rather than
  re-running a CLI command with the same flags as the original bootstrap.

### `gitops/` directory layout

The `gitops/` directory follows a dual-layer Kustomize pattern (base + environment overlays)
with a `clusters/` entry point that Flux reconciles:

```text
gitops/
├── clusters/
│   └── staging/
│       ├── flux-instance.yaml      ← FluxInstance CR (Flux Operator entry point)
│       ├── 01-infrastructure.yaml  ← Flux Kustomization for infrastructure
│       └── 02-apps.yaml            ← Flux Kustomization for applications
├── infrastructure/
│   ├── environments/staging/       ← aggregator kustomization.yaml
│   └── <component>/
│       ├── base/                   ← HelmRelease + HelmRepository / OCIRepository
│       └── overlays/staging/       ← environment-specific patches
└── apps/
    ├── core-config/                ← shared business-owned CRs (Kafka topics/users, realm import, DBs)
    └── <service>/
        ├── base/                   ← Deployment, Service, ConfigMap, ExternalSecret
        └── overlays/staging/       ← image tag, replica count, env-specific patches
```

### Dependency ordering

`02-apps.yaml` carries `spec.dependsOn: [infrastructure]` so Flux never attempts to reconcile
application resources before the operators and databases they depend on are healthy.
`HelmRelease` resources for infrastructure operators that depend on CRDs (e.g. cert-manager
CRDs must exist before `ClusterIssuer` resources can be applied) use Flux's built-in
`spec.dependsOn` on the `HelmRelease` level.

---

## Consequences

### Positive

- **Continuous reconciliation:** any manual change to the cluster is automatically reverted
  within one sync interval (10 minutes by default; instant via Webhook Receiver).
- **Self-healing:** deleting a managed resource causes Flux to re-create it immediately.
- **Declarative operators:** all 8 infrastructure operators are `HelmRelease` CRDs;
  adding or upgrading an operator is a Git commit, not a Makefile command.
- **Audit trail:** every infrastructure and application change is a Git commit with author,
  timestamp, and diff — full change history without additional tooling.
- **Environment parity:** the same `base/` + `overlays/` structure scales to production
  overlays without any structural changes to the `gitops/` layout.

### Neutral

- **Makefile `k8s-*-helm` targets are deprecated** but kept as documented fallbacks during
  the transition period (marked `[DEPRECATED — use Flux HelmRelease]`). Full removal is
  planned post-Phase 5 (Image Automation).
- **Flux Operator is the preferred but newer bootstrapping approach.** The `FluxInstance`
  API (`fluxcd.controlplane.io/v1`) is maintained by ControlPlane, the commercial backer of
  Flux, and tracks upstream Flux releases. Monitor for breaking changes between
  `FluxInstance` API versions.

### Negative

- **Image tag management is deferred to a subsequent phase (Image Automation).** Until image
  automation is active, staging overlays pin `:latest` which is an anti-pattern for GitOps
  immutability. This is a known temporary gap.
- **`prune: true` on first sync.** Flux deletes any resource present in the cluster that is
  not declared in Git. A dry-run (`flux diff kustomization infrastructure`) is mandatory
  before the first live sync to avoid unintentional deletions.

---

## References

- [Flux CD documentation](https://fluxcd.io/flux/)
- [Flux Operator documentation](https://fluxcd.control-plane.io/operator/)
- [`design/flux-deployment-strategy.md`](flux-deployment-strategy.md) — full architectural strategy
- [`design/flux-cd-migration-plan.md`](flux-cd-migration-plan.md) — phased migration plan
