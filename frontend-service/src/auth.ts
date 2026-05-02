import NextAuth from "next-auth";
import Keycloak from "next-auth/providers/keycloak";
import type { JWT } from "next-auth/jwt";

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({
      // Auth.js v5 auto-reads AUTH_KEYCLOAK_ID / AUTH_KEYCLOAK_SECRET / AUTH_KEYCLOAK_ISSUER
      // from the environment — no need to pass them explicitly.
      // Request all optional scopes the BFF needs so they appear in the access_token.
      authorization: {
        params: {
          scope:
            "openid profile email users:read orders:read products:read reviews:read cart:read cart:write",
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
        // First login: store tokens
        return {
          ...token,
          accessToken: account.access_token,
          refreshToken: account.refresh_token,
          expiresAt: account.expires_at,
        };
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
