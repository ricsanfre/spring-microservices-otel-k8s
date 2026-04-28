# ──────────────────────────────────────────────────────────────────────────────
# E-Commerce platform — local development Makefile
#
# Prerequisites: Java 25, Maven 3.9+, Docker (Compose v2), curl, jq
#                k3d 5+, kubectl, helm 3.15+, kustomize 5+  (for k8s-* targets)
#
# Sections:
#   us-*    user-service (build / test / infra / run / tokens)
#   k3d-*   k3d cluster lifecycle
#   k8s-*   Kubernetes operator install + application deploy
# ──────────────────────────────────────────────────────────────────────────────

MAVEN   ?= mvn
DOMAIN  ?= local.test           # default domain for all ingress hostnames
KEYCLOAK_OPERATOR_VERSION ?= 26.6.1  # https://github.com/keycloak/keycloak-k8s-resources/releases

.DEFAULT_GOAL := help
.PHONY: help \
        us-build us-test us-image \
        us-infra-up us-infra-down us-infra-clean us-infra-logs us-infra-ps \
        us-run us-dev \
        us-token us-token-sa \
        ps-build ps-test ps-image \
        ps-infra-up ps-infra-down ps-infra-clean \
        ps-run ps-dev ps-seed \
        cs-build cs-test cs-image \
        cs-infra-up cs-infra-down cs-infra-clean \
        cs-run cs-dev \
        k3d-create k3d-delete k3d-info \
        k8s-namespaces k8s-operators k8s-keycloak-operator \
        k8s-infra k8s-infra-cert-manager k8s-infra-postgres k8s-infra-mongodb k8s-infra-valkey \
        k8s-infra-kafka k8s-infra-keycloak k8s-infra-envoy-gateway k8s-infra-monitoring k8s-infra-otel-collector k8s-up \
        k8s-apps-deploy k8s-apps-delete \
        k8s-us-deploy k8s-us-delete k8s-us-image \
        k8s-cs-deploy k8s-cs-delete k8s-cs-image

# ──────────────────────────────────────────────────────────────────────────────
# Help
# ──────────────────────────────────────────────────────────────────────────────
help: ## Show this help
	@echo ""
	@echo "E-Commerce platform — local dev targets"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	    | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ──────────────────────────────────────────────────────────────────────────────
# user-service — build & test
# ──────────────────────────────────────────────────────────────────────────────

us-build: ## Compile + package user-service JAR (tests skipped)
	$(MAVEN) -pl common,user-service -am package -DskipTests --no-transfer-progress

us-test: ## Run user-service unit + integration tests (Testcontainers — needs Docker)
	$(MAVEN) -pl common,user-service -am test --no-transfer-progress

us-image: us-build ## Build user-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl user-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress

# ──────────────────────────────────────────────────────────────────────────────
# user-service — infrastructure  (Docker Compose profiles: infra + auth + observability)
# ──────────────────────────────────────────────────────────────────────────────

_COMPOSE_PROFILES := --profile infra --profile auth --profile observability

us-infra-up: ## Start infrastructure: postgres, keycloak, grafana-lgtm (waits for healthy)
	docker compose $(_COMPOSE_PROFILES) up -d --wait

us-infra-down: ## Stop + remove infra containers (named volumes are preserved)
	docker compose $(_COMPOSE_PROFILES) down

us-infra-clean: ## Stop + remove infra containers AND delete data volumes
	docker compose $(_COMPOSE_PROFILES) down -v

us-infra-logs: ## Tail infra container logs (Ctrl-C to stop)
	docker compose $(_COMPOSE_PROFILES) logs -f

us-infra-ps: ## Show infra container status
	docker compose $(_COMPOSE_PROFILES) ps

# ──────────────────────────────────────────────────────────────────────────────
# user-service — run locally (JAR, Spring profile: local)
# ──────────────────────────────────────────────────────────────────────────────

us-run: us-build ## Build then run user-service JAR with 'local' Spring profile
	java -jar user-service/target/user-service-*.jar \
	    --spring.profiles.active=local

us-dev: us-infra-up us-run ## Full local dev loop: start infra, then run service

# ──────────────────────────────────────────────────────────────────────────────
# product-service — build & test
# ──────────────────────────────────────────────────────────────────────────────

