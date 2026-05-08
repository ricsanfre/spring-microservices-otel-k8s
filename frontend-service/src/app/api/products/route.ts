import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

/** POST /api/products — create a new product (admin only) */
export async function POST(request: NextRequest) {
  const body = await request.json();
  const res = await apiFetch("products", "/api/v1/products", {
    method: "POST",
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  return NextResponse.json(data, { status: res.status });
}
