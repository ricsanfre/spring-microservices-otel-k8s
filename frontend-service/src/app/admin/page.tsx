import Link from "next/link";
import { auth } from "@/auth";
import { redirect } from "next/navigation";

export default async function AdminDashboardPage() {
  const session = await auth();
  if (!session?.scope?.split(" ").includes("products:write")) {
    redirect("/products");
  }

  return (
    <div style={{ maxWidth: "800px", margin: "0 auto", padding: "2rem 1rem" }}>
      <h1>Admin Dashboard</h1>
      <p style={{ color: "#64748b", marginBottom: "2rem" }}>Manage the e-commerce platform.</p>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: "1rem" }}>
        <Link href="/admin/products/new" style={cardStyle}>
          <span style={iconStyle}>📦</span>
          <span style={labelStyle}>Products</span>
          <span style={descStyle}>Create &amp; edit products</span>
        </Link>

        <Link href="/admin/orders" style={cardStyle}>
          <span style={iconStyle}>🛒</span>
          <span style={labelStyle}>Orders</span>
          <span style={descStyle}>View &amp; update all orders</span>
        </Link>

        <Link href="/admin/users" style={cardStyle}>
          <span style={iconStyle}>👥</span>
          <span style={labelStyle}>Users</span>
          <span style={descStyle}>Browse registered users</span>
        </Link>
      </div>
    </div>
  );
}

const cardStyle: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: "0.5rem",
  padding: "1.5rem 1rem",
  background: "#1e293b",
  borderRadius: "0.75rem",
  textDecoration: "none",
  color: "inherit",
  border: "1px solid #334155",
  transition: "background 0.2s",
};

const iconStyle: React.CSSProperties = {
  fontSize: "2rem",
};

const labelStyle: React.CSSProperties = {
  fontSize: "1.1rem",
  fontWeight: 600,
};

const descStyle: React.CSSProperties = {
  fontSize: "0.8rem",
  color: "#94a3b8",
  textAlign: "center",
};
