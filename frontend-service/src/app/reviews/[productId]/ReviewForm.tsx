"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

interface EligibleOrder {
  id: string;
  createdAt: string;
}

interface Props {
  productId: string;
  /** Pre-selected when navigating from an order detail page */
  orderId?: string;
}

export function ReviewForm({ productId, orderId: defaultOrderId }: Props) {
  const router = useRouter();
  const [rating, setRating] = useState(0);
  const [hovered, setHovered] = useState(0);
  const [orderId, setOrderId] = useState(defaultOrderId ?? "");
  const [comment, setComment] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  const [eligibleOrders, setEligibleOrders] = useState<EligibleOrder[] | null>(null);
  const [loadingOrders, setLoadingOrders] = useState(true);

  // Fetch DELIVERED orders that contain this product
  useEffect(() => {
    let cancelled = false;
    setLoadingOrders(true);
    fetch(`/api/orders/delivered?productId=${encodeURIComponent(productId)}`)
      .then((r) => r.json())
      .then((data: EligibleOrder[]) => {
        if (cancelled) return;
        setEligibleOrders(data);
        // Pre-select the first order if none was passed via props
        if (!defaultOrderId && data.length === 1) {
          setOrderId(data[0].id);
        }
      })
      .catch(() => {
        if (!cancelled) setEligibleOrders([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingOrders(false);
      });
    return () => { cancelled = true; };
  }, [productId, defaultOrderId]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (rating === 0) {
      setError("Please select a rating.");
      return;
    }
    if (!orderId) {
      setError("Please select an order.");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const res = await fetch("/api/reviews", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          productId,
          orderId,
          rating,
          comment: comment.trim() || undefined,
        }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data?.detail ?? data?.message ?? `Failed to submit review (HTTP ${res.status})`);
      } else {
        setSubmitted(true);
        router.refresh();
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unexpected error");
    } finally {
      setSubmitting(false);
    }
  }

  if (submitted) {
    return (
      <div className="review-form review-form--success">
        <p>✓ Your review was submitted. Thank you!</p>
      </div>
    );
  }

  const noEligibleOrders = !loadingOrders && eligibleOrders?.length === 0;
  const canSubmit = !loadingOrders && !noEligibleOrders && !!orderId;

  return (
    <form onSubmit={handleSubmit} className="review-form">
      <h3>Write a Review</h3>

      <div className="form-field">
        <label htmlFor="orderSelect">Order</label>
        {loadingOrders ? (
          <p className="form-hint">Loading your orders…</p>
        ) : noEligibleOrders ? (
          <p className="form-hint">
            You have no delivered orders containing this product.
          </p>
        ) : (
          <select
            id="orderSelect"
            value={orderId}
            onChange={(e) => setOrderId(e.target.value)}
            required
          >
            {!defaultOrderId && eligibleOrders!.length > 1 && (
              <option value="">— Select a delivered order —</option>
            )}
            {eligibleOrders!.map((o) => (
              <option key={o.id} value={o.id}>
                {new Date(o.createdAt).toLocaleDateString(undefined, {
                  year: "numeric",
                  month: "short",
                  day: "numeric",
                })}{" "}
                — {o.id.slice(0, 8)}…
              </option>
            ))}
          </select>
        )}
      </div>

      <div className="form-field">
        <label>Rating</label>
        <div className="star-rating">
          {[1, 2, 3, 4, 5].map((star) => (
            <button
              key={star}
              type="button"
              className={`star-btn ${star <= (hovered || rating) ? "star-filled" : ""}`}
              onMouseEnter={() => setHovered(star)}
              onMouseLeave={() => setHovered(0)}
              onClick={() => setRating(star)}
              aria-label={`${star} star`}
            >
              ★
            </button>
          ))}
        </div>
      </div>

      <div className="form-field">
        <label htmlFor="comment">
          Comment <span style={{ color: "#94a3b8" }}>(optional)</span>
        </label>
        <textarea
          id="comment"
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          rows={3}
          placeholder="Share your thoughts about this product…"
          disabled={noEligibleOrders}
        />
      </div>

      {error && <p className="error" style={{ marginBottom: "0.5rem" }}>{error}</p>}

      <button
        type="submit"
        className="btn-primary"
        disabled={submitting || rating === 0 || !canSubmit}
      >
        {submitting ? "Submitting…" : "Submit Review"}
      </button>
    </form>
  );
}
