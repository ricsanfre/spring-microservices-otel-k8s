import { apiFetch } from "@/lib/api";

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
  let products: Product[] = [];
  let error: string | null = null;

  try {
    const res = await apiFetch("products", "/api/v1/products?page=0&size=20");
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
      <h1>Products</h1>

      {error && <p className="error">{error}</p>}

      <div className="card-grid">
        {products.map((p) => (
          <div key={p.id} className="card">
            <h3>{p.name}</h3>
            <p style={{ fontSize: "0.8rem", color: "#64748b", margin: "0.25rem 0" }}>
              {p.category}
            </p>
            <p className="price">${p.price.toFixed(2)}</p>
            <p className="stock">
              {p.stockQty > 0 ? `${p.stockQty} in stock` : "Out of stock"}
            </p>
          </div>
        ))}
      </div>

      {products.length === 0 && !error && (
        <p style={{ color: "#94a3b8", marginTop: "1rem" }}>No products found.</p>
      )}
    </div>
  );
}
