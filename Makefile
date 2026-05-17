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
GITHUB_OWNER ?= ricsanfre  # Override: GITHUB_OWNER=myorg make k8s-us-image
GITHUB_REPO  ?= spring-microservices-otel-k8s  # Override: GITHUB_REPO=myrepo make k8s-us-image
KEYCLOAK_OPERATOR_VERSION ?= 26.6.1  # https://github.com/keycloak/keycloak-k8s-resources/releases
POSTGRES_PASSWORD ?= postgres
KEYCLOAK_PASSWORD ?= admin
KEYCLOAK_DB_PASSWORD ?= keycloak_db_password
USERS_DB_PASSWORD ?= users_db_password
ORDERS_DB_PASSWORD ?= orders_db_password
MONGODB_PRODUCTS_PASSWORD ?= mongodb_products_password
MONGODB_REVIEWS_PASSWORD ?= mongodb_reviews_password
MONGODB_NOTIFICATIONS_PASSWORD ?= mongodb_notifications_password
GRAFANA_PASSWORD ?= grafana_password
FRONTEND_SERVICE_KEYCLOAK_SECRET ?= e-commerce-web-secret
CART_SERVICE_CLIENT_SECRET ?= cart-service-secret
ORDER_SERVICE_CLIENT_SECRET ?= order-service-secret
REVIEWS_SERVICE_CLIENT_SECRET ?= reviews-service-secret

.DEFAULT_GOAL := help
.PHONY: help \
        infra-up infra-down infra-clean infra-logs infra-ps \
        infra-min-up infra-min-down infra-min-clean infra-min-logs infra-min-ps \
        us-build us-test us-verify us-image \
        us-run us-dev \
        us-token us-token-sa \
        ps-build ps-test ps-verify ps-image \
        ps-run ps-dev ps-seed \
        cs-build cs-test cs-verify cs-image \
        cs-run cs-dev \
        os-build os-test os-verify os-image \
        os-run os-dev \
        rvs-build rvs-test rvs-verify rvs-image \
        rvs-run rvs-dev \
        ns-build ns-test ns-verify ns-image \
        ns-run \
        k3d-create k3d-delete k3d-info \
        k8s-namespaces k8s-keycloak-operator \
		k8s-postgres-secret k8s-keycloak-secret k8s-mongodb-secrets k8s-grafana-secret k8s-frontend-service-secret \
		k8s-us-secret k8s-ps-secret k8s-cs-secret k8s-os-secret k8s-rvs-secret k8s-ns-secret k8s-secrets \
		k8s-eso-helm k8s-infra-eso \
		k8s-cert-manager-helm k8s-envoy-gateway-helm k8s-strimzi-operator-helm k8s-cnpg-operator-helm k8s-mongodb-operator-helm k8s-otel-operator-helm \
        k8s-infra k8s-infra-cert-manager k8s-infra-postgres k8s-infra-mongodb k8s-infra-valkey \
        k8s-infra-kafka k8s-infra-keycloak k8s-infra-envoy-gateway k8s-infra-monitoring k8s-infra-otel-collector k8s-up \
        flux-operator-install flux-bootstrap flux-status \
        k8s-apps-deploy k8s-apps-delete k8s-ecommerce-config k8s-ecommerce-config-delete \
        k8s-us-deploy k8s-us-delete k8s-us-image \
        k8s-ps-deploy k8s-ps-delete k8s-ps-image \
        k8s-cs-deploy k8s-cs-delete k8s-cs-image \
        k8s-os-deploy k8s-os-delete k8s-os-image \
        k8s-rvs-deploy k8s-rvs-delete k8s-rvs-image \
        k8s-ns-deploy k8s-ns-delete k8s-ns-image \
        k8s-fe-deploy k8s-fe-delete k8s-fe-image

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
# Infrastructure (Docker Compose) — generic targets
# ──────────────────────────────────────────────────────────────────────────────

INFRA_PROFILES = --profile infra --profile auth --profile observability
INFRA_MIN_PROFILES = --profile infra --profile auth

