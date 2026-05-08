import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

/** PUT /api/products/[id] — update a product (admin only) */
export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const body = await request.json();
  const res = await apiFetch("products", `/api/v1/products/${id}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  return NextResponse.json(data, { status: res.status });
}
