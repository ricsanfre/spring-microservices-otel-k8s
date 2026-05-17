# Technical Proposal: Multi-Namespace GitOps Packaging with Flux CD and External Secrets Operator (ESO)

This document details the architecture and deployment strategy for a microservices application packaged using **Flux CD**, whose infrastructure dependencies (**CloudNativePG, Strimzi Kafka, MongoDB, Keycloak, Cert-Manager, and Envoy Gateway**) are managed via a service-separated infrastructure layer orchestrated through decoupled Flux dependencies, anonymous read-only HTTPS mirrors, and automated Semantic Versioning (SemVer) pull requests via Renovate Bot.

---

## 1. Architectural Principles

* **Total Decoupling:** Applications never embed raw infrastructure or hardcoded credentials; they consume endpoints via internal cluster DNS and keys via injected secrets.
* **Unified Service-Separated Infrastructure Layer:** All operator controllers and live instances are consolidated inside a single `infrastructure/` directory organized strictly by service. This eliminates separate platform layers and matches operator lifecycles directly with the services they manage.
* **Application-Owned Business Resources:** Custom resources explicitly tied to the application's domain logic (**`KafkaTopic`**, **`KafkaUser`**, and **`KeycloakRealmImport`**) are packaged inside the application layer to ensure an aligned lifecycle.
* **Anonymous Read-Only HTTPS GitOps (Flux Operator):** Because the GitOps repository is public, cluster bootstrapping operates under a zero-credential constraint. The control plane utilizes the modern **Flux Operator** and **`FluxInstance` CRD** via an **anonymous HTTPS** connection. This completely removes the requirement for GitHub Personal Access Tokens (PATs), SSH keys, or basic authentication cluster secrets.
* **Global Cluster Parameterization (Flux Post-Build Substitution):** Key cluster parameters (e.g., `CLUSTER_DOMAIN`, `ENVIRONMENT`) are defined once in a central cluster settings ConfigMap. Flux automatically substitutes these variables dynamically across all manifests using its post-build substitution engine.
* **Decoupled Multi-Kustomization Pipeline:** To leverage Flux's native dependency engine and parallel execution, the continuous delivery process runs on distinct, specialized Flux `Kustomization` files linked via strict `dependsOn` arrays, allowing non-dependent tasks to run concurrently.
* **Dual-Layer Kustomize Overlays:** Both infrastructure services and application logic use clean `base` and `overlays/` (`staging`, `production`) directory footprints.
* **Polymorphic Secret Provisioning:** The active environment overlay dictates the secret store backend. Staging provisions an isolated `Fake` provider, while Production securely mounts a **HashiCorp Vault** instance inside the ESO service directory.
* **External Image Lifecycle via Renovate (Anti-SNAPSHOT & SemVer Rule):** To enforce immutability without granting write access to the cluster, image updates are managed via **Renovate Bot (Mend)**. Floating tags like `:latest` and active development tags containing `-SNAPSHOT` are prohibited. Renovate tracks `ghcr.io`, filters out volatile pre-releases, and opens structured Pull Requests directly in GitHub using Spring Boot strict Semantic Versioning (`>=1.0.0 <2.0.0`), keeping the cluster secure and declarative.
* **Ambient Mesh & Edge Routing (Production-Only):** Mutual TLS (mTLS) is strictly enforced in Production via **Istio Ambient Mesh (ztunnel)**. Traffic entering the cluster is managed by **Envoy Gateway** (implementing the Kubernetes Gateway API), which acts as the edge Ingress and routes traffic directly into the Ambient mesh. **Cert-Manager** automates public TLS termination at the Envoy edge.
* **Unified Telemetry Pipeline (Production-Only):** Observability in Production is standardized on the **OpenTelemetry (OTel) Operator** routing signals (Metrics, Logs, Traces) to a centralized backend stack managed by the **kube-prometheus-stack, Grafana Loki, and Grafana Tempo**.

---

## 2. GitOps Monorepo Directory Structure

