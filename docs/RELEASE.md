# Release & artifact distribution

This is **Release pipeline v0** (issue #496, epic #502). It builds Connex as three container images — a **version-locked set** — and publishes them to GHCR, signed and with SBOM attestations. The same images serve every deployment form (SaaS, silo, on-prem); the deployment mode is chosen at runtime, never by a different build.

## The artifacts

| Image | Contents | Runtime |
| --- | --- | --- |
| `ghcr.io/itkla/connex-backend:<version>` | Spring Boot executable WAR on a Temurin 26 JRE | `java -jar app.war`, serves `:8080` |
| `ghcr.io/itkla/connex-frontend:<version>` | Next.js standalone server | `node server.js`, serves `:3000` |
| `ghcr.io/itkla/connex-ocr:<version>` | CPU-only PaddleOCR with pre-fetched EN/JA card models | private Python service, serves `:8090` |

All images are tagged with the **same product version** (from the git tag) plus an immutable `sha-<commit>` tag. A release is always the set at one version — the components are never upgraded independently. The running backend version is exposed at `GET /api/version`.

The frontend image bakes the **internal** backend address (`BACKEND_URL`, default `http://backend:8080`) into its build-time route rewrites, and defaults the server-side fetch base (`API_URL`) to the same. Because that is the internal service hop (the browser always talks to the frontend origin), the one image is portable across all deployment modes as long as the bundle names the backend service `backend`. Decoupling this behind an ingress proxy is a follow-up (#499).

## Cutting a release

Releases are **tag-triggered**. From `main` at the commit you want to ship:

```bash
git tag v1.4.0
git push origin v1.4.0
```

`.github/workflows/release.yml` then, for each component:

1. Builds the image with `--build-arg VERSION=1.4.0` and pushes `:1.4.0` + `:sha-<commit>` to GHCR.
2. **Signs** the image digest with cosign (keyless, GitHub OIDC — no long-lived keys).
3. Generates an **SPDX SBOM** (syft) and attaches it as a cosign attestation.
4. Publishes a GitHub Release with auto-generated notes and the SBOM files.

Never move a tag once published — on-prem and air-gapped installs pin exact versions/digests, and moving tags would break checksum verification.

## Verifying an image

```bash
cosign verify \
  --certificate-identity-regexp 'https://github.com/itkla/connex/.github/workflows/release.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/itkla/connex-backend:1.4.0

cosign verify-attestation --type spdxjson \
  --certificate-identity-regexp 'https://github.com/itkla/connex/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/itkla/connex-backend:1.4.0
```

## Running the images

The images carry no configuration. Every deployment supplies environment (DB URL/credentials, secret-store master key, audit HMAC, `connex.mail.*`, and — once #497 lands — `connex.deployment.profile`). The reusable Compose bundle + per-profile config templates that turn these images into a silo/on-prem deployment are tracked in **#499**; until then, run them with the same env the stack already documents in `backend/AGENTS.md`.

Smoke-check a running backend:

```bash
curl -s http://<backend>/api/version   # {"version":"1.4.0","buildTime":"..."}
```

## Release channels

Two, no more:

- **Continuous** — SaaS/staging track `main`.
- **Stable tags** — silo and on-prem run a pinned `vX.Y.Z`. "Silo staged rollout" is Connex applying a stable tag on a schedule, not separate channel infrastructure.

## Repointing staging (host-side, not in this repo)

Staging currently checks out `main` and runs it via an out-of-repo systemd unit on the staging host. To consume the pipeline instead, on that host:

1. Authenticate to GHCR (`docker login ghcr.io`).
2. Replace the checkout-and-run unit with one that `docker pull`s the pinned `ghcr.io/itkla/connex-{backend,frontend}:<version>` set and runs them with the existing staging env (the fail-closed security env staging already requires — DB `sslMode`, secret-store, and audit secrets). When local OCR is enabled, pull the matching OCR image too, configure its token, and activate the `ocr` Compose profile.
3. Bump the pinned version to roll forward.

This step lives on the host because the systemd units and cloudflared config are not in the repository.
