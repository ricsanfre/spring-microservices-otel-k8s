#!/bin/sh
# ──────────────────────────────────────────────────────────────────────────────
# Valkey startup entrypoint — generates an ACL file from environment variables
# then exec-replaces itself with valkey-server.
#
# ACL user definitions
# ─────────────────────
#   default       on  nopass   — PING only (no keys, no data commands).
#                                Allows Lettuce’s bare HELLO probe to succeed
#                                before it sends AUTH <user> <pass>.
#                                nopass is safe: the only allowed command is PING.
#   admin         on  password — full access (+@all, all keys, all channels);
#                                for operational tooling and ACL management.
#   cart-service  on  password — read-write access to the e-commerce:cart:* key namespace.
#
# Key namespace convention
# ─────────────────────────
#   e-commerce:cart:*   → cart-service (read-write)
#
# Adding a new service
# ─────────────────────
#   1. Add a password env var below (e.g. MY_SERVICE_VALKEY_PASSWORD).
#   2. Add a `user my-service reset on >…` line to the ACL file block.
#   3. Grant read-only  (~<project>:<service>:* +@read +@connection) or
#      read-write       (~<project>:<service>:* +@read +@write +@connection).
#
# Passwords are read from environment variables so they can be supplied via
# Docker Compose env / .env file (local dev) or Kubernetes Secrets (staging/prod).
# ──────────────────────────────────────────────────────────────────────────────
set -eu

CART_SERVICE_PW="${CART_SERVICE_VALKEY_PASSWORD:-cart-service-secret}"
ADMIN_PW="${VALKEY_ADMIN_PASSWORD:-admin-secret}"

ACL_FILE="/tmp/valkey-acl.conf"

# ── Write ACL file ─────────────────────────────────────────────────────────────
# `reset` clears all flags (→ disabled, nokeys, nocommands) before each entry.
{
  # Default user: PING only — no key access, no data commands.
  # Must stay ON (nopass) because Lettuce sends a bare HELLO probe before AUTH.
  # The probe succeeds, then Lettuce sends AUTH <user> <pass> to switch users.
  printf 'user default reset on nopass +ping\n'
  printf 'user health reset on nopass resetchannels +ping\n'
  printf 'user admin reset on >%s ~* &* +@all\n' \
    "${ADMIN_PW}"
  printf 'user cart-service reset on >%s ~e-commerce:cart:* resetchannels +@read +@write +@connection\n' \
    "${CART_SERVICE_PW}"
} > "${ACL_FILE}"

echo "[valkey-init] ACL file written to ${ACL_FILE}"
echo "[valkey-init] Starting Valkey server..."

exec valkey-server \
  --aclfile "${ACL_FILE}" \
  --save 60 1 \
  --loglevel warning
