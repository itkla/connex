# Release & artifact distribution

This is **Release pipeline v0** (issue #496, epic #502). It builds Connex as three container images — a **version-locked set** — and publishes them to GHCR, signed and with SBOM attestations. The same images serve every deployment form (SaaS, silo, on-prem); the deployment mode is chosen at runtime, never by a different build.

## The artifacts

| Image | Contents | Runtime |
| --- | --- | --- |
| `ghcr.io/itkla/connex-backend:<version>` | Spring Boot executable WAR on a Temurin 26 JRE | `java -jar app.war`, serves `:8080` |
| `ghcr.io/itkla/connex-frontend:<version>` | Next.js standalone server | `node server.js`, serves `:3000` |
| `ghcr.io/itkla/connex-ocr:<version>` | CPU-only PaddleOCR with pre-fetched EN/JA card models | private Python service, serves `:8090` |

All images receive the **same product version** (from the git tag) plus a `sha-<commit>` convenience
tag. Tags are registry pointers, not an integrity boundary. The signed release manifest is the source
of truth and pins every component by immutable digest. A release is always the set at one version —
the components are never upgraded independently. The running backend version is exposed at
`GET /api/version`.

The frontend image bakes the **internal** backend address (`BACKEND_URL`, default `http://backend:8080`) into its build-time route rewrites, and defaults the server-side fetch base (`API_URL`) to the same. Because that is the internal service hop (the browser always talks to the frontend origin), the one image is portable across all deployment modes as long as the bundle names the backend service `backend`. Decoupling this behind an ingress proxy is a follow-up (#499).

## Cutting a release

Releases are **tag-triggered**. From the qualified commit on `main`:

```bash
git tag v1.4.0
git push origin v1.4.0
```

`.github/workflows/release.yml` treats the three images as one release transaction:

Before the workflow can cut or resume a release, repository immutable releases must be enabled and
the `CONNEX_RELEASE_ADMIN_TOKEN` Actions secret must provide repository Administration read access.
The normal `GITHUB_TOKEN` remains the only token used to upload release assets. The administration
token is used only to fail closed on the immutable-release policy precondition.

1. It rejects anything except strict `vMAJOR.MINOR.PATCH`. Before creating a transaction, it requires
   the tag to point to the current `main` head and waits for the latest `push` run of the repository's
   CI, security, and deployment-smoke workflows to succeed for that exact commit. A resume uses the
   already signed transaction even if `main` has advanced. Workflow identity is resolved through the
   GitHub Actions API, not by accepting a matching check name from another integration.
2. It builds each component with a reproducible commit timestamp and pushes only an attempt-scoped
   `candidate-<run>-<attempt>` tag. Every attempt builds from the checked-out release commit with
   pinned Buildx and BuildKit versions and emits BuildKit provenance; it never trusts content found
   behind a pre-existing candidate tag. Each resulting digest is rejected on high-or-critical known
   vulnerabilities, signed with cosign, and receives an SPDX SBOM attestation.
3. It boots the exact three candidate digests together through the deployment Compose bundle with
   OCR enabled. The gate verifies `/api/version`, scanning/import capabilities, running image
   identities, OCR health/isolation/resource limits, and the one-shot maintenance invocation. It
   then generates the canonical fixtures with the pinned font inside the exact OCR image and runs
   the authenticated 40-case English/Japanese/mixed benchmark. Promotion is blocked unless every
   HTTP response and accuracy/latency gate passes.
4. After those gates pass, it creates one signed, run-scoped release transaction containing the
   three image digests, raw SBOM hashes, deterministic deployment bundle hash, benchmark report, and
   deterministic fixture archive. The transaction artifact is named for its originating run attempt.
   A retry discovers and reuses this committed transaction instead of mixing or rebuilding candidates,
   so partial tag promotion can safely resume the same digest set.
5. Promotion verifies the transaction, every candidate signature, SBOM attestation, and GitHub
   build attestation; rejects any conflicting destination tag; and fills only absent matching
   `:<version>` and `:sha-<commit>` convenience names.
6. Publication uploads every transaction-bound asset to a draft GitHub Release, downloads and
   re-hashes the complete draft, and only then makes it public. A public release is the availability
   signal; partial drafts and orphaned registry tags are not.

The GitHub Release and its signed manifest are the release-set availability signal. Ignore orphaned
registry tags if promotion is interrupted before that release exists. A failed run before transaction
commit must be rerun with **all jobs**; a failed-job-only retry has no complete same-attempt candidate
set and fails explicitly. After commit, any rerun consumes the original transaction, accepts matching
tags, fills absent tags, and rejects conflicts. The remote tag target is re-fetched immediately before
promotion and publication. Never move a git tag once published.

The signed manifest records `qualification.protections.rotatingSecretHoldout=false` and
`qualification.protections.debianSnapshot=false`. The canonical 40-card suite is deterministic and
reviewed, but it is not a rotating secret holdout, and Debian packages installed in image builds are
not yet resolved through a dated snapshot repository. Those are explicit unresolved release blockers
for any assurance policy that requires either protection. Do not describe a release as having either
protection until dedicated holdout storage/rotation and Debian snapshot inputs exist and the manifest
flags are changed by a reviewed workflow update.

## Verifying an image

```bash
VERSION=1.4.0
IDENTITY="https://github.com/itkla/connex/.github/workflows/release.yml@refs/tags/v${VERSION}"

cosign verify-blob \
  --bundle release-manifest.bundle.json \
  --certificate-identity "$IDENTITY" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  release-manifest.json

test "$(jq -r '.version' release-manifest.json)" = "$VERSION"
for component in backend frontend ocr; do
  expected="ghcr.io/itkla/connex-${component}"
  test "$(jq -r --arg component "$component" \
    '.images[$component].image' release-manifest.json)" = "$expected"
  image="$(jq -r --arg component "$component" \
    '.images[$component].image + "@" + .images[$component].digest' release-manifest.json)"
  sbom="$(jq -r --arg component "$component" \
    '.images[$component].sbom.file' release-manifest.json)"
  test "$(sha256sum "$sbom" | cut -d ' ' -f1)" = \
    "$(jq -r --arg component "$component" \
      '.images[$component].sbom.sha256' release-manifest.json)"
  cosign verify \
    --certificate-identity "$IDENTITY" \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    "$image" >/dev/null
  cosign verify-attestation --type spdxjson \
    --certificate-identity "$IDENTITY" \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    "$image" >/dev/null
  gh attestation verify "oci://${image}" \
    --repo itkla/connex \
    --signer-workflow itkla/connex/.github/workflows/release.yml \
    --source-digest "$(jq -r '.sourceSha' release-manifest.json)" \
    --source-ref "refs/tags/v${VERSION}" >/dev/null
done
for selector in qualification.report qualification.fixtures deploymentBundle; do
  file="$(jq -r ".${selector}.file" release-manifest.json)"
  test "$(sha256sum "$file" | cut -d ' ' -f1)" = \
    "$(jq -r ".${selector}.sha256" release-manifest.json)"
done
for component in backend frontend ocr; do
  variable="CONNEX_$(tr '[:lower:]' '[:upper:]' <<<"$component")_DIGEST"
  digest="$(jq -r --arg component "$component" '.images[$component].digest' release-manifest.json)"
  printf '%s=%s\n' "$variable" "${digest#sha256:}"
done
```

Put the three printed digest-only assignments into the mode-0600 `deploy/.env`. The production
Compose file fixes the GHCR repositories and `sha256` algorithm, so its image inputs cannot carry a
tag or alternate repository. Extract the verified `connex-<version>-deploy.tar` and run only that
bundle with those digest values.

The manifest signature bundle and every manifest-bound release asset can be verified without a
registry connection. Cosign image signatures, SBOM attestations, and GitHub build provenance are
registry-backed online checks; perform them while staging OCI images for an air-gapped transfer.
Inside the disconnected environment, verify the transferred OCI digest against the already verified
manifest before loading it. Do not describe registry provenance as independently offline-verifiable.

## Running the images

The images carry no configuration. Every deployment supplies environment for the database,
secret-store master key, audit HMAC, mail, deployment profile, private object storage, and the
default private OCR service. Use the reusable [`../deploy/`](../deploy) Compose bundle and its
per-profile templates; the full operator contract, including the low-resource OCR opt-out, is in
[DEPLOYMENT.md](DEPLOYMENT.md).

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
2. Replace the checkout-and-run unit with one that verifies the release manifest using the exact
   tag-bound identity above, derives the three `image@sha256:...` references, and runs those digests
   with the existing staging env (the fail-closed security env staging already requires — DB
   `sslMode`, secret-store, audit secrets, and OCR token). The supported templates activate the
   `ocr` Compose profile by default; use the documented low-resource opt-out only when the host lacks
   AVX or cannot spare the sidecar resources.
3. Verify and deploy a new signed manifest to roll forward.

This step lives on the host because the systemd units and cloudflared config are not in the repository.
