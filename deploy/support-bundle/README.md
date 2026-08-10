# Connex support bundle tooling

Operator-side commands for downloading, verifying, and reading a **redacted**
Connex support bundle.

The bundle itself is produced by the backend at
`GET /api/orgs/{orgId}/support-bundle`. What it contains, what it never
contains, and the full redaction contract are documented in
[`docs/SUPPORT_BUNDLE.md`](../../docs/SUPPORT_BUNDLE.md). Read that first — these
scripts are transport and verification only, and they cannot widen what a bundle
holds.

## Commands

| Script | Purpose |
|---|---|
| `collect.sh` | Downloads a bundle for one organization, verifies it, and publishes it atomically. Optionally appends a closed-field journal projection. |
| `read.sh` | Verifies a bundle's manifest and hashes, then renders it and filters client errors plus the explicitly untrusted client-asserted audit column by correlation ID. |
| `support-bundle-lib.sh` | Shared logging, validation, and integrity primitives. Not run directly. |
| `tests/run-tests.sh` | Offline regression tests. No network, backend, Docker, root, or systemd. |

## Requirements

`curl`, `jq`, `unzip`, `zipinfo`, `sha256sum`, `column`, and `python3` (used to parse CSV
correctly, so a comma inside a quoted field cannot shift values under the wrong header). Missing
dependencies exit 64.

## Authentication

The endpoint requires an **organization administrator** who has completed a
**recent WebAuthn step-up**. That step-up cannot be scripted from a password, so
`collect.sh` does not attempt a login flow. Instead:

1. Sign in to Connex as an organization administrator.
2. Complete the passkey step-up when the bundle download prompts for it.
3. Export the session to a Netscape/curl cookie file.
4. `chmod 600` the file and pass it with `--cookie-file`.

The session value is never accepted on the command line, in an environment
variable, or written to a log field. A cookie file that is not mode 0600 is
refused rather than warned about — a group-readable file hands a live
administrator session to every local account.

Step-up windows are short. If the download returns 403 with a step-up remedy,
repeat the step-up and export a fresh cookie file.

## Usage

Download the default seven-day window for an organization:

```bash
deploy/support-bundle/collect.sh \
    --base-url https://connex.example.com \
    --org-id 3 \
    --cookie-file /etc/connex-support/cookies.txt \
    --output /var/tmp/bundle.zip
```

Investigate one record with a correlation ID from a user report:

```bash
deploy/support-bundle/collect.sh \
    --base-url https://connex.example.com \
    --org-id 3 \
    --cookie-file /etc/connex-support/cookies.txt \
    --workspace-id 7 \
    --entity-type person --entity-id 412 \
    --correlation-id abcd1234efgh \
    --since 2026-07-24T05:00:00Z \
    --output /var/tmp/bundle.zip
```

`--entity-type` and `--entity-id` are only legal together, and they require
`--workspace-id`. That trio is what unlocks workspace record events; the backend
additionally requires `AUDIT_READ` in the resolved workspace, so organization
administration alone never exposes them.

Read a bundle:

```bash
deploy/support-bundle/read.sh --archive /var/tmp/bundle.zip
deploy/support-bundle/read.sh --archive /var/tmp/bundle.zip --correlation-id abcd1234efgh
deploy/support-bundle/read.sh --archive /var/tmp/bundle.zip --digest 3819274061
deploy/support-bundle/read.sh --archive /var/tmp/bundle.zip --section audit
deploy/support-bundle/read.sh --archive /var/tmp/bundle.zip --section client-errors
```

The correlation filter is a lookup aid, not an audit identity. It matches
`client-errors.json.correlationId` and
`audit-slice.csv.untrustedClientAssertedCorrelationId`. Use the separate
`serverMintedRequestId` column for the non-spoofable within-audit pivot.
The digest filter is the normal broken-page workflow: it finds the exact
framework reference in `client-errors.json` and leaves the bundle's complete audit
slice visible. The error report is a later request, so its correlation id is not
treated as proof that an earlier audit event caused the page failure.

Nothing is rendered until the archive has passed verification in full: safe
entry names, every inventory entry matched to its recorded byte length and
SHA-256, and every extracted file present in the inventory. A bundle whose
manifest is missing — the shape a truncated backend stream produces — is refused
rather than partially displayed.

## Why there is no journal slice

An earlier revision could append a projection of the systemd journal. It was removed, because a
unit's journal cannot be scoped to one organization from here: on a multi-tenant backend unit,
collecting the time window would append other tenants' correlation ids, request paths, statuses,
and event classes to an artifact built specifically to leave the deployment. That is the exact
failure this feature exists to prevent.

It was also ineffective. In the production logging configuration Spring writes ECS JSON to the
console and journald stores that whole record in `MESSAGE`; nothing emits the native structured
fields the projection read, so every projected record would have been empty anyway.

Re-introducing it needs a trustworthy per-record organization discriminator to filter on exactly.
Until then, correlate against the deployment's own logs directly, as
[`docs/DEPLOYMENT.md`](../../docs/DEPLOYMENT.md) describes.

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | Success. A `*_summary status=success` line is emitted. |
| 64 | Usage, configuration, dependency, unsafe path, or unsafe cookie-file permissions. |
| 65 | Authentication, organization authorization, or step-up failure. |
| 66 | API transport failure, including 400 and 429. |
| 67 | Bundle integrity: ZIP structure, manifest schema, inventory coverage, or SHA-256 mismatch. |
| 69 | Reader extraction, rendering, or filtering failure. |

Every run emits single-line structured events (`ts=… level=… event=… key=value`)
and one final `support_bundle_collect_summary` or `support_bundle_read_summary`
line carrying `status`, `exit_code`, `phase`, and `duration_seconds`. Failure
summaries are mirrored to stderr.

## Reproducing a reported state locally

`read.sh` is read-only by design and offers **no import path**. A bundle is
metadata; it cannot rebuild a tenant's data, and building an importer would turn
a redacted artefact back into a data-bearing one.

To reproduce locally, seed a workspace and recreate the facts the bundle
reports:

```bash
cd backend && bash gradlew seedData
```

Then use the bundle's `migrations.json` to match schema state, `config.json` and
`readiness.json` to match the deployment's feature posture, and the audit slice
to replay the sequence of actions that produced the report.

## Tests

```bash
deploy/support-bundle/tests/run-tests.sh
```

The redaction fixtures are ported verbatim from `RequestPathRedactorTest`. If
the Java rules change, both that test and these fixtures must change together,
or the operator-side journal projection will silently diverge from the redaction
the backend guarantees.
