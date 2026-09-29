import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

import { FRONTEND_SECURITY_HEADERS } from "./security-headers";

const withNextIntl = createNextIntlPlugin("./i18n/request.ts");

/** `agentRules` is off because this repository maintains its own AGENTS.md guides. */
const nextConfig: NextConfig = {
  agentRules: false,
  async headers() {
    return [{ source: "/:path*", headers: FRONTEND_SECURITY_HEADERS }];
  },
};

export default withNextIntl(nextConfig);
