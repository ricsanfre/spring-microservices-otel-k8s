import type { DefaultSession } from "next-auth";

declare module "next-auth" {
  interface Session extends DefaultSession {
    /** The user's Keycloak access token — forwarded as Bearer on server-side API calls. */
    accessToken: string;
    /** Set when the refresh token exchange fails — trigger a re-login. */
    error?: string;
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    accessToken?: string;
    refreshToken?: string;
    expiresAt?: number;
    error?: string;
  }
}