infra-up: ## Start all infrastructure: infra, auth, observability (waits for healthy)
	docker compose $(INFRA_PROFILES) up -d --wait

infra-down: ## Stop + remove all infra containers (named volumes preserved)
	docker compose $(INFRA_PROFILES) down

infra-clean: ## Stop + remove all infra containers AND delete data volumes
	docker compose $(INFRA_PROFILES) down -v

infra-logs: ## Tail all infra container logs (Ctrl-C to stop)
	docker compose $(INFRA_PROFILES) logs -f

infra-ps: ## Show all infra container status
	docker compose $(INFRA_PROFILES) ps

# Minimal infra (no observability)
infra-min-up: ## Start minimal infra: infra, auth (no observability)
	docker compose $(INFRA_MIN_PROFILES) up -d --wait

infra-min-down: ## Stop + remove minimal infra containers (named volumes preserved)
	docker compose $(INFRA_MIN_PROFILES) down

infra-min-clean: ## Stop + remove minimal infra containers AND delete data volumes
	docker compose $(INFRA_MIN_PROFILES) down -v

infra-min-logs: ## Tail minimal infra container logs (Ctrl-C to stop)
	docker compose $(INFRA_MIN_PROFILES) logs -f

infra-min-ps: ## Show minimal infra container status
	docker compose $(INFRA_MIN_PROFILES) ps

# ──────────────────────────────────────────────────────────────────────────────
# user-service — build & test
# ──────────────────────────────────────────────────────────────────────────────

us-build: ## Compile + package user-service JAR (tests skipped)
	$(MAVEN) -pl common,user-service -am package -DskipTests --no-transfer-progress

us-test: ## Run user-service unit tests only (fast, no containers)
	$(MAVEN) -pl common,user-service -am test --no-transfer-progress

us-verify: ## Run user-service unit + integration tests (Testcontainers — needs Docker)
	$(MAVEN) -pl common,user-service -am verify --no-transfer-progress

us-image: us-build ## Build user-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl user-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress



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

ps-test: ## Run product-service unit tests only (fast, no containers)
	$(MAVEN) -pl common,product-service -am test --no-transfer-progress

ps-verify: ## Run product-service unit + integration tests (Testcontainers — needs Docker)
	$(MAVEN) -pl common,product-service -am verify --no-transfer-progress

ps-image: ps-build ## Build product-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl product-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress

# ──────────────────────────────────────────────────────────────────────────────
# product-service — run locally (JAR, Spring profile: local)
# ──────────────────────────────────────────────────────────────────────────────

ps-run: ps-build ## Build then run product-service JAR
	java -jar product-service/target/product-service-*.jar

ps-dev: ps-infra-up ps-run ## Full local dev loop: start infra, then run product-service

ps-seed: ## Seed product-service MongoDB with 20 sci-fi & fantasy books (idempotent)
	docker compose cp docker/mongo/init-products.js mongo:/tmp/init-products.js
	docker compose exec -T mongo mongosh --quiet products /tmp/init-products.js

# ──────────────────────────────────────────────────────────────────────────────
# cart-service — build & test
# ──────────────────────────────────────────────────────────────────────────────

cs-build: ## Compile + package cart-service JAR (tests skipped)
	$(MAVEN) -pl common,cart-service -am package -DskipTests --no-transfer-progress

cs-test: ## Run cart-service unit tests only (fast, no containers)
	$(MAVEN) -pl common,cart-service -am test --no-transfer-progress

cs-verify: ## Run cart-service unit + integration tests (Testcontainers — needs Docker)
	$(MAVEN) -pl common,cart-service -am verify --no-transfer-progress

cs-image: cs-build ## Build cart-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl cart-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress

# ──────────────────────────────────────────────────────────────────────────────
# cart-service — run locally (JAR)
# ──────────────────────────────────────────────────────────────────────────────

cs-run: cs-build ## Build then run cart-service JAR
	java -jar cart-service/target/cart-service-*.jar