The repository utilizes a modular, component-driven architecture leveraging Kustomize bases and overlays across both service infrastructure and core applications:
```text
📂 my-gitops-repo
├── 📜 renovate.json             # Layer -2: External Dependency & SemVer Automation
├── 📂 .github/                  # Layer -1: Continuous Integration Matrix
│   └── 📂 workflows/
│       └── ci.yaml              # Multi-Service path filter build & push
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
|
├── 📂 infrastructure/           # Layer 1: Service-Separated Infrastructure & Operators
│   ├── 📂 environments/         # Coordination manifests for Flux
│   │   ├── 📂 staging/
│   │   └── 📂 production/
│   ├── 📂 cert-manager/         # Cert-Manager Service Component
│   ├── 📂 ingress-gateway/      # Envoy Gateway Service Component
│   ├── 📂 eso-stores/           # External Secrets Component
│   ├── 📂 databases/            # CloudNativePG & Mongo Component
│   ├── 📂 messaging/            # Strimzi Kafka Component
│   ├── 📂 keycloak/             # Keycloak Server Component
│   └── 📂 observability/        # OTel & Prometheus Alerting Component
|
└── 📂 apps/                     # Layer 2: Application Overlays Layout
    └── 📂 order-service/        # Replicated structure across all matrix microservices
        ├── 📂 base/
        └── 📂 overlays/
            └── 📂 staging/
                ├── kustomization.yaml
                └── network-policies.yaml
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

### B. Declarative Anonymous HTTPS Flux Management (`clusters/production/flux-instance.yaml`)
The control plane is configured to query the public repository over an anonymous HTTPS endpoint. Since the repository is public, no `secretRef` field is required.
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
    url: "https://github.com" # Public HTTPS endpoint
    ref:
      branch: "main"
    # No secretRef needed due to anonymous public read access
```

### C. Parallel & Decoupled Kustomization Pipeline
Non-dependent tasks run concurrently to maximize performance. All resources share the automated `postBuild` substitution from the global `cluster-settings` ConfigMap.

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
  dependsOn:
    - name: infra-backends
    - name: infra-routing
    - name: infra-monitoring
  interval: 5m
  path: ./apps/order-service/overlays/production
  prune: true
  postBuild:
    substituteFrom:
      - kind: ConfigMap
        name: cluster-settings
```

---

## 4. Parameterized Infrastructure Blueprint (Layer 1)

### A. Envoy Ingress Gateway Component (`infrastructure/ingress-gateway/overlays/production/gateway.yaml`)
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
      hostname: "api.\${CLUSTER_DOMAIN}" 
      tls:
        mode: Terminate
        certificateRefs:
        - kind: Secret
          name: api-gateway-tls-cert
---
# infrastructure/ingress-gateway/overlays/production/http-routes.yaml
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

### B. ESO Secret Stores Component (`infrastructure/eso-stores/overlays/`)

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

### C. Cloud Databases Components Blueprint (`infrastructure/databases/base/`)
```yaml
# infrastructure/databases/base/cnpg-cluster.yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: prod-database
  namespace: databases
spec:
  instances: 3
  primaryUpdateStrategy: unsupervised
  storage:
    size: 10Gi
---
# infrastructure/databases/base/mongo-cluster.yaml
apiVersion: ://mongodb.com
kind: MongoDBCommunity
metadata:
  name: prod-mongodb
  namespace: databases
spec:
  members: 3
  type: ReplicaSet
  version: "6.0.5"
  security:
    authentication:
      modes: [/SCRAM/]
```

### D. Messaging Infrastructure Component (`infrastructure/messaging/base/`)
```yaml
# infrastructure/messaging/base/kafka-cluster.yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-kafka-cluster
  namespace: messaging
spec:
  kafka:
    version: 3.4.0
    replicas: 3
    listeners:
      - name: plaintext # TLS is offloaded completely to Istio Ambient Mesh layer
        port: 9092
        type: internal
        tls: false
    config:
      offsets.topic.replication.factor: 3
      transaction.state.log.replication.factor: 3
      transaction.state.log.min.isr: 2
  zookeeper:
    replicas: 3
