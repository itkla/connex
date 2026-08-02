# Support bundle

A support bundle is a small, **redacted, metadata-only** archive that an
organization administrator can download from Connex and hand to a support
engineer. It exists so that a support person can diagnose a real ticket —
"a contact vanished", "this person cannot see the deal", "the scheduled report
never arrived" — **without database access and without SSH onto the host.**

A bundle is deliberately not a data export. It answers *what happened, in what
order, under what deployment posture*. It never answers *what the record said*.

- Operator commands: [`deploy/support-bundle/`](../deploy/support-bundle/README.md)
- Deployment context: [`DEPLOYMENT.md`](DEPLOYMENT.md)

## Contents

`GET /api/orgs/{orgId}/support-bundle` streams a ZIP containing:

| Entry | What it holds |
|---|---|
| `manifest.json` | Bundle schema version, product version, generation time, organization id, the filters that were applied, a SHA-256 inventory of every other entry, and a map of declared omissions. |
| `readiness.json` | Deployment profile, capability availability, provider readiness booleans, and stable reason codes. |
| `config.json` | Values for an explicit allowlist of configuration keys — nothing else. |
| `migrations.json` | Flyway history: version, description, success, installed-on. |
| `audit-slice.csv` | The audit events for the requested window, organization-plane always, workspace record events only under an entity filter. |
| `job-runs.json` | Scheduler run outcomes, when the deployment has the job-run table. |

### Filters

| Parameter | Contract |
|---|---|
| `correlationId` | Matches the request correlation id recorded on audit rows. |
| `entityType` + `entityId` | Legal only together. Unlocks workspace record events for that one record. |
| `since` | Defaults to 7 days before generation; 30 days is the maximum. Older or future values are rejected. |

### Declared omissions

When a source is unavailable or cannot be proven safe, the bundle **says so** in
`manifest.json` rather than silently shipping a smaller archive:

