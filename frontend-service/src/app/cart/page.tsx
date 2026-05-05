import Link from "next/link";
import { apiFetch } from "@/lib/api";
import { CartActions } from "./CartActions";
import { CheckoutButton } from "./CheckoutButton";

interface CartItem {
  productId: string;
  productName: string;
  price: number;
  quantity: number;
  lineTotal: number;
}

interface CartResponse {
  userId: string;
  items: CartItem[];
  totalItems: number;
  grandTotal: number;
  expiresAt: string;
}

export default async function CartPage() {
  let cart: CartResponse | null = null;
  let error: string | null = null;

  try {
    const res = await apiFetch("cart", "/api/v1/cart");
    if (!res.ok) {
      error = `Failed to load cart (HTTP ${res.status})`;
    } else {
      cart = await res.json();
    }
  } catch (err) {
    error = err instanceof Error ? err.message : "Unexpected error";
  }

  const items = cart?.items ?? [];

  return (
    <div>
      <h1>My Cart</h1>

      {error && <p className="error">{error}</p>}

      {!error && items.length === 0 && (
        <p style={{ color: "#94a3b8", marginTop: "1rem" }}>Your cart is empty.</p>
      )}

      {items.length > 0 && (
        <>
          <table>
            <thead>
              <tr>
                <th>Product</th>
                <th>Unit Price</th>
                <th>Quantity</th>
                <th>Line Total</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.productId}>
                  <td>{item.productName}</td>
                  <td>${item.price.toFixed(2)}</td>
                  <td>
                    <CartActions
                      productId={item.productId}
                      productName={item.productName}
                      price={item.price}
                      quantity={item.quantity}
                    />
                  </td>
                  <td>${item.lineTotal.toFixed(2)}</td>
                  <td>
                    <form action={`/api/cart/items/${item.productId}`} method="DELETE">
                      {/* Handled by CartActions remove button */}
                    </form>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="cart-summary">
            <span>
              {cart!.totalItems} {cart!.totalItems === 1 ? "item" : "items"}
            </span>
            <span className="cart-grand-total">
              Grand total: <strong>${cart!.grandTotal.toFixed(2)}</strong>
            </span>
          </div>

          <div className="cart-actions">
            <Link href="/products" className="btn-continue-shopping">
              ← Continue Shopping
            </Link>
            <CheckoutButton />
          </div>
        </>
      )}
    </div>
  );
}
