#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

pass() {
  echo "[OK] $1"
}

require_file() {
  local f="$1"
  [[ -f "$f" ]] || fail "Missing file: $f"
}

require_grep() {
  local pattern="$1"
  local file="$2"
  local label="$3"
  if grep -Eq -- "$pattern" "$file"; then
    pass "$label"
  else
    fail "$label (pattern not found: $pattern in $file)"
  fi
}

KUBE_VALUES="$ROOT_DIR/gitops/infrastructure/observability/app/base/kube-prometheus-stack-values.yaml"
COMPOSE_FILE="$ROOT_DIR/compose.yaml"

require_file "$KUBE_VALUES"
require_file "$COMPOSE_FILE"

# Ensure Tempo -> Loki query wiring exists
require_grep "tracesToLogsV2:" "$KUBE_VALUES" "Tempo tracesToLogsV2 block exists"
require_grep "datasourceUid:[[:space:]]*loki" "$KUBE_VALUES" "Tempo tracesToLogsV2 points to Loki datasource"
require_grep "trace_id = \"\\$\\$\{__trace.traceId\}\"" "$KUBE_VALUES" "Tempo tracesToLogsV2 query uses trace_id"

# Ensure Loki -> Tempo reverse mapping exists
require_grep "derivedFields:" "$KUBE_VALUES" "Loki derivedFields block exists"
require_grep "matcherRegex:[[:space:]]*trace_id" "$KUBE_VALUES" "Loki derivedFields matches trace_id"
require_grep "datasourceUid:[[:space:]]*tempo" "$KUBE_VALUES" "Loki derivedFields points to Tempo datasource"

# Ensure local compose exposes required endpoints for runtime troubleshooting
require_grep "grafana-lgtm:" "$COMPOSE_FILE" "Local Grafana LGTM service exists"
require_grep "- \"4317:4317\"" "$COMPOSE_FILE" "OTLP gRPC port is exposed in local compose"
require_grep "- \"4318:4318\"" "$COMPOSE_FILE" "OTLP HTTP port is exposed in local compose"
require_grep "- \"3000:3000\"" "$COMPOSE_FILE" "Grafana UI port is exposed in local compose"

pass "Trace/log correlation config checks passed"