ps-build: ## Compile + package product-service JAR (tests skipped)
	$(MAVEN) -pl common,product-service -am package -DskipTests --no-transfer-progress

ps-test: ## Run product-service unit + integration tests
	$(MAVEN) -pl common,product-service -am test --no-transfer-progress

ps-image: ps-build ## Build product-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl product-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress

# ──────────────────────────────────────────────────────────────────────────────
# product-service — infrastructure  (Docker Compose profiles: infra + auth + observability)
# ──────────────────────────────────────────────────────────────────────────────

ps-infra-up: ## Start infrastructure for product-service: mongodb, keycloak, grafana-lgtm
	docker compose $(_COMPOSE_PROFILES) up -d --wait

ps-infra-down: ## Stop + remove infra containers (named volumes preserved)
	docker compose $(_COMPOSE_PROFILES) down

ps-infra-clean: ## Stop + remove infra containers AND delete data volumes
	docker compose $(_COMPOSE_PROFILES) down -v

# ──────────────────────────────────────────────────────────────────────────────
# product-service — run locally (JAR, Spring profile: local)
# ──────────────────────────────────────────────────────────────────────────────

ps-run: ps-build ## Build then run product-service JAR
	java -jar product-service/target/product-service-*.jar

ps-dev: ps-infra-up ps-run ## Full local dev loop: start infra, then run product-service

ps-seed: ## Seed product-service MongoDB with 20 sci-fi & fantasy books (idempotent)
	docker compose exec mongo \
	    mongosh --quiet products /docker-entrypoint-initdb.d/init-products.js

# ──────────────────────────────────────────────────────────────────────────────
# cart-service — build & test
# ──────────────────────────────────────────────────────────────────────────────

cs-build: ## Compile + package cart-service JAR (tests skipped)
	$(MAVEN) -pl common,cart-service -am package -DskipTests --no-transfer-progress

cs-test: ## Run cart-service unit + integration tests
	$(MAVEN) -pl common,cart-service -am test --no-transfer-progress

cs-image: cs-build ## Build cart-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl cart-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress

# ──────────────────────────────────────────────────────────────────────────────
# cart-service — infrastructure  (Docker Compose profiles: infra + auth + observability)
# Valkey is part of the 'infra' profile — same as product-service / user-service.
# ──────────────────────────────────────────────────────────────────────────────

cs-infra-up: ## Start infrastructure for cart-service: valkey, keycloak, grafana-lgtm
	docker compose $(_COMPOSE_PROFILES) up -d --wait

cs-infra-down: ## Stop + remove infra containers (named volumes preserved)
	docker compose $(_COMPOSE_PROFILES) down

cs-infra-clean: ## Stop + remove infra containers AND delete data volumes
	docker compose $(_COMPOSE_PROFILES) down -v

# ──────────────────────────────────────────────────────────────────────────────
# cart-service — run locally (JAR)
# ──────────────────────────────────────────────────────────────────────────────

cs-run: cs-build ## Build then run cart-service JAR
	java -jar cart-service/target/cart-service-*.jar

cs-dev: cs-infra-up cs-run ## Full local dev loop: start infra, then run cart-service

# ──────────────────────────────────────────────────────────────────────────────
# user-service — Keycloak tokens  (manual API testing with curl)
#
# e-commerce-web is a confidential BFF client (directAccessGrantsEnabled=false).
# us-token uses Authorization Code flow via oauth2c — a browser window will open.
#
# Install oauth2c:
#   curl -sSfL https://raw.githubusercontent.com/cloudentity/oauth2c/master/install.sh | \
#     sudo sh -s -- -b /usr/local/bin latest
#
# Usage:
#   TOKEN=$(make -s us-token)
#   curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/v1/users/me
# ──────────────────────────────────────────────────────────────────────────────

us-token: ## Fetch user access token via Authorization Code flow (opens browser — requires oauth2c + jq)
	@oauth2c "http://localhost:8180/realms/e-commerce" \
	    --client-id e-commerce-web \
	    --client-secret e-commerce-web-secret \
	    --grant-type authorization_code \
	    --auth-method client_secret_post \
	    --response-types code \
	    --response-mode query \
	    --scopes "openid profile email products:read orders:read orders:write reviews:read reviews:write users:read" \
	    --redirect-url http://localhost:9876/callback \
	    | jq -r .access_token

