import Link from "next/link";
import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { publicFetch } from "@/lib/api";
import { ProductForm } from "../../ProductForm";

interface Product {
  id: string;
  name: string;
  description: string;
  price: number;
  category: string;
  imageUrl: string;
  stockQty: number;
}

export default async function EditProductPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  const session = await auth();
  if (!session?.scope?.split(" ").includes("products:write")) {
    redirect("/products");
  }

  const res = await publicFetch("products", `/api/v1/products/${id}`);
  if (!res.ok) {
    return (
      <div>
        <h1>Edit Product</h1>
        <p className="error">Product not found (HTTP {res.status}).</p>
        <Link href="/products" style={{ color: "#0ea5e9" }}>← Back to Products</Link>
      </div>
    );
  }
  const product: Product = await res.json();

  return (
    <div>
      <Link href="/products" style={{ color: "#64748b", fontSize: "0.875rem", textDecoration: "none" }}>
        ← Back to Products
      </Link>
      <h1 style={{ marginTop: "0.75rem" }}>Edit Product</h1>
      <p style={{ color: "#64748b", fontSize: "0.8rem", marginBottom: "1rem" }}>
        ID: <code>{product.id}</code>
      </p>
      <ProductForm
        productId={product.id}
        initial={{
          name: product.name,
          description: product.description ?? "",
          price: String(product.price),
          category: product.category,
          imageUrl: product.imageUrl ?? "",
          stockQty: String(product.stockQty),
        }}
      />
    </div>
  );
}
