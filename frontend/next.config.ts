import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

import {
  FRAME_ANCESTORS_DIRECTIVE,
  FRONTEND_SECURITY_HEADERS,
} from "./security-headers";

const withNextIntl = createNextIntlPlugin("./i18n/request.ts");

/**
 * Ceiling for request bodies proxied to the backend through Next's rewrites.
 *
 * Next buffers a proxied request body and truncates past its default 10 MiB, which silently
 * corrupts the larger payloads the backend deliberately accepts — `CONNEX_IMPORT_MAX_BODY_BYTES`
 * defaults to 64 MiB for CSV import and `CONNEX_UPLOAD_MAX_BODY_BYTES` to 27 MiB for attachments.
 * This must stay at or above the largest backend limit; the backend remains the enforcing boundary
 * and rejects anything beyond its own per-endpoint ceiling.
 *
 * Production fronts Next with Caddy, which routes `/api/*` straight to the backend, so this governs
 * local development and any deployment without that edge.
 */
const BACKEND_IMPORT_MAX_BODY_BYTES = 67_108_864;

const nextConfig: NextConfig = {
  output: "standalone",
  outputFileTracingIncludes: {
    "/*": [
      "node_modules/.pnpm/sharp@*/node_modules/@img/sharp-*/**/*",
      "node_modules/.pnpm/@img+sharp-*/node_modules/@img/sharp-*/**/*",
    ],
  },
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  experimental: {
    proxyClientMaxBodySize: BACKEND_IMPORT_MAX_BODY_BYTES,
  },
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${process.env.BACKEND_URL ?? "http://localhost:8080"}/api/:path*`,
      },
      {
        source: "/saml2/:path*",
        destination: `${process.env.BACKEND_URL ?? "http://localhost:8080"}/saml2/:path*`,
      },
    ];
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: FRONTEND_SECURITY_HEADERS,
      },
      {
        source: "/attachments/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Content-Disposition", value: "attachment" },
          {
            key: "Content-Security-Policy",
            value: ["default-src 'none'", "sandbox", FRAME_ANCESTORS_DIRECTIVE].join("; "),
          },
        ],
      },
    ];
  },
};

export default withNextIntl(nextConfig);