cs-dev: cs-infra-up cs-run ## Full local dev loop: start infra, then run cart-service

# ──────────────────────────────────────────────────────────────────────────────
# order-service — build & test
# ──────────────────────────────────────────────────────────────────────────────

os-build: ## Compile + package order-service JAR (tests skipped)
	$(MAVEN) -pl common,order-service -am package -DskipTests --no-transfer-progress

os-test: ## Run order-service unit tests only (fast, no containers)
	$(MAVEN) -pl common,order-service -am test --no-transfer-progress

os-verify: ## Run order-service unit + integration tests (Testcontainers — needs Docker)
	$(MAVEN) -pl common,order-service -am verify --no-transfer-progress

os-image: os-build ## Build order-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl order-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress

# ──────────────────────────────────────────────────────────────────────────────
# order-service — run locally (JAR)
# ──────────────────────────────────────────────────────────────────────────────

os-run: os-build ## Build then run order-service JAR
	java -jar order-service/target/order-service-*.jar

os-dev: infra-min-up os-run ## Full local dev loop: start infra, then run order-service

# ──────────────────────────────────────────────────────────────────────────────
# reviews-service — build & test
# ──────────────────────────────────────────────────────────────────────────────

rvs-build: ## Compile + package reviews-service JAR (tests skipped)
	$(MAVEN) -pl common,reviews-service -am package -DskipTests --no-transfer-progress

rvs-test: ## Run reviews-service unit tests only (fast, no containers)
	$(MAVEN) -pl common,reviews-service -am test --no-transfer-progress

rvs-verify: ## Run reviews-service unit + integration tests (Testcontainers — needs Docker)
	$(MAVEN) -pl common,reviews-service -am verify --no-transfer-progress

rvs-image: rvs-build ## Build reviews-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl reviews-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress

# ──────────────────────────────────────────────────────────────────────────────
# reviews-service — run locally (JAR)
# ──────────────────────────────────────────────────────────────────────────────

rvs-run: rvs-build ## Build then run reviews-service JAR
	java -jar reviews-service/target/reviews-service-*.jar

rvs-dev: infra-min-up rvs-run ## Full local dev loop: start infra, then run reviews-service

# ──────────────────────────────────────────────────────────────────────────────
# notification-service — build & test
# ──────────────────────────────────────────────────────────────────────────────

ns-build: ## Compile + package notification-service JAR (tests skipped)
	$(MAVEN) -pl common,notification-service -am package -DskipTests --no-transfer-progress

ns-test: ## Run notification-service unit tests only (fast, no containers)
	$(MAVEN) -pl common,notification-service -am test --no-transfer-progress

ns-verify: ## Run notification-service unit + integration tests (Testcontainers — needs Docker)
	$(MAVEN) -pl common,notification-service -am verify --no-transfer-progress

ns-image: ns-build ## Build notification-service container image to local Docker daemon (Jib)
	$(MAVEN) -pl notification-service jib:dockerBuild \
	    -Ddocker.registry=local \
	    --no-transfer-progress

# ──────────────────────────────────────────────────────────────────────────────
# notification-service — run locally (JAR)
# ──────────────────────────────────────────────────────────────────────────────

ns-run: ns-build ## Build then run notification-service JAR
	java -jar notification-service/target/notification-service-*.jar

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

us-token-sa: ## Fetch cart-service service account token (users:resolve + orders:write, requires jq)
	@curl -sf -X POST \
	    "http://localhost:8180/realms/e-commerce/protocol/openid-connect/token" \
	    -H "Content-Type: application/x-www-form-urlencoded" \
	    -d "grant_type=client_credentials&client_id=cart-service&client_secret=cart-service-secret" \
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
# k8s — Infra installation  (run once after k3d-create)
# ──────────────────────────────────────────────────────────────────────────────

k8s-namespaces: ## Create all Kubernetes namespaces
	kubectl apply -f gitops/infrastructure/namespaces/namespaces.yaml

