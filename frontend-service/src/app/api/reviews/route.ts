import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

export async function POST(request: NextRequest) {
  const body = await request.json();

  const upstream = await apiFetch("reviews", "/api/v1/reviews", {
    method: "POST",
    body: JSON.stringify(body),
  });

  const data = await upstream.json().catch(() => null);
  return NextResponse.json(data, { status: upstream.status });
}
