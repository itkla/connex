import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

import {
  FRAME_ANCESTORS_DIRECTIVE,
  FRONTEND_SECURITY_HEADERS,
} from "./security-headers";

const withNextIntl = createNextIntlPlugin("./i18n/request.ts");

const BACKEND_IMPORT_MAX_BODY_BYTES = 67_108_864;

const isDevelopment = process.env.NODE_ENV === "development";

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
    return [
      // The help-centre article moved with the rules-to-workflows migration (#1341). Keep the old
      // path resolving permanently so shared and bookmarked deep links do not 404.
      {
        source: "/docs/settings/rules-and-automation",
        destination: "/docs/settings/workflows-and-automation",
        permanent: true,
      },
    ];
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
