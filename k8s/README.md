## k3d Staging Environment — Setup Guide

### Prerequisites

| Tool | Minimum version | Install |
|------|----------------|---------|
| k3d  | 5.x | `brew install k3d` / [k3d.io](https://k3d.io) |
| kubectl | 1.30+ | `brew install kubectl` |
| helm | 3.15+ | `brew install helm` |
| kustomize | 5.x | `brew install kustomize` |

---

### Cluster management

```bash
# Create cluster (defined in k8s/k3d-cluster.yaml)
make k3d-create

# Delete cluster
make k3d-delete
```

---

### Install operators (once per cluster)

```bash
make k8s-operators
```

Installs via Helm / kubectl:

| Operator | Namespace | Method |
|----------|-----------|--------|
| cert-manager | `cert-manager` | Helm (`jetstack/cert-manager`) |
| Envoy Gateway | `envoy-gateway-system` | Helm OCI (`gateway-helm`) |
| Strimzi Kafka | `kafka` | Helm OCI (`strimzi-kafka-operator`) |
| CloudNativePG | `cnpg-system` | Helm (`cnpg/cloudnative-pg`) |
| MongoDB Community | `mongodb` | Helm (`mongodb/community-operator`) |
| Keycloak Operator | `keycloak` | `kubectl apply -k` (no Helm chart) |
| OpenTelemetry Operator | `monitoring` | Helm (`open-telemetry/opentelemetry-operator`) |

Keycloak Operator version is controlled by `KEYCLOAK_OPERATOR_VERSION` (default `26.6.1`).

---

### Create required secrets

Secrets are not committed to git. Create them before running `make k8s-infra`:

```bash
# PostgreSQL superuser (CNPG bootstrap)
kubectl create secret generic postgres-superuser-secret \
  --from-literal=username=postgres --from-literal=password=<CHANGE_ME> \
  --namespace postgres

# Keycloak admin + DB credentials
kubectl create secret generic keycloak-admin-secret \
  --from-literal=username=admin --from-literal=password=<CHANGE_ME> \
  --namespace keycloak
kubectl create secret generic keycloak-db-secret \
  --from-literal=username=keycloak_owner --from-literal=password=<CHANGE_ME> \
  --namespace keycloak

# MongoDB per-service credentials
kubectl create secret generic mongodb-reviews-secret \
  --from-literal=password=<CHANGE_ME> --namespace mongodb
kubectl create secret generic mongodb-notifications-secret \
  --from-literal=password=<CHANGE_ME> --namespace mongodb

# Grafana admin credentials
kubectl create secret generic grafana-admin-secret \
  --from-literal=username=admin --from-literal=password=<CHANGE_ME> \
  --namespace monitoring
```

---

### Deploy infrastructure resources

```bash
make k8s-infra
```

Or deploy each component individually:

```bash
make k8s-infra-cert-manager    # self-signed CA issuers + *.local.test wildcard cert
make k8s-infra-postgres        # CNPG PostgreSQL cluster + per-service databases
make k8s-infra-mongodb         # MongoDB replica set
make k8s-infra-kafka           # Kafka cluster + topics
make k8s-infra-keycloak        # Keycloak instance + realm import
make k8s-infra-envoy-gateway   # GatewayClass, Gateway, HTTPRoutes, SecurityPolicy
make k8s-infra-monitoring      # Grafana LGTM stack (lgtm-distributed Helm chart)
make k8s-infra-otel-collector  # OpenTelemetry Collector (fan-out to Tempo/Loki/Mimir)
```

---

### Deploy application services

```bash
# Deploy all services with Kustomize staging overlay
make k8s-apps-deploy

# Deploy a single service
make k8s-us-deploy

# One-shot full setup (cluster + operators + infra)
make k8s-up
```

---

### Directory layout

```
k8s/
├── k3d-cluster.yaml                    ← k3d cluster definition (1 server, 2 agents)
├── namespaces.yaml                     ← all namespaces
├── helm/                               ← Helm values for each operator
│   ├── cert-manager-values.yaml
│   ├── envoy-gateway-values.yaml
│   ├── strimzi-operator-values.yaml
│   ├── cnpg-operator-values.yaml
│   ├── mongodb-operator-values.yaml
│   ├── keycloak-operator-values.yaml   ← install notes (no official Helm chart)
│   ├── lgtm-distributed-values.yaml    ← Grafana LGTM (Loki+Grafana+Tempo+Mimir)
│   └── otel-operator-values.yaml       ← OpenTelemetry Operator
├── infra/                              ← Kustomize apps — operator-managed CRs
│   ├── cert-manager/
│   │   ├── kustomization.yaml
│   │   ├── cluster-issuer.yaml         ← self-signed bootstrap issuer + CA ClusterIssuer
│   │   └── wildcard-certificate.yaml   ← *.local.test wildcard TLS cert
│   ├── postgres/
│   │   ├── kustomization.yaml
│   │   ├── cluster.yaml                ← CNPG Cluster CR
│   │   └── databases.yaml              ← CNPG Database CRs (one per service)
│   ├── mongodb/
│   │   ├── kustomization.yaml
│   │   └── community.yaml              ← MongoDBCommunity CR
│   ├── kafka/
│   │   ├── kustomization.yaml
│   │   ├── cluster.yaml                ← Strimzi Kafka CR (KRaft mode)
│   │   └── topics.yaml                 ← KafkaTopic CRs
│   ├── keycloak/
│   │   ├── kustomization.yaml
│   │   ├── operator/                   ← Kustomize app — Keycloak Operator (CRDs + Deployment)
│   │   │   ├── kustomization.yaml
│   │   │   └── namespace.yaml
│   │   ├── keycloak.yaml               ← Keycloak CR
│   │   └── realm-import.yaml           ← KeycloakRealmImport CR
│   └── otel-collector/             ← Kustomize app — OpenTelemetryCollector CR
│       ├── kustomization.yaml
│       └── collector.yaml              ← OTLP fan-out → Tempo, Loki, Mimir
├── envoy-gateway/                      ← Kustomize app — Gateway API resources
│   ├── kustomization.yaml
│   ├── gateway-class.yaml
│   ├── gateway.yaml                    ← HTTP redirect + HTTPS TLS termination
│   ├── httproutes.yaml                 ← HTTPRoute per business service
│   └── security-policy.yaml           ← JWT SecurityPolicy (Keycloak JWKS)
└── apps/                               ← Kustomize apps — business services
    └── user-service/
        ├── base/                       ← Deployment, Service, ConfigMap, ServiceAccount, RBAC
        └── overlays/
            └── staging/               ← image tag patch + env-specific config
```
