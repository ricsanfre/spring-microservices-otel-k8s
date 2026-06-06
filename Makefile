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
		obs-correlation-check obs-correlation-runtime-check obs-correlation-e2e-check \
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
        flux-operator-install flux-bootstrap flux-status \
        ghcr-us-image ghcr-ps-image ghcr-cs-image ghcr-os-image ghcr-rvs-image ghcr-ns-image ghcr-fe-image

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

obs-correlation-check: ## Validate Grafana trace<->logs correlation wiring config
	./scripts/observability/check-correlation-config.sh

obs-correlation-runtime-check: ## Validate runtime Grafana datasource correlation wiring (requires running Grafana)
	./scripts/observability/runtime-correlation-smoke.sh

obs-correlation-e2e-check: ## End-to-end trace/log correlation check (requires Keycloak + user-service + Grafana)
	./scripts/observability/e2e-correlation-smoke.sh

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
# Flux CD — GitOps bootstrap 
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
# Image build and push to GitHub Container Registry (ghcr.io)
# ──────────────────────────────────────────────────────────────────────────────

ghcr-us-image: us-build ## Build + push user-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl user-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

ghcr-cs-image: cs-build ## Build + push cart-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl cart-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

ghcr-ps-image: ps-build ## Build + push product-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl product-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

ghcr-os-image: os-build ## Build + push order-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl order-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

ghcr-rvs-image: rvs-build ## Build + push reviews-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl reviews-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

ghcr-ns-image: ns-build ## Build + push notification-service image to ghcr.io
	$(MAVEN) -N install -DskipTests --no-transfer-progress
	$(MAVEN) -pl common install -DskipTests --no-transfer-progress
	$(MAVEN) -pl notification-service jib:build \
	    -Ddocker.registry=ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO) \
	    --no-transfer-progress

ghcr-fe-image: ## Build + push frontend-service image to ghcr.io
	docker build -t ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO)/frontend-service:latest frontend-service/
	docker push ghcr.io/$(GITHUB_OWNER)/$(GITHUB_REPO)/frontend-service:latest

