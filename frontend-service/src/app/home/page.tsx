import { auth } from "@/auth";
import Link from "next/link";

export default async function HomePage() {
  const session = await auth();

  return (
    <div>
      <h1>Welcome, {session?.user?.name ?? session?.user?.email}</h1>
      <p style={{ color: "#475569", marginTop: "0.5rem" }}>
        You are signed in. Explore the store below.
      </p>
      <div className="links">
        <Link href="/products">Browse Products</Link>
        <Link href="/orders">My Orders</Link>
      </div>
    </div>
  );
}
