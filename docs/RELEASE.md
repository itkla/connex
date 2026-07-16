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

Before tagging a release that includes OCR changes, qualify the exact candidate commit on an
x86-64 AVX host with two CPUs and 2 GiB assigned to the OCR container. Run the authenticated
40-case English/Japanese/mixed benchmark against a disposable workspace by following
[`../ocr/benchmark/README.md`](../ocr/benchmark/README.md), retain its JSON report in the release
issue, and require all gates to pass: email and phone accuracy at least 95%, name at least 85%, title
and company at least 80%, and end-to-end P95 latency at most eight seconds. Do not tag from an
unqualified commit.

Releases are **tag-triggered**. From the qualified commit on `main`:

```bash
git tag v1.4.0
git push origin v1.4.0
```

`.github/workflows/release.yml` treats the three images as one release transaction:

1. It rejects anything except strict `vMAJOR.MINOR.PATCH`, requires the tag to point at the current
   `main` head, and waits for the commit's required CI and security checks to succeed.
2. It builds each component with a reproducible commit timestamp and pushes only a run-scoped
   `candidate-<run>` tag. Each exact candidate is rejected on high-or-critical known vulnerabilities,
   signed with cosign, receives an SPDX SBOM attestation, and the OCR candidate must pass a real
   authenticated inference smoke test. A rerun reuses that run's validated candidate digest instead
   of rebuilding a potentially different image.
3. It boots the exact three candidate digests together through the deployment Compose bundle with
   OCR enabled. The gate verifies `/api/version`, scanning/import capabilities, the running image
   digests, OCR isolation and resource limits, and the one-shot maintenance invocation.
4. Only after the whole release set passes does it copy each candidate digest to the final
   `:<version>` and `:sha-<commit>` names. Promotion first rejects any existing destination tag that
   resolves to a different digest, then verifies both promoted names, signatures, and SBOM
   attestations.
5. It publishes a GitHub Release last, with release notes, all three SBOMs, a signed manifest mapping
   every component to its immutable digest, and a signed Compose override that consumes those exact
   digests.

No final release tag is published before every component and the integrated release set pass. A
failed run can be rerun safely: an absent final tag may be created, a matching tag is accepted, and
a conflicting tag stops promotion. Never move a git tag once published — on-prem and air-gapped
installs pin exact versions/digests, and moving tags would break checksum verification.

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

cosign verify-blob \
  --bundle release-manifest.bundle.json \
  --certificate-identity 'https://github.com/itkla/connex/.github/workflows/release.yml@refs/tags/v1.4.0' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  release-manifest.json
```

Use the verified `release-compose-images.json` as a Compose override to pin every component by
digest: `docker compose -f docker-compose.yml -f release-compose-images.json pull`.

## Running the images

The images carry no configuration. Every deployment supplies environment for the database,
secret-store master key, audit HMAC, mail, deployment profile, private object storage, and optional
OCR service. Use the reusable [`../deploy/`](../deploy) Compose bundle and its per-profile templates;
the full operator contract is in [DEPLOYMENT.md](DEPLOYMENT.md).

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
