import { NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";
import { auth } from "@/auth";

/**
 * GET /api/admin/users
 *
 * Returns all registered user profiles (admin only).
 * Proxies to GET /api/v1/users on user-service.
 */
export async function GET() {
  const session = await auth();
  if (!session?.scope?.split(" ").includes("products:write")) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  const res = await apiFetch("users", "/api/v1/users");
  if (!res.ok) {
    return NextResponse.json({ error: "Failed to fetch users" }, { status: res.status });
  }
  const data = await res.json();
  return NextResponse.json(data);
}
