import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ productId: string }> },
) {
  const { productId } = await params;
  const body = await request.json();

  const upstream = await apiFetch(`cart`, `/api/v1/cart/items/${productId}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });

  const data = await upstream.json().catch(() => null);
  return NextResponse.json(data, { status: upstream.status });
}

export async function DELETE(
  _request: NextRequest,
  { params }: { params: Promise<{ productId: string }> },
) {
  const { productId } = await params;

  const upstream = await apiFetch(`cart`, `/api/v1/cart/items/${productId}`, {
    method: "DELETE",
  });

  if (upstream.status === 200) {
    const data = await upstream.json().catch(() => null);
    return NextResponse.json(data, { status: 200 });
  }
  return new NextResponse(null, { status: upstream.status });
}
