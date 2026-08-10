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
| `client-errors.json` | Redacted client-error metadata: id, workspace id, correlation id, optional framework digest, page path, and report time. |
| `job-runs.json` | Not produced in this release — see [Declared omissions](#declared-omissions). |

### Filters

| Parameter | Contract |
|---|---|
| `correlationId` | Matches the `untrustedClientAssertedCorrelationId` on audit rows and the `correlationId` on client-error metadata — see [Request ids](#request-ids). |
| `entityType` + `entityId` | Legal only together. Unlocks workspace record events for that one record. |
| `since` | Defaults to 7 days before generation; 30 days is the maximum. Older or future values are rejected. |

### Declared omissions

When a source is unavailable or cannot be proven safe, the bundle **says so** in
`manifest.json` rather than silently shipping a smaller archive:

| Omission | Meaning |
|---|---|
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
- raw journald messages or stack traces (no log content is collected at all);
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

`ClientErrorService` still forwards the full bounded report to the deployment-local logging sink,
but it separately persists a constructive metadata projection containing only `id`, `workspaceId`,
`correlationId`, optional framework `digest`, `pagePath`, and `reportedAt`. The page path has query
and fragment content removed and credential-bearing segments replaced by `RequestPathRedactor`
before persistence. Metadata is retained for 30 days, matching the maximum bundle window.

The client error's `message`, composed `detail`, and browser `stack` never reach the metadata mapper,
the database table, or `client-errors.json`. They remain user-data-bearing local log content and must
not be copied into a support artefact. A framework digest is an opaque reference, not error text;
support can quote it back to the organization administrator or compare it with the deployment's own
logs without receiving the message or stack. Only the Next.js-generated decimal digest shape, with
an optional framework error-code suffix, is persisted or disclosed. Any other client-supplied value
is omitted from the metadata projection even though the local reporter still receives it.

### Request ids

`audit-slice.csv` carries both identifiers under names that encode their trust boundary:

- `serverMintedRequestId` is the unchanged, non-spoofable `audit_log.request_id`. It groups audit
  events emitted during one server request and remains the trustworthy audit pivot.
- `untrustedClientAssertedCorrelationId` is copied from the client-settable `X-Correlation-Id`.
  It is useful for joining a user's report, `client-errors.json`, and deployment-local logs, but it
  is **not evidence that two requests share an origin**. Any authenticated caller can reuse or
  choose it.

The `correlationId` bundle filter matches only `untrustedClientAssertedCorrelationId`; it never
changes or aliases the server-minted audit id. The manifest repeats both trust labels in
`auditSliceIdentifiers` and names `auditSliceCorrelationFilterField` explicitly. Organization and
workspace predicates are applied independently of this untrusted value, so choosing a value used in
another tenant cannot pull that tenant's audit row into the bundle.

The untrusted lookup column deliberately stays outside the existing audit-row HMAC payload so old
and new binaries can verify the same chain during a rolling deployment or rollback. It is not
integrity evidence. The server-minted id and the audited event fields retain their existing chain
semantics.

Audit rows written on scheduler and other non-request threads have neither request identifier; this
is long-standing behaviour, not a gap introduced here. Servlet redispatches keep the correlation
filter's stashed client value, and audit rows written against the same request retain its
server-minted id.

### Truncation and inconclusive results

The audit and client-error slices are capped, and a saturated window is never silently
indistinguishable from a complete one. Each query asks for one row beyond its cap and never emits
the extra row. The manifest records `auditSliceRowCount`, `auditSliceTruncated`,
`auditSliceLimit`, `clientErrorSliceRowCount`, `clientErrorSliceTruncated`, and
`clientErrorSliceLimit`. When truncation is reported, narrow `since` or add an entity filter and
collect again.

A request need not emit an audit row, and a client-asserted id is not authoritative. An empty audit
slice under a correlation filter therefore means "no permitted audit rows carried this untrusted
value", not "nothing happened". The manifest records `auditSliceInconclusive: true` for exactly
that case so an empty slice is not misread as evidence of absence.

### Why there is no journal slice

The backend never reads logs from disk, and the operator tooling no longer collects them either.

A systemd unit's journal cannot be scoped to one organization: on a multi-tenant unit, collecting
a time window would append other tenants' correlation ids, request paths, statuses, and event
classes into an artifact designed to travel to support. A filter that cannot be proven exact is
not a filter, and a bundle that silently mixes tenants is worse than one with no log slice at all.

It was ineffective as well as unsafe. In the production logging configuration Spring writes ECS
JSON to the console and journald stores that record in `MESSAGE`, so nothing emits the native
structured fields the projection read.

Re-introducing it requires a trustworthy per-record organization discriminator to filter on
exactly. Until then, correlate against the deployment's own logs directly.

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
- Bundles are served with `no-store`, `nosniff`, a sandboxed CSP, and an attachment
  disposition, and are never written to disk server-side.
- **`CONNEX_RECENT_AUTHENTICATION_WINDOW` must never be `0` in production.** Zero disables the
  step-up requirement globally, which would leave this organization-wide export behind an ordinary
  session.
- The endpoint is a `GET`, so a malicious page could cause a signed-in administrator's browser to
  request bundles. It cannot read the response — CORS, CORP, and the attachment disposition all
  hold — but it can consume the concurrency limit and write audit rows attributing downloads to
  that administrator. The terminal-outcome audit event makes such activity legible.

## Operational notes

Assembly is **synchronous**: the bundle is built in full and returned as one response, rather than
streamed. That is deliberate. A support bundle is a capped metadata snapshot, so it does not need
the async writer, monotonic deadline, and cancellation machinery that the tenant export requires
for unbounded workspace data — and an async writer would run on a thread where the tenant routing
and security context the queries depend on are not installed.

The size is bounded by construction. The audit slice dominates it and is capped at 10,000 rows of
bounded columns — roughly 300 bytes each, so about 3 MB uncompressed before compression, with the
other entries measured in kilobytes. Assembly refuses to exceed a 64 MB uncompressed ceiling, checked **before** each entry is added
rather than after, and a 30-second wall-clock budget checked between sources. Both fail closed
with `413` and a message telling the operator to narrow `since` or add an entity filter, rather
than returning a bundle that looks complete but is not.

The two collection queries additionally carry a 20-second **statement** timeout, so a pathological
query is cancelled at the database rather than merely noticed afterwards — the wall-clock budget
alone cannot interrupt a query already in flight.

Concurrent assembly is capped **per JVM**, not cluster-wide: an N-instance deployment can assemble
N times that many at once. Saturation returns `429`.

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

2. **Read from the reference the user can quote.** A broken-page screen shows a framework
   `Reference:` digest, not a correlation id. Use that exact value:

   ```bash
   deploy/support-bundle/read.sh \
       --archive /var/tmp/bundle.zip --digest 3819274061
   ```

   Hashes are checked before anything renders. The reader finds only exact digest matches in
   `client-errors.json` and preserves the complete entity-scoped audit slice. The client-error
   report is a later request, so its correlation id is not used to hide earlier entity events.

3. **Rule out the platform.** `readiness.json` shows the deployment profile and
   capability/provider state; `job-runs.json` (where present) shows recent
   scheduler outcomes. If sync and scheduling were healthy, the record was not
   lost by an integration or a failed job. `migrations.json` confirms the schema
   is fully migrated, ruling out a half-applied deployment.

4. **Correlate the report.** `client-errors.json` finds the user's exact digest and shows the
   later report request's correlation id, redacted page path, workspace, and report time. It
   contains no error text or stack. `audit-slice.csv` remains the complete history for entity 412,
   with `untrustedClientAssertedCorrelationId` and `serverMintedRequestId` separately labelled.

5. **Find the event.** The audit slice contains a `person.archive` row for entity `412`, with its
   timestamp, outcome, both clearly-labelled ids, and `actorId`. The record was archived
   deliberately — it was never deleted and never lost.

6. **Resolve the actor.** Support reports the `actorId` and timestamp back to
   the organization administrator, who resolves that id to a colleague in their
   own admin UI. The name never leaves the tenant.

7. **Resolve the ticket.** The administrator restores the person from the
   Archived view. Support's answer is complete: *what happened* (archived, not
   lost), *when*, *by which account*, and *that no platform fault was involved* —
   established entirely from the bundle, with no database and no SSH.

Treat a match on `untrustedClientAssertedCorrelationId` as a lookup aid, never as proof of request
identity. Use `serverMintedRequestId` for the trustworthy within-audit pivot, and retain the entity,
workspace, organization, and time predicates when answering the ticket.

A digest match is likewise not a causal audit filter. The browser reports an error in a later
request, whose correlation id can differ from the request or action that produced the broken page.
Use `--digest` to locate the redacted frontend reference, then investigate the complete
entity-scoped audit slice alongside it.

### Exit codes

`collect.sh` and `read.sh` share one catalog:

| Code | Meaning |
|---:|---|
| 0 | Success; a `*_summary status=success` line is emitted. |
| 64 | Usage, configuration, dependency, unsafe path, unsafe cookie-file permissions, or a refused publish because the output already exists. |
| 65 | Authentication, organization authorization, step-up failure, or a `404` from the endpoint. |
| 66 | API transport failure, including `400`, `429`, other `5xx`, and any non-2xx that is not an authorization outcome. |
| 67 | Bundle integrity: unreadable or missing archive, ZIP structure, manifest schema, inventory coverage, byte length, or SHA-256 mismatch. |
| 69 | Reader rendering or filtering failure. |

`68` is unallocated: it belonged to the removed journal-collection step and is left free so the
other codes stay stable for existing automation.

Note that `EXIT_INTEGRITY` is `67` here while `deploy/backup` uses `69` for its own integrity
class; the two catalogs are independent and both export their constants, so do not source the two
libraries into one shell.

## Reproducing locally

`read.sh` offers no import path, and none should be built: an importer would
turn a deliberately redacted artefact back into a data-bearing one. To reproduce
a reported state, seed a local workspace with `bash gradlew seedData` and
recreate the facts the bundle reports, using `migrations.json` for schema state
and `config.json`/`readiness.json` for the deployment's feature posture.
