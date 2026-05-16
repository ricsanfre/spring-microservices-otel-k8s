# Technical Proposal: Multi-Namespace GitOps Packaging with Flux CD and External Secrets Operator (ESO)

This document details the architecture and deployment strategy for a Kubernetes application packaged using **Flux CD**, whose infrastructure dependencies (**CloudNativePG, Strimzi Kafka, MongoDB, Keycloak, Cert-Manager, and Envoy Gateway**) are managed via a service-separated infrastructure layer orchestrated through decoupled Flux dependencies.

---

## 1. Architectural Principles

* **Total Decoupling:** The application template never embeds raw infrastructure definitions or hardcoded credentials. It consumes endpoints via internal cluster DNS and credentials via injected secrets.
* **Unified Service-Separated Infrastructure Layer:** All operator controllers and live instances are consolidated into a single `infrastructure/` directory organized by service. This eliminates separate platform layers and matches operator lifecycles directly with the services they manage.
* **Application-Owned Business Resources:** Custom resources explicitly tied to the application's domain logic (**`KafkaTopic`**, **`KafkaUser`**, and **`KeycloakRealmImport`**) are strictly packaged inside the application layer to ensure an aligned lifecycle.
* **Tokenless & Commitless GitOps (Flux Operator):** Cluster bootstrapping avoids traditional `flux bootstrap` commands. The control plane utilizes the modern **Flux Operator** and **`FluxInstance` CRD**. Cluster authentication relies natively on cluster-level machine identity (OIDC/SSH Deploy Keys), **completely eliminating GitHub Personal Access Tokens (PATs)** and automated Flux write-back commits.
* **Global Cluster Parameterization (Flux Post-Build Substitution):** Key cluster parameters (e.g., `CLUSTER_DOMAIN`, `ENVIRONMENT`) are defined once in a central cluster settings ConfigMap. Flux automatically substitutes these variables dynamically across all manifests using its post-build substitution engine.
* **Decoupled Multi-Kustomization Pipeline:** To leverage Flux's native dependency engine and parallel execution, the continuous delivery process avoids large, monolithic manifests. Instead, it runs on distinct, specialized Flux `Kustomization` files linked via strict `dependsOn` arrays.
* **Dual-Layer Kustomize Overlays:** Both infrastructure components and application logic are separated into environment-agnostic `base` layers and environment-specific `overlays` (`staging` and `production`) to maximize DRY compliance.
* **Polymorphic Secret Provisioning:** The active environment overlay dictates the secret store backend. Staging provisions an isolated `Fake` provider, while Production securely mounts a **HashiCorp Vault** instance inside the ESO service directory.
* **Ambient Service Mesh & Edge Routing (Production-Only):** Mutual TLS (mTLS) is strictly enforced in Production via **Istio Ambient Mesh (ztunnel)**. Traffic entering the cluster is managed by **Envoy Gateway**, which acts as the edge Ingress and routes traffic directly into the Ambient mesh. **Cert-Manager** automates public TLS termination at the Envoy edge.
* **Unified Telemetry Pipeline (Production-Only):** Observability in Production is standardized on the **OpenTelemetry (OTel) Operator** routing signals (Metrics, Logs, Traces) to a centralized backend stack managed by the **kube-prometheus-stack, Grafana Loki, and Grafana Tempo**.

---

## 2. GitOps Monorepo Directory Structure

The repository utilizes a modular, component-driven architecture leveraging Kustomize bases and overlays across both service infrastructure and core applications:

