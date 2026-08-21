# Frontend CI Build-Asset Gate

`verify_build_chunks.mjs` protects the production build from declaring route assets that were never emitted.

A route may appear healthy when fetched directly while its browser later requests a missing client chunk or stylesheet and fails/ renders unstyled. This gate was introduced after that class of failure shipped in issue #972.

## What it verifies

The script reads each route's `*_client-reference-manifest.js` under `<build-dir>/server/app` and verifies that every declared JavaScript chunk and every non-inlined `entryCSSFiles` stylesheet exists in the assembled production build.

Usage:

```bash
node ci/verify_build_chunks.mjs <build-dir>
```

The build directory defaults to `.next`. If the build uses `NEXT_DIST_DIR`, pass that directory explicitly.

The target must include both `server/app` and `static/`. In a standalone image those may arrive from separate `COPY` layers, so verify the assembled `/app/.next`, not only the `standalone/` subtree.

## Required integration points

Any change to frontend build layout, standalone packaging, staging assembly, or release verification must keep the gate operating against a real completed production build at all established boundaries:

1. CI e2e/production build, against `.next` after `next build`.
2. Deployable image smoke, against the assembled `/app/.next`.
3. Release candidate digest verification, through the same image smoke path.
4. Staging release builder, against the isolated candidate's `.next-new` before sealing/switching the live runtime.

Do not satisfy the gate with an empty/partial directory or move it earlier than asset assembly. If build output location changes, update all integration points together.

## Review checklist

For a build/pipeline change:

- A real production build is produced before the gate runs.
- The gate sees both server manifests and emitted static assets.
- CI, deploy image, release candidate, and staging builder still invoke it.
- Standalone packaging verifies the assembled image layout rather than its source subtree.
- A targeted failure case proves a manifest reference to a missing chunk/CSS asset is rejected.