k8s-postgres-secret: ## Create Kubernetes secret for PostgreSQL credentials (used by CNPG bootstrap)
	kubectl create secret generic postgres-superuser-secret \
	--from-literal=username=postgres \
	--from-literal=password=$(POSTGRES_PASSWORD) \
	--namespace postgres
	kubectl create secret generic users-db-secret \
	--from-literal=username=users_owner \
	--from-literal=password=$(USERS_DB_PASSWORD) \
	--namespace postgres
	kubectl create secret generic orders-db-secret \
	--from-literal=username=orders_owner \
	--from-literal=password=$(ORDERS_DB_PASSWORD) \
	--namespace postgres
	kubectl create secret generic keycloak-db-secret \
	--from-literal=username=keycloak_owner \
	--from-literal=password=$(KEYCLOAK_DB_PASSWORD) \
	--namespace postgres
	kubectl create secret generic keycloak-db-secret \
	--from-literal=username=keycloak_owner \
	--from-literal=password=$(KEYCLOAK_DB_PASSWORD) \
	--namespace keycloak

k8s-keycloak-secret: ## Create Kubernetes secret for Keycloak admin credentials (used by Keycloak Operator bootstrap)
	kubectl create secret generic keycloak-admin-secret \
	--from-literal=username=admin --from-literal=password=$(KEYCLOAK_PASSWORD) \
	--namespace keycloak


k8s-mongodb-secrets: ## Create Kubernetes secrets for MongoDB credentials (used by Community Operator bootstrap)
	kubectl create secret generic mongodb-products-secret \
	--from-literal=password=$(MONGODB_PRODUCTS_PASSWORD) --namespace mongodb
	kubectl create secret generic mongodb-reviews-secret \
	--from-literal=password=$(MONGODB_REVIEWS_PASSWORD) --namespace mongodb
	kubectl create secret generic mongodb-notifications-secret \
	--from-literal=password=$(MONGODB_NOTIFICATIONS_PASSWORD) --namespace mongodb

k8s-grafana-secret: ## Create Kubernetes secret for Grafana admin credentials (used by kube-prometheus-stack)
	kubectl create secret generic grafana-admin-secret \
	--from-literal=admin-user=admin --from-literal=admin-password=$(GRAFANA_PASSWORD) \
	--namespace monitoring

k8s-frontend-service-secret: ## Create Kubernetes secret for frontend-service (Auth.js session key + Keycloak BFF client secret)
	kubectl create secret generic frontend-service-secret \
	--from-literal=AUTH_SECRET=$(openssl rand -base64 32) \
	--from-literal=AUTH_KEYCLOAK_SECRET=$(FRONTEND_SERVICE_KEYCLOAK_SECRET) \
	--namespace e-commerce

k8s-us-secret: ## Create Kubernetes secret for user-service DB credentials
	kubectl create secret generic user-service-db-secret \
	--from-literal=username=users_owner \
	--from-literal=password=$(USERS_DB_PASSWORD) \
	--namespace e-commerce

k8s-ps-secret: ## Create Kubernetes secret for product-service MongoDB URI
	kubectl create secret generic product-service-mongodb-secret \
	--from-literal=MONGODB_URI="mongodb://products_owner:$(MONGODB_PRODUCTS_PASSWORD)@mongodb-0.mongodb-svc.mongodb.svc.cluster.local:27017/products?authSource=admin&replicaSet=mongodb" \
	--namespace e-commerce

k8s-cs-secret: ## Create Kubernetes secrets for cart-service (OAuth2 client + Kafka password from Strimzi)
	kubectl create secret generic cart-service-oauth-secret \
	--from-literal=CART_SERVICE_CLIENT_SECRET=$(CART_SERVICE_CLIENT_SECRET) \
	--namespace e-commerce
	kubectl create secret generic cart-service-kafka-secret \
	--from-literal=password="$(shell kubectl get secret cart-service -n kafka -o jsonpath='{.data.password}' | base64 -d)" \
	--namespace e-commerce

