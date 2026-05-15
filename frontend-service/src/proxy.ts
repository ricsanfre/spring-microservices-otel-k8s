export { auth as proxy } from "@/auth";

export const config = {
  // Protect all routes except static assets, auth callbacks, login, public root, and product browsing
  matcher: ["/((?!api/auth|api/cart|_next/static|_next/image|favicon.ico|login|products).+)"],
};
