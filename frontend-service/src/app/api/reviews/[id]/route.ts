import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

export async function DELETE(
  _request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;

  const upstream = await apiFetch("reviews", `/api/v1/reviews/${id}`, {
    method: "DELETE",
  });

  return new NextResponse(null, { status: upstream.status });
}
