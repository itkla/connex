# Landing — Agent Guide

Prelaunch site for `connexcrm.jp`, served by the `connex-landing` Cloudflare Worker (Next.js 16 via `@opennextjs/cloudflare`). Root `AGENTS.md` rules apply. Deployment, secrets, signup bounds, rollback, and verification: [`docs/DEPLOYMENT.md` — The public prelaunch site](../docs/DEPLOYMENT.md#the-public-prelaunch-site-connexcrmjp-on-cloudflare-workers).

## Structure

- **Vendored from `frontend/`:** the paths listed in `scripts/sync-from-frontend.sh`. Never edit them here; change `frontend/`, then run `scripts/sync-from-frontend.sh`. They cannot be imported across packages: components resolved from `frontend/` load `frontend/node_modules`, so React and `next-intl` contexts split and providers vanish.
- `www.` hosts are redirect-only, handled in `custom-worker.ts` before the Next.js handler; keep it that way or the alias escapes the hostname-scoped edge rules in `docs/EDGE_DEFENCE.md`.
- **Landing-owned:** `app/layout.tsx`, `app/page.tsx`, `app/robots.ts`, `app/sitemap.ts`, `app/opengraph-image.png`, `app/api/launch-signups/route.ts`, `signup-limiter.ts`, `custom-worker.ts`, `proxy.ts`, `i18n/request.ts`, `next.config.ts`, `wrangler.jsonc`, `public/_headers`, and the `app/lib/landingMode.ts` / `app/lib/utils.ts` / `app/lib/deploymentSurface.ts` stand-ins that let vendored files compile unchanged.
- `app/opengraph-image.png` is rendered once from `frontend/app/opengraph-image.tsx`; `next/og` would push the Worker past the free plan's 3 MiB limit.

## Workers constraints

- `fetch` rejects `redirect: "error"`; use `"manual"` and treat non-2xx as failure.
- Module-level state is per isolate and short-lived. Shared counters and schedules belong in the `SignupLimiter` Durable Object, and a slot must be claimed before any `await` that yields the object.
- `keep_names` stays `false`: esbuild's `__name()` otherwise leaks into next-themes' inline script.
- Static assets bypass the Worker and `next.config.ts`; their headers come from `public/_headers`.
- `wrangler deploy` warns that `SignupLimiter` is not exported. That is a false positive for the `export … from` re-export.

## Commands (from `landing/`)

- `pnpm install` · `node_modules/.bin/tsc --noEmit` · `node_modules/.bin/opennextjs-cloudflare build`
- Local Workers runtime: `node_modules/.bin/wrangler dev --local`, with test secrets in an untracked `.dev.vars`.
- After `wrangler types`, repoint `SignupLimiter` in `cloudflare-env.d.ts` to `./signup-limiter` and set `mainModule: unknown`, or `tsc` fails on the unbuilt `.open-next/worker.js`.
