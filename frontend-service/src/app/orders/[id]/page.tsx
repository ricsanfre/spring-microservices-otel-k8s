import Link from "next/link";
import { apiFetch, publicFetch } from "@/lib/api";

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
  updatedAt?: string;
}

async function fetchProductName(productId: string): Promise<string> {
  try {
    const res = await publicFetch("products", `/api/v1/products/${productId}`);
    if (res.ok) {
      const p: { name: string } = await res.json();
      return p.name;
    }
  } catch {
    // fall through
  }
  return productId;
}

export default async function OrderDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  let order: Order | null = null;
  let error: string | null = null;
  let productNames: Record<string, string> = {};

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

  if (order) {
    const uniqueIds = [...new Set(order.items.map((i) => i.productId))];
    const names = await Promise.all(uniqueIds.map((pid) => fetchProductName(pid)));
    productNames = Object.fromEntries(uniqueIds.map((pid, idx) => [pid, names[idx]]));
  }

  if (error || !order) {
    return (
      <div>
        <h1>Order Details</h1>
        <p className="error">{error ?? "Order not found."}</p>
        <Link href="/orders" style={{ marginTop: "1rem", display: "inline-block", color: "#0ea5e9" }}>
          ← Back to My Orders
        </Link>
      </div>
    );
  }

  return (
    <div>
      <Link href="/orders" style={{ color: "#64748b", fontSize: "0.875rem", textDecoration: "none" }}>
        ← Back to My Orders
      </Link>

      <h1 style={{ marginTop: "0.75rem" }}>Order Details</h1>

      <div
        style={{
          background: "white",
          borderRadius: "0.5rem",
          padding: "1rem 1.25rem",
          boxShadow: "0 1px 3px rgba(0,0,0,0.1)",
          marginTop: "1rem",
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
          gap: "0.75rem",
        }}
      >
        <div>
          <p style={{ fontSize: "0.75rem", color: "#64748b", marginBottom: "0.2rem" }}>Order ID</p>
          <code style={{ fontSize: "0.8rem" }}>{order.id}</code>
        </div>
        <div>
          <p style={{ fontSize: "0.75rem", color: "#64748b", marginBottom: "0.2rem" }}>Status</p>
          <span className={`status status-${order.status}`}>{order.status}</span>
        </div>
        <div>
          <p style={{ fontSize: "0.75rem", color: "#64748b", marginBottom: "0.2rem" }}>Placed</p>
          <p style={{ fontSize: "0.875rem" }}>{new Date(order.createdAt).toLocaleString()}</p>
        </div>
        {order.updatedAt && (
          <div>
            <p style={{ fontSize: "0.75rem", color: "#64748b", marginBottom: "0.2rem" }}>Last Updated</p>
            <p style={{ fontSize: "0.875rem" }}>{new Date(order.updatedAt).toLocaleString()}</p>
          </div>
        )}
      </div>

      <h2 style={{ marginTop: "1.5rem" }}>Items</h2>

      <table>
        <thead>
          <tr>
            <th>Product</th>
            <th>Unit Price</th>
            <th>Qty</th>
            <th>Line Total</th>
            {order.status === "DELIVERED" && <th></th>}
          </tr>
        </thead>
        <tbody>
          {order.items.map((item) => (
            <tr key={item.id}>
              <td>{productNames[item.productId] ?? item.productId}</td>
              <td>${item.unitPrice.toFixed(2)}</td>
              <td>{item.quantity}</td>
              <td>${(item.unitPrice * item.quantity).toFixed(2)}</td>
              {order.status === "DELIVERED" && (
                <td>
                  <Link
                    href={`/reviews/${item.productId}?orderId=${order.id}`}
                    style={{ color: "#0ea5e9", textDecoration: "none", fontSize: "0.8rem" }}
                  >
                    Write Review →
                  </Link>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      <div className="cart-summary" style={{ marginTop: "1rem" }}>
        <span>
          {order.items.length} {order.items.length === 1 ? "item" : "items"}
        </span>
        <span className="cart-grand-total">
          Total: <strong>${order.totalAmount.toFixed(2)}</strong>
        </span>
      </div>

      {order.status === "PENDING" && (
        <div style={{ marginTop: "1.25rem" }}>
          <Link
            href={`/orders/${order.id}/review`}
            style={{
              display: "inline-block",
              background: "#1e293b",
              color: "#f8fafc",
              padding: "0.5rem 1.25rem",
              borderRadius: "0.375rem",
              textDecoration: "none",
              fontSize: "0.9rem",
            }}
          >
            Review &amp; Confirm Order
          </Link>
        </div>
      )}
    </div>
  );
}
