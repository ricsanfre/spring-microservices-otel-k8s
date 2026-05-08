import { registerOTel } from "@vercel/otel";

/**
 * Next.js instrumentation hook — loaded automatically at server startup.
 *
 * Registers the OpenTelemetry SDK for the Node.js runtime. This gives us:
 *   - A server span for every incoming page / API-route request
 *   - Automatic W3C `traceparent` header injection into every `fetch()` call
 *     made from Server Components / Route Handlers (apiFetch, publicFetch),
 *     which links frontend spans to the corresponding Spring Boot backend spans
 *     visible in Tempo.
 *
 * Configuration via environment variables (same as backend services):
 *   OTEL_EXPORTER_OTLP_ENDPOINT  — OTLP HTTP base URL (default: http://localhost:4318)
 *   OTEL_SERVICE_NAME             — overrides the service name when set
 *
 * The `register()` function is the stable Next.js 15 instrumentation entry point.
 * No `experimental.instrumentationHook` flag is required.
 */
export function register() {
  registerOTel({
    serviceName: process.env.OTEL_SERVICE_NAME ?? "frontend-service",
  });
}
