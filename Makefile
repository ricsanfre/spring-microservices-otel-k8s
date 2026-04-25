# ──────────────────────────────────────────────────────────────────────────────
# E-Commerce platform — local development Makefile
#
# Prerequisites: Java 25, Maven 3.9+, Docker (Compose v2), curl, jq
#
# Sections:
#   us-*   user-service (build / test / infra / run / tokens)
# ──────────────────────────────────────────────────────────────────────────────

MAVEN ?= mvn

.DEFAULT_GOAL := help
.PHONY: help \
        us-build us-test us-image \
        us-infra-up us-infra-down us-infra-clean us-infra-logs us-infra-ps \
        us-run us-dev \
        us-token us-token-sa

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
# user-service — infrastructure  (Docker Compose profiles: user-service + observability)
# ──────────────────────────────────────────────────────────────────────────────

_COMPOSE_PROFILES := --profile user-service --profile observability

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
# user-service — Keycloak tokens  (manual API testing with curl)
#
# Usage:
#   TOKEN=$(make -s us-token)
#   curl -H "Authorization: Bearer $TOKEN" http://localhost:8085/users/me
# ──────────────────────────────────────────────────────────────────────────────

us-token: ## Fetch access token for testuser via password grant (requires jq)
	@curl -sf -X POST \
	    "http://localhost:8180/realms/e-commerce/protocol/openid-connect/token" \
	    -H "Content-Type: application/x-www-form-urlencoded" \
	    -d "grant_type=password&client_id=e-commerce-web&username=testuser&password=password" \
	    | jq -r .access_token

us-token-sa: ## Fetch service account token for e-commerce-service (requires jq)
	@curl -sf -X POST \
	    "http://localhost:8180/realms/e-commerce/protocol/openid-connect/token" \
	    -H "Content-Type: application/x-www-form-urlencoded" \
	    -d "grant_type=client_credentials&client_id=e-commerce-service&client_secret=e-commerce-service-secret" \
	    | jq -r .access_token
