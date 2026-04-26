import { auth } from "@/auth";
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
  totalAmount: number;
  createdAt: string;
  items?: OrderItem[];
}

export default async function OrdersPage() {
  const session = await auth();
  let orders: Order[] = [];
  let error: string | null = null;

  try {
    // Resolve the current user's internal ID first
    const userRes = await apiFetch("users", "/api/v1/users/me");
    if (!userRes.ok) {
      error = `Could not resolve user profile (HTTP ${userRes.status})`;
    } else {
      const user: { id: string } = await userRes.json();
      const ordersRes = await apiFetch("orders", `/api/v1/orders/user/${user.id}`);
      if (!ordersRes.ok) {
        error = `Failed to load orders (HTTP ${ordersRes.status})`;
      } else {
        orders = await ordersRes.json();
      }
    }
  } catch (err) {
    error = err instanceof Error ? err.message : "Unexpected error";
  }

  return (
    <div>
      <h1>My Orders</h1>
      <p style={{ color: "#475569", fontSize: "0.875rem", marginBottom: "0.5rem" }}>
        Signed in as {session?.user?.email}
      </p>

      {error && <p className="error">{error}</p>}

      {orders.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Order ID</th>
              <th>Status</th>
              <th>Total</th>
              <th>Placed</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.id}>
                <td>
                  <code style={{ fontSize: "0.75rem" }}>{o.id.slice(0, 8)}…</code>
                </td>
                <td>
                  <span className={`status status-${o.status}`}>{o.status}</span>
                </td>
                <td>${o.totalAmount.toFixed(2)}</td>
                <td>{new Date(o.createdAt).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        !error && (
          <p style={{ color: "#94a3b8", marginTop: "1rem" }}>No orders yet.</p>
        )
      )}
    </div>
  );
}
