import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";

interface ShippingAddress {
  street?: string;
  city?: string;
  state?: string;
  postalCode?: string;
  country?: string;
}

interface BillingAccount {
  cardHolder?: string;
  cardLast4?: string;
  cardExpiry?: string;
  sameAsShipping?: boolean;
}

interface UserProfile {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  shippingAddress?: ShippingAddress;
  billingAccount?: BillingAccount;
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
      shippingAddress: {
        street:     (formData.get("addressStreet") as string) || null,
        city:       (formData.get("addressCity") as string) || null,
        state:      (formData.get("addressState") as string) || null,
        postalCode: (formData.get("addressPostalCode") as string) || null,
        country:    (formData.get("addressCountry") as string) || null,
      },
      billingAccount: {
        cardHolder:    (formData.get("billingCardHolder") as string) || null,
        cardLast4:     (formData.get("billingCardLast4") as string) || null,
        cardExpiry:    (formData.get("billingCardExpiry") as string) || null,
        sameAsShipping: formData.get("billingSameAsShipping") === "on",
      },
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

  const addr = profile?.shippingAddress;
  const billing = profile?.billingAccount;

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

          {/* ── Personal details ─────────────────────────────────────── */}
          <h2 className="form-section-heading">Personal details</h2>

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

          {/* ── Shipping address ──────────────────────────────────────── */}
          <h2 className="form-section-heading">Shipping address</h2>

          <div className="form-group">
            <label htmlFor="addressStreet">Street</label>
            <input id="addressStreet" name="addressStreet" defaultValue={addr?.street ?? ""} />
          </div>

          <div className="form-group">
            <label htmlFor="addressCity">City</label>
            <input id="addressCity" name="addressCity" defaultValue={addr?.city ?? ""} />
          </div>

          <div className="form-group">
            <label htmlFor="addressState">State / Region</label>
            <input id="addressState" name="addressState" defaultValue={addr?.state ?? ""} />
          </div>

          <div className="form-group">
            <label htmlFor="addressPostalCode">Postal code</label>
            <input id="addressPostalCode" name="addressPostalCode" defaultValue={addr?.postalCode ?? ""} />
          </div>

          <div className="form-group">
            <label htmlFor="addressCountry">Country (ISO code)</label>
            <input
              id="addressCountry"
              name="addressCountry"
              defaultValue={addr?.country ?? ""}
              maxLength={2}
              placeholder="e.g. US"
            />
          </div>

          {/* ── Billing account ───────────────────────────────────────── */}
          <h2 className="form-section-heading">Billing account</h2>

          <div className="form-group">
            <label htmlFor="billingCardHolder">Cardholder name</label>
            <input id="billingCardHolder" name="billingCardHolder" defaultValue={billing?.cardHolder ?? ""} />
          </div>

          <div className="form-group">
            <label htmlFor="billingCardLast4">Card last 4 digits</label>
            <input
              id="billingCardLast4"
              name="billingCardLast4"
              defaultValue={billing?.cardLast4 ?? ""}
              maxLength={4}
              pattern="\d{4}"
              placeholder="1234"
            />
          </div>

          <div className="form-group">
            <label htmlFor="billingCardExpiry">Card expiry (MM/YY)</label>
            <input
              id="billingCardExpiry"
              name="billingCardExpiry"
              defaultValue={billing?.cardExpiry ?? ""}
              maxLength={5}
              pattern="\d{2}/\d{2}"
              placeholder="12/28"
            />
          </div>

          <div className="form-group form-group--checkbox">
            <input
              type="checkbox"
              id="billingSameAsShipping"
              name="billingSameAsShipping"
              defaultChecked={billing?.sameAsShipping ?? true}
            />
            <label htmlFor="billingSameAsShipping">
              Billing address same as shipping address
            </label>
          </div>

          <button type="submit" className="btn-primary">
            Save changes
          </button>
        </form>
      )}
    </div>
  );
}
