export { auth as middleware } from "@/auth";

export const config = {
  // Protect all routes except static assets and the auth callback endpoints
  matcher: ["/((?!api/auth|_next/static|_next/image|favicon.ico).*)"],
};
