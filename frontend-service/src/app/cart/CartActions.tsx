"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";

interface Props {
  productId: string;
  productName: string;
  price: number;
  quantity: number;
}

export function CartActions({ productId, productName, price, quantity }: Props) {
  const router = useRouter();
  const [isPending, startTransition] = useTransition();

  async function upsert(newQty: number) {
    await fetch(`/api/cart/items/${productId}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ productName, price, quantity: newQty }),
    });
    startTransition(() => router.refresh());
  }

  async function remove() {
    await fetch(`/api/cart/items/${productId}`, { method: "DELETE" });
    startTransition(() => router.refresh());
  }

  return (
    <span className="qty-control" aria-busy={isPending}>
      <button
        className="qty-btn"
        onClick={() => (quantity > 1 ? upsert(quantity - 1) : remove())}
        disabled={isPending}
        aria-label="Decrease quantity"
      >
        −
      </button>
      <span className="qty-value">{quantity}</span>
      <button
        className="qty-btn"
        onClick={() => upsert(quantity + 1)}
        disabled={isPending}
        aria-label="Increase quantity"
      >
        +
      </button>
      <button
        className="qty-btn qty-btn--remove"
        onClick={remove}
        disabled={isPending}
        aria-label="Remove item"
        title="Remove"
      >
        🗑
      </button>
    </span>
  );
}