```text
📂 my-gitops-repo
├── 📂 clusters/                 # Layer 0: Flux Operator Management & Variables
│   ├── 📂 staging/
│   │   ├── flux-instance.yaml   # Declares the Flux Instance for Staging
│   │   └── cluster-settings.yaml # ConfigMap holding CLUSTER_DOMAIN=staging.internal
│   └── 📂 production/
│       ├── flux-instance.yaml   # Declares the Flux Instance for Production
│       ├── cluster-settings.yaml # ConfigMap holding CLUSTER_DOMAIN=production.internal
│       │
│       # Decoupled Multi-Kustomizations Orchestration
│       ├── infra-routing.yaml   # Flux config for Cert-Manager & Envoy Gateway
│       ├── infra-security.yaml  # Flux config for ESO Stores
│       ├── infra-backends.yaml  # Flux config for Databases, Kafka & Keycloak
│       ├── infra-monitoring.yaml# Flux config for kube-prometheus & OTel
│       └── core-apps.yaml       # Flux config for Business Applications
│
├── 📂 infrastructure/           # Layer 1: Service-Separated Infrastructure & Operators
│   ├── 📂 cert-manager/         # Cert-Manager Service Component
│   ├── 📂 ingress-gateway/      # Envoy Gateway Service Component
│   ├── 📂 eso-stores/           # External Secrets Component
│   ├── 📂 databases/            # CloudNativePG & Mongo Component
│   ├── 📂 messaging/            # Strimzi Kafka Component
│   ├── 📂 keycloak/             # Keycloak Server Component
│   └── 📂 observability/        # OTel & Prometheus Alerting Component
│
└── 📂 apps/                     # Layer 2: Application Overlays Layout
    └── 📂 core-app/
        ├── 📂 base/             # Common application manifests & Business Resources
        │   ├── deployment.yaml
        │   ├── configmap.yaml   # Uses dynamic \${CLUSTER_DOMAIN} variables
        │   ├── external-secrets.yaml
        │   ├── kafka-topics.yaml 
        │   ├── kafka-user.yaml   
        │   └── keycloak-realm.yaml 
        └── 📂 overlays/
            ├── 📂 staging/      
            └── 📂 production/   
```

---

## 3. Flux CD Control Plane & Decoupled Dependency Pipeline (Layer 0)

### A. Centralized Settings (`clusters/production/cluster-settings.yaml`)
This ConfigMap hosts the environment-specific values that Flux will extrapolate across all template files.
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: cluster-settings
  namespace: flux-system
data:
  CLUSTER_DOMAIN: "production.internal"
  ENVIRONMENT: "production"
```

### B. Declarative Flux Management via Flux Operator (`clusters/production/flux-instance.yaml`)
The Flux Instance orchestrates synchronization without requiring developer-owned GitHub write tokens or PATs.
```yaml
apiVersion: fluxcd.controlplane.io/v1alpha1
kind: FluxInstance
metadata:
  name: flux-prod
  namespace: flux-system
spec:
  distribution:
    version: "v2.x"
  gitRepository:
    url: "ssh://git@://github.com"
    ref:
      branch: "main"
    # References a cluster-level SSH deploy key secret (No individual GitHub PATs needed)
    secretRef:
      name: git-deploy-key 
```

### C. Parallel & Decoupled Kustomization Pipeline
Instead of a single infrastructure step, the orchestration uses independent Flux `Kustomization` manifests. This approach allows **non-dependent tasks to run in parallel** (e.g., Monitoring and Ingress boot simultaneously) while enforcing that backend infrastructure blocks until its security prerequisites are healthy.

All resources share the automated `postBuild` substitution from the global `cluster-settings` ConfigMap.

```yaml
# clusters/production/infra-routing.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: infra-routing
  namespace: flux-system
spec:
  interval: 10m
  path: ./infrastructure/ingress-gateway/overlays/production
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
---
# clusters/production/infra-security.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: infra-security
  namespace: flux-system
spec:
  interval: 10m
  path: ./infrastructure/eso-stores/overlays/production
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
---
# clusters/production/infra-monitoring.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: infra-monitoring
  namespace: flux-system
spec:
  interval: 10m
  path: ./infrastructure/observability/overlays/production
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
---
# clusters/production/infra-backends.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: infra-backends
  namespace: flux-system
spec:
  # CRITICAL: Wait for ESO stores to exist so DB/Kafka operators can bootstrap secrets safely
  dependsOn:
    - name: infra-security
  interval: 10m
  path: ./infrastructure/databases/overlays/production
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
  healthChecks:
    - apiVersion: postgresql.cnpg.io/v1
      kind: Cluster
      name: prod-database
      namespace: databases
    - apiVersion: kafka.strimzi.io/v1beta2
      kind: Kafka
      name: my-kafka-cluster
      namespace: messaging
---
# clusters/production/core-apps.yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: core-apps
  namespace: flux-system
spec:
  # CRITICAL: Application layer blocks until ALL infrastructure dependencies pass health checks
  dependsOn:
    - name: infra-backends
    - name: infra-routing
    - name: infra-monitoring
  interval: 5m
  path: ./apps/core-app/overlays/production
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
```

---

## 4. Parameterized Infrastructure Blueprint (Layer 1)

### Ingress Gateway Component (`infrastructure/ingress-gateway/overlays/production/gateway.yaml`)
```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: production-edge-gateway
  namespace: envoy-gateway-system
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-production"
spec:
  gatewayClassName: envoy-gateway
  listeners:
    - name: https
      protocol: HTTPS
      port: 443
      hostname: "api.\${CLUSTER_DOMAIN}" # Extrapolated dynamically via postBuild engine
      tls:
        mode: Terminate
        certificateRefs:
          - kind: Secret
            name: api-gateway-tls-cert
