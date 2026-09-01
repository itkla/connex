# Connex ClamAV — Agent Guide

This guide applies to `clamav/` in addition to the root `AGENTS.md`. The package is a private,
network-isolated malware-scanning sidecar used only by the Spring backend, at the upload boundary.

**This is a control, not a feature.** OCR is optional; this is not. If the sidecar is unreachable,
slow, erroring, or running past the signature ceiling, the backend refuses the upload with 503. It
never admits unscanned bytes. Every change here must preserve that: there is no acceptable
code path in which a scanner problem produces a `clean` verdict.

## Runtime and dependencies

- Production and local runs go through `clamav/Dockerfile`; a host Python environment is not a
  supported runtime. The base is the same digest-pinned `python:3.12-slim-bookworm` as `ocr/`.
- **The service is Python-stdlib-only on purpose.** It has no `requirements.txt` and no lock file,
  so there is no pip supply chain and no dependency audit to run. Adding a pip dependency means
  adding the whole `ocr/`-style hash-pinned lock and audit apparatus; justify it first.
- ClamAV comes from Debian packages (`clamav`, `clamav-daemon`, `clamav-freshclam`), matching
  `ocr/`'s unpinned-apt precedent. Dependabot watches the base image.
- `clamd` runs as a supervised child of `clamav_service.supervisor`, on a **unix socket only**.
  It has no TCP listener and the image exposes **only 8091**. Never publish 3310 and never add a
  `TCPSocket` line to `clamd.conf`: `clamd`'s own `SCAN`/`MULTISCAN` commands take filesystem
  paths, so a reachable clamd port is both an unauthenticated scanning surface and an
  arbitrary-file-read primitive. `clamav/ci/smoke_image.sh` asserts the exposed-port set.
- The backend-to-sidecar bearer token must be unique, secret, newline-free, and at least 32
  characters. A short token fails startup rather than silently disabling the control.

## `clamd.conf` is load-bearing — read the comments before changing a number

Every limit is set explicitly. Several upstream defaults are actively unsafe here.

- **`AlertExceedsMax yes` is the single most important line in the package.** Upstream default is
  `no`, and with `no` a file that exceeds `MaxFileSize`, `MaxScanSize`, `MaxFiles` or
  `MaxRecursion` is reported **clean**. Removing it silently converts this control into
  decoration. `tests/test_clamd.py` and `ci/runtime_smoke.py` both guard it; if you change the
  verdict mapping, mutate it and confirm those go red.
- `AlertEncrypted*` are on: an encrypted container cannot be inspected, so admitting one is a
  trivial bypass.
