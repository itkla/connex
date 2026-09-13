# connex-landing

The prelaunch landing page, deployed to Cloudflare Workers so `connexcrm.jp` does not depend on the
staging host. Tracked in [#1684](https://github.com/itkla/connex/issues/1684).

## Why this is a copy, not an import

The landing surface is vendored here rather than imported from `frontend/`. Cross-package imports do
not work: a component under `frontend/` resolves `next-intl` from `frontend/node_modules` while this
app's layout resolves its own copy, so the two get different React contexts and
`NextIntlClientProvider` is invisible to `LandingNav`. Turbopack's `resolveAlias` does not override
resolution for files outside this package.

The alternative — one pnpm workspace spanning both packages — would touch `frontend/Dockerfile` and
both CI workflows, so it was deferred. The prelaunch page is removed at launch, which bounds how long
the two copies can drift.

**If you change the landing page, change it in both places** until this deployment is retired:
`frontend/app/components/landing/` and `landing/app/components/landing/`.

## Verified

The rendered markup is identical to the product application's prelaunch render in both locales,
through the closing `</footer>`. The product layout additionally renders an empty Sonner toast
region, which this app omits because nothing on the landing page raises a toast.

This app serves 2 message namespaces rather than 38, so a rendered page is ~396 KB against ~982 KB.

## Not yet built

- `app/api/launch-signups/route.ts` and the `SignupLimiter` Durable Object. The binding and its
  `new_sqlite_classes` migration are declared in `wrangler.jsonc`; the implementation is not written.
  The Workers Rate Limiting binding cannot substitute — it supports only 10s/60s periods and limits
  per Cloudflare location, while the policy is 5 per client per 15 minutes and 30 per minute globally.
- The legal routes (`/privacy`, `/legal`, `/disclosure`, `/tokushoho`), which the footer links to.
- Deployment, custom domains, and moving the apex CNAME off the tunnel.

## Secrets

`RESEND_API_KEY` must be a **Full access** key; the route uses `/contacts`, which a send-only key
cannot reach. Set it and `RESEND_LAUNCH_SEGMENT_ID` as Worker secrets, never in the repository.
