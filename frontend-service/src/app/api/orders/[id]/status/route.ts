import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

/** PUT /api/orders/[id]/status — update order status (admin only) */
export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const body = await request.json();
  const res = await apiFetch("orders", `/api/v1/orders/${id}/status`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  return NextResponse.json(data, { status: res.status });
}
