#!/usr/bin/env bash
set -euo pipefail

# Runtime smoke check for Grafana trace<->logs correlation wiring.
#
# What it validates at runtime:
# 1) Grafana is reachable.
# 2) Tempo and Loki datasources exist.
# 3) Tempo tracesToLogsV2 points to Loki and uses __trace.traceId.
# 4) Loki derivedFields maps trace_id back to Tempo datasource.
#
# Usage:
#   ./scripts/observability/runtime-correlation-smoke.sh
#
# Optional env vars:
#   GRAFANA_URL          default: http://localhost:3000
#   GRAFANA_USERNAME     default: admin
#   GRAFANA_PASSWORD     default: admin

GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
GRAFANA_USERNAME="${GRAFANA_USERNAME:-admin}"
GRAFANA_PASSWORD="${GRAFANA_PASSWORD:-admin}"

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

ok() {
  echo "[OK] $1"
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

api_get() {
  local path="$1"
  curl -sSf -u "$GRAFANA_USERNAME:$GRAFANA_PASSWORD" "$GRAFANA_URL$path"
}

need_cmd curl
need_cmd jq

if ! curl -sSf "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
  fail "Grafana is not reachable at $GRAFANA_URL. Start infra first (make infra-up)."
fi
ok "Grafana health endpoint is reachable"

tempo_json="$(api_get "/api/datasources/name/Tempo")" || fail "Tempo datasource not found in Grafana"
loki_json="$(api_get "/api/datasources/name/Loki")" || fail "Loki datasource not found in Grafana"

tempo_uid="$(jq -r '.uid // empty' <<<"$tempo_json")"
loki_uid="$(jq -r '.uid // empty' <<<"$loki_json")"
[[ -n "$tempo_uid" ]] || fail "Tempo datasource uid is empty"
[[ -n "$loki_uid" ]] || fail "Loki datasource uid is empty"
ok "Tempo and Loki datasources exist (tempo=$tempo_uid, loki=$loki_uid)"

tempo_target_uid="$(jq -r '.jsonData.tracesToLogsV2.datasourceUid // empty' <<<"$tempo_json")"
[[ "$tempo_target_uid" == "$loki_uid" ]] || fail "Tempo tracesToLogsV2.datasourceUid ($tempo_target_uid) does not match Loki uid ($loki_uid)"
ok "Tempo tracesToLogsV2 points to Loki datasource"

tempo_query="$(jq -r '.jsonData.tracesToLogsV2.query // empty' <<<"$tempo_json")"
if [[ "$tempo_query" != *'__trace.traceId'* ]]; then
  fail "Tempo tracesToLogsV2 query does not reference __trace.traceId"
fi
ok "Tempo tracesToLogsV2 query references __trace.traceId"

has_derived_field="$(( $(jq '[.jsonData.derivedFields[]? | select((.matcherRegex // "") == "trace_id" and (.datasourceUid // "") == "'"$tempo_uid"'" )] | length' <<<"$loki_json") ))"
if [[ "$has_derived_field" -lt 1 ]]; then
  fail "Loki derivedFields does not map matcherRegex=trace_id to Tempo datasource uid=$tempo_uid"
fi
ok "Loki derivedFields maps trace_id to Tempo datasource"

ok "Runtime correlation smoke check passed"