- `AlertOLE2Macros` is deliberately **off**. Scan macros for known malware; do not blanket-reject
  macro-bearing Office documents (issue #1240). It is largely moot today because `UploadPolicy`'s
  allowlist already excludes macro-enabled OOXML — it matters only if that allowlist widens.
- Limits are reconciled with the backend: `connex.object-storage.max-upload-bytes` is 25 MiB, so
  `StreamMaxLength`/`MaxFileSize` are 32 MiB and `MaxScanSize` is 192 MiB (above
  `UploadContentInspector`'s 64 MiB ZIP expansion bound). CI asserts
  `CONNEX_CLAMAV_MAX_SCAN_BYTES` equals the backend's upload ceiling.
- Only directives that exist in the pinned release's `clamd.conf.sample` may appear. `clamd`
  fails to start on an unknown directive, and a bare directive with no argument is a parse error.

## Writable scratch and sizing — not optional, not copyable from `ocr/`

`clamd` does **not** scan an `INSTREAM` incrementally. It spools the submitted bytes to a
temporary file and unpacks archive members beside it. With `read_only: true` this requires a
correctly sized tmpfs, and the size is arithmetic, not preference:

    max_concurrent_scans x (StreamMaxLength + MaxScanSize) = 2 x (32 MiB + 192 MiB) = 448 MiB

`clamav_service.config.verify_scratch_capacity` **refuses to start** below that, so an undersized
mount is a deployment error rather than a stream of ENOSPC failures under exactly the load the
control exists for. Raising `CONNEX_CLAMAV_MAX_CONCURRENT_SCANS` requires raising the tmpfs and
`mem_limit` together. The `ocr` service's 64 MiB tmpfs is not a usable reference.

Memory: `clamd` holds the signature database resident (~2 GiB and growing) and tmpfs pages are
charged to the same cgroup, so `mem_limit` is 3 GiB. An undersized limit presents as an
OOM-killed container that looks exactly like a scanner outage.

## Signatures

- **Baked at image build, verified by digital signature — not by SHA-256.** Signature databases
  are the one artifact class where pinning a digest is wrong: `daily.cvd` is republished several
  times a day, so a pinned hash breaks the build within hours and pressures whoever hits it into
  deleting the check. CVD containers are RSA-signed by the publisher; `prefetch.py` verifies that
  with `sigtool --info` and records versions in a baked manifest. The build fails rather than
  shipping an image without signatures.
- **No runtime downloads. Ever.** `clamav_internal` is `internal: true`. The container reaches
  readiness with `--network none`, which is what makes the air-gapped on-prem story true.
- Freshness is measured from `daily.cvd` only. `main.cvd` is a rarely-republished base set that
  is legitimately months or years old; aging on the oldest container makes a freshly built image
  start life past the ceiling and refuse every upload. Both must be present; only daily is aged.
- Freshness is graded: warn at 7 days, **hard-block uploads at 30**, and the ceiling cannot be
  raised. `/health` reports `signature_age_seconds` and `seconds_until_block` so operators see the
  countdown well before the cliff. Definition *failure* is different from *age*: a database that
  cannot load means the sidecar never reports ready, which is an immediate 503.
- Operator update procedures, including the air-gapped `cvdupdate` workflow, live in
  `docs/MALWARE_SCANNING.md`.

## Code rules

- Python follows the root no-inline-comments rule. Docstrings only, on genuine public surfaces.
  `clamd.conf`, `freshclam.conf`, the Dockerfile and the shell scripts are config/operator files
  and **should** carry comments explaining non-obvious constraints.
- Keep the HTTP surface at unauthenticated `GET /health`, authenticated `GET /ready`, and
  authenticated raw-bytes `POST /v1/scan`. Do not add filesystem paths, remote URL fetching,
  multipart parsing, or arbitrary clamd option passthrough.
- Never log scanned bytes, filenames, signature names, request headers, or the token. Startup
  diagnostics use the allowlisted `ClamAV startup failed: component=...; reason=...;
  exception_types=...` shape; scan failures use `ClamAV scan failed: reason=...`.
- Verdicts are exactly `clean` | `infected` | `unscannable`. A detection that means "could not be
  inspected" (`Heuristics.Limits.Exceeded*`, `Heuristics.Encrypted*`,
  `Heuristics.OLE2.ContainsMacros*`) is `unscannable`, not `infected`: both reject the upload, but
  only one tells a user their spreadsheet is a virus. Any unrecognised reply raises and becomes a
  503; there is no clean-by-default branch.
- Signature names are collapsed through `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$` to `unnamed` before
  they leave the sidecar, because the attacker chooses which signature fires.
- Preserve non-blocking backpressure: overlapping scans beyond the concurrency limit return `429`,
  never an unbounded queue. Every request stays under an absolute deadline, and clamd's
  `MaxScanTime` is set below it so clamd gives up first and returns a classifiable answer.

## Delegation and review

Follow the root delegation tiers. Changes to the verdict mapping, `clamd.conf` limits, the scan
deadline, network isolation, signature acquisition, freshness thresholds, or resource limits are
**Tier 3**: two non-overlapping reviews (security/isolation and correctness/fail-closed
behaviour). Parser, diagnostics, fixture and documentation changes are normally Tier 1.

## Verification

```bash
cd clamav
PYTHONPATH=. python3 -m unittest discover -s tests -v
shellcheck -x ci/smoke_image.sh
docker build -t connex-clamav:verify .
bash ci/smoke_image.sh connex-clamav:verify
```

The image build downloads signatures from `database.clamav.net`, which rate-limits shared CI
egress; `prefetch.py` retries with backoff, and a 429 is an infrastructure retry, not a code
failure. The smoke script boots the real image with `--network none` and asserts EICAR detection,
that a limit-exceeding archive is reported `unscannable` rather than `clean`, that an undersized
scan mount fails startup, and that only 8091 is exposed.

**Be hostile to your own green.** Before claiming a verdict-mapping change is safe, mutate the
mapping to answer `clean` and confirm the suite goes red.

## Deployment checks

When runtime behaviour or structure changes, update all of these together:

- `backend/docker-compose.yml` (local `clamav` profile);
- `deploy/docker-compose.yml` and `deploy/docker-compose.build.yml`;
- `deploy/onprem.env.example` and `deploy/silo.env.example` (both enable it);
  `deploy/eval.env.example` deliberately disables it and must say why;
- `.github/workflows/ci.yml`, `release.yml`, `deploy-smoke.yml`, `.github/dependabot.yml`,
  `.github/scripts/classify-ci-changes.py` and `test_deployment_networks.py`;
- `docs/MALWARE_SCANNING.md`, `docs/DEPLOYMENT.md`, `docs/UPGRADING.md`;
- this guide and `backend/AGENTS.md` when conventions change.