us-token-sa: ## Fetch service account token for e-commerce-service (requires jq)
	@curl -sf -X POST \
	    "http://localhost:8180/realms/e-commerce/protocol/openid-connect/token" \
	    -H "Content-Type: application/x-www-form-urlencoded" \
	    -d "grant_type=client_credentials&client_id=e-commerce-service&client_secret=e-commerce-service-secret" \
	    | jq -r .access_token

# ──────────────────────────────────────────────────────────────────────────────
# k3d — cluster lifecycle
# ──────────────────────────────────────────────────────────────────────────────

k3d-create: ## Create k3d staging cluster (defined in k8s/k3d-cluster.yaml)
	k3d cluster create --config k8s/k3d-cluster.yaml
	@echo "Cluster context: k3d-e-commerce"
	@echo "kube-api: https://kube-api.$(DOMAIN):6445"

k3d-delete: ## Delete the k3d staging cluster (irreversible — all data is lost)
	k3d cluster delete e-commerce

k3d-info: ## Show k3d cluster status and kubeconfig context
	@k3d cluster list
	@echo ""
	@kubectl config get-contexts

# ──────────────────────────────────────────────────────────────────────────────
# k8s — operator installation  (run once after k3d-create)
# ──────────────────────────────────────────────────────────────────────────────

k8s-namespaces: ## Create all Kubernetes namespaces
	kubectl apply -f k8s/namespaces.yaml

k8s-operators: k8s-namespaces ## Install all infrastructure operators via Helm
	@echo "── cert-manager ────────────────────────────────────────────────────"
	helm repo add jetstack https://charts.jetstack.io --force-update
	helm upgrade --install cert-manager jetstack/cert-manager \
	    --namespace cert-manager --create-namespace \
	    --version v1.16.2 \
	    --values k8s/helm/cert-manager-values.yaml \
	    --wait

	@echo "── Envoy Gateway ───────────────────────────────────────────────────"
	helm upgrade --install envoy-gateway \
	    oci://docker.io/envoyproxy/gateway-helm \
	    --version v1.4.1 \
	    --namespace envoy-gateway-system --create-namespace \
	    --values k8s/helm/envoy-gateway-values.yaml \
	    --wait

	@echo "── Strimzi Kafka Operator ───────────────────────────────────────────"
	helm upgrade --install strimzi-kafka-operator \
	    oci://quay.io/strimzi-helm-charts/strimzi-kafka-operator \
	    --namespace kafka --create-namespace \
	    --values k8s/helm/strimzi-operator-values.yaml \
	    --wait

	@echo "── CloudNativePG Operator ───────────────────────────────────────────"
	helm repo add cnpg https://cloudnative-pg.github.io/charts --force-update
	helm upgrade --install cnpg cnpg/cloudnative-pg \
	    --namespace cnpg-system --create-namespace \
	    --values k8s/helm/cnpg-operator-values.yaml \
	    --wait

	@echo "── MongoDB Community Operator ────────────────────────────────────────"
	helm repo add mongodb https://mongodb.github.io/helm-charts --force-update
	helm upgrade --install mongodb-operator mongodb/community-operator \
	    --namespace mongodb --create-namespace \
	    --values k8s/helm/mongodb-operator-values.yaml \
	    --wait

	@echo "── Keycloak Operator ─────────────────────────────────────────────────"
	$(MAKE) k8s-keycloak-operator

	@echo "── OpenTelemetry Operator ────────────────────────────────────────────"
	helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts --force-update
	helm upgrade --install opentelemetry-operator open-telemetry/opentelemetry-operator \
	    --namespace monitoring --create-namespace \
	    --values k8s/helm/otel-operator-values.yaml \
	    --wait

k8s-keycloak-operator: ## Install Keycloak Operator via Kustomize (version controlled by KEYCLOAK_OPERATOR_VERSION)
	@echo "Installing Keycloak Operator v$(KEYCLOAK_OPERATOR_VERSION) via Kustomize"
	@# Substitute the version into the kustomization.yaml URLs before applying
	kustomize build k8s/infra/keycloak/operator \
	    | sed 's|/26\.6\.1/|/$(KEYCLOAK_OPERATOR_VERSION)/|g' \
	    | kubectl apply -f -