k8s-os-secret: ## Create Kubernetes secrets for order-service (DB password + OAuth2 client + Kafka password from Strimzi)
	kubectl create secret generic order-service-db-secret \
	--from-literal=password=$(ORDERS_DB_PASSWORD) \
	--namespace e-commerce
	kubectl create secret generic order-service-oauth-secret \
	--from-literal=ORDER_SERVICE_CLIENT_SECRET=$(ORDER_SERVICE_CLIENT_SECRET) \
	--namespace e-commerce
	kubectl create secret generic order-service-kafka-secret \
	--from-literal=password="$(shell kubectl get secret order-service -n kafka -o jsonpath='{.data.password}' | base64 -d)" \
	--namespace e-commerce

k8s-rvs-secret: ## Create Kubernetes secrets for reviews-service (MongoDB URI + OAuth2 client)
	kubectl create secret generic reviews-service-mongodb-secret \
	--from-literal=MONGODB_URI="mongodb://reviews_owner:$(MONGODB_REVIEWS_PASSWORD)@mongodb-0.mongodb-svc.mongodb.svc.cluster.local:27017/reviews?authSource=admin&replicaSet=mongodb" \
	--namespace e-commerce
	kubectl create secret generic reviews-service-oauth-secret \
	--from-literal=REVIEWS_SERVICE_CLIENT_SECRET=$(REVIEWS_SERVICE_CLIENT_SECRET) \
	--namespace e-commerce

k8s-ns-secret: ## Create Kubernetes secret for notification-service Kafka password (from Strimzi)
	kubectl create secret generic notification-service-kafka-secret \
	--from-literal=password="$(shell kubectl get secret notification-service -n kafka -o jsonpath='{.data.password}' | base64 -d)" \
	--namespace e-commerce

k8s-secrets: k8s-postgres-secret k8s-keycloak-secret k8s-mongodb-secrets k8s-grafana-secret k8s-frontend-service-secret k8s-us-secret k8s-ps-secret k8s-cs-secret k8s-os-secret k8s-rvs-secret k8s-ns-secret ## Create all Kubernetes secrets

k8s-cert-manager-helm: ## [DEPRECATED — use Flux HelmRelease] Install cert-manager + trust-manager via Helm
	@echo "── cert-manager ────────────────────────────────────────────────────"
	helm repo add jetstack https://charts.jetstack.io --force-update
	helm upgrade --install cert-manager jetstack/cert-manager \
	    --namespace cert-manager --create-namespace \
	    --version v1.16.2 \
	    --values gitops/infrastructure/cert-manager/base/cert-manager-values.yaml \
	    --wait
k8s-envoy-gateway-helm: ## [DEPRECATED — use Flux HelmRelease] Install Envoy Gateway via Helm (version controlled by helm chart version v1.4.1)
	@echo "── Envoy Gateway ───────────────────────────────────────────────────"
	helm upgrade --install envoy-gateway \
	    oci://docker.io/envoyproxy/gateway-helm \
	    --version v1.4.1 \
	    --namespace envoy-gateway-system --create-namespace \
	    --values gitops/infrastructure/envoy-gateway/base/envoy-gateway-values.yaml \
	    --wait
k8s-strimzi-operator-helm: ## [DEPRECATED — use Flux HelmRelease] Install Strimzi Kafka Operator via Helm (version controlled by strimzi-operator-values.yaml)
	@echo "── Strimzi Kafka Operator ───────────────────────────────────────────"
	helm upgrade --install strimzi-kafka-operator \
	    oci://quay.io/strimzi-helm/strimzi-kafka-operator \
		--version 1.0.0 \
	    --namespace kafka --create-namespace \
	    --values gitops/infrastructure/kafka/base/strimzi-operator-values.yaml \
	    --wait

k8s-cnpg-operator-helm: ## [DEPRECATED — use Flux HelmRelease] Install CloudNativePG Operator via Helm (version controlled by cnpg-operator-values.yaml)
	@echo "── CloudNativePG Operator ───────────────────────────────────────────"
	helm repo add cnpg https://cloudnative-pg.github.io/charts --force-update
	helm upgrade --install cnpg cnpg/cloudnative-pg \
	    --namespace cnpg-system --create-namespace \
	    --values gitops/infrastructure/databases/base/cnpg-operator-values.yaml \
	    --wait

