import Link from "next/link";
import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";

interface UserProfile {
  id: string;
  username: string;
  email: string;
  firstName?: string;
  lastName?: string;
  createdAt: string;
}

export default async function AdminUsersPage() {
  const session = await auth();
  if (!session?.scope?.split(" ").includes("products:write")) {
    redirect("/products");
  }

  let users: UserProfile[] = [];
  let error: string | null = null;

  try {
    const res = await apiFetch("users", "/api/v1/users");
    if (!res.ok) {
      error = `Failed to load users (HTTP ${res.status})`;
    } else {
      users = await res.json();
    }
  } catch (err) {
    error = err instanceof Error ? err.message : "Unexpected error";
  }

  return (
    <div style={{ maxWidth: "1000px", margin: "0 auto", padding: "1rem" }}>
      <Link href="/admin" style={{ color: "#64748b", fontSize: "0.875rem", textDecoration: "none" }}>
        ← Admin Dashboard
      </Link>
      <h1 style={{ marginTop: "0.75rem" }}>All Users</h1>

      {error && <p style={{ color: "#ef4444" }}>{error}</p>}

      {users.length === 0 && !error && <p style={{ color: "#64748b" }}>No users found.</p>}

      {users.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse", marginTop: "1rem", fontSize: "0.875rem" }}>
          <thead>
            <tr style={{ borderBottom: "1px solid #334155", textAlign: "left" }}>
              <th style={th}>ID</th>
              <th style={th}>Username</th>
              <th style={th}>Email</th>
              <th style={th}>Name</th>
              <th style={th}>Registered</th>
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <tr key={user.id} style={{ borderBottom: "1px solid #1e293b" }}>
                <td style={{ ...td, fontFamily: "monospace", fontSize: "0.75rem", color: "#94a3b8" }}>
                  {user.id.slice(0, 8)}…
                </td>
                <td style={td}>{user.username}</td>
                <td style={td}>{user.email}</td>
                <td style={td}>
                  {[user.firstName, user.lastName].filter(Boolean).join(" ") || "—"}
                </td>
                <td style={td}>{new Date(user.createdAt).toLocaleDateString()}</td>
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
