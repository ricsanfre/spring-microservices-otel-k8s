"use server";

import { auth, signOut } from "@/auth";
import { redirect } from "next/navigation";

/**
 * Federated sign-out — clears both the local BFF session and the Keycloak SSO session.
 *
 * ## Why two steps are required
 *
 * Auth.js manages a **local** encrypted session cookie (`__Secure-next-auth.session-token`).
 * Keycloak independently maintains its own **SSO session** via the `KEYCLOAK_SESSION` browser
 * cookie on the `:8180` origin. Calling `signOut()` alone clears only the local cookie.
 * On the next sign-in, Keycloak sees its own session is still valid and silently re-authenticates
 * the user without showing a login page — making sign-out appear broken.
 *
 * ## Flow
 *
 * 1. Read the current session to retrieve the `id_token` stored at login.
 * 2. Call `signOut({ redirect: false })` — clears the local session cookie without navigating.
 * 3. Redirect the browser to Keycloak's end-session endpoint:
 *      `{ISSUER}/protocol/openid-connect/logout`
 *         `?id_token_hint={idToken}`           ← proves the caller's identity; enables auto-redirect
 *         `&post_logout_redirect_uri={base}/login` ← where Keycloak sends the user after logout
 * 4. Keycloak validates the `id_token_hint`, terminates the SSO session,
 *    deletes its own session cookie, and redirects the browser to `/login`.
 *
 * ## Keycloak client requirement
 *
 * The `post_logout_redirect_uri` value must be listed in `postLogoutRedirectUris` on the
 * `e-commerce-web` Keycloak client (a separate list from `redirectUris`). Without it,
 * Keycloak returns `invalid_redirect_url`.
 *
 * ## Fallback
 *
 * If `idToken` is not present in the session (e.g., session pre-dates the idToken field),
 * only the local cookie is cleared and the user is redirected to `/login`. The Keycloak
 * SSO session persists but will resolve itself once the user logs in again.
 */
export async function federatedSignOut() {
  const session = await auth();
  const idToken = session?.idToken;

  // Clear the local Next.js session cookie
  await signOut({ redirect: false });

  const issuer = process.env.AUTH_KEYCLOAK_ISSUER!;
  const baseUrl = process.env.AUTH_URL ?? "http://localhost:3001";
  const postLogoutRedirectUri = encodeURIComponent(`${baseUrl}/login`);

  if (idToken) {
    // Full federated logout — Keycloak ends the SSO session
    redirect(
      `${issuer}/protocol/openid-connect/logout?id_token_hint=${idToken}&post_logout_redirect_uri=${postLogoutRedirectUri}`,
    );
  } else {
    // Fallback: id_token not available, just go to login
    redirect("/login");
  }
}
