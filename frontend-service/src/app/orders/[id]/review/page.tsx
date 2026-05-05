import Link from "next/link";
import { apiFetch } from "@/lib/api";
import { ConfirmOrderButton } from "./ConfirmOrderButton";

interface OrderItemResponse {
  id: string;
  productId: string;
  quantity: number;
  unitPrice: number;
}

interface OrderResponse {
  id: string;
  userId: string;
  status: string;
  items: OrderItemResponse[];
  totalAmount: number;
  createdAt: string;
}

export default async function OrderReviewPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  let order: OrderResponse | null = null;
  let error: string | null = null;

  try {
    const res = await apiFetch("orders", `/api/v1/orders/${id}`);
    if (!res.ok) {
      error = `Failed to load order (HTTP ${res.status})`;
    } else {
      order = await res.json();
    }
  } catch (err) {
    error = err instanceof Error ? err.message : "Unexpected error";
  }

  if (error || !order) {
    return (
      <div>
        <h1>Order Review</h1>
        <p className="error">{error ?? "Order not found."}</p>
        <Link href="/cart" className="btn-continue-shopping" style={{ marginTop: "1rem", display: "inline-block" }}>
          ← Back to Cart
        </Link>
      </div>
    );
  }

  if (order.status !== "PENDING") {
    return (
      <div>
        <h1>Order Review</h1>
        <p style={{ color: "#64748b" }}>
          This order is already <span className={`status status-${order.status}`}>{order.status}</span>.
        </p>
        <Link href="/orders" className="btn-continue-shopping" style={{ marginTop: "1rem", display: "inline-block" }}>
          View My Orders
        </Link>
      </div>
    );
  }

  return (
    <div>
      <h1>Review Your Order</h1>
      <p style={{ color: "#475569", fontSize: "0.875rem", marginBottom: "1rem" }}>
        Please review the items below before confirming. Stock is reserved only after confirmation.
      </p>

      <table>
        <thead>
          <tr>
            <th>Product ID</th>
            <th>Unit Price</th>
            <th>Qty</th>
            <th>Line Total</th>
          </tr>
        </thead>
        <tbody>
          {order.items.map((item) => (
            <tr key={item.id}>
              <td>
                <code style={{ fontSize: "0.75rem" }}>{item.productId}</code>
              </td>
              <td>${item.unitPrice.toFixed(2)}</td>
              <td>{item.quantity}</td>
              <td>${(item.unitPrice * item.quantity).toFixed(2)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="cart-summary" style={{ marginTop: "1rem" }}>
        <span>{order.items.length} {order.items.length === 1 ? "item" : "items"}</span>
        <span className="cart-grand-total">
          Total: <strong>${order.totalAmount.toFixed(2)}</strong>
        </span>
      </div>

      <div className="cart-actions">
        <Link href="/cart" className="btn-continue-shopping">
          ← Back to Cart
        </Link>
        <ConfirmOrderButton orderId={order.id} />
      </div>
    </div>
  );
}
