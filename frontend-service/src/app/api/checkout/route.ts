import { NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

export async function POST() {
  const upstream = await apiFetch("cart", "/api/v1/cart/checkout", {
    method: "POST",
  });

  const data = await upstream.json().catch(() => null);
  return NextResponse.json(data, { status: upstream.status });
}
