import { NextRequest, NextResponse } from "next/server";
import { publicFetch } from "@/lib/api";

export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ productId: string }> },
) {
  const { productId } = await params;

  const upstream = await publicFetch(
    "reviews",
    `/api/v1/reviews/product/${productId}`,
  );

  const data = await upstream.json().catch(() => []);
  return NextResponse.json(data, { status: upstream.status });
}