k8s-mongodb-operator-helm: ## [DEPRECATED — use Flux HelmRelease] Install MongoDB Community Operator via Helm (version controlled by mongodb-operator-values.yaml)
	@echo "── MongoDB Community Operator ────────────────────────────────────────"
	helm repo add mongodb https://mongodb.github.io/helm-charts --force-update
	helm upgrade --install mongodb-operator mongodb/community-operator \
	    --namespace mongodb --create-namespace \
	    --values gitops/infrastructure/databases/base/mongodb-operator-values.yaml \
	    --wait

k8s-otel-operator-helm: ## [DEPRECATED — use Flux HelmRelease] Install OpenTelemetry Operator via Helm (version controlled by otel-operator-values.yaml)
	@echo "── OpenTelemetry Operator ────────────────────────────────────────────"
	helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts --force-update
	helm upgrade --install opentelemetry-operator open-telemetry/opentelemetry-operator \
	    --namespace monitoring --create-namespace \
	    --values gitops/infrastructure/observability/base/otel-operator-values.yaml \
	    --wait

k8s-keycloak-operator: ## [DEPRECATED — use Flux after Phase 3 bootstrap] Install Keycloak Operator via Kustomize (version controlled by KEYCLOAK_OPERATOR_VERSION)
	@echo "Installing Keycloak Operator v$(KEYCLOAK_OPERATOR_VERSION) via Kustomize"
	@# Substitute the version into the kustomization.yaml URLs before applying
	kustomize build gitops/infrastructure/keycloak/base/operator \
	    | sed 's|/26\.6\.1/|/$(KEYCLOAK_OPERATOR_VERSION)/|g' \
	    | kubectl apply -f -

k8s-eso-helm: ## [DEPRECATED — use Flux HelmRelease] Install External Secrets Operator via Helm
	@echo "── External Secrets Operator ───────────────────────────────────────────────────────"
	helm repo add external-secrets https://charts.external-secrets.io --force-update
	helm upgrade --install external-secrets external-secrets/external-secrets \
	    --namespace external-secrets --create-namespace \
	    --version 0.14.0 \
	    --values gitops/infrastructure/eso-stores/base/eso-values.yaml \
	    --wait

k8s-infra-eso: k8s-eso-helm ## Deploy ClusterSecretStore (Fake provider) — ExternalSecrets live with each component
	kubectl apply -k gitops/infrastructure/eso-stores/overlays/staging
	@echo "ClusterSecretStore applied. ExternalSecrets are deployed together with each infra/app component."

k8s-infra-cert-manager: k8s-cert-manager-helm ## Deploy cert-manager issuers + wildcard TLS certificate
	kubectl apply -k gitops/infrastructure/cert-manager/overlays/staging
	@echo "Waiting for local-test-ca-issuer to be Ready..."
	kubectl wait --for=condition=ready clusterissuer/local-test-ca-issuer --timeout=120s

k8s-infra-postgres: k8s-cnpg-operator-helm ## Deploy PostgreSQL cluster via CNPG operator
	kubectl apply -k gitops/infrastructure/databases/overlays/staging

k8s-infra-mongodb: k8s-mongodb-operator-helm ## Deploy MongoDB replica set via Community operator
	kubectl apply -k gitops/infrastructure/databases/overlays/staging

k8s-infra-kafka: k8s-strimzi-operator-helm ## Deploy Kafka cluster + topics via Strimzi operator
	kubectl apply -k gitops/infrastructure/kafka/overlays/staging

k8s-infra-keycloak: k8s-keycloak-operator ## Deploy Keycloak instance + realm import via Keycloak operator
	kubectl apply -k gitops/infrastructure/keycloak/overlays/staging

