# ADR-011 — oauth2c for Local Authorization Code Flow Testing

**Date:** 2026-04-26  
**Status:** Accepted  
**Deciders:** Project team

---

## Context

The `e-commerce-web` Keycloak client is a **confidential BFF client** (see
[ADR-007](adr-007-nextjs-bff-frontend.md)) with `directAccessGrantsEnabled: false`. This means the
Resource Owner Password Credentials (ROPC) grant is disabled — a developer cannot obtain a user
access token with a simple `curl -d grant_type=password ...` call.

The only supported user-authentication flow is **Authorization Code**, which requires:

1. A browser redirect to the Keycloak login page
2. User login (interactive)
3. Keycloak redirecting back to a registered `redirect_uri` with an authorization code
4. A server-side `POST /token` to exchange the code for an access token

This creates a usability problem for local API testing (e.g., calling `GET /users/me` on
`user-service` with `curl`): a developer needs a user JWT but cannot get one without a browser and
a local HTTP server to receive the redirect.

### Why ROPC is disabled

The Resource Owner Password grant (sending username + password directly to the token endpoint) is:

- [Deprecated in OAuth 2.1](https://oauth.net/2.1/) — removed from the draft specification
- Inappropriate for a confidential BFF — the BFF never handles raw user credentials; it only
  exchanges authorization codes
- A security risk if accidentally enabled in staging/production alongside the authorization code
  flow

Keeping `directAccessGrantsEnabled: false` in the realm JSON ensures parity with the real
production flow and prevents accidental password-grant usage.

---

## Decision

Use **[oauth2c](https://github.com/cloudentity/oauth2c)** as the CLI tool for obtaining user access
tokens via the Authorization Code flow during local development and API testing.

oauth2c is pinned in `.mise.toml` using the Go backend:

```toml
"go:github.com/cloudentity/oauth2c" = "latest"
```

The Makefile `us-token` target wraps oauth2c:

```makefile
us-token:
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
```

Usage:

```bash
TOKEN=$(make -s us-token) && \
  curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/v1/users/me
```

oauth2c's default callback URL (`http://localhost:9876/callback`) is registered in
`e-commerce-web.redirectUris` in `docker/keycloak/realm-e-commerce.json`.

---

## Rationale

### Why oauth2c

| Concern | `curl` (password grant) | `oidc-client.sh` | `oauth2c` |
|---------|------------------------|------------------|-----------|
| Works with confidential BFF (`directAccessGrantsEnabled: false`) | ✗ | ✓ | ✓ |
| Handles Authorization Code flow automatically | ✗ | ✓ (via `nc`) | ✓ (built-in HTTP server) |
| Scriptable (no manual steps after browser login) | ✓ | Partial | ✓ (logs to stderr, token JSON to stdout) |
| Single binary, no shell dependencies | ✓ | ✗ (requires `nc`, `bash`) | ✓ |
| Actively maintained | — | Low activity | ✓ (900+ stars, regular releases) |
| Installable via mise | ✓ | ✗ | ✓ (`go:` backend) |
| PKCE support | ✗ | ✓ | ✓ |

### How it works

oauth2c starts a local HTTP server on `localhost:9876` before redirecting the browser to the
Keycloak `/auth` endpoint. When Keycloak sends the browser back to
`http://localhost:9876/callback?code=<auth_code>`, oauth2c:

1. Captures the authorization code from the redirect URL
2. Performs the `POST /token` exchange server-side (passing `client_id`, `client_secret`, `code`)
3. Prints the full token response JSON to stdout
4. Shuts down the local server

oauth2c writes verbose request/response logs to stderr, keeping stdout clean so the token JSON
can be captured directly: `TOKEN=$(make -s us-token)`.

### Service account tokens (unchanged)

The `us-token-sa` Makefile target uses plain `curl` with the `client_credentials` grant on
the `cart-service` Keycloak client (which has `serviceAccountsEnabled: true`). No browser is required for this
flow and the target is unaffected by this ADR.

### oidc-client.sh as a fallback

`oidc-client.sh` (pure bash, no compiled binary) is documented in
[keycloak-configuration.md](keycloak-configuration.md) as a fallback for environments where
installing binaries is not possible. It requires adding an additional `redirect_uri` to the realm
JSON and depends on `nc` (netcat) being available.

---

## Consequences

### Positive

- `make us-token` works correctly against a confidential BFF client — the flow matches production
- Developers get a realistic Authorization Code round-trip rather than a deprecated password grant
- oauth2c is installed automatically via `mise install` — no separate installation step
- oauth2c writes verbose request/response logs to stderr; stdout carries only the token JSON,
  so `| jq -r .access_token` and `TOKEN=$(make -s us-token)` work correctly

### Negative / Trade-offs

- `make us-token` now opens a browser window — it is no longer a fully non-interactive one-liner.
  After logging in, the token is captured automatically, so the overall flow takes ~5 seconds
  instead of instantaneous
- Requires a running browser on the developer's machine — not suitable for headless CI environments.
  CI pipelines should use `us-token-sa` (client credentials) or a dedicated test client with
  ROPC enabled, scoped to CI only
- The oauth2c binary is installed via the Go toolchain; `mise install` will download Go if not
  already present, adding ~200 MB to the first-time setup

---

## Related ADRs

- [ADR-007 — Next.js BFF Frontend](adr-007-nextjs-bff-frontend.md) — motivation for the
  confidential BFF client and why ROPC is disabled
- [ADR-008 — mise for Developer Tool Version Management](adr-008-mise-tool-version-management.md) — oauth2c is pinned via mise
- [ADR-003 — Keycloak as IAM](adr-003-keycloak-as-iam.md) — Keycloak realm and client configuration
