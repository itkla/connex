import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

import { FRONTEND_SECURITY_HEADERS } from "./security-headers";

const withNextIntl = createNextIntlPlugin("./i18n/request.ts");

const nextConfig: NextConfig = {
  // This repository maintains its own AGENTS.md files; Next.js must not generate competing ones.
  agentRules: false,
  async headers() {
    return [{ source: "/:path*", headers: FRONTEND_SECURITY_HEADERS }];
  },
};

export default withNextIntl(nextConfig);
