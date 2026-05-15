# Frontend Design — E-Commerce Platform

> **Stack:** Next.js 16 (App Router) · Auth.js v5 · TypeScript · Keycloak (OIDC)  
> **Pattern:** Backend-for-Frontend (BFF) — the browser never calls microservices directly

---

## 1. Architecture Overview

```
Browser
  │
  │  HTTPS (cookie-based session — no tokens in browser)
  ▼
┌──────────────────────────────────────────┐
│          Next.js 16 (BFF)                │
│                                          │
│  Server Components   ─── apiFetch() ──► │─► product-service :8081
│  Route Handlers (BFF API)               │─► order-service   :8082
│  Server Actions                         │─► reviews-service :8083
│  Auth.js v5 proxy                       │─► user-service    :8085
│                                          │─► cart-service    :8086
└──────────────────────────────────────────┘
              │
              │  OAuth 2.0 Authorization Code + PKCE
              ▼
        Keycloak :8180
        realm: e-commerce
```

**Key principle:** Access tokens live exclusively in the encrypted server-side JWT session cookie (`__Secure-next-auth.session-token`). Client components in the browser never receive or store a token — they call BFF Route Handlers which attach the token server-side before forwarding to the microservices.

---

## 2. Authentication — Auth.js v5 + Keycloak

### 2.1 Configuration (`src/auth.ts`)

| Concern | Detail |
|---------|--------|
| Provider | `next-auth/providers/keycloak` — reads `AUTH_KEYCLOAK_ID`, `AUTH_KEYCLOAK_SECRET`, `AUTH_KEYCLOAK_ISSUER` from env |
| Grant type | Authorization Code Flow (confidential client `e-commerce-web`) |
| Scopes requested | `openid profile email` + all service scopes: `products:read/write`, `orders:read/write`, `reviews:read/write`, `users:read`, `cart:read/write` |
| Session storage | Encrypted JWT cookie (stateless — no server-side session store) |
| Custom `/login` page | `pages.signIn = "/login"` |

### 2.2 JWT Callback — what is stored in the session cookie

```
account (from Keycloak first login)
  ├── access_token   → token.accessToken   (forwarded as Bearer to services)
  ├── refresh_token  → token.refreshToken  (used for silent renewal)
  ├── expires_at     → token.expiresAt     (Unix epoch, for expiry check)
  ├── scope          → token.scope         (space-separated granted scopes)
  └── id_token       → token.idToken       (used only for federated logout)
```

### 2.3 Session Callback — what is exposed to Server Components

```typescript
session.accessToken  // forwarded as Authorization: Bearer <token>
session.idToken      // used by federatedSignOut for id_token_hint
session.scope        // used for admin/feature gating across all pages
session.error        // "RefreshAccessTokenError" when refresh fails
```

### 2.4 Token Refresh

When `Date.now() >= expiresAt * 1000`, the `jwt` callback calls `refreshAccessToken()`:

1. Calls `POST {issuer}/protocol/openid-connect/token` with `grant_type=refresh_token`
2. On success: replaces `accessToken`, `refreshToken`, `expiresAt` in the cookie
3. On failure: sets `token.error = "RefreshAccessTokenError"` — the session callback propagates this to `session.error`, which `apiFetch()` uses to reject API calls rather than forwarding a bad token

### 2.5 Lazy User Registration

On first login (when `account` is present in the `jwt` callback), `triggerLazyRegistration()` is called asynchronously (`void`). It makes a `GET /api/v1/users/me` call to `user-service`, which auto-creates a user profile from the JWT claims (`email`, `given_name`, `family_name`, `preferred_username`). This is non-blocking and non-fatal.

---

## 3. Sign-In Flow

```
1. User clicks "Sign in" → form action: signIn("keycloak", { redirectTo: "/home" })
2. Auth.js builds the Keycloak authorization URL with all requested scopes
3. Browser redirects to Keycloak login page (:8180/realms/e-commerce/...)
4. User authenticates with credentials
5. Keycloak redirects back to: /api/auth/callback/keycloak
6. Auth.js exchanges the authorization code for tokens (confidential client)
7. jwt() callback stores access_token, refresh_token, id_token, scope, expiresAt
8. triggerLazyRegistration() fires asynchronously
9. Encrypted session cookie written to browser
10. User is redirected to /home
```

---

## 4. Sign-Out Flow (Federated Logout)

