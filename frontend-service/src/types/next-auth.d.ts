import type { DefaultSession } from "next-auth";

declare module "next-auth" {
  interface Session extends DefaultSession {
    /** The user's Keycloak access token — forwarded as Bearer on server-side API calls. */
    accessToken: string;
    /** The Keycloak ID token — used for federated logout (id_token_hint). */
    idToken?: string;
    /** Space-separated list of OAuth2 scopes granted to this session. */
    scope?: string;
    /** Set when the refresh token exchange fails — trigger a re-login. */
    error?: string;
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    accessToken?: string;
    refreshToken?: string;
    idToken?: string;
    expiresAt?: number;
    scope?: string;
    error?: string;
  }
}