k8s-infra-envoy-gateway: k8s-envoy-gateway-helm ## Deploy Envoy Gateway resources (GatewayClass, Gateway, HTTPRoutes, SecurityPolicy)
	kubectl apply -k gitops/infrastructure/envoy-gateway/overlays/staging

k8s-infra-monitoring: ## [DEPRECATED — use Flux HelmReleases] Deploy observability stack: kube-prometheus-stack + Tempo + Loki (monolithic, emptyDir)
	@echo "── Grafana ExternalSecret (admin credentials) ───────────────────────"
	kubectl apply -k gitops/infrastructure/observability/overlays/staging
	@echo "── kube-prometheus-stack (Prometheus + Grafana) ────────────────────"
	helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update
	helm upgrade --install kube-prom-stack prometheus-community/kube-prometheus-stack \
	    --namespace monitoring --create-namespace \
	    --values gitops/infrastructure/observability/base/kube-prometheus-stack-values.yaml \
	    --wait --timeout 10m
	@echo "── Grafana Tempo (monolithic) ───────────────────────────────────────"
	helm repo add grafana https://grafana.github.io/helm-charts --force-update
	helm upgrade --install tempo grafana/tempo \
	    --namespace monitoring --create-namespace \
	    --values gitops/infrastructure/observability/base/tempo-values.yaml \
	    --wait
	@echo "── Grafana Loki (monolithic) ────────────────────────────────────────"
	helm upgrade --install loki grafana/loki \
	    --namespace monitoring --create-namespace \
	    --values gitops/infrastructure/observability/base/loki-values.yaml \
	    --wait --timeout 5m

k8s-infra-otel-collector: k8s-otel-operator-helm ## Deploy OpenTelemetry Collector via the OTel Operator
	kubectl apply -k gitops/infrastructure/observability/overlays/staging

k8s-infra-valkey: ## Deploy Valkey (Redis-compatible cache) via plain Deployment
	kubectl apply -k gitops/infrastructure/valkey/overlays/staging

k8s-infra: k8s-namespaces k8s-infra-eso k8s-infra-cert-manager k8s-infra-envoy-gateway k8s-infra-postgres k8s-infra-mongodb k8s-infra-valkey k8s-infra-kafka k8s-infra-keycloak k8s-infra-monitoring k8s-infra-otel-collector ## Deploy all infrastructure resources (eso, cert-manager, postgres, mongodb, valkey, kafka, keycloak, envoy-gateway, monitoring, otel-collector)

k8s-up: k3d-create k8s-infra ## Full staging environment setup (create cluster + install operators + deploy infra)
# After k8s-up, push images with k8s-*-image targets (GITHUB_OWNER=<your-username>)
# then deploy with k8s-apps-deploy

# ──────────────────────────────────────────────────────────────────────────────
# Flux CD — GitOps bootstrap (Phase 3)
# ──────────────────────────────────────────────────────────────────────────────

flux-operator-install: ## Install Flux Operator via Helm (prerequisite for flux-bootstrap)
	helm install flux-operator oci://ghcr.io/controlplaneio-fluxcd/charts/flux-operator \
	  --namespace flux-system --create-namespace

flux-bootstrap: ## Apply FluxInstance to start GitOps reconciliation (run after flux-operator-install)
	kubectl apply -f gitops/clusters/staging/flux-instance.yaml

flux-status: ## Show status of all Flux Kustomizations and HelmReleases
	flux get kustomizations --all-namespaces
	flux get helmreleases --all-namespaces

# ──────────────────────────────────────────────────────────────────────────────
# k8s — application deployment
# ──────────────────────────────────────────────────────────────────────────────

k8s-us-image: us-build ## Build + push user-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl user-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

k8s-us-deploy: ## Deploy user-service to staging (Kustomize staging overlay)
	kubectl apply -k gitops/apps/user-service/overlays/staging

k8s-us-delete: ## Remove user-service from staging
	kubectl delete -k gitops/apps/user-service/overlays/staging --ignore-not-found

k8s-cs-image: cs-build ## Build + push cart-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl cart-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

