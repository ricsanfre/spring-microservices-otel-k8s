import { redirect } from "next/navigation";
import { auth, signIn } from "@/auth";

export default async function LoginPage() {
  const session = await auth();
  if (session) redirect("/");

  return (
    <div className="login-card">
      <h1>E-Commerce</h1>
      <p style={{ color: "#64748b", marginBottom: "1.5rem" }}>
        Sign in to your account to access the store.
      </p>
      <form
        action={async () => {
          "use server";
          await signIn("keycloak");
        }}
      >
        <button type="submit" className="btn-primary btn-full">
          Continue with Keycloak
        </button>
      </form>
    </div>
  );
}
