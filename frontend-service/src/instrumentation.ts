import { registerOTel } from "@vercel/otel";

/**
 * Next.js instrumentation hook — loaded automatically at server startup.
 *
 * Registers the OpenTelemetry SDK for the Node.js runtime. This gives us:
 *   - A server span for every incoming page / API-route request
 *   - Automatic W3C `traceparent` header injection into every `fetch()` call
 *     made from Server Components / Route Handlers (apiFetch, publicFetch),
 *     for outgoing requests whose URLs match those listed in `propagateContextUrls`.
 *     This links frontend spans to the corresponding Spring Boot backend spans
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

  const allowedUrls: (string | RegExp)[] = [];

  // 1. Get automatically all environment variables that match our criteria (e.g., ending with _URL, _ISSUER or containing _SERVICE_)
   Object.keys(process.env).forEach((key) => {
    // Filter only the variables that interest you (e.g., those ending with _URL, _ISSUER or containing _SERVICE_URL)
    if (key.endsWith('_URL') || key.endsWith('_ISSUER') || key.includes('_SERVICE_')) {
      const urlValue = process.env[key];

      if (urlValue && (urlValue.startsWith('http://') || urlValue.startsWith('https://'))) {
        try {
          const urlObj = new URL(urlValue);
          const rootHost = urlObj.host; // Extracts "localhost:8081", "auth-service:8080", etc.
          
          // Escape dots (.) for the regular expression engine
          const escapedHost = rootHost.replace(/\./g, '\\.');
          
          // Create a flexible regex for this specific backend
          const serviceRegex = new RegExp(`^https?://${escapedHost}.*`);
          allowedUrls.push(serviceRegex);
        } catch (e) {
          // If the value is not a valid URL, we safely ignore the variable
        }
      }
    }
  });
  registerOTel({
    serviceName: process.env.OTEL_SERVICE_NAME ?? "frontend-service",
    // Configure the fetch instrumentation to propagate context to backend services
    // See details inhttps://vercel.com/docs/tracing/instrumentation#configuring-context-propagation
      instrumentationConfig: {
      fetch: {
        propagateContextUrls: allowedUrls,
        // Optionally, you can add dontPropagateContextUrls or ignoreUrls if needed
      },
    },
  });
}
