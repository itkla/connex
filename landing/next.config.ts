import path from "node:path";

import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

import { FRONTEND_SECURITY_HEADERS } from "../frontend/security-headers";

const withNextIntl = createNextIntlPlugin("./i18n/request.ts");

const repositoryRoot = path.join(import.meta.dirname, "..");

/**
 * Packages that carry React context and must resolve to exactly one copy.
 *
 * The landing body lives in `frontend/`, which has its own `node_modules`. Without these aliases a
 * component imported from there receives a second instance of the package, so the provider rendered
 * by this app's layout is invisible to it.
 */
const singletons = ["react", "react-dom", "next-intl", "next-themes", "motion"];

// Turbopack resolves alias targets as module requests rooted at `turbopack.root`, so these are
// written relative to the repository root rather than as absolute paths.
const resolveAlias = Object.fromEntries(
  singletons.map((name) => [name, `./landing/node_modules/${name}`]),
);

const nextConfig: NextConfig = {
  // This repository maintains its own AGENTS.md files; Next.js must not generate competing ones.
  agentRules: false,
  // The landing body, design tokens and messages are read from `frontend/`, so tracing and the
  // Turbopack root have to span both packages rather than stopping at this one.
  outputFileTracingRoot: repositoryRoot,
  turbopack: { root: repositoryRoot, resolveAlias },
  async headers() {
    return [{ source: "/:path*", headers: FRONTEND_SECURITY_HEADERS }];
  },
};

export default withNextIntl(nextConfig);
