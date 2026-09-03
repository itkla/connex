import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

import {
  DOCUMENT_ACCEPTANCE_SECURITY_HEADERS,
  FRAME_ANCESTORS_DIRECTIVE,
  FRONTEND_SECURITY_HEADERS,
} from "./security-headers";

const withNextIntl = createNextIntlPlugin("./i18n/request.ts");

const BACKEND_IMPORT_MAX_BODY_BYTES = 67_108_864;

const isDevelopment = process.env.NODE_ENV === "development";

/**
 * Documentation articles whose slug has been renamed, and the address each one moved to.
 *
 * A docs slug is a route: it is bookmarked, linked from inside the product, and indexed, so a
 * rename that does not forward the old address breaks all three. Both entries are renames the
 * product overhaul made — Rules to Workflows, and the warmth article whose title #1339 swept while
 * its slug kept saying "warmth-and-temperature" (#1340 WS4.6, since §7 gives a destination one name
 * and the address is part of the name).
 *
 * These resolve before routing, which is what makes them true `308`s rather than a redirect a
 * rendered page has to throw. `docsNavConsistency.test.ts` holds each source to being a slug the
 * registry no longer serves and each destination to one it does.
 */
const DOCS_SLUG_REDIRECTS = [
  {
    source: "/docs/settings/rules-and-automation",
    destination: "/docs/settings/workflows-and-automation",
  },
  {
    source: "/docs/relationship-intelligence/warmth-and-temperature",
    destination: "/docs/relationship-intelligence/warmth",
  },
  {
    source: "/docs/overview-suite/calendar",
    destination: "/docs/activity/calendar",
  },
] as const;

const nextConfig: NextConfig = {
  output: "standalone",
  outputFileTracingIncludes: {
    "/*": [
      "node_modules/.pnpm/sharp@*/node_modules/@img/sharp-*/**/*",
      "node_modules/.pnpm/@img+sharp-*/node_modules/@img/sharp-*/**/*",
    ],
  },
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  ...(isDevelopment
    ? { experimental: { proxyClientMaxBodySize: BACKEND_IMPORT_MAX_BODY_BYTES } }
    : {}),
  async redirects() {
    return DOCS_SLUG_REDIRECTS.map((redirect) => ({ ...redirect, permanent: true }));
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
        source: "/document-acceptance/:path*",
        headers: DOCUMENT_ACCEPTANCE_SECURITY_HEADERS,
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
