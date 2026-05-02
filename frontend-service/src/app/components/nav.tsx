import { auth, signIn, signOut } from "@/auth";
import Link from "next/link";

export async function Nav() {
  const session = await auth();

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