| Omission | Meaning |
|---|---|
| `client-errors.json: no_persisted_source` | Client errors are forwarded to the log sink and never persisted, so there is no safe stored source to slice. See [Client errors](#client-errors). |
| `job-runs.json: job_run_not_available` | This deployment predates the job-run table. |

An absent file therefore never means "no problem found". It means "this source
was not collected, and here is why".

## What is never collected

This list is the contract, not a summary of current behaviour. No bundle entry
may contain:

- secret plaintext, ciphertext, or encrypted data keys;
- master, HMAC, private, or API keys, or any key bytes;
- passwords, or bearer, session, CSRF, invite, unsubscribe, or webhook tokens;
- cookies or authorization headers;
- database or datasource URLs;
- environment variables or JVM system properties;
- provider credential bundles;
- raw mail, AI, OCR, SSO, storage, or database hosts and URLs;
- any hostname carrying URI user-info;
- audit `changes` or `context` bodies, target labels, or summaries;
- client error messages or stack traces;
- job-run `detail` payloads;
- personal names, email addresses, or any record field values;
- raw journald messages or stack traces;
- IP addresses, user agents, or session identifiers.

## Redaction contract

The bundle is built by **constructive allowlisting**. Each entry is assembled
field by field from sources proven safe, rather than serialized broadly and
scrubbed afterwards. Scrubbing fails silently when a new field appears;
allowlisting fails closed.

Three rules follow from that, and they are the ones to hold the implementation
to:

1. **If a field cannot be proven safe, it is omitted — never masked.** A
   secret-shaped configuration key does not appear as `"***"`, as a hash, as a
   presence marker, or as `null`. It is absent, and its absence is what the test
   suite asserts.
2. **Omission is recorded.** A dropped file is declared in `manifest.json`, so a
   reader can distinguish "collected and empty" from "not collected".
3. **Identity is an id, not a name.** The audit slice carries `actorId` and no
   display name. See [Actor attribution](#actor-attribution).

### Configuration allowlist

`config.json` is produced from a closed constant. Only keys that describe
deployment *posture* — which features are on, which profile is active — are
eligible. A key is ineligible if its name contains any of:

```
password, passwd, secret, token, credential, authorization, cookie, session,
key, client-id, username, email, host, url, uri, origin, endpoint, domain,
bucket, catalog, database, datasource
```

A test enforces this against the allowlist itself, so a future key that looks
credential- or location-bearing cannot be added without the test failing. This
excludes hosts and URLs deliberately: a mail or storage hostname is often
customer-identifying, and a datasource URL routinely carries user-info.

### Actor attribution

`audit-slice.csv` identifies actors by `actorId` only. Display names are
excluded even though the bundle's whole purpose is answering "who did this",
because a bundle **travels** — it leaves the tenant and reaches Connex support.
Employee names are exactly the personal data a redacted artefact must not
export.

This costs the support engineer nothing. The audit row proves *which account*
acted; the organization administrator resolves that id to a person in their own
admin UI, inside their own tenant, where the name was never a secret. The
resolution step is part of the walk-through below.

### Client errors

`ClientErrorService` forwards reports to the logging sink and persists nothing.
Its `message` and `detail` fields carry the browser's error text and stack, which
are only length-bounded — never redacted — and routinely embed record values,
email addresses, and query strings.

So there is no safe stored source, and the bundle declares
`client-errors.json: no_persisted_source` rather than reading logs. Persisting a
metadata-only projection (correlation id, redacted page path, digest, timestamp)
is tracked as follow-up work; until it exists, correlate client errors through
the correlation id in the journal slice instead.

### Correlation ids

Audit rows record the request's correlation id, which is the same value returned
to the client as `X-Correlation-Id`. A user who reports "it failed and the page
showed this id" can therefore be traced directly to the audit rows for that
request.

**Pre-cutoff blind spot:** audit rows written before this alignment shipped
carry a separately generated request id that was never surfaced to any client.
Those rows are findable by entity, actor, and window, but not by a correlation
id a user could have seen. When investigating older activity, filter by
`entityType`/`entityId` and `since` instead.

### Journal slice

The backend never reads logs from disk. When `collect.sh` runs on the host,
`--include-journal` appends a **closed-field projection** of the systemd
journal: timestamp, level, logger, correlation id, request method, redacted
request path, response status, and event class. Raw `MESSAGE` bodies and stack
traces are dropped wholesale rather than filtered, and paths pass through the
same redaction rules as `RequestPathRedactor`. There is no raw fallback: if the
projection fails, the run fails.

## Access control

- **Organization administrator** role is required.
- **Recent authentication** (WebAuthn step-up) is required. Both checks run
  before any bytes are streamed, so an unauthorized caller never receives a
  partial archive.
- **Workspace record events are double-gated.** They appear only when an entity
  filter is supplied, *and* the resolved active workspace belongs to the
  requested organization, *and* the caller holds `AUDIT_READ` in that workspace.
  Organization administration alone does not unlock them.
- Every download is itself audited as an organization-plane event carrying only
  the filter metadata.
- Bundles are streamed with `no-store`, `nosniff`, and a sandboxed CSP, and are
  never written to disk server-side.

## Integrity

`manifest.json` is written last and lists a SHA-256 and byte length for every
other entry. It does not hash itself.

Verification is total in both directions: every inventory entry must exist with
the recorded length and digest, and every file in the archive must appear in the
inventory. An unlisted extra file is rejected rather than displayed, because an
unlisted file has not passed the redaction review the inventory represents.

If the stream fails part-way the archive ends up **without a manifest**, and
`read.sh` refuses it. A truncated bundle is never partially trusted.

## Walk-through: "a contact vanished"

A user reports that a person record disappeared. Support has no database access
and no SSH.

1. **Collect.** The organization administrator signs in, completes the passkey
   step-up, exports a mode-0600 cookie file, and runs:

   ```bash
   deploy/support-bundle/collect.sh \
       --base-url https://connex.example.com \
       --org-id 3 --cookie-file /etc/connex-support/cookies.txt \
       --workspace-id 7 --entity-type person --entity-id 412 \
       --since 2026-07-24T05:00:00Z --output /var/tmp/bundle.zip
   ```

   The bundle is verified against its manifest before it is published.

2. **Read.**

   ```bash
   deploy/support-bundle/read.sh --archive /var/tmp/bundle.zip
   ```

   Hashes are checked before anything renders.

3. **Rule out the platform.** `readiness.json` shows the deployment profile and
   capability/provider state; `job-runs.json` (where present) shows recent
   scheduler outcomes. If sync and scheduling were healthy, the record was not
   lost by an integration or a failed job. `migrations.json` confirms the schema
   is fully migrated, ruling out a half-applied deployment.

4. **Find the event.** The audit slice contains a `person.archive` row for
   entity `412`, with its timestamp, outcome, correlation id, and `actorId`.
   The record was archived deliberately — it was never deleted and never lost.

5. **Resolve the actor.** Support reports the `actorId` and timestamp back to
   the organization administrator, who resolves that id to a colleague in their
   own admin UI. The name never leaves the tenant.

6. **Resolve the ticket.** The administrator restores the person from the
   Archived view. Support's answer is complete: *what happened* (archived, not
   lost), *when*, *by which account*, and *that no platform fault was involved* —
   established entirely from the bundle, with no database and no SSH.

If the user quoted an error id, passing `--correlation-id` to both commands
narrows every section to that one request.

## Reproducing locally

`read.sh` offers no import path, and none should be built: an importer would
turn a deliberately redacted artefact back into a data-bearing one. To reproduce
a reported state, seed a local workspace with `bash gradlew seedData` and
recreate the facts the bundle reports, using `migrations.json` for schema state
and `config.json`/`readiness.json` for the deployment's feature posture.
