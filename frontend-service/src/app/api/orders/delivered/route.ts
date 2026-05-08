import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

interface OrderItem {
  id: string;
  productId: string;
  quantity: number;
  unitPrice: number;
}

interface Order {
  id: string;
  userId: string;
  status: string;
  items: OrderItem[];
  totalAmount: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * GET /api/orders/delivered?productId={productId}
 *
 * Returns the current user's DELIVERED orders that contain the given product.
 * Used by the ReviewForm to populate the order dropdown.
 */
export async function GET(request: NextRequest) {
  const productId = request.nextUrl.searchParams.get("productId");
  if (!productId) {
    return NextResponse.json({ error: "productId is required" }, { status: 400 });
  }

  // Step 1: resolve the current user's internal UUID
  const meRes = await apiFetch("users", "/api/v1/users/me");
  if (!meRes.ok) {
    return NextResponse.json({ error: "Failed to resolve user" }, { status: meRes.status });
  }
  const me: { id: string } = await meRes.json();

  // Step 2: fetch all orders for this user
  const ordersRes = await apiFetch("orders", `/api/v1/orders/user/${me.id}`);
  if (!ordersRes.ok) {
    return NextResponse.json({ error: "Failed to fetch orders" }, { status: ordersRes.status });
  }
  const orders: Order[] = await ordersRes.json();

  // Step 3: filter to DELIVERED orders that contain the requested product
  const eligible = orders
    .filter(
      (o) =>
        o.status === "DELIVERED" &&
        o.items.some((item) => item.productId === productId),
    )
    .map((o) => ({ id: o.id, createdAt: o.createdAt }));

  return NextResponse.json(eligible);
}
