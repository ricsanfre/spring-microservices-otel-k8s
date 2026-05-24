import NextAuth from "next-auth";
import Keycloak from "next-auth/providers/keycloak";
import type { JWT } from "next-auth/jwt";

const USERS_SERVICE_URL = process.env.USERS_SERVICE_URL ?? "http://localhost:8085";

// Keycloak URL strategy — two different base URLs serve different purposes:
//
//   AUTH_KEYCLOAK_ISSUER (external, e.g. https://keycloak.local.test/realms/e-commerce)
//     • Must match the "iss" claim in every JWT issued by Keycloak.
//     • Used for: provider.issuer (iss claim validation), authorization endpoint
//       (browser redirect — user must be able to reach this URL).
//
//   AUTH_KEYCLOAK_INTERNAL_ISSUER (internal, e.g. http://keycloak-service.keycloak.svc.cluster.local:8080/realms/e-commerce)
//     • Used for server-side back-channel calls that never leave the cluster.
//     • Used for: token endpoint (code exchange), userinfo endpoint, token refresh.
//     • Absent in local dev — falls back to AUTH_KEYCLOAK_ISSUER.
//
// Why this works without OIDC discovery:
//   Auth.js (oauth4webapi) skips the discoveryRequest() call in both
//   authorization-url.js and callback.js when all three of authorization.url,
//   token, and userinfo are set to real (non-authjs.dev) URLs.  No network
//   call to the external Keycloak hostname is ever made server-side.
//
// Why backchannelDynamic:true is NOT needed for this approach:
//   We bypass discovery entirely and point each endpoint explicitly, so the
//   discovery document's endpoint rewrites are irrelevant here.
const KEYCLOAK_EXTERNAL_ISSUER = process.env.AUTH_KEYCLOAK_ISSUER!;
const KEYCLOAK_INTERNAL_ISSUER =
  process.env.AUTH_KEYCLOAK_INTERNAL_ISSUER ?? KEYCLOAK_EXTERNAL_ISSUER;

async function triggerLazyRegistration(accessToken: string): Promise<void> {
  try {
    await fetch(`${USERS_SERVICE_URL}/api/v1/users/me`, {
      cache: "no-store",
      headers: { Authorization: `Bearer ${accessToken}` },
    });
  } catch {
    // Non-fatal: user will be registered on next profile access
  }
}

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({
      // Auth.js v5 auto-reads AUTH_KEYCLOAK_ID / AUTH_KEYCLOAK_SECRET from env.
      // issuer is read from AUTH_KEYCLOAK_ISSUER automatically — it is the
      // external URL and must match the JWT "iss" claim.

      // authorization: explicit URL (not just params) is required so that
      // oauth4webapi skips OIDC discovery in the sign-in step.
      // The browser is redirected here — must be the external (Envoy) URL.
      authorization: {
        url: `${KEYCLOAK_EXTERNAL_ISSUER}/protocol/openid-connect/auth`,
        params: {
          scope:
            "openid profile email users:read orders:read orders:write products:read products:write reviews:read reviews:write cart:read cart:write",
        },
      },

      // token + userinfo: setting both to real URLs tells oauth4webapi to skip
      // OIDC discovery in the callback step and use these endpoints directly.
      // Internal URL is used so these server-side calls never leave the cluster.
      token: `${KEYCLOAK_INTERNAL_ISSUER}/protocol/openid-connect/token`,
      userinfo: `${KEYCLOAK_INTERNAL_ISSUER}/protocol/openid-connect/userinfo`,
    }),
  ],

  pages: {
    signIn: "/login",
  },

  callbacks: {
    // Persist the access_token and refresh_token in the encrypted JWT session cookie
    jwt({ token, account }) {
      if (account) {
        // First login: store tokens and trigger lazy user registration
        const newToken = {
          ...token,
          accessToken: account.access_token,
          refreshToken: account.refresh_token,
          expiresAt: account.expires_at,
          scope: account.scope,
          // id_token is stored solely for federated logout (id_token_hint parameter).
          // It is NOT forwarded to microservices — only the access_token is used for that.
          idToken: account.id_token,
        };
        void triggerLazyRegistration(account.access_token!);
        return newToken;
      }

      // Subsequent requests: return the token as-is if still valid
      if (Date.now() < (token.expiresAt as number) * 1000) {
        return token;
      }

      // Access token expired — refresh it
      return refreshAccessToken(token);
    },

    // Expose the access_token on the session object so Server Components can forward it
    session({ session, token }) {
      session.accessToken = token.accessToken as string;
      session.idToken = token.idToken as string | undefined;
      session.scope = token.scope as string | undefined;
      if (token.error) {
        session.error = token.error as string;
      }
      return session;
    },
  },
});

async function refreshAccessToken(token: JWT): Promise<JWT> {
  // Use the internal Keycloak URL (falls back to external in local dev).
  const tokenEndpoint = `${KEYCLOAK_INTERNAL_ISSUER}/protocol/openid-connect/token`;

  try {
    const response = await fetch(tokenEndpoint, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: process.env.AUTH_KEYCLOAK_ID!,
        client_secret: process.env.AUTH_KEYCLOAK_SECRET!,
        refresh_token: token.refreshToken as string,
      }),
    });

    const refreshed = await response.json();

    if (!response.ok) {
      throw refreshed;
    }

    return {
      ...token,
      accessToken: refreshed.access_token,
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      expiresAt: Math.floor(Date.now() / 1000) + (refreshed.expires_in as number),
      error: undefined,
    };
  } catch {
    return { ...token, error: "RefreshAccessTokenError" };
  }
}
