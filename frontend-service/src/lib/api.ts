import { auth } from "@/auth";

const SERVICE_URLS: Record<string, string> = {
  products: process.env.PRODUCTS_SERVICE_URL ?? "http://localhost:8081",
  orders:   process.env.ORDERS_SERVICE_URL   ?? "http://localhost:8082",
  reviews:  process.env.REVIEWS_SERVICE_URL  ?? "http://localhost:8083",
  users:    process.env.USERS_SERVICE_URL    ?? "http://localhost:8085",
};

/**
 * Server-side fetch to a microservice with the current user's access_token forwarded.
 * Throws if there is no active session. Must only be called from Server Components or Route Handlers.
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

/**
 * Server-side fetch to a public microservice endpoint.
 * Forwards the access_token if a session exists, but does NOT require one.
 * Use for endpoints that permit unauthenticated access on the backend (e.g. product catalog).
 */
export async function publicFetch(
  service: keyof typeof SERVICE_URLS,
  path: string,
  init?: RequestInit,
): Promise<Response> {
  const session = await auth();
  const url = `${SERVICE_URLS[service]}${path}`;

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string>),
  };
  if (session?.accessToken) {
    headers["Authorization"] = `Bearer ${session.accessToken}`;
  }

  return fetch(url, {
    ...init,
    cache: "no-store",
    headers,
  });
}
