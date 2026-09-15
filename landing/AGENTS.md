# Landing — Agent Guide

Prelaunch site served at `connexcrm.jp` and `www.connexcrm.jp` by the `connex-landing` Cloudflare Worker (Next.js 16 via `@opennextjs/cloudflare`). The product application keeps `preview.connexcrm.jp`. Root `AGENTS.md` rules apply.

## Structure

- **Vendored from `frontend/`:** the landing, legal, and not-found components and pages, UI primitives, tokens, and messages, listed in `scripts/sync-from-frontend.sh`. Never edit these here; change `frontend/`, then run `scripts/sync-from-frontend.sh`. They cannot be imported across packages: components resolved from `frontend/` load `frontend/node_modules`, so React and `next-intl` contexts split and providers vanish.
- **Landing-owned:** `app/layout.tsx`, `app/page.tsx`, `app/robots.ts`, `app/sitemap.ts`, `app/opengraph-image.png`, `app/api/launch-signups/route.ts`, `signup-limiter.ts`, `custom-worker.ts`, `proxy.ts`, `i18n/request.ts`, `next.config.ts`, `wrangler.jsonc`, and the `app/lib/landingMode.ts` / `app/lib/utils.ts` stand-ins that let vendored files compile unchanged.
- `app/opengraph-image.png` is rendered once from `frontend/app/opengraph-image.tsx`; `next/og` would push the Worker past the free plan's 3 MiB limit.

## Runtime contracts

- **Signups** go to Resend (`RESEND_API_KEY`, `RESEND_LAUNCH_SEGMENT_ID`), set as Worker secrets with `wrangler secret put` — never in the repo. Deploys keep them.
- **Rate limiting** is the `SignupLimiter` Durable Object (SQLite, free tier): 5 attempts per client per 15 minutes, 30 signups per minute overall, keyed on `CF-Connecting-IP`. Module-level state does not work on Workers, and the Rate Limiting binding cannot express these windows.
- Workers `fetch` rejects `redirect: "error"`; use `"manual"` and treat non-2xx as failure.
- `keep_names` stays `false`: esbuild's `__name()` otherwise leaks into next-themes' inline script and throws in browsers.
- Security and cache headers for static assets come from `public/_headers`; the assets binding bypasses the Worker and `next.config.ts`.
- `wrangler deploy` warns that `SignupLimiter` is not exported. It is a false positive for the `export … from` re-export; the limiter works in production.

## Commands (from `landing/`)

- `pnpm install` · `node_modules/.bin/tsc --noEmit` · `node_modules/.bin/opennextjs-cloudflare build`
- Local Workers runtime: `node_modules/.bin/wrangler dev --local` (put test secrets in an untracked `.dev.vars`).
- After `wrangler types`, repoint `SignupLimiter` in `cloudflare-env.d.ts` to `./signup-limiter` and set `mainModule: unknown`, or `tsc` fails on the unbuilt `.open-next/worker.js`.

## Deployment

`.github/workflows/landing-deploy.yml` checks drift on pull requests touching `frontend/` or `landing/`, builds when `landing/` changed, and on pushes to `main` that touch `landing/` deploys with the `CLOUDFLARE_API_TOKEN` / `CLOUDFLARE_ACCOUNT_ID` repository secrets, then smoke-tests `connexcrm.jp`. The token is limited to Workers scripts and routes; it cannot edit DNS. Verify changes in a real browser, not only with `curl` — both runtime bugs above were invisible to HTTP checks.