```

#### Production Route Mapping (`infrastructure/ingress-gateway/overlays/production/http-routes.yaml`)
```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: core-app-route
  namespace: my-app-production
spec:
  parentRefs:
    - name: production-edge-gateway
      namespace: envoy-gateway-system
  hostnames:
    - "api.\${CLUSTER_DOMAIN}"
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /
      backendRefs:
        - name: core-application-service
          port: 8080
```

---

### B. ESO Secrets Component (`infrastructure/eso-stores/`)

#### Staging Overlay (`infrastructure/eso-stores/overlays/staging/staging-fake-store.yaml`)
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata:
  name: k8s-cluster-store
spec:
  provider:
    fake:
      data:
        - key: prod-database-app
          value: '{"username": "staging_user", "password": "mock_staging_password"}'
        - key: my-kafka-user
          value: '{"password": "mock_kafka_scram_secret"}'
```

#### Production Overlay (`infrastructure/eso-stores/overlays/production/prod-vault-store.yaml`)
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata:
  name: k8s-cluster-store
spec:
  provider:
    vault:
      server: "https://prod.internal"
      path: "secret"
      version: "v2"
      auth:
        kubernetes:
          mountPath: "kubernetes"
          role: "eso-read-role"
          serviceAccountRef:
            name: "eso-sa"
            namespace: "flux-system"
```

---

### C. Observability & Alerting Component (`infrastructure/observability/`)

#### Production Overlay (`infrastructure/observability/overlays/production/otel-collector.yaml`)
```yaml
apiVersion: opentelemetry.io/v1alpha1
kind: OpenTelemetryCollector
metadata:
  name: cluster-collector
  namespace: monitoring
spec:
  config: |
    receivers:
      otlp:
        protocols:
          grpc:
          http:
    processors:
      batch:
      memory_limiter:
        check_interval: 1s
        limit_percentage: 75
        spike_limit_percentage: 15
    exporters:
      otlp/tempo:
        endpoint: "tempo-distributor.monitoring.svc.cluster.local:4317"
        tls:
          insecure: true
      loki:
        endpoint: "http://cluster.local"
      prometheus:
        endpoint: "0.0.0.0:8889"
    service:
      pipelines:
        traces:
          receivers: [otlp]
          processors: [memory_limiter, batch]
          exporters: [otlp/tempo]
        metrics:
          receivers: [otlp]
          processors: [memory_limiter, batch]
          exporters: [prometheus]
        logs:
          receivers: [otlp]
          processors: [memory_limiter, batch]
          exporters: [loki]
```

#### Production Overlay (`infrastructure/observability/overlays/production/flux-eso-alerts.yaml`)
```yaml
apiVersion: ://coreos.com
kind: PrometheusRule
metadata:
  name: gitops-delivery-alerts
  namespace: monitoring
spec:
  groups:
  - name: GitOpsPipelineHealth
    rules:
    - alert: FluxReconciliationFailed
      expr: flux_kustomization_condition{status="False", type="Ready"} > 0
      for: 10m
      labels:
        severity: critical
      annotations:
        summary: "Flux CD failed to apply configuration for {{ \$labels.name }}"
        description: "The Kustomization sync has been failing for more than 10 minutes."
    - alert: ESOSyncFailed
      expr: externalsecret_status_condition{status="False", type="Ready"} > 0
      for: 5m
      labels:
        severity: warning
      annotations:
        summary: "ExternalSecret {{ \$labels.name }} synchronization failed"
        description: "ESO cannot sync secret references in namespace {{ \$labels.namespace }}."
```

---

## 5. Application Overlays Layer with Variables (Layer 2)

### A. The Core Base (`apps/core-app/base/`)

#### Application-Owned Kafka Custom Resources
```yaml
# apps/core-app/base/kafka-topics.yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: orders-v1
  namespace: messaging
  labels:
    strimzi.io/cluster: my-kafka-cluster
spec:
  partitions: 3
  replicas: 1 
  config:
    retention.ms: 604800000
    segment.bytes: 1073741824
