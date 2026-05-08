"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export function DeleteReviewButton({ reviewId }: { reviewId: string }) {
  const router = useRouter();
  const [deleting, setDeleting] = useState(false);

  async function handleDelete() {
    if (!confirm("Delete this review? This cannot be undone.")) return;
    setDeleting(true);
    try {
      await fetch(`/api/reviews/${reviewId}`, { method: "DELETE" });
      router.refresh();
    } finally {
      setDeleting(false);
    }
  }

  return (
    <button
      onClick={handleDelete}
      disabled={deleting}
      className="btn-delete-review"
      aria-label="Delete review"
    >
      {deleting ? "Deleting…" : "Delete"}
    </button>
  );
}