k8s-infra-cert-manager: ## Deploy cert-manager issuers + wildcard TLS certificate
	kubectl apply -k k8s/infra/cert-manager
	@echo "Waiting for local-test-ca-issuer to be Ready..."
	kubectl wait --for=condition=ready clusterissuer/local-test-ca-issuer --timeout=120s

k8s-infra-postgres: ## Deploy PostgreSQL cluster via CNPG operator
	kubectl apply -k k8s/infra/postgres

k8s-infra-mongodb: ## Deploy MongoDB replica set via Community operator
	kubectl apply -k k8s/infra/mongodb

k8s-infra-kafka: ## Deploy Kafka cluster + topics via Strimzi operator
	kubectl apply -k k8s/infra/kafka

k8s-infra-keycloak: ## Deploy Keycloak instance + realm import via Keycloak operator
	kubectl apply -k k8s/infra/keycloak

k8s-infra-envoy-gateway: ## Deploy Envoy Gateway resources (GatewayClass, Gateway, HTTPRoutes, SecurityPolicy)
	kubectl apply -k k8s/envoy-gateway

k8s-infra-monitoring: ## Deploy Grafana LGTM observability stack (lgtm-distributed Helm chart)
	@echo "── Grafana LGTM Stack ──────────────────────────────────────────────"
	helm repo add grafana https://grafana.github.io/helm-charts --force-update
	helm upgrade --install lgtm grafana/lgtm-distributed \
	    --namespace monitoring --create-namespace \
	    --values k8s/helm/lgtm-distributed-values.yaml \
	    --wait --timeout 10m

k8s-infra-otel-collector: ## Deploy OpenTelemetry Collector via the OTel Operator
	kubectl apply -k k8s/infra/otel-collector

k8s-infra-valkey: ## Deploy Valkey (Redis-compatible cache) via plain Deployment
	kubectl apply -k k8s/infra/valkey

k8s-infra: k8s-infra-cert-manager k8s-infra-postgres k8s-infra-mongodb k8s-infra-valkey k8s-infra-kafka k8s-infra-keycloak k8s-infra-envoy-gateway k8s-infra-monitoring k8s-infra-otel-collector ## Deploy all infrastructure resources (cert-manager, postgres, mongodb, valkey, kafka, keycloak, envoy-gateway, monitoring, otel-collector)

k8s-up: k3d-create k8s-operators k8s-infra ## Full staging environment setup (create cluster + install operators + deploy infra)

# ──────────────────────────────────────────────────────────────────────────────
# k8s — application deployment
# ──────────────────────────────────────────────────────────────────────────────

k8s-us-image: us-build ## Build + push user-service image to k3d local registry
	$(MAVEN) -pl user-service jib:build \
	    -Ddocker.registry=localhost:5000 \
	    --no-transfer-progress

k8s-us-deploy: ## Deploy user-service to staging (Kustomize staging overlay)
	kubectl apply -k k8s/apps/user-service/overlays/staging

k8s-us-delete: ## Remove user-service from staging
	kubectl delete -k k8s/apps/user-service/overlays/staging --ignore-not-found

k8s-cs-image: cs-build ## Build + push cart-service image to k3d local registry
	$(MAVEN) -pl cart-service jib:build \
	    -Ddocker.registry=localhost:5000 \
	    --no-transfer-progress

k8s-cs-deploy: ## Deploy cart-service to staging (Kustomize staging overlay)
	kubectl apply -k k8s/apps/cart-service/overlays/staging

k8s-cs-delete: ## Remove cart-service from staging
	kubectl delete -k k8s/apps/cart-service/overlays/staging --ignore-not-found

k8s-apps-deploy: ## Deploy all services to staging
	kubectl apply -k k8s/apps/user-service/overlays/staging
	kubectl apply -k k8s/apps/cart-service/overlays/staging

k8s-apps-delete: ## Remove all services from staging
	kubectl delete -k k8s/apps/user-service/overlays/staging --ignore-not-found
	kubectl delete -k k8s/apps/cart-service/overlays/staging --ignore-not-found