Signing out requires two distinct steps: clearing the **local** Next.js session and terminating the **Keycloak SSO session**. Without the second step, Keycloak's own browser session cookie (`KEYCLOAK_SESSION`) remains valid, causing silent re-authentication on the next sign-in attempt.

### 4.1 Flow Diagram

```
User clicks "Sign out"
        │
        ▼
   <form action={federatedSignOut}>  (Server Action)
        │
        ▼
   src/app/actions/auth.ts :: federatedSignOut()
        │
        ├─ 1. Read session to retrieve idToken
        │
        ├─ 2. signOut({ redirect: false })
        │        └─ Clears the __Secure-next-auth.session-token cookie
        │
        └─ 3. redirect() to Keycloak end-session endpoint:
                 {ISSUER}/protocol/openid-connect/logout
                   ?id_token_hint={idToken}
                   &post_logout_redirect_uri={AUTH_URL}/login
                        │
                        ▼
               Keycloak validates id_token_hint,
               terminates the SSO session,
               deletes KEYCLOAK_SESSION cookie,
               redirects browser to → /login
```

### 4.2 Key Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `id_token_hint` | Stored `id_token` from original login | Proves the caller's identity to Keycloak; required for automatic redirect without a confirmation page |
| `post_logout_redirect_uri` | `{AUTH_URL}/login` | Where Keycloak sends the user after logout — must match a URI registered in `post.logout.redirect.uris` on the `e-commerce-web` Keycloak client |

### 4.3 Keycloak Client Configuration Requirement

Post-logout redirect URIs must be configured on the `e-commerce-web` client. **Keycloak 26
changed how this is represented in the realm import JSON**: the top-level
`postLogoutRedirectUris` field is not recognised and silently ignored. Use the `attributes` map
instead, with multiple URIs separated by `##`:

```json
{
  "clientId": "e-commerce-web",
  "attributes": {
    "post.logout.redirect.uris": "http://localhost:3001/*##https://app.local.test/*"
  }
}
```

Do **not** use the top-level field — it causes Keycloak to start with no post-logout URIs
registered, and the end-session endpoint returns `invalid_redirect_url`.

Without this configuration, Keycloak returns `invalid_redirect_url`.

### 4.4 Fallback

If `idToken` is missing from the session (e.g., session was created before the `idToken` field was added), the action falls back to `redirect("/login")` — only the local cookie is cleared. The Keycloak SSO session remains, but this is an edge case that resolves itself after one new login.

---

## 5. Route Protection (Proxy)

`src/proxy.ts` re-exports `auth` from Auth.js as the Next.js proxy (renamed from `middleware` in Next.js 16):

```typescript
export { auth as proxy } from "@/auth";

export const config = {
  matcher: ["/((?!api/auth|api/cart|_next/static|_next/image|favicon.ico|login|products).+)"],
};
```

**Protected:** all routes by default  
**Public:** `/login`, `/products` (catalog browsing), `/api/auth/*` (Auth.js callbacks), `/api/cart` (cart widget), static assets

Unauthenticated requests are redirected to `/login`. Individual pages additionally check `session.scope` for finer-grained access (e.g., admin-only pages check for `products:write`).

---

## 6. Admin vs. Customer Role Discrimination

**Discriminator:** the `products:write` scope. Only users with the `admin` composite role in Keycloak receive this scope. Customer users receive `products:read` only.

```typescript
const isAdmin = session?.scope?.split(" ").includes("products:write") ?? false;
```

This check is used consistently across Server Components and BFF Route Handlers.

| Feature | Customer | Admin |
|---------|----------|-------|
| Browse products | ✅ | ✅ |
| Add to cart | ✅ | ❌ (hidden + no `cart:read/write` scope) |
| Place orders | ✅ | ❌ |
| Write reviews | ✅ | ❌ |
| Delete own reviews | ✅ | ❌ |
| Delete any review | ❌ | ✅ |
| Create/edit products | ❌ | ✅ |
| View own orders | ✅ (`/orders`) | — |
| View all orders | ❌ | ✅ (`/admin/orders`) |
| Update order status | ❌ | ✅ |
| View all users | ❌ | ✅ (`/admin/users`) |
| Admin dashboard | ❌ | ✅ (`/admin`) |
| Nav: "Orders" link | `/orders` | `/admin/orders` |
| Nav: cart icon | ✅ | ❌ |

---

## 7. Page Inventory

### Customer Pages

