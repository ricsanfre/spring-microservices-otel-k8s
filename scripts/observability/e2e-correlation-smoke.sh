#!/usr/bin/env bash
set -euo pipefail

# End-to-end trace/log correlation smoke check.
#
# Preconditions:
# - Grafana LGTM is running (make infra-up)
# - Keycloak is running
# - user-service is running on localhost:8085
#
# Flow:
# 1) Get service-account token from Keycloak (cart-service client)
# 2) Send request to user-service /users/resolve with a unique traceparent
# 3) Poll Loki via Grafana datasource proxy for that trace_id
# 4) Poll Tempo via Grafana datasource proxy for that trace_id
#
# Exit 0 only if both logs and trace are found.

GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
GRAFANA_USERNAME="${GRAFANA_USERNAME:-admin}"
GRAFANA_PASSWORD="${GRAFANA_PASSWORD:-admin}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
USER_SERVICE_URL="${USER_SERVICE_URL:-http://localhost:8085}"
CLIENT_ID="${CORR_CLIENT_ID:-cart-service}"
CLIENT_SECRET="${CORR_CLIENT_SECRET:-cart-service-secret}"
POLL_ATTEMPTS="${CORR_POLL_ATTEMPTS:-15}"
POLL_SLEEP_SECONDS="${CORR_POLL_SLEEP_SECONDS:-4}"

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

urlencode() {
  jq -rn --arg v "$1" '$v|@uri'
}

random_hex() {
  local bytes="$1"
  od -An -N"$bytes" -tx1 /dev/urandom | tr -d ' \n'
}

need_cmd curl
need_cmd jq
need_cmd od

# Quick reachability checks
curl -sSf "$GRAFANA_URL/api/health" >/dev/null 2>&1 || fail "Grafana not reachable at $GRAFANA_URL"
curl -sSf "$KEYCLOAK_URL/realms/e-commerce/.well-known/openid-configuration" >/dev/null 2>&1 || fail "Keycloak not reachable at $KEYCLOAK_URL"
ok "Grafana and Keycloak are reachable"

# user-service must be running for end-to-end request generation
if ! curl -sS -o /dev/null "$USER_SERVICE_URL/api-docs"; then
  fail "user-service is not reachable at $USER_SERVICE_URL. Start it first (for example: make us-run)."
fi
ok "user-service endpoint is reachable"

# Datasource IDs for proxy calls
loki_id="$(api_get "/api/datasources/name/Loki" | jq -r '.id // empty')"
tempo_id="$(api_get "/api/datasources/name/Tempo" | jq -r '.id // empty')"
[[ -n "$loki_id" ]] || fail "Loki datasource id not found"
[[ -n "$tempo_id" ]] || fail "Tempo datasource id not found"
ok "Resolved Grafana datasource IDs (loki=$loki_id, tempo=$tempo_id)"

# Obtain service account token
access_token="$(
  curl -sSf -X POST "$KEYCLOAK_URL/realms/e-commerce/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=client_credentials&client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET" \
  | jq -r '.access_token // empty'
)"
[[ -n "$access_token" ]] || fail "Could not obtain service-account token from Keycloak"
ok "Obtained service-account token"

# Generate deterministic traceparent header pieces
trace_id="$(random_hex 16)"
parent_id="$(random_hex 8)"
traceparent="00-${trace_id}-${parent_id}-01"
unknown_sub="smoke-sub-$(date +%s)"
start_ns="$(date +%s%N)"

# Trigger request path that produces application logs on 404 via GlobalExceptionHandler
if ! http_code="$(curl -sS -o /tmp/e2e-correlation-body.$$ -w "%{http_code}" \
  -H "Authorization: Bearer $access_token" \
  -H "traceparent: $traceparent" \
  "$USER_SERVICE_URL/api/v1/users/resolve?idp_subject=$unknown_sub")"; then
  rm -f /tmp/e2e-correlation-body.$$
  fail "Failed to call user-service resolve endpoint at $USER_SERVICE_URL"
fi

# Expected usually 404 (unknown subject), but allow 200 for seeded fixtures.
if [[ "$http_code" != "404" && "$http_code" != "200" ]]; then
  rm -f /tmp/e2e-correlation-body.$$
  fail "Unexpected HTTP status from user-service resolve endpoint: $http_code"
fi
rm -f /tmp/e2e-correlation-body.$$
ok "Triggered traced request (status=$http_code, trace_id=$trace_id)"

# Poll Loki for log line containing this trace_id
found_loki="false"
for ((i=1; i<=POLL_ATTEMPTS; i++)); do
  now_ns="$(date +%s%N)"
  query='{service_name="user-service"} | trace_id = "'"$trace_id"'"'
  encoded_query="$(urlencode "$query")"
  resp="$(curl -sS -u "$GRAFANA_USERNAME:$GRAFANA_PASSWORD" \
    "$GRAFANA_URL/api/datasources/proxy/$loki_id/loki/api/v1/query_range?query=$encoded_query&start=$start_ns&end=$now_ns&limit=20")"
  count="$(jq -r '.data.result | length' <<<"$resp" 2>/dev/null || echo 0)"
  if [[ "$count" -gt 0 ]]; then
    found_loki="true"
    ok "Found trace_id in Loki on attempt $i"
    break
  fi
  sleep "$POLL_SLEEP_SECONDS"
done
[[ "$found_loki" == "true" ]] || fail "Did not find trace_id=$trace_id in Loki within polling window"

# Poll Tempo for trace by id
found_tempo="false"
for ((i=1; i<=POLL_ATTEMPTS; i++)); do
  status="$(curl -sS -o /tmp/e2e-tempo.$$ -w "%{http_code}" \
    -u "$GRAFANA_USERNAME:$GRAFANA_PASSWORD" \
    "$GRAFANA_URL/api/datasources/proxy/$tempo_id/api/traces/$trace_id")"
  if [[ "$status" == "200" ]]; then
    if jq -e '.batches != null or .trace != null' /tmp/e2e-tempo.$$ >/dev/null 2>&1; then
      found_tempo="true"
      ok "Found trace_id in Tempo on attempt $i"
      break
    fi
  fi
  sleep "$POLL_SLEEP_SECONDS"
done
rm -f /tmp/e2e-tempo.$$
[[ "$found_tempo" == "true" ]] || fail "Did not find trace_id=$trace_id in Tempo within polling window"

ok "End-to-end trace/log correlation smoke check passed (trace_id=$trace_id)"
