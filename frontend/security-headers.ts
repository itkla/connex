export const FRAME_ANCESTORS_DIRECTIVE = "frame-ancestors 'none'";

export const FRONTEND_CONTENT_SECURITY_POLICY = [FRAME_ANCESTORS_DIRECTIVE].join("; ");

const FRONTEND_STANDARD_SECURITY_HEADERS = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
];

export const FRONTEND_SECURITY_HEADERS = [
  ...FRONTEND_STANDARD_SECURITY_HEADERS,
  { key: "Content-Security-Policy", value: FRONTEND_CONTENT_SECURITY_POLICY },
];

/** Runtime mode for the frontend's full Content Security Policy. */
export type ContentSecurityPolicyMode = "enforce" | "report-only";

/** Inputs used to resolve one request's nonce-bearing Content Security Policy. */
export type ContentSecurityPolicyOptions = {
  nonce: string;
  requestUrl: string;
  isDevelopment: boolean;
  configuredWebSocketUrl?: string;
  configuredImageOrigins?: string;
};

function isSafeContentSecurityPolicySource(value: string): boolean {
  return /^(?:https?|wss?):\/\/(?:\[[0-9a-f:.]+\]|[a-z0-9.-]+)(?::\d+)?$/i.test(value);
}

function configuredImageSources(value: string | undefined): string[] {
  if (!value) return [];

  const origins = new Set<string>();
  for (const candidate of value.split(",")) {
    const source = candidate.trim();
    if (!source || source.includes("*")) continue;
    try {
      const url = new URL(source);
      if (
        url.protocol === "https:"
        && !url.username
        && !url.password
        && url.pathname === "/"
        && !url.search
        && !url.hash
        && isSafeContentSecurityPolicySource(url.origin)
      ) {
        origins.add(url.origin);
      }
    } catch {}
  }
  return [...origins].sort();
}

function configuredWebSocketSource(value: string | undefined): string | null {
  if (!value || value.includes("*")) return null;
  try {
    const url = new URL(value);
    if (
      (url.protocol === "ws:" || url.protocol === "wss:")
      && !url.username
      && !url.password
      && isSafeContentSecurityPolicySource(url.origin)
    ) {
      return url.origin;
    }
  } catch {}
  return null;
}

function webSocketSources(requestUrl: string, configuredUrl: string | undefined): string[] {
  const url = new URL(requestUrl);
  const requestSources = [`ws://${url.host}`, `wss://${url.host}`]
    .filter(isSafeContentSecurityPolicySource);
  const sources = new Set<string>(requestSources);
  const configuredSource = configuredWebSocketSource(configuredUrl);

  if (configuredSource) {
    sources.add(configuredSource);
  } else if (url.hostname === "localhost" || url.hostname === "127.0.0.1") {
    sources.add(`ws://${url.hostname}:8080`);
  }

  return [...sources].sort();
}

/** Resolves the full nonce-bearing policy for one frontend request. */
export function createFrontendContentSecurityPolicy(options: ContentSecurityPolicyOptions): string {
  const scriptSources = [
    "'self'",
    `'nonce-${options.nonce}'`,
    "'strict-dynamic'",
    ...(options.isDevelopment ? ["'unsafe-eval'"] : []),
  ];
  const imageSources = [
    "'self'",
    "blob:",
    "data:",
    ...configuredImageSources(options.configuredImageOrigins),
  ];
  const connectSources = [
    "'self'",
    ...webSocketSources(options.requestUrl, options.configuredWebSocketUrl),
  ];

  return [
    "default-src 'self'",
    `script-src ${scriptSources.join(" ")}`,
    "style-src 'self' 'unsafe-inline'",
    `img-src ${imageSources.join(" ")}`,
    "font-src 'self'",
    `connect-src ${connectSources.join(" ")}`,
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    FRAME_ANCESTORS_DIRECTIVE,
    "frame-src 'none'",
    "worker-src 'none'",
  ].join("; ");
}

/** Resolves an operator-supplied CSP mode, defaulting invalid or absent values to Report-Only. */
export function resolveContentSecurityPolicyMode(value: string | undefined): ContentSecurityPolicyMode {
  return value === "enforce" ? "enforce" : "report-only";
}

/** Applies the shared browser security headers to a frontend response. */
export function applyFrontendSecurityHeaders(headers: Headers): void {
  for (const header of FRONTEND_STANDARD_SECURITY_HEADERS) {
    headers.set(header.key, header.value);
  }
}

/** Applies the full policy in the selected runtime mode. */
export function applyFrontendContentSecurityPolicy(
  headers: Headers,
  policy: string,
  mode: ContentSecurityPolicyMode,
): void {
  if (mode === "enforce") {
    headers.set("Content-Security-Policy", policy);
    headers.delete("Content-Security-Policy-Report-Only");
    return;
  }

  headers.delete("Content-Security-Policy");
  headers.set("Content-Security-Policy-Report-Only", policy);
}
