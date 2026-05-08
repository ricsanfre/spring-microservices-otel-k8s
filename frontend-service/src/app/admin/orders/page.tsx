import Link from "next/link";
import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";
import { OrderStatusEditor } from "@/app/orders/[id]/OrderStatusEditor";

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

export default async function AdminOrdersPage() {
  const session = await auth();
  if (!session?.scope?.split(" ").includes("products:write")) {
    redirect("/products");
  }

  let orders: Order[] = [];
  let error: string | null = null;

  try {
    const res = await apiFetch("orders", "/api/v1/orders");
    if (!res.ok) {
      error = `Failed to load orders (HTTP ${res.status})`;
    } else {
      orders = await res.json();
    }
  } catch (err) {
    error = err instanceof Error ? err.message : "Unexpected error";
  }

  return (
    <div style={{ maxWidth: "1000px", margin: "0 auto", padding: "1rem" }}>
      <Link href="/admin" style={{ color: "#64748b", fontSize: "0.875rem", textDecoration: "none" }}>
        ← Admin Dashboard
      </Link>
      <h1 style={{ marginTop: "0.75rem" }}>All Orders</h1>

      {error && <p style={{ color: "#ef4444" }}>{error}</p>}

      {orders.length === 0 && !error && <p style={{ color: "#64748b" }}>No orders found.</p>}

      {orders.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse", marginTop: "1rem", fontSize: "0.875rem" }}>
          <thead>
            <tr style={{ borderBottom: "1px solid #334155", textAlign: "left" }}>
              <th style={th}>Order ID</th>
              <th style={th}>User ID</th>
              <th style={th}>Items</th>
              <th style={th}>Total</th>
              <th style={th}>Created</th>
              <th style={th}>Status</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((order) => (
              <tr key={order.id} style={{ borderBottom: "1px solid #1e293b" }}>
                <td style={td}>
                  <Link href={`/orders/${order.id}`} style={{ color: "#38bdf8", textDecoration: "none", fontFamily: "monospace", fontSize: "0.75rem" }}>
                    {order.id.slice(0, 8)}…
                  </Link>
                </td>
                <td style={{ ...td, fontFamily: "monospace", fontSize: "0.75rem", color: "#94a3b8" }}>
                  {order.userId.slice(0, 8)}…
                </td>
                <td style={td}>{order.items.length}</td>
                <td style={td}>${order.totalAmount.toFixed(2)}</td>
                <td style={td}>{new Date(order.createdAt).toLocaleDateString()}</td>
                <td style={td}>
                  <OrderStatusEditor orderId={order.id} currentStatus={order.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

const th: React.CSSProperties = {
  padding: "0.5rem 0.75rem",
  color: "#94a3b8",
  fontWeight: 600,
};

const td: React.CSSProperties = {
  padding: "0.5rem 0.75rem",
  verticalAlign: "middle",
};