| Route | Type | Auth | Description |
|-------|------|------|-------------|
| `/` | Server | public | Landing — redirects to `/products` |
| `/home` | Server | required | Post-login landing |
| `/products` | Server | public (token optional) | Product catalog with add-to-cart |
| `/cart` | Server | required | Shopping cart with checkout |
| `/orders` | Server | required | Current user's order list (redirects admin to `/admin/orders`) |
| `/orders/[id]` | Server | required | Order detail; shows `OrderStatusEditor` for admin |
| `/profile` | Server | required | User profile view/edit |
| `/reviews/[productId]` | Server | required | Product reviews + submit form |
| `/login` | Client | public | Keycloak sign-in trigger |

### Admin Pages

| Route | Type | Auth | Description |
|-------|------|------|-------------|
| `/admin` | Server | `products:write` | Admin dashboard with navigation cards |
| `/admin/products/new` | Server | `products:write` | Create new product |
| `/admin/products/[id]/edit` | Server | `products:write` | Edit existing product |
| `/admin/orders` | Server | `products:write` | All platform orders with inline status editor |
| `/admin/users` | Server | `products:write` | All registered users |

---

## 8. BFF Route Handlers (API Routes)

All client components that need to mutate data call a BFF Route Handler in `src/app/api/`. The handlers attach the `Authorization: Bearer` header server-side before forwarding to the appropriate microservice.

| BFF Route | Method | Upstream | Purpose |
|-----------|--------|----------|---------|
| `/api/cart/items/[productId]` | `POST`/`DELETE` | cart-service | Add/remove cart item |
| `/api/checkout` | `POST` | cart-service `/cart/checkout` | Convert cart to order |
| `/api/orders/[id]/confirm` | `POST` | order-service | Confirm a pending order |
| `/api/orders/[id]/status` | `PUT` | order-service | Update order status (admin) |
| `/api/orders/delivered` | `GET` | order-service + user-service | Delivered orders for a product (review form) |
| `/api/products` | `POST` | product-service | Create product (admin) |
| `/api/products/[id]` | `PUT` | product-service | Update product (admin) |
| `/api/reviews` | `POST` | reviews-service | Submit review |
| `/api/reviews/[id]` | `DELETE` | reviews-service | Delete review |
| `/api/admin/orders` | `GET` | order-service | All orders (admin) |
| `/api/admin/users` | `GET` | user-service | All users (admin) |

---

## 9. API Utility (`src/lib/api.ts`)

Two server-side fetch helpers used by Server Components and Route Handlers:

**`apiFetch(service, path, init?)`**  
- Requires an active, error-free session — throws if absent  
- Always sets `Authorization: Bearer {accessToken}`  
- Always sets `cache: "no-store"` (user-specific data must not be cached)

**`publicFetch(service, path, init?)`**  
- Does not require a session  
- Attaches the Bearer token only when a valid session exists  
- Used for the product catalog (publicly browsable but richer when authenticated)

Service base URLs are resolved from environment variables (`PRODUCTS_SERVICE_URL`, `ORDERS_SERVICE_URL`, etc.) with localhost defaults for development.

---

## 10. Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `AUTH_SECRET` | Auth.js cookie encryption key | — (required) |
| `AUTH_URL` | Public base URL of the BFF | `http://localhost:3001` |
| `AUTH_KEYCLOAK_ID` | Keycloak client ID | `e-commerce-web` |
| `AUTH_KEYCLOAK_SECRET` | Keycloak client secret | — (required) |
| `AUTH_KEYCLOAK_ISSUER` | Keycloak realm URL | — (required) |
| `PRODUCTS_SERVICE_URL` | product-service base URL | `http://localhost:8081` |
| `ORDERS_SERVICE_URL` | order-service base URL | `http://localhost:8082` |
| `REVIEWS_SERVICE_URL` | reviews-service base URL | `http://localhost:8083` |
| `USERS_SERVICE_URL` | user-service base URL | `http://localhost:8085` |
| `CART_SERVICE_URL` | cart-service base URL | `http://localhost:8086` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP HTTP base URL for traces | `http://localhost:4318` |
| `OTEL_SERVICE_NAME` | Service name reported in traces | `frontend-service` |

---

## 11. Observability — OpenTelemetry

### 11.1 Approach

Next.js 16 has a stable instrumentation hook (`src/instrumentation.ts`) that runs once at server startup on the Node.js runtime. The `@vercel/otel` package wires up the OpenTelemetry SDK there with zero boilerplate.

**What is automatically instrumented (server-side only):**
- Every incoming HTTP request (pages, Route Handlers, Server Actions) → a root server span
- Every outbound `fetch()` call → a child span with `traceparent` header injected

