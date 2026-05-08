import { NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";
import { auth } from "@/auth";

/**
 * GET /api/admin/orders
 *
 * Returns all orders across all users (admin only).
 * Proxies to GET /api/v1/orders on order-service.
 */
export async function GET() {
  const session = await auth();
  if (!session?.scope?.split(" ").includes("products:write")) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  const res = await apiFetch("orders", "/api/v1/orders");
  if (!res.ok) {
    return NextResponse.json({ error: "Failed to fetch orders" }, { status: res.status });
  }
  const data = await res.json();
  return NextResponse.json(data);
}
