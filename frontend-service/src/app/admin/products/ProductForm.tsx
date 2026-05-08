"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export interface ProductFormValues {
  name: string;
  description: string;
  price: string;
  category: string;
  imageUrl: string;
  stockQty: string;
}

interface Props {
  /** When provided the form submits a PUT (edit); otherwise POST (create). */
  productId?: string;
  initial?: Partial<ProductFormValues>;
}

const empty: ProductFormValues = {
  name: "",
  description: "",
  price: "",
  category: "",
  imageUrl: "",
  stockQty: "",
};

export function ProductForm({ productId, initial }: Props) {
  const router = useRouter();
  const [values, setValues] = useState<ProductFormValues>({ ...empty, ...initial });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function set(field: keyof ProductFormValues, value: string) {
    setValues((prev) => ({ ...prev, [field]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    const payload = {
      name: values.name,
      description: values.description || undefined,
      price: parseFloat(values.price),
      category: values.category,
      imageUrl: values.imageUrl || undefined,
      stockQty: parseInt(values.stockQty, 10),
    };
    try {
      const res = await fetch(
        productId ? `/api/products/${productId}` : "/api/products",
        {
          method: productId ? "PUT" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        },
      );
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data?.detail ?? data?.message ?? `Request failed (HTTP ${res.status})`);
      } else {
        const saved = await res.json();
        router.push("/products");
        router.refresh();
        // For edits, also refresh the product page if we can get the id
        if (!productId && saved?.id) {
          router.refresh();
        }
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unexpected error");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="review-form" style={{ maxWidth: "560px" }}>
      <div className="form-field">
        <label htmlFor="pf-name">Name *</label>
        <input id="pf-name" required value={values.name} onChange={(e) => set("name", e.target.value)} maxLength={255} />
      </div>

      <div className="form-field">
        <label htmlFor="pf-category">Category *</label>
        <input id="pf-category" required value={values.category} onChange={(e) => set("category", e.target.value)} maxLength={100} />
      </div>

      <div className="form-field">
        <label htmlFor="pf-price">Price ($) *</label>
        <input id="pf-price" required type="number" min={0} step="0.01" value={values.price} onChange={(e) => set("price", e.target.value)} />
      </div>

      <div className="form-field">
        <label htmlFor="pf-stock">Stock Quantity *</label>
        <input id="pf-stock" required type="number" min={0} step="1" value={values.stockQty} onChange={(e) => set("stockQty", e.target.value)} />
      </div>

      <div className="form-field">
        <label htmlFor="pf-desc">Description</label>
        <textarea id="pf-desc" rows={3} value={values.description} onChange={(e) => set("description", e.target.value)} maxLength={2000} />
      </div>

      <div className="form-field">
        <label htmlFor="pf-image">Image URL</label>
        <input id="pf-image" type="url" value={values.imageUrl} onChange={(e) => set("imageUrl", e.target.value)} maxLength={1024} />
      </div>

      {error && <p className="error" style={{ marginBottom: "0.5rem" }}>{error}</p>}

      <div style={{ display: "flex", gap: "0.75rem", alignItems: "center" }}>
        <button type="submit" className="btn-primary" disabled={submitting}>
          {submitting ? "Saving…" : productId ? "Save Changes" : "Create Product"}
        </button>
        <button
          type="button"
          style={{ background: "none", border: "none", color: "#64748b", cursor: "pointer", fontSize: "0.9rem" }}
          onClick={() => router.back()}
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
