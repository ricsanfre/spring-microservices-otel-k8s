import Link from "next/link";
import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { ProductForm } from "../ProductForm";

export default async function NewProductPage() {
  const session = await auth();
  if (!session?.scope?.split(" ").includes("products:write")) {
    redirect("/products");
  }

  return (
    <div>
      <Link href="/products" style={{ color: "#64748b", fontSize: "0.875rem", textDecoration: "none" }}>
        ← Back to Products
      </Link>
      <h1 style={{ marginTop: "0.75rem" }}>Add New Product</h1>
      <ProductForm />
    </div>
  );
}
