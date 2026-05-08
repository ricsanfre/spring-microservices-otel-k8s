import Image from "next/image";
import Link from "next/link";
import { auth } from "@/auth";
import { publicFetch } from "@/lib/api";
import { AddToCartButton } from "./AddToCartButton";

interface Product {
  id: string;
  name: string;
  description: string;
  price: number;
  category: string;
  imageUrl: string;
  stockQty: number;
}

interface PagedResponse {
  content: Product[];
  totalElements: number;
}

export default async function ProductsPage() {
  const session = await auth();
  const isSignedIn = !!session?.accessToken;
  const isAdmin = session?.scope?.split(" ").includes("products:write") ?? false;

  let products: Product[] = [];
  let error: string | null = null;

  try {
    const res = await publicFetch("products", "/api/v1/products?page=0&size=20");
    if (!res.ok) {
      error = `Failed to load products (HTTP ${res.status})`;
    } else {
      const data: PagedResponse = await res.json();
      products = data.content ?? [];
    }
  } catch (err) {
    error = err instanceof Error ? err.message : "Unexpected error";
  }

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "0.5rem" }}>
        <h1 style={{ margin: 0 }}>Products</h1>
        {isAdmin && (
          <Link
            href="/admin/products/new"
            className="btn-primary"
            style={{ textDecoration: "none", fontSize: "0.875rem", padding: "0.4rem 1rem" }}
          >
            + Add Product
          </Link>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      <div className="card-grid">
        {products.map((p) => (
          <div key={p.id} className="card">
            {p.imageUrl && (
              <div style={{ position: "relative", width: "100%", height: "240px", marginBottom: "0.5rem" }}>
                <Image
                  src={p.imageUrl}
                  alt={p.name}
                  fill
                  style={{ objectFit: "cover", borderRadius: "0.25rem" }}
                />
              </div>
            )}
            <h3>{p.name}</h3>
            <p style={{ fontSize: "0.8rem", color: "#64748b", margin: "0.25rem 0" }}>
              {p.category}
            </p>
            <p className="price">${p.price.toFixed(2)}</p>
            <p className="stock">
              {p.stockQty > 0 ? `${p.stockQty} in stock` : "Out of stock"}
            </p>
            <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", marginTop: "0.75rem" }}>
              {isSignedIn && !isAdmin && (
                <AddToCartButton
                  productId={p.id}
                  productName={p.name}
                  price={p.price}
                />
              )}
              <Link
                href={`/reviews/${p.id}`}
                style={{ fontSize: "0.8rem", color: "#0ea5e9", textDecoration: "none" }}
              >
                Reviews →
              </Link>
              {isAdmin && (
                <Link
                  href={`/admin/products/${p.id}/edit`}
                  style={{ fontSize: "0.8rem", color: "#f59e0b", textDecoration: "none" }}
                >
                  Edit ✎
                </Link>
              )}
            </div>
          </div>
        ))}
      </div>

      {products.length === 0 && !error && (
        <p style={{ color: "#94a3b8", marginTop: "1rem" }}>No products found.</p>
      )}
    </div>
  );
}
