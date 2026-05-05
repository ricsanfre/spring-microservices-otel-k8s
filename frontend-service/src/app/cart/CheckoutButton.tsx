"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export function CheckoutButton() {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleCheckout() {
    setPending(true);
    setError(null);
    try {
      const res = await fetch("/api/checkout", { method: "POST" });
      if (res.status === 400) {
        setError("Your cart is empty.");
        return;
      }
      if (!res.ok) {
        setError(`Checkout failed (HTTP ${res.status}).`);
        return;
      }
      const order: { id: string } = await res.json();
      router.push(`/orders/${order.id}/review`);
    } catch {
      setError("Unexpected error during checkout.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div>
      {error && <p className="error" style={{ marginBottom: "0.5rem" }}>{error}</p>}
      <button
        className="btn-checkout"
        onClick={handleCheckout}
        disabled={pending}
      >
        {pending ? "Preparing order…" : "Checkout"}
      </button>
    </div>
  );
}
