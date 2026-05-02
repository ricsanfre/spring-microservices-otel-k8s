import { auth, signIn, signOut } from "@/auth";
import Link from "next/link";
import { apiFetch } from "@/lib/api";

async function getCartItemCount(): Promise<number> {
  try {
    const res = await apiFetch("cart", "/api/v1/cart");
    if (!res.ok) return 0;
    const cart: { totalItems: number } = await res.json();
    return cart.totalItems ?? 0;
  } catch {
    return 0;
  }
}

export async function Nav() {
  const session = await auth();
  const cartCount = session ? await getCartItemCount() : 0;

  return (
    <nav>
      <Link href="/">E-Commerce</Link>
      {session && (
        <>
          <Link href="/products">Products</Link>
          <Link href="/orders">Orders</Link>
          <Link href="/profile">Profile</Link>
        </>
      )}
      <span className="spacer" />
      {session?.user ? (
        <>
          <Link href="/cart" className="cart-icon" aria-label="Shopping cart">
            🛒
            {cartCount > 0 && (
              <span className="cart-badge">{cartCount}</span>
            )}
          </Link>
          <span style={{ fontSize: "0.875rem", color: "#94a3b8" }}>
            {session.user.email}
          </span>
          <form
            action={async () => {
              "use server";
              await signOut({ redirectTo: "/login" });
            }}
          >
            <button type="submit">Sign out</button>
          </form>
        </>
      ) : (
        <form
          action={async () => {
            "use server";
            await signIn("keycloak", { redirectTo: "/home" });
          }}
        >
          <button type="submit">Sign in</button>
        </form>
      )}
    </nav>
  );
}
