export { auth as middleware } from "@/auth";

export const config = {
  // Protect all routes except static assets, auth callback endpoints, and the login page
  matcher: ["/((?!api/auth|_next/static|_next/image|favicon.ico|login).*)"],
};
