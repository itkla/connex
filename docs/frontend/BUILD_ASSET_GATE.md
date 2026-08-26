# Frontend Production Build-Asset Gate

This document is authoritative for `frontend/ci/verify_build_chunks.mjs` and every pipeline that assembles or promotes a production frontend build.

Read it before changing the Next.js build directory, standalone-image assembly, release image smoke tests, or staging release builder.

## What the gate proves

`ci/verify_build_chunks.mjs` reads each route's `*_client-reference-manifest.js` under `<dist>/server/app` and verifies that every JavaScript chunk and every non-inlined `entryCSSFiles` stylesheet declared by the route was actually emitted.

A route can respond successfully while still naming a chunk or stylesheet that the browser later requests as a 404. Fetching one HTML page does not prove those referenced assets exist; this gate checks the production build graph directly.

The script accepts the production build directory as its argument and defaults to `.next`:

```bash
node ci/verify_build_chunks.mjs .next
```

If a build uses another `NEXT_DIST_DIR`, pass that exact directory.

## Required invocation sites

Any change to how the frontend is built, assembled, staged, or released must preserve all four checks:

1. **CI E2E production build** — immediately after `next build`, verify the checkout's `.next` directory before starting the frontend for browser E2E.
2. **Deployable frontend image** — `frontend/ci/smoke_image.sh` verifies the assembled image at `/app/.next`.
3. **Release candidate digest** — `release.yml` runs the same image smoke against the exact candidate digest before promotion.
4. **Staging release builder** — verify the isolated target commit's `.next-new` before sealing its standalone runtime and before changing either live service.

Do not remove one because another environment already runs the script. Each invocation proves a different artifact boundary.

## Verify the assembled build, not `standalone/`

The verifier needs both:

- `<dist>/server/app` manifests; and
- `<dist>/static/` chunks/stylesheets.

In a standalone container image those are assembled from separate `COPY` layers. The `standalone/` subtree by itself does not contain the complete asset set, so verifying it can either fail for the wrong reason or miss an image-assembly mistake.

Always run the image gate against the final assembled `/app/.next` directory after all standalone and static asset copies are complete.

## Change checklist

For a frontend build/pipeline change:

- The verifier receives the actual production build directory.
- `server/app` and `static/` are present together at verification time.
- CI E2E still verifies immediately after build.
- The deployable image smoke still verifies `/app/.next`.
- The exact release candidate digest still runs that image smoke before promotion.
- Staging still verifies `.next-new` before sealing/switching services.
- A failing asset reference blocks the build/release rather than becoming a warning.
- `frontend/AGENTS.md`, this contract, and the affected pipeline/runbook are updated together when the architecture changes.
