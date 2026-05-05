"use client";

import { useState } from "react";

interface Props {
  productId: string;
  productName: string;
  price: number;
}

export function AddToCartButton({ productId, productName, price }: Props) {
  const [pending, setPending] = useState(false);
  const [added, setAdded] = useState(false);

  async function handleAddToCart() {
    setPending(true);
    try {
      await fetch(`/api/cart/items/${productId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ productName, price, quantity: 1 }),
      });
      setAdded(true);
      setTimeout(() => setAdded(false), 1500);
    } finally {
      setPending(false);
    }
  }

  return (
    <button
      className="add-to-cart-btn"
      onClick={handleAddToCart}
      disabled={pending}
      aria-label={`Add ${productName} to cart`}
      title="Add to cart"
    >
      {added ? (
        <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      ) : (
        <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <circle cx="9" cy="21" r="1" />
          <circle cx="20" cy="21" r="1" />
          <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
        </svg>
      )}
    </button>
  );
}
