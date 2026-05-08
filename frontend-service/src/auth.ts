import NextAuth from "next-auth";
import Keycloak from "next-auth/providers/keycloak";
import type { JWT } from "next-auth/jwt";

const USERS_SERVICE_URL = process.env.USERS_SERVICE_URL ?? "http://localhost:8085";

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
      // Auth.js v5 auto-reads AUTH_KEYCLOAK_ID / AUTH_KEYCLOAK_SECRET / AUTH_KEYCLOAK_ISSUER
      // from the environment — no need to pass them explicitly.
      // Request all optional scopes the BFF needs so they appear in the access_token.
      authorization: {
        params: {
          scope:
            "openid profile email users:read orders:read orders:write products:read products:write reviews:read reviews:write cart:read cart:write",
        },
      },
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
  const issuer = process.env.AUTH_KEYCLOAK_ISSUER!;
  const tokenEndpoint = `${issuer}/protocol/openid-connect/token`;

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
