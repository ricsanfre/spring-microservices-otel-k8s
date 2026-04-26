import { auth } from "@/auth";

const SERVICE_URLS: Record<string, string> = {
  products: process.env.PRODUCTS_SERVICE_URL ?? "http://localhost:8081",
  orders:   process.env.ORDERS_SERVICE_URL   ?? "http://localhost:8082",
  reviews:  process.env.REVIEWS_SERVICE_URL  ?? "http://localhost:8083",
  users:    process.env.USERS_SERVICE_URL    ?? "http://localhost:8085",
};

/**
 * Server-side fetch to a microservice with the current user's access_token forwarded.
 * Must only be called from Server Components or Route Handlers.
 */
export async function apiFetch(
  service: keyof typeof SERVICE_URLS,
  path: string,
  init?: RequestInit,
): Promise<Response> {
  const session = await auth();

  if (!session?.accessToken) {
    throw new Error(`apiFetch: no active session (service=${service}, path=${path})`);
  }

  const url = `${SERVICE_URLS[service]}${path}`;

  return fetch(url, {
    ...init,
    // Disable Next.js fetch cache for all authenticated calls — data is user-specific
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
      Authorization: `Bearer ${session.accessToken}`,
    },
  });
}