---
# apps/core-app/base/kafka-user.yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaUser
metadata:
  name: my-kafka-user
  namespace: messaging
  labels:
    strimzi.io/cluster: my-kafka-cluster
spec:
  authentication:
    type: scram-sha-512
  authorization:
    type: simple
    acls:
      - resource:
          type: topic
          name: orders-v1
          patternType: literal
        operation: Read
        host: "*"
```

#### Application-Owned Keycloak Realm Resource
```yaml
# apps/core-app/base/keycloak-realm.yaml
apiVersion: k8s.keycloak.org/v2alpha1
kind: KeycloakRealmImport
metadata:
  name: my-app-realm
  namespace: auth
spec:
  keycloakCRName: my-keycloak-server
  realm:
    id: my-app-realm
    realm: my-app-realm
    enabled: true
    displayName: "Core Application Realm"
    clients:
      - clientId: my-core-app
        enabled: true
        protocol: openid-connect
        publicClient: false
        secret: "super-secret-client-credential-key"
        redirectUris:
          - "https://api.\${CLUSTER_DOMAIN}/*" 
```

#### Foundational Application Skeleton
```yaml
# apps/core-app/base/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  DATABASE_HOST: "prod-database-rw.databases.svc.cluster.local"
  KAFKA_BOOTSTRAP_SERVERS: "my-kafka-cluster-kafka-bootstrap.messaging.svc.cluster.local:9092"
  MONGO_URI: "mongodb://my-mongo-cluster-svc.mongodb.svc.cluster.local:27017"
  KEYCLOAK_URL: "http://auth.svc.\${CLUSTER_DOMAIN}:8080"
---
# apps/core-app/base/external-secrets.yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: app-infra-secrets
spec:
  refreshInterval: "1h"
  secretStoreRef:
    kind: ClusterSecretStore
    name: k8s-cluster-store
  target:
    name: local-app-secrets
    creationPolicy: Owner
  data:
    - secretKey: db-password
      remoteRef:
        key: prod-database-app
        property: password
    - secretKey: kafka-password
      remoteRef:
        key: my-kafka-user
        property: password
---
# apps/core-app/base/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: core-application
spec:
  replicas: 1
  template:
    metadata:
      labels:
        app: core-app
    spec:
      serviceAccountName: core-app-sa
      initContainers:
      - name: wait-for-infrastructure
        image: busybox:1.36
        command: 
        - 'sh'
        - '-c'
        - |
          until nc -z prod-database-rw.databases.svc.cluster.local 5432; do echo "Waiting for Database..."; sleep 2; done;
          until nc -z my-kafka-cluster-kafka-bootstrap.messaging.svc.cluster.local 9092; do echo "Waiting for Kafka..."; sleep 2; done;
      containers:
      - name: application
        image: my-registry/my-app:latest
        envFrom:
        - configMapRef:
            name: app-config
        env:
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: local-app-secrets
              key: db-password
        - name: KAFKA_PASSWORD
          valueFrom:
            secretKeyRef:
              name: local-app-secrets
              key: kafka-password
```

---

### B. Production Application Overlay (`apps/core-app/overlays/production/`)

```yaml
# apps/core-app/overlays/production/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: my-app-production
resources:
  - ../../base
  - security-policies.yaml
  - otel-instrumentation.yaml
patches:
  - target:
      kind: Deployment
      name: core-application
    patch: |-
      - op: replace
        path: /spec/replicas
        value: 3
      - op: add
        path: /spec/template/metadata/annotations
        value:
          instrumentation.opentelemetry.io/inject-java: "true"
          ://stakater.com: "local-app-secrets"
  - target:
      kind: KafkaTopic
      name: orders-v1
    patch: |-
      - op: replace
        path: /spec/partitions
        value: 6
      - op: replace
        path: /spec/replicas
        value: 3
```

```yaml
# apps/core-app/overlays/production/security-policies.yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: allow-app-to-postgres
  namespace: databases
spec:
  selector:
    matchLabels:
      cnpg.io/cluster: prod-database
  action: ALLOW
  rules:
  - from:
    - source:
        principals: ["cluster.local/ns/my-app-production/sa/core-app-sa"]
```

```yaml
# apps/core-app/overlays/production/otel-instrumentation.yaml
apiVersion: opentelemetry.io/v1alpha1
kind: Instrumentation
metadata:
  name: production-instrumentation
  namespace: my-app-production
spec:
  exporter:
    endpoint: "http://cluster.local"
  propagators:
    - tracecontext
    - baggage
  java:
    image: ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:latest
```
