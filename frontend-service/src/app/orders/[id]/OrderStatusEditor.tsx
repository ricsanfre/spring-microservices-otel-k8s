"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

const STATUSES = ["PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED"] as const;

interface Props {
  orderId: string;
  currentStatus: string;
}

export function OrderStatusEditor({ orderId, currentStatus }: Props) {
  const router = useRouter();
  const [status, setStatus] = useState(currentStatus);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSave() {
    if (status === currentStatus) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(`/api/orders/${orderId}/status`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data?.detail ?? data?.message ?? `Failed (HTTP ${res.status})`);
      } else {
        router.refresh();
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unexpected error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", flexWrap: "wrap" }}>
      <select
        value={status}
        onChange={(e) => setStatus(e.target.value)}
        style={{
          padding: "0.25rem 0.5rem",
          borderRadius: "0.25rem",
          border: "1px solid #d1d5db",
          fontSize: "0.875rem",
          background: "#f9fafb",
        }}
      >
        {STATUSES.map((s) => (
          <option key={s} value={s}>{s}</option>
        ))}
      </select>
      <button
        onClick={handleSave}
        disabled={saving || status === currentStatus}
        style={{
          padding: "0.25rem 0.75rem",
          fontSize: "0.8rem",
          borderRadius: "0.25rem",
          border: "none",
          background: status !== currentStatus ? "#0ea5e9" : "#cbd5e1",
          color: "white",
          cursor: status !== currentStatus ? "pointer" : "default",
        }}
      >
        {saving ? "Saving…" : "Update"}
      </button>
      {error && <span style={{ color: "#ef4444", fontSize: "0.8rem" }}>{error}</span>}
    </div>
  );
}
