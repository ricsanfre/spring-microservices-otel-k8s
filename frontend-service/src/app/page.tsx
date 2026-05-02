import Link from "next/link";

export default function LandingPage() {
  return (
    <div className="landing">
      <div className="landing-hero">
        <h1>Welcome to E-Commerce</h1>
        <p className="landing-subtitle">
          Discover thousands of products at great prices. Fast shipping, easy
          returns, and secure checkout.
        </p>
        <Link href="/products" className="btn-primary">
          Browse Products
        </Link>
      </div>

      <div className="landing-features">
        <div className="feature-card">
          <span className="feature-icon">🛍️</span>
          <h2>Wide Selection</h2>
          <p>Explore a curated catalogue of products across all categories.</p>
        </div>
        <div className="feature-card">
          <span className="feature-icon">🔒</span>
          <h2>Secure Payments</h2>
          <p>Your transactions are protected end-to-end with industry-standard encryption.</p>
        </div>
        <div className="feature-card">
          <span className="feature-icon">🚚</span>
          <h2>Fast Delivery</h2>
          <p>Get your orders delivered quickly with real-time tracking.</p>
        </div>
      </div>
    </div>
  );
}
