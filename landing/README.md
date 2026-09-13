# connex-landing

The prelaunch landing page, deployed to Cloudflare Workers so `connexcrm.jp` does not depend on the
staging host. Tracked in [#1684](https://github.com/itkla/connex/issues/1684).

## Status: incomplete — blocked on a dependency-resolution decision

The app builds and renders, but **returns 500 at runtime**. Components imported from `frontend/`
resolve `next-intl` from `frontend/node_modules`, while this app's layout resolves it from
`landing/node_modules`. Two module instances means two React contexts, so `NextIntlClientProvider`
is invisible to `LandingNav`.

Turbopack's `resolveAlias` does not override resolution for files outside this package; hiding
`frontend/node_modules/next-intl` makes resolution fail outright rather than fall back. A shared
dependency tree (a pnpm workspace spanning both packages) or a self-contained copy of the landing
components is required. See the issue for the trade-off.

## Not yet built

- `app/api/launch-signups/route.ts` and the `SignupLimiter` Durable Object. The binding and its
  `new_sqlite_classes` migration are declared in `wrangler.jsonc`; the implementation is not written.
  The Workers Rate Limiting binding cannot substitute — it supports only 10s/60s periods and limits
  per Cloudflare location, while the policy is 5 per client per 15 minutes and 30 per minute globally.
- The legal routes (`/privacy`, `/legal`, `/disclosure`, `/tokushoho`).
