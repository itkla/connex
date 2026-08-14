export const FRAME_ANCESTORS_DIRECTIVE = "frame-ancestors 'none'";

export const FRONTEND_CONTENT_SECURITY_POLICY = [FRAME_ANCESTORS_DIRECTIVE].join("; ");

export const FRONTEND_SECURITY_HEADERS = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Content-Security-Policy", value: FRONTEND_CONTENT_SECURITY_POLICY },
];

export function applyFrontendSecurityHeaders(headers: Headers): void {
  for (const header of FRONTEND_SECURITY_HEADERS) {
    headers.set(header.key, header.value);
  }
}
