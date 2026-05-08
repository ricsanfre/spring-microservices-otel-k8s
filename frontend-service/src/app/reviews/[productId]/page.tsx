import Link from "next/link";
import { auth } from "@/auth";
import { apiFetch, publicFetch } from "@/lib/api";
import { ReviewForm } from "./ReviewForm";
import { DeleteReviewButton } from "./DeleteReviewButton";

interface Product {
  id: string;
  name: string;
  description: string;
  price: number;
}

interface Review {
  id: string;
  productId: string;
  orderId: string;
  userId: string;
  rating: number;
  comment?: string;
  createdAt: string;
}

function StarDisplay({ rating }: { rating: number }) {
  return (
    <span className="star-display" aria-label={`${rating} out of 5 stars`}>
      {[1, 2, 3, 4, 5].map((s) => (
        <span key={s} className={s <= rating ? "star-filled-static" : "star-empty-static"}>
          ★
        </span>
      ))}
    </span>
  );
}

export default async function ReviewsPage({
  params,
  searchParams,
}: {
  params: Promise<{ productId: string }>;
  searchParams: Promise<{ orderId?: string }>;
}) {
  const { productId } = await params;
  const { orderId } = await searchParams;

  const session = await auth();

  // Fetch product info and reviews in parallel
  const [productRes, reviewsRes] = await Promise.all([
    publicFetch("products", `/api/v1/products/${productId}`),
    publicFetch("reviews", `/api/v1/reviews/product/${productId}`),
  ]);

  const product: Product | null = productRes.ok ? await productRes.json() : null;
  const reviews: Review[] = reviewsRes.ok ? await reviewsRes.json() : [];

  // Resolve the current user's internal ID to identify their own reviews
  let currentUserId: string | null = null;
  if (session?.accessToken) {
    try {
      const userRes = await apiFetch("users", "/api/v1/users/me");
      if (userRes.ok) {
        const user: { id: string } = await userRes.json();
        currentUserId = user.id;
      }
    } catch {
      // ignore — won't show delete buttons
    }
  }

  const avgRating =
    reviews.length > 0
      ? reviews.reduce((sum, r) => sum + r.rating, 0) / reviews.length
      : null;

  const alreadyReviewed = currentUserId
    ? reviews.some((r) => r.userId === currentUserId)
    : false;

  return (
    <div>
      <Link
        href="/products"
        style={{ color: "#64748b", fontSize: "0.875rem", textDecoration: "none" }}
      >
        ← Back to Products
      </Link>

      <h1 style={{ marginTop: "0.75rem" }}>
        {product ? product.name : `Product ${productId}`}
      </h1>

      {product && (
        <p style={{ color: "#475569", fontSize: "0.9rem", marginBottom: "1rem" }}>
          {product.description}
        </p>
      )}

      {/* Summary bar */}
      <div className="reviews-summary">
        <span style={{ fontWeight: 600, fontSize: "1.1rem" }}>
          {reviews.length} {reviews.length === 1 ? "review" : "reviews"}
        </span>
        {avgRating !== null && (
          <span style={{ display: "flex", alignItems: "center", gap: "0.4rem" }}>
            <StarDisplay rating={Math.round(avgRating)} />
            <span style={{ color: "#475569", fontSize: "0.875rem" }}>
              {avgRating.toFixed(1)} / 5
            </span>
          </span>
        )}
      </div>

      {/* Write-a-review form */}
      {session && !alreadyReviewed && (
        <div style={{ marginTop: "1.5rem" }}>
          <ReviewForm productId={productId} orderId={orderId} />
        </div>
      )}
      {session && alreadyReviewed && (
        <p
          style={{
            marginTop: "1rem",
            color: "#475569",
            fontSize: "0.875rem",
            fontStyle: "italic",
          }}
        >
          You have already reviewed this product.
        </p>
      )}
      {!session && (
        <p style={{ marginTop: "1rem", color: "#475569", fontSize: "0.875rem" }}>
          <Link href="/login" style={{ color: "#0ea5e9" }}>Sign in</Link> to write a review.
        </p>
      )}

      {/* Reviews list */}
      <div style={{ marginTop: "2rem" }}>
        <h2>Customer Reviews</h2>

        {reviews.length === 0 && (
          <p style={{ color: "#94a3b8", marginTop: "0.75rem" }}>
            No reviews yet. Be the first!
          </p>
        )}

        <div className="reviews-list">
          {reviews.map((review) => (
            <div key={review.id} className="review-card">
              <div className="review-card-header">
                <StarDisplay rating={review.rating} />
                <span className="review-date">
                  {new Date(review.createdAt).toLocaleDateString()}
                </span>
                {currentUserId && review.userId === currentUserId && (
                  <DeleteReviewButton reviewId={review.id} />
                )}
              </div>
              {review.comment && (
                <p className="review-comment">{review.comment}</p>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
