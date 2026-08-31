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

`POST /api/orgs/{orgId}/support-bundle` streams a ZIP containing:

| Entry | What it holds |
|---|---|
| `manifest.json` | Bundle schema version, product version, generation time, organization id, the filters that were applied, a SHA-256 inventory of every other entry, and a map of declared omissions. |
| `readiness.json` | Deployment profile, capability availability, provider readiness booleans, and stable reason codes. |
| `config.json` | Values for an explicit allowlist of configuration keys — nothing else. |
| `migrations.json` | Flyway history: version, description, success, installed-on. |
| `audit-slice.csv` | The audit events for the requested window, organization-plane always, workspace record events only under an entity filter. |
| `client-errors.json` | Redacted client-error metadata: id, workspace id, client-asserted correlation HMAC, closed-vocabulary route template, and report time. |
| `journal-slice.jsonl` | Optional operator-side projection of dedicated tenant request-completion events. It uses the same organization-scoped disclosure-HMAC derivation as current audit and client-error rows, never the raw client assertion. Present only when `collect.sh --include-journal` succeeds. |
| `job-runs.json` | Not produced in this release — see [Declared omissions](#declared-omissions). |

### Filters

| Parameter | Contract |
|---|---|
| `correlationId` | Raw, client-asserted collection-time lookup input. The backend matches its storage-domain HMAC (plus legacy raw rows), returns only the disclosure-domain HMAC, and never matches or aliases `serverMintedRequestId`. The journal projector receives that disclosure HMAC from the verified manifest and never serializes the raw value. See [Request ids](#request-ids). |
| `entityType` + `entityId` | Legal only together. Unlocks workspace record events for that one record. |
| `since` | Defaults to 7 days before generation; 30 days is the maximum. Older or future values are rejected. |

### Declared omissions

When a source is unavailable or cannot be proven safe, the bundle **says so** in
`manifest.json` rather than silently shipping a smaller archive:

| Omission | Meaning |
|---|---|
| `readiness.json`, `config.json`, `migrations.json`, `audit-slice.csv`, or `client-errors.json`: `source_failed` | That backend source failed. The corresponding file is absent; slice counts are `null` when their source failed. |
| `job-runs.json: job_run_not_available` | This deployment predates the job-run table. |

The optional `journal-slice.jsonl` is different: when journal collection was not requested, the file
is absent and `journalSlice` is missing or `null`, rather than declared as an omission. Once it is
requested, any journal-stage failure exits `68` and publishes no archive.

An absent required backend file therefore never means "no problem found". It means "this source
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
- client error messages, framework digests, or stack traces;
- job-run `detail` payloads;
- personal names, email addresses, or any record field values;
- raw journald messages, log messages, exception text, or stack traces;
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
the organization-scoped storage-domain correlation HMAC, a server-owned route template, and
`reportedAt`.
`RequestPathRedactor` accepts only the closed vocabulary of known frontend route patterns; any
unrecognized caller-controlled path becomes the single value `unknown`. The same mapping runs
again when a row is read, so a future vocabulary tightening also protects rows already stored.
Metadata is purged on startup and hourly using the fixed 30-day UTC cutoff.

The client error's `message`, composed `detail`, and browser `stack` never reach the metadata mapper,
the database table, or `client-errors.json`. They remain user-data-bearing local log content and must
not be copied into a support artefact. A framework digest is client-asserted and has no
cryptographic framework provenance, so it is never persisted or disclosed even when it has the
expected decimal shape. It remains available only in the deployment-local error report.

Rows written before this boundary was tightened receive the same safe read projection: raw legacy
correlation values are replaced by an organization-scoped disclosure-domain HMAC, old paths are
mapped to a recognized template or `unknown`, and old digest columns are not selected. Existing
stored paths therefore cannot escape through a later bundle or complete workspace export. A
filtered slice normalizes matched legacy and current correlation rows to the same disclosure HMAC;
an unfiltered legacy row remains independently pseudonymized because its raw-at-rest provenance
cannot safely be inferred from syntax.

### Request ids

`audit-slice.csv` carries both identifiers under names that encode their provenance:

- `serverMintedRequestId` is the unchanged, non-spoofable `audit_log.request_id`. It groups audit
  events emitted during one server request and remains the trustworthy audit pivot.
- `untrustedClientAssertedCorrelationHmac` is an organization-scoped, domain-separated HMAC derived
  from the client-settable `X-Correlation-Id`. It remains useful for joining bundle rows without
  disclosing the raw caller value and is still not proof that two requests share an origin.

The server accepts the raw `correlationId` only as a lookup input, applies the same storage-domain
HMAC, and matches both new pseudonymized rows and legacy raw rows. The bundle carries only a
separately domain-separated, organization-scoped disclosure HMAC in its backend-produced audit and
client-error entries; the manifest never repeats the raw input. The filter never changes or aliases
the server-minted audit id. Organization and workspace predicates are applied independently, so
choosing a value used in another tenant cannot pull that tenant's row into the bundle.

The optional journal projection uses the same
`untrustedClientAssertedCorrelationHmac` representation. The dedicated completion event replaces
the raw MDC value with the organization-scoped disclosure HMAC before it is written, and the
collector checks the exact organization integer before reading or comparing it. The HMAC remains
only a secondary lookup aid within that already scoped projection, never a tenant boundary, proof
of request identity, or substitute for `serverMintedRequestId`.

The client-correlation lookup column deliberately stays outside the existing audit-row HMAC payload so old
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
`clientErrorSliceLimit`. Narrow `since` for either slice. An entity filter narrows only the audit
slice; use a collection-time correlation filter to narrow both audit and client-error rows.

A request need not emit an audit row, and a client-asserted id is not authoritative. An empty audit
slice under a correlation filter therefore means "no permitted audit rows carried this untrusted
value", not "nothing happened". The manifest records `auditSliceInconclusive: true` for exactly
that case so an empty slice is not misread as evidence of absence.

### Optional journal slice

The backend never reads logs from disk. An operator on the deployment host can opt in to a bounded
journal projection with `collect.sh --include-journal`; the downloaded backend bundle is verified
first, then the collector appends `journal-slice.jsonl`, updates the manifest inventory, repacks,
and verifies the finished archive before publishing it.

The appended manifest's `journalSlice` object records the unit, organization id, organization
discriminator, projection version, and redactor version. It does not add journal row counts or a
truncation flag; the closed projection instead fails if it exceeds its fixed input or row bounds.

This is not a general log export. `TenantResolutionInterceptor` emits one dedicated `INFO`
completion event only for explicitly allowlisted current-tenant controllers. Its numeric
`connexOrganizationId` comes from the server-resolved workspace membership and tenant context,
not a copied header or route parameter; any requested workspace is membership-validated before
the server looks up its organization. The request path is Spring's matched route template, never
the raw URI or query string. Requests without a resolved organization, async and background work,
pre-auth traffic, and handlers with explicit organization or workspace targets are omitted rather
than guessed.

Production journald stores Spring's ECS JSON record inside `MESSAGE`, so the collector parses that
string with recursive duplicate-key rejection. It checks the organization integer exactly before
reading any projectable field, then constructs a new object containing only timestamp, level,
logger, `untrustedClientAssertedCorrelationHmac`, method, redacted route template, status, and fixed
event class. Unknown ECS keys, raw `MESSAGE`, messages, stack traces, headers, hosts, and query
strings are never copied. A missing, malformed, ambiguous, or other-organization discriminator drops
the record. Any failure of journal collection, projection, repack, or pre-publication post-repack
verification aborts publication with exit code `68`.

Because the marker is deliberately narrow, absence from the slice is not evidence that no request
occurred. For client-error digests, messages, and stacks, and for non-allowlisted handlers, use the
deployment's own logs locally.

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
- The endpoint is a `POST`, and therefore CSRF-protected. It used to be a `GET`, which let a
  malicious page navigate a signed-in administrator's browser to it: the attacker could not read the
  response — CORS, CORP, and the attachment disposition all held — but the request still consumed
  the concurrency limit, dropped a ZIP of redacted organization data into that administrator's
  downloads, and wrote audit rows attributing an export to them. `JSESSIONID` is `SameSite=Lax`,
  which *is* sent on cross-site top-level GET navigation, so that was reachable in practice. Callers
  must now echo the CSRF token from `GET /api/auth/csrf` in the configured header;
  [`deploy/support-bundle/collect.sh`](../deploy/support-bundle/collect.sh) does this automatically.

## Operational notes

Assembly is **synchronous**: the bundle is built in full and returned as one response, rather than
streamed. That is deliberate. A support bundle is a capped metadata snapshot, so it does not need
the async writer, monotonic deadline, and cancellation machinery that the tenant export requires
for unbounded workspace data — and an async writer would run on a thread where the tenant routing
and security context the queries depend on are not installed.

The size is bounded by construction. The backend caps both the audit and client-error slices at
10,000 rows, and the optional journal projector caps its output at 50,000 rows. Backend assembly
refuses to exceed a 64 MB uncompressed ceiling, checked **before** each entry is added rather than
after, and a 30-second wall-clock budget checked between sources. Both fail closed with `413` and a
message telling the operator to narrow `since` or add an entity filter, rather than returning a
bundle that looks complete but is not. The journal append independently rechecks the same 64 MB
ceiling and fails with `68` before publication if it would cross it.

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
       --since 2026-07-24T05:00:00Z \
       --include-journal --journal-unit connex-backend.service \
       --output /var/tmp/bundle.zip
   ```

   The bundle is verified against its manifest before it is published.

2. **Read the verified bundle.** Render the complete, already server-filtered archive:

   ```bash
   deploy/support-bundle/read.sh --archive /var/tmp/bundle.zip
   ```

   Hashes are checked before anything renders. Offline correlation and digest filtering is not
   offered: the raw correlation is absent by design, and a client-asserted digest is not a trusted
   support pivot. Apply a correlation filter while collecting when one was quoted from a raw API
   response.

3. **Rule out the platform.** `readiness.json` shows the deployment profile and
   capability/provider state; `job-runs.json` (where present) shows recent
   scheduler outcomes. If sync and scheduling were healthy, the record was not
   lost by an integration or a failed job. `migrations.json` confirms the schema
   is fully migrated, ruling out a half-applied deployment.

4. **Correlate the report.** `client-errors.json` shows the later report request's correlation HMAC,
   route template, workspace, and report time. It contains no digest, error text, or stack.
   `audit-slice.csv` remains the complete history for entity 412, with
   `untrustedClientAssertedCorrelationHmac` and `serverMintedRequestId` separately labelled.

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

Treat a match on `untrustedClientAssertedCorrelationHmac` as a lookup aid, never as proof of request
identity. Use `serverMintedRequestId` for the trustworthy within-audit pivot, and retain the entity,
workspace, organization, and time predicates when answering the ticket.

The optional journal slice uses the same `untrustedClientAssertedCorrelationHmac` field and
derivation as current audit and client-error rows, so those rows join on the disclosed value. A
correlation-filtered slice normalizes matched legacy rows to that value; unfiltered legacy rows
remain independently pseudonymized. The HMAC still proves neither request identity nor a link to
`serverMintedRequestId`.

A framework `Reference:` can still help a deployment operator find the local structured error
report, but it is not exported because the server cannot prove the caller did not choose it.

### Exit codes

`collect.sh` and `read.sh` share one catalog:

| Code | Meaning |
|---:|---|
| 0 | Success; a `*_summary status=success` line is emitted. |
| 64 | Usage, configuration, dependency, unsafe path, unsafe cookie-file permissions, or a refused publish because the output already exists. |
| 65 | Authentication, organization authorization, step-up failure, or a `404` from the endpoint. |
| 66 | API transport failure, including `400`, `429`, other `5xx`, and any non-2xx that is not an authorization outcome. |
| 67 | Bundle integrity: unreadable or missing archive, ZIP structure, manifest schema, inventory coverage, byte length, or SHA-256 mismatch. |
| 68 | Optional journal collection, closed projection, repack, or pre-publication post-repack verification failure. No output archive is published. |
| 69 | Reader rendering failure. |

Note that `EXIT_INTEGRITY` is `67` here while `deploy/backup` uses `69` for its own integrity
class; the two catalogs are independent and both export their constants, so do not source the two
libraries into one shell.

## Reproducing locally

`read.sh` offers no import path, and none should be built: an importer would
turn a deliberately redacted artefact back into a data-bearing one. To reproduce
a reported state, seed a local workspace with `bash gradlew seedData` and
recreate the facts the bundle reports, using `migrations.json` for schema state
and `config.json`/`readiness.json` for the deployment's feature posture.