k8s-cs-deploy: ## Deploy cart-service to staging (Kustomize staging overlay)
	kubectl apply -k gitops/apps/cart-service/overlays/staging

k8s-cs-delete: ## Remove cart-service from staging
	kubectl delete -k gitops/apps/cart-service/overlays/staging --ignore-not-found

k8s-ps-image: ps-build ## Build + push product-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl product-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

k8s-ps-deploy: ## Deploy product-service to staging (Kustomize staging overlay)
	kubectl apply -k gitops/apps/product-service/overlays/staging

k8s-ps-delete: ## Remove product-service from staging
	kubectl delete -k gitops/apps/product-service/overlays/staging --ignore-not-found

k8s-os-image: os-build ## Build + push order-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl order-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

k8s-os-deploy: ## Deploy order-service to staging (Kustomize staging overlay)
	kubectl apply -k gitops/apps/order-service/overlays/staging

k8s-os-delete: ## Remove order-service from staging
	kubectl delete -k gitops/apps/order-service/overlays/staging --ignore-not-found

k8s-rvs-image: rvs-build ## Build + push reviews-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl reviews-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

k8s-rvs-deploy: ## Deploy reviews-service to staging (Kustomize staging overlay)
	kubectl apply -k gitops/apps/reviews-service/overlays/staging

k8s-rvs-delete: ## Remove reviews-service from staging
	kubectl delete -k gitops/apps/reviews-service/overlays/staging --ignore-not-found

k8s-ns-image: ns-build ## Build + push notification-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl notification-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

k8s-ns-deploy: ## Deploy notification-service to staging (Kustomize staging overlay)
	kubectl apply -k gitops/apps/notification-service/overlays/staging

k8s-ns-delete: ## Remove notification-service from staging
	kubectl delete -k gitops/apps/notification-service/overlays/staging --ignore-not-found

k8s-fe-image: ## Build + push frontend-service image to ghcr.io
	docker build -t ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO)/frontend-service:latest frontend-service/
	docker push ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO)/frontend-service:latest

k8s-fe-deploy: ## Deploy frontend-service to staging (Kustomize staging overlay)
	kubectl apply -k gitops/apps/frontend-service/overlays/staging

k8s-fe-delete: ## Remove frontend-service from staging
	kubectl delete -k gitops/apps/frontend-service/overlays/staging --ignore-not-found

k8s-ecommerce-config: ## Apply e-commerce platform config (Keycloak realm, Kafka topics+users, DB schemas)
	kubectl apply -k gitops/apps/core-config/overlays/staging

k8s-ecommerce-config-delete: ## Remove e-commerce platform config
	kubectl delete -k gitops/apps/core-config/overlays/staging --ignore-not-found

k8s-apps-deploy: k8s-ecommerce-config ## Deploy all services to staging (includes e-commerce platform config)
	kubectl apply -k gitops/apps/user-service/overlays/staging
	kubectl apply -k gitops/apps/product-service/overlays/staging
	kubectl apply -k gitops/apps/cart-service/overlays/staging
	kubectl apply -k gitops/apps/order-service/overlays/staging
	kubectl apply -k gitops/apps/reviews-service/overlays/staging
	kubectl apply -k gitops/apps/notification-service/overlays/staging
	kubectl apply -k gitops/apps/frontend-service/overlays/staging

k8s-apps-delete: ## Remove all services from staging
	kubectl delete -k gitops/apps/user-service/overlays/staging --ignore-not-found
	kubectl delete -k gitops/apps/product-service/overlays/staging --ignore-not-found
	kubectl delete -k gitops/apps/cart-service/overlays/staging --ignore-not-found
	kubectl delete -k gitops/apps/order-service/overlays/staging --ignore-not-found
	kubectl delete -k gitops/apps/reviews-service/overlays/staging --ignore-not-found
	kubectl delete -k gitops/apps/notification-service/overlays/staging --ignore-not-found
	kubectl delete -k gitops/apps/frontend-service/overlays/staging --ignore-not-found
	kubectl delete -k gitops/apps/core-config/overlays/staging --ignore-not-found
