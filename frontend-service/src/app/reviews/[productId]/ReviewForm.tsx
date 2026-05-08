"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

interface Props {
  productId: string;
  /** Pre-filled when navigating from an order detail page */
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

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (rating === 0) {
      setError("Please select a rating.");
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

  return (
    <form onSubmit={handleSubmit} className="review-form">
      <h3>Write a Review</h3>

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

      {/* Only show orderId input when not pre-filled from order detail */}
      {!defaultOrderId && (
        <div className="form-field">
          <label htmlFor="orderId">Order ID</label>
          <input
            id="orderId"
            type="text"
            value={orderId}
            onChange={(e) => setOrderId(e.target.value)}
            placeholder="UUID of your delivered order"
            required
          />
          <p className="form-hint">
            Find the Order ID on your <a href="/orders">Orders</a> page.
          </p>
        </div>
      )}

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
        />
      </div>

      {error && <p className="error" style={{ marginBottom: "0.5rem" }}>{error}</p>}

      <button
        type="submit"
        className="btn-primary"
        disabled={submitting || rating === 0}
      >
        {submitting ? "Submitting…" : "Submit Review"}
      </button>
    </form>
  );
}
