"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

interface Props {
  orderId: string;
}

export function ConfirmOrderButton({ orderId }: Props) {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleConfirm() {
    setPending(true);
    setError(null);
    try {
      const res = await fetch(`/api/orders/${orderId}/confirm`, { method: "POST" });
      if (res.status === 409) {
        setError("Insufficient stock for one or more items. Your cart is still intact.");
        return;
      }
      if (res.status === 403) {
        setError("You are not the owner of this order.");
        return;
      }
      if (!res.ok) {
        setError(`Confirmation failed (HTTP ${res.status}). Please try again.`);
        return;
      }
      // Optimistically treat cart as cleared per ADR-013 and navigate to orders list
      router.push("/orders");
    } catch {
      setError("Unexpected error. Please try again.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div>
      {error && <p className="error" style={{ marginBottom: "0.5rem" }}>{error}</p>}
      <button
        className="btn-confirm-order"
        onClick={handleConfirm}
        disabled={pending}
      >
        {pending ? "Confirming…" : "Confirm Order"}
      </button>
    </div>
  );
}