The `traceparent` header injection is the key integration point: `apiFetch()` and `publicFetch()` in `src/lib/api.ts` use the global `fetch`, so the OTel-patched version automatically propagates the W3C trace context to all microservices. In Tempo you see the full end-to-end trace: `GET /products` (frontend) → `GET /api/v1/products` (product-service) → JPA/MongoDB span.

**Browser-side (client components)** is not instrumented — it requires `@opentelemetry/sdk-trace-web` and a CORS-enabled OTLP endpoint, which adds complexity without proportionate observability value for a BFF architecture.

### 11.2 Implementation

**`src/instrumentation.ts`** (loaded automatically by Next.js 16):
```typescript
import { registerOTel } from "@vercel/otel";

export function register() {
  registerOTel({
    serviceName: process.env.OTEL_SERVICE_NAME ?? "frontend-service",
  });
}
```

`registerOTel` reads `OTEL_EXPORTER_OTLP_ENDPOINT` from the environment and configures an OTLP HTTP exporter — the same convention used by all Spring Boot services.

### 11.3 Configuration

Local development (LGTM all-in-one container on `:4318`):
```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

Kubernetes staging (`otel-collector` in the `monitoring` namespace):
```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector.monitoring:4318
```

### 11.4 Dependency

```json
"@vercel/otel": "^2.1.2"
```

No additional OTel dependencies are needed — `@vercel/otel` bundles the required `@opentelemetry/sdk-node` and OTLP exporter packages.

---

## 12. Source Layout

```
frontend-service/
├── .env.local.example     ← copy to .env.local; fill AUTH_SECRET + AUTH_KEYCLOAK_SECRET + AUTH_URL
├── Dockerfile             ← multi-stage build; output:standalone for k8s
├── next.config.ts         ← output:"standalone" enabled
├── package.json           ← Next.js 16, next-auth 5.0.0-beta (Auth.js v5), React 19; dev port 3001
└── src/
    ├── auth.ts            ← Auth.js v5: Keycloak provider, JWT/session callbacks (stores id_token),
    │                         token refresh, id_token forwarded for federated logout only
    ├── proxy.ts           ← protects all routes (redirects to Keycloak login if no session)
    ├── instrumentation.ts ← @vercel/otel bootstrap (runs once at server startup)
    ├── types/
    │   └── next-auth.d.ts ← Session augmented with accessToken, idToken, scope, error fields
    ├── lib/
    │   └── api.ts         ← apiFetch() / publicFetch() — forwards Bearer JWT server-side to microservices
    └── app/
        ├── layout.tsx     ← root layout with Nav server component
        ├── page.tsx       ← home page (welcome + links)
        ├── actions/
        │   └── auth.ts    ← federatedSignOut() server action — clears local Auth.js cookie then
        │                     redirects to Keycloak end-session endpoint with id_token_hint
        ├── api/
        │   ├── auth/[...nextauth]/route.ts  ← Auth.js OIDC callback handler
        │   ├── cart/items/[productId]/route.ts
        │   ├── checkout/route.ts
        │   ├── orders/[id]/confirm/route.ts
        │   ├── orders/[id]/status/route.ts
        │   ├── orders/delivered/route.ts
        │   ├── products/route.ts
        │   ├── products/[id]/route.ts
        │   ├── reviews/route.ts
        │   ├── reviews/[id]/route.ts
        │   └── admin/
        │       ├── orders/route.ts  ← guards products:write scope; proxies to order-service
        │       └── users/route.ts   ← guards products:write scope; proxies to user-service
        ├── components/
        │   └── nav.tsx    ← server component: conditional nav links; sign-out triggers federatedSignOut
        ├── login/
        │   └── page.tsx   ← custom sign-in page
        ├── admin/
        │   ├── page.tsx          ← admin dashboard (redirects non-admin to /products)
        │   ├── products/new/page.tsx
        │   ├── products/[id]/edit/page.tsx
        │   ├── orders/page.tsx   ← all-orders table with status editor (admin only)
        │   └── users/page.tsx    ← all-users table (admin only)
        ├── products/
        │   └── page.tsx   ← product catalog; Add to Cart hidden for admin
        ├── cart/
        │   └── page.tsx   ← shopping cart with checkout
        ├── orders/
        │   ├── page.tsx   ← current user's orders; redirects admin to /admin/orders
        │   └── [id]/page.tsx
        ├── profile/
        │   └── page.tsx
        └── reviews/
            └── [productId]/page.tsx
```
