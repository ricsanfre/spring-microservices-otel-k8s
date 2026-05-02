export { auth as middleware } from "@/auth";

export const config = {
  // Protect all routes except static assets, auth callbacks, login, public root, and product browsing
  matcher: ["/((?!api/auth|_next/static|_next/image|favicon.ico|login|products).+)"],
};
