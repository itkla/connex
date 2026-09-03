# Release & artifact distribution

This is **Release pipeline v0** (issue #496, epic #502). It builds Connex as three container images — a **version-locked set** — and publishes them to GHCR, signed and with SBOM attestations. The same images serve every deployment form (SaaS, silo, on-prem); the deployment mode is chosen at runtime, never by a different build.

## The artifacts

| Image | Contents | Runtime |
| --- | --- | --- |
| `ghcr.io/itkla/connex-backend:<version>` | Spring Boot executable WAR on a Temurin 26 JRE | `java -jar app.war`, serves `:8080` |
| `ghcr.io/itkla/connex-frontend:<version>` | Next.js standalone server | `node server.js`, serves `:3000` |
| `ghcr.io/itkla/connex-ocr:<version>` | CPU-only PaddleOCR with pre-fetched EN/JA card models | private Python service, serves `:8090` |
| `ghcr.io/itkla/connex-clamav:<version>` | ClamAV with a build-time baked, signature-verified database | private Python service, serves `:8091` |

All images receive the **same product version** (from the git tag) plus a `sha-<commit>` convenience
tag. Tags are registry pointers, not an integrity boundary. The signed release manifest is the source
of truth and pins every component by immutable digest. A release is always the set at one version —
the components are never upgraded independently. The running backend version is exposed at
`GET /api/version`.

The frontend image bakes the **internal** backend address (`BACKEND_URL`, default `http://backend:8080`) into its build-time route rewrites, and defaults the server-side fetch base (`API_URL`) to the same. The published Compose bundle overrides `API_URL` with the app-network-only `http://backend-app:8080` alias so recipient-preview SSR reaches the backend from the Docker-DNS-resolved `frontend` peer; browser rewrites retain the baked `backend` service hop. The one image remains portable across deployment modes as long as the bundle names the backend service `backend`. Decoupling this behind an ingress proxy is a follow-up (#499).

## Cutting a release

Releases are **tag-triggered**. From the qualified commit on `main`:

```bash
git tag v1.4.0
git push origin v1.4.0
```

Trial candidates and release candidates use SemVer prerelease tags such as `v1.4.0-tc.1` and
`v1.4.0-rc.2`. Their complete prerelease version is preserved in every image, manifest, bundle, and
runtime version surface.

### Candidate soak evidence

The candidate soak is a read-only qualification record for [#1226](https://github.com/itkla/connex/issues/1226)
and the [0.9 Wave 5 candidate in #857](https://github.com/itkla/connex/issues/857). It does not close
the independent installed-artifact review in #1101 and it does not publish a release. One candidate
pipeline means one successful run each of `CI`, `Security`, and `Deploy smoke`, not CI alone.

Freeze the commit with an annotated qualification tag named
`candidate-soak-vMAJOR.MINOR.PATCH-tc.N`, where each version component is a canonical non-negative
decimal integer without leading zeroes and `N` is a positive decimal integer without leading
zeroes. This exact pattern does not match the release workflow's `v*.*.*` trigger, so it avoids
starting publication before the soak evidence exists:

```bash
candidate_sha="$(git rev-parse origin/main)"
qualification_tag="candidate-soak-v0.9.0-tc.2"
git tag -a "$qualification_tag" "$candidate_sha" \
  -m "Qualify $candidate_sha for candidate soak"
git push origin "refs/tags/$qualification_tag"
```

Treat that tag as immutable. The candidate-soak workflow resolves only the exact
`refs/tags/<qualification-tag>` through the Git refs API, requires the ref to identify one annotated
tag object that points directly to one commit, and binds both object IDs before collecting evidence.
It resolves the same chain again after collection and rejects any change. The current release
workflow also requires a new release tag to point at the current `main` head and separately accepts
only successful `push` runs on `main` for the exact commit. It does not consume `workflow_dispatch`
soak runs. Therefore `main` must remain frozen until the soak is accepted and the owner approves the
corresponding `vX.Y.Z-tc.N` tag; if `main` advances, repeat qualification on its new head. Whether to
replace this qualification tag with a prerelease tag before the soak is a release-owner decision,
because the latter immediately starts the release workflow.

#### Candidate-soak auditor trust boundary

The verdict is valid only when every row below is positively proven. An omitted, malformed,
ambiguous, truncated, duplicated, contradictory, or out-of-bound value is unusable evidence; the
auditor emits or causes a failing verdict instead of guessing, defaulting, or discarding the value.

| Verdict input | Exact positive proof required | Fail-closed action when absent or inconsistent |
| --- | --- | --- |
| Repository identity | `github.repository` is exactly `itkla/connex`; `github.repository_id` and `github.event.repository.id` are both exactly the pinned canonical repository ID `1222010579`; `github.event.repository.fork` is the Boolean `false`. Collection uses the fixed `itkla/connex` API path. | Reject the entire input before counting any run. Every evidence document the auditor can generate records all four received values in `repositoryProvenance`; a pre-audit collection failure fails the workflow without manufacturing an evidence document. |
| Auditor workflow ref and SHAs | `github.ref` is exactly `refs/heads/main`; `github.workflow_ref` is exactly `itkla/connex/.github/workflows/candidate-soak.yml@refs/heads/main`; `github.sha` and `github.workflow_sha` are lowercase 40-hex commit IDs and are equal. | Reject the entire input before counting any run. Every evidence document the auditor can generate records all four received values in `workflowProvenance`; a pre-audit collection failure fails the workflow. |
| Requested qualification tag | The caller value exactly matches `candidate-soak-vMAJOR.MINOR.PATCH-tc.N`, with canonical non-negative version integers and positive `N`, and the API ref name is exactly `refs/tags/<caller-value>`. | Reject the entire input; do not accept a branch, abbreviated name, alternate ref, or tag with non-canonical numeric spelling. |
| Start tag object ID | At collection start the exact tag ref has `object.type: tag` and one lowercase 40-hex `object.sha`; fetching `/git/tags/<object.sha>` returns the same `sha`, the exact requested `tag` name, and `object.type: commit`. | Reject lightweight tags, tag chains, missing tag objects, mismatched names or IDs, and malformed object types or IDs. |
| Start resolved commit SHA | The start tag object names one lowercase 40-hex commit SHA. | Reject the entire input; never infer a commit through another endpoint or accept a caller-supplied substitute. |
| Annotated-tag creation instant | The start and end annotated-tag objects both contain the same valid offset-aware `tagger.date`. Every relevant run's `created_at` and `run_started_at` is at or after that instant. | Reject the entire input when the date is absent, malformed, changes, or any relevant run predates it. Pre-tag runs are contradictory evidence and never age out before the counted window. Record the instant in both `tagBinding` snapshots. |
| Candidate ancestry from `main` | At collection start, `refs/heads/main` resolves directly to a lowercase 40-hex commit SHA. The canonical compare response for `compare/main...<resolved-candidate-sha>` names that recorded SHA as `base_commit.sha`, names the candidate as `merge_base_commit.sha`, and reports `identical` when the SHAs match or `behind` when the candidate is an ancestor. | Reject an absent or malformed main snapshot, mismatched SHAs or status, and every `ahead` or `diverged` candidate. Record the collection-time main SHA, candidate SHA, and compare status in `mainBinding`. |
| End tag object ID and commit SHA | After all run and job pages are collected, the exact ref and annotated tag object are resolved again under the same rules; the tag object ID, commit SHA, and tagger date equal their start values. | Reject the entire input on any move, replacement, deletion, type change, or resolution failure. Record both snapshots in `tagBinding` on passing, ordinary failing, and unusable-input evidence. |
| Run page cardinalities and snapshot stability | Unfiltered canonical-repository Actions responses taken before and after job collection each contain every advertised page at 100 entries per full page; every page in a snapshot has the same non-negative `total_count`; raw entry count and unique positive run-ID count both equal it. The two snapshots' complete verdict-relevant run records for the requested tag and three workflow paths are identical. | Reject the entire run document on a missing/extra page, short non-final page, overfull page, changed count, duplicate ID, malformed entry, or any relevant run added, removed, or changed during collection. Unrelated workflow or ref activity may differ. |
| Relevant run identity and binding | For every returned `CI`, `Security`, or `Deploy smoke` workflow-path run: its ID is a globally unique positive integer; its positive run number is unique within that workflow; its canonicalized path is one of the three declared workflow files; `head_branch` exactly equals the requested tag; and `head_sha` exactly equals the start/end resolved commit. No relevant run on that tag may name another SHA. | Reject the entire input. In particular, a duplicate per-workflow run number or a moved tag's earlier run is contradictory evidence, never a run to filter out. |
| Run workflow-source provenance | Every relevant run's complete API `path`, including any `@<ref>` suffix, and every `referenced_workflows` entry's `path`, commit SHA, and optional ref are retained and must remain identical across the two run snapshots. The canonical top-level path and candidate `head_sha`, combined with the candidate's proven `main` ancestry, bind the executed top-level workflow file to a commit on `main`. | Reject malformed provenance or any snapshot change. Preserve the full values in each pipeline or streak-break run record instead of discarding `@…` source information. |
| Run attempt and terminal result | Every relevant run has recognized `status` and `conclusion` values, `status: completed`, and the canonical repository Actions URL for its ID. Every counted run additionally has `run_attempt: 1`, `event: workflow_dispatch`, and `conclusion: success`. | Reject malformed fields, unknown statuses, non-completed runs, and non-canonical URLs globally because they lack usable terminal proof. A structurally valid non-first attempt, other event, or completed non-success conclusion breaks the streak and can age out before a later counted window. |
| Run chronology | Every relevant run has UTC `created_at`, `run_started_at`, and `updated_at`, with `created_at <= run_started_at <= updated_at`. Global creation order is strict and agrees with strict start order. A derived completion watermark is the latest `updated_at`, job `started_at`, or job `completed_at`. | Reject the entire input for missing, non-UTC, reversed, equal-order, or globally contradictory chronology. A non-serialized or over-six-hour triple breaks the streak. |
| Job page-set coverage and cardinalities | There is exactly one attempt-specific page set for every relevant run and none for another run. Its positive `run_attempt` equals the run attempt. Each set proves its own consistent `total_count`, exact page count and occupancy at 100 entries per full page, and equal raw and unique positive job-ID counts. Job IDs are also unique across run page sets, and each job's positive `run_id` equals its enclosing run. | Reject the entire jobs document on any missing/extra/repeated set, attempt mismatch, truncated/duplicated page, duplicate ID, or run mismatch. |
| Job identity and terminal result | Every job has a non-empty name, recognized `status`, recognized `conclusion`, and `status: completed`. In every counted run, each declared mandatory leaf and aggregate job occurs exactly once and has `conclusion: success`. | Reject malformed fields, every non-completed job, and duplicate mandatory names globally. A missing, skipped, or unsuccessful mandatory job breaks the streak and can age out; aggregate success never substitutes for a leaf. |
| Job chronology | Every returned job, including non-mandatory jobs, has UTC `started_at` and `completed_at`, with `started_at <= completed_at`, and both are inside its run's closed `[run_started_at, updated_at]` window. Both values participate in the run's serialization watermark. | Reject the entire input for a missing, non-UTC, reversed, pre-run, or post-update job timestamp. |
| Round composition and ordering | Counted runs form adjacent `CI` → `Security` → `Deploy smoke` triples in strict global creation and start order. Each member starts strictly after the preceding serialization watermark, the next round starts strictly after the preceding Deploy watermark, and all six run creation/start timestamps in a triple fit one six-hour window. | A completed failure, extra/out-of-order/unpaired run, equality, overlap, or overlong triple breaks the streak while preserving the latest preceding completion watermark; such structurally valid evidence can age out before a later counted window. |
| Required round count `N` | The compiled and dispatch-default minimum is `10`; omitting the dispatch field selects `10`. An explicitly supplied dispatch value is a canonical positive decimal integer at least `10` and can only raise the gate. The caller/default value is recorded as `requestedRuns` and as the effective `requiredRuns`. The local CLI requires the argument. | Reject an explicitly supplied malformed or lower value, or an omitted local-CLI value; never clamp downward or count against it. A valid history passes only when its newest clean streak is at least the recorded effective `N`. |

Run ten serialized rounds on the qualification tag. A round is positively proven only by three
adjacent `workflow_dispatch` runs on the exact resolved SHA and exact qualification-tag
`head_branch`, in strict global creation and start order `CI` → `Security` → `Deploy smoke`. Both
the `created_at` and `run_started_at` timestamps for all three runs must fall in one six-hour window.
Every relevant run must have `updated_at`, and every returned job must have both `started_at` and
`completed_at`; missing chronology makes the input unusable. A run's conservative completion
boundary is the latest of its `updated_at` and all of its jobs' start and completion values. Security
must start strictly after the CI boundary, Deploy smoke strictly after the Security boundary, and the
next round's CI strictly after the previous round's Deploy smoke boundary. The completion watermark is preserved across
failed, extra, out-of-order, and temporally invalid runs, so the first CI in a replacement streak must
also start after every preceding relevant run has completed. Equality or overlap fails the streak. In
each round, dispatch CI and wait for it to finish, dispatch Security and wait, then dispatch Deploy
smoke and wait. Start the next round only after all three runs in the current round have completed:

```bash
gh workflow run ci.yml --ref "$qualification_tag"
gh workflow run security.yml --ref "$qualification_tag"
gh workflow run deploy-smoke.yml --ref "$qualification_tag"
```

Do not batch multiple dispatches of the same workflow. No other CI, Security, or Deploy smoke run on
that SHA and tag may appear inside a round or between its rounds. Each workflow's concurrency group
is keyed by the tag, so a newer pending dispatch replaces an older pending dispatch and creates a
`cancelled` streak break. Any non-completed run or job makes the entire snapshot unusable because it
has no proven terminal boundary. A completed failed, cancelled, or timed-out run, a non-first-attempt
run, or an unpaired or out-of-order extra dispatch resets the streak. Such structurally valid terminal
evidence may be ignored only when it is strictly older than the beginning of the newest counted
window. Do not re-run a failed run: dispatch a new first-attempt round and continue until the newest
streak contains ten clean rounds.

Run the auditor from `main`, passing the frozen qualification tag:

```bash
gh workflow run candidate-soak.yml \
  --ref main \
  -f ref="$qualification_tag" \
  -f required_runs=10
```

The uploaded soak artifact is valid only when its repository and workflow provenance match the first
two rows of the trust-boundary table. This excludes a fork even when it contains identical refs and
SHAs, and excludes an auditor dispatched from a feature branch. The evidence artifact and step
summary record the repository name, pinned repository ID, non-fork event value, `github.ref`,
`github.workflow_ref`, `github.sha`, `github.workflow_sha`, both tagger dates, the collection-time
`main` SHA, and the compare status. Preserve all provenance values with the gate evidence.

The workflow binds the tag object and commit SHA before and after collection, reads two complete
unfiltered Actions run snapshots and attempt-specific job pages from the fixed canonical repository,
writes the verdict and every counted run URL to the step summary, and uploads
`candidate-soak-evidence.json`. The same auditor can be run locally over saved API responses.
Evidence schema version 2 records `ref`, `resolvedSha`, `repositoryProvenance`,
`workflowProvenance`, both `tagBinding` snapshots, the collection-time `mainBinding`,
`requestedRuns`, effective `requiredRuns`,
`cleanStreak`, `verdict`, `reasons`, `coverageGaps`, newest-first `pipelines`, and `streakBreaks`. Runs are ordered once by
global `created_at`; the auditor recognizes only adjacent CI/Security/Deploy smoke triples whose
strict `created_at` and `run_started_at` order, completion-based serialization, and six-hour bound
prove one coherent round. It never pairs independently sorted workflow histories. Across the
relevant history, `run_started_at` order must also agree with global creation order, so a late rerun
cannot hide at its original creation position. When recreating evidence locally, pass the repository,
workflow, tag-binding, and main-binding values captured from the original auditor workflow run:

```bash
auditor_sha="<recorded github.sha>"
auditor_workflow_sha="<recorded github.workflow_sha>"
python3 .github/scripts/audit-candidate-soak.py \
  --runs-json candidate-runs.json \
  --jobs-json candidate-jobs.json \
  --tag-json candidate-tag.json \
  --main-json candidate-main.json \
  --ref "$qualification_tag" \
  --repository itkla/connex \
  --repository-id 1222010579 \
  --event-repository-id 1222010579 \
  --repository-fork false \
  --workflow-ref refs/heads/main \
  --workflow-sha "$auditor_sha" \
  --github-workflow-ref \
    itkla/connex/.github/workflows/candidate-soak.yml@refs/heads/main \
  --github-workflow-sha "$auditor_workflow_sha" \
  --required-runs 10 \
  --output candidate-soak-evidence.json \
  --summary candidate-soak-summary.md
```

The run input is an object whose `start` and `end` values are the arrays of
`{total_count, workflow_runs}` pages produced by unfiltered `gh api --paginate --slurp` calls before
and after job collection. Only the records on the requested tag for the three relevant workflow paths
must be identical between snapshots; unrelated repository activity does not invalidate the audit.
The jobs input contains one
`{run_id, run_attempt, pages: [{total_count, jobs}, ...]}` page set for every run of the three relevant workflows
on the requested tag; jobs for unrelated workflows or refs are not collected. The tag input contains
the raw Git ref and annotated-tag responses at `start` and `end`. The main input contains the raw
`refs/heads/main` Git ref response and `compare/main...<resolved-candidate-sha>` response captured
before run collection. Every page set must be complete, every run and job `id` must be unique, every
run number must be unique within its workflow, and the number of unique IDs must equal the
consistent `total_count`; duplicate IDs, even when the raw entry count still matches, make the input
unusable. Workflow identity is the canonical portion of the run's workflow-file `path`, not its
display name; the complete path and `referenced_workflows` provenance remain in the evidence. Every
relevant run and returned job must explicitly report `status: completed` and a recognized terminal
conclusion; every counted run and mandatory job must conclude `success`. The run's `head_sha` and
`head_branch` must equal the resolved SHA and requested qualification tag. Any relevant run on that tag at another
SHA invalidates the whole input. A run from `main` or another tag pointing at the same SHA does not
count. Ten green runs across changing commits or refs do not satisfy this gate.

For a full dispatch, the evidence checks every runnable leaf job declared by each workflow plus its
aggregate context: the eight CI leaves and `CI — required`; the six Security leaves and
`Security — required`; and the four Deploy smoke leaves and `Deploy — required`. This includes OCR,
ClamAV, backup, support-bundle, workflow-pin, dependency-audit, compose, staging, profile-boot, and
source-install coverage. Event-specific Security jobs that cannot run on `workflow_dispatch` are not
part of this dispatch contract. Aggregate contexts are recorded but are not substitutes for their
leaves because their gate scripts accept some dependencies as `skipped`; a skipped mandatory leaf
never counts as soak success. The parity regression derives this set from the workflow dependency
graphs and fails when the declarations drift.

A `workflow_dispatch` Security run uploads the backend and frontend CodeQL analyses but does not run
the `Block Critical, High, or error-severity alerts` steps, which are restricted to pull requests and
merge groups. The evidence therefore labels a passing verdict as qualified and records this CodeQL
alert-blocking coverage gap. It is soak evidence for repeated complete-suite execution, not a
replacement for the pull-request/merge-group alert gate or the release workflow's push-on-main
precondition. Paste the artifact and summary into the gate issue manually; the workflow intentionally
has no `issues: write` permission.

`.github/workflows/release.yml` treats the three images as one release transaction:

Before the workflow can cut or resume a release, repository immutable releases must be enabled and
the `CONNEX_RELEASE_ADMIN_TOKEN` Actions secret must provide repository Administration read access.
The normal `GITHUB_TOKEN` remains the only token used to upload release assets. The administration
token is used only to fail closed on the immutable-release policy precondition.

1. It rejects anything except strict `vMAJOR.MINOR.PATCH` with optional SemVer prerelease
   identifiers. Before creating a transaction, it requires the tag to point to the current `main`
   head and waits for the latest `push` run of the repository's CI, security, and deployment-smoke
   workflows to succeed for that exact commit. A resume uses the already signed transaction even if
   `main` has advanced. Workflow identity is resolved through the GitHub Actions API, not by accepting
   a matching check name from another integration.
2. It builds each component with a reproducible commit timestamp and pushes only an attempt-scoped
   `candidate-<run>-<attempt>` tag. Every attempt builds from the checked-out release commit with
   pinned Buildx and BuildKit versions and emits BuildKit provenance; it never trusts content found
   behind a pre-existing candidate tag. Each resulting digest is rejected on high-or-critical known
   vulnerabilities, signed with cosign, and receives an SPDX SBOM attestation.
3. It boots the exact three candidate digests together through the deployment Compose bundle with
   OCR enabled. The gate verifies `/api/version`, scanning/import capabilities, running image
   identities, OCR and ClamAV health/isolation/resource limits, and the one-shot maintenance
   invocation. The ClamAV candidate is separately smoke-tested with no network at all, asserting
   that the EICAR standard test file is detected and that a limit-exceeding archive is reported
   unscannable rather than clean. It
   then generates the canonical fixtures with the pinned font inside the exact OCR image and runs
   the authenticated 40-case English/Japanese/mixed benchmark. Promotion is blocked unless every
   HTTP response and accuracy/latency gate passes.
4. After those gates pass, it creates one signed, run-scoped release transaction containing the
   backend, frontend, OCR and ClamAV image digests, raw SBOM hashes, deterministic deployment
   bundle hash, benchmark report, and
   deterministic fixture archive. The transaction artifact is named for its originating run attempt.
   A retry discovers and reuses this committed transaction instead of mixing or rebuilding candidates,
   so partial tag promotion can safely resume the same digest set.
5. Promotion verifies the transaction, every candidate signature, SBOM attestation, and GitHub
   build attestation; rejects any conflicting destination tag; and fills only absent matching
   `:<version>` and `:sha-<commit>` convenience names.
6. Publication uploads every transaction-bound asset to a draft GitHub Release, downloads and
   re-hashes the complete draft, and only then makes it public. Prerelease tags are explicitly marked
   as prereleases and excluded from GitHub's latest release. A public release is the availability
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
for component in backend frontend ocr clamav; do
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
for component in backend frontend ocr clamav; do
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

### Checking versions in the product

An authorized administrator can open **Settings → Workspace → Audit & diagnostics** or
**Settings → Organization → Audit & diagnostics** and read **Version check**. The section compares
the version of the app shown in the browser with the server version. It also shows when the server
version was created and its version reference when those details are available. Matching version
numbers alone are reported as unconfirmed. Connex reports a confirmed match only when it can also
verify that the browser app and server were released together. If that check says they were not
released together, the section reports a mismatch even when the displayed version numbers agree.

## Release channels

Three, no more:

- **Continuous** — SaaS/staging track `main`.
- **Prerelease tags** — trial and release candidates use pinned `vX.Y.Z-<identifier>` artifacts and
  never become GitHub's latest release.
- **Stable tags** — silo and on-prem run a pinned `vX.Y.Z`. "Silo staged rollout" is Connex applying a stable tag on a schedule, not separate channel infrastructure.

## Repointing staging (host-side, not in this repo)

Staging currently checks out `main` and runs it via an out-of-repo systemd unit on the staging host. To consume the pipeline instead, on that host:

1. Authenticate to GHCR (`docker login ghcr.io`).
2. Replace the checkout-and-run unit with one that verifies the release manifest using the exact
   tag-bound identity above, derives the four `image@sha256:...` references, and runs those digests
   with the existing staging env (the fail-closed security env staging already requires — DB
   `sslMode`, secret-store, audit secrets, OCR token, and ClamAV token). The supported templates
   activate the `ocr,clamav` Compose profiles by default; the documented low-resource opt-out drops
   only `ocr`, never `clamav` — malware scanning is a mandatory control, and a backend with a
   deployment profile refuses to start without it.
3. Verify and deploy a new signed manifest to roll forward.

This step lives on the host because the systemd units and cloudflared config are not in the repository.