```

### E. Observability & Alerting Component (`infrastructure/observability/overlays/production/`)

#### OTel Collector Config (`infrastructure/observability/overlays/production/otel-collector.yaml`)
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

#### Prometheus Alerting Rules (`infrastructure/observability/overlays/production/flux-eso-alerts.yaml`)
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

## 6. Application Overlays Layer & Business Custom Resources (Layer 2)

### A. The Core Base (`apps/order-service/base/`)

#### Application-Owned Kafka Custom Resources
```yaml
# apps/order-service/base/kafka-topics.yaml
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
# apps/order-service/base/kafka-user.yaml
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
# apps/order-service/base/keycloak-realm.yaml
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
# apps/order-service/base/configmap.yaml
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
# apps/order-service/base/external-secrets.yaml
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
# apps/order-service/base/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
spec:
  replicas: 1
  template:
    metadata:
      labels:
        app: order-service
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
        image: ghcr.io/my-org/my-gitops-repo/order-service:1.0.0
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

### B. Staging Application Overlay Configured for Renovate (`apps/order-service/overlays/staging/`)

```yaml
# apps/order-service/overlays/staging/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: my-app-staging
resources:
  - ../../base
  - network-policies.yaml

images:
  - name: ghcr.io/my-org/my-gitops-repo/order-service
    newName: ghcr.io/my-org/my-gitops-repo/order-service
    newTag: 1.2.4 # Renovate parses this string dynamically via target dependency scanning
```

```yaml
# apps/order-service/overlays/staging/network-policies.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-app-to-postgres
  namespace: databases
spec:
  podSelector:
    matchLabels:
      cnpg.io/cluster: prod-database
  policyTypes:
  - Ingress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: my-app-staging
    ports:
    - protocol: TCP
      port: 5432
```

---

### C. Production Application Overlay (`apps/order-service/overlays/production/`)

```yaml
# apps/order-service/overlays/production/kustomization.yaml
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
      name: order-service
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
# apps/order-service/overlays/production/security-policies.yaml
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

```yaml
# apps/order-service/overlays/production/otel-instrumentation.yaml
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
    image: "ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:latest"
```

---

## 7. Version Control and Tagging Strategy

Project versioning policy, see [VERSIONING.md](../VERSIONING.md) document, defines a strict Semantic Versioning which is enforced across all application images. The `:latest` tag is prohibited in the cluster to ensure immutability and reproducibility. All image updates are managed via Renovate Bot, which scans the `kustomization.yaml` files for image references, applies SemVer rules, and opens structured Pull Requests for version updates.

Snapshot versions containing `-SNAPSHOT` are actively filtered out by Renovate to prevent unstable images from entering the cluster. Only stable releases (e.g., `1.0.0`, `1.2.4`) are allowed, and all updates must adhere to the defined versioning constraints.

## 7. Renovate Bot External Pipeline Engine Configuration

This file lives in the root directory of the monorepo (`renovate.json`). It dictates how Renovate parses Kustomize patterns, implements explicit **Spring Boot Semantic Versioning logic**, and actively filters out `-SNAPSHOT` dependencies from entering the pipeline.

```json
{
  "\$schema": "https://renovatebot.com",
  "extends": [
    "config:recommended"
  ],
  "regexManagers": [
    {
      "fileMatch": ["(^|/)kustomization\\.yaml\$"],
      "matchStrings": [
        "name: (?<depName>ghcr\\.io/[\\w-]+/[\\w-]+/[\\w-]+)\\s+newName: .*\\s+newTag: (?<currentValue>[\\w.-]+)"
      ],
      "datasourceTemplate": "docker"
    }
  ],
  "packageRules": [
    {
      "matchDatasources": ["docker"],
      "matchPackageNames": ["ghcr.io/my-org/my-gitops-repo/order-service"],
      "allowedVersions": "!/SNAPSHOT/",
      "versioning": "semver"
    }
  ]
}
```

