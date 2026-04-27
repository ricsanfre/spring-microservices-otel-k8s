import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";

interface UserProfile {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
}

export default async function ProfilePage({
  searchParams,
}: {
  searchParams: Promise<{ saved?: string; error?: string }>;
}) {
  const params = await searchParams;
  let profile: UserProfile | null = null;
  let fetchError: string | null = null;

  try {
    const res = await apiFetch("users", "/api/v1/users/me");
    if (!res.ok) {
      fetchError = `Failed to load profile (HTTP ${res.status})`;
    } else {
      profile = await res.json();
    }
  } catch (err) {
    fetchError = err instanceof Error ? err.message : "Unexpected error";
  }

  async function updateProfile(formData: FormData) {
    "use server";
    if (!profile) return;
    const body = {
      firstName: formData.get("firstName") as string,
      lastName: formData.get("lastName") as string,
    };
    let success = false;
    try {
      const res = await apiFetch("users", `/api/v1/users/${profile.id}`, {
        method: "PUT",
        body: JSON.stringify(body),
      });
      success = res.ok;
    } catch {
      // fall through to error redirect
    }
    redirect(success ? "/profile?saved=1" : "/profile?error=1");
  }

  return (
    <div>
      <h1>My Profile</h1>

      {params.saved && (
        <p className="success">Profile saved successfully.</p>
      )}
      {params.error && (
        <p className="error">Failed to save profile. Please try again.</p>
      )}
      {fetchError && <p className="error">{fetchError}</p>}

      {profile && (
        <form action={updateProfile} className="profile-form">
          <div className="form-group">
            <label>Email</label>
            <p className="form-value">{profile.email}</p>
          </div>

          <div className="form-group">
            <label htmlFor="firstName">First name</label>
            <input
              id="firstName"
              name="firstName"
              defaultValue={profile.firstName}
              required
            />
          </div>

          <div className="form-group">
            <label htmlFor="lastName">Last name</label>
            <input
              id="lastName"
              name="lastName"
              defaultValue={profile.lastName}
              required
            />
          </div>

          <button type="submit" className="btn-primary">
            Save changes
          </button>
        </form>
      )}
    </div>
  );
}
