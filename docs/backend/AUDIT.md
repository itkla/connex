# Audit execution context

`AuditService` builds the audit metadata envelope before appending through `AuditIntegrityService`.
An actor identifies the member or system principal on whose behalf an operation ran. Execution origin
identifies the source of the work separately, so a rule action cannot be mistaken for that member's
manual action.

## Context keys

| Key | Meaning |
| --- | --- |
| `error` | Existing failure reason or exception-class token. Sensitive secret-store and AI disclosures retain only the approved exception-token format. |
| `detail` | Preserves an existing scalar or list context when an execution origin is added. Existing object context keys stay at the top level. |
| `executionOrigin` | Server-owned execution envelope, captured from `AutomationScope` while building the entry. Absent for manual work and pure observers. Existing failure context remains alongside it. |
| `executionOrigin.source` | Closed source vocabulary listed below. |
| `executionOrigin.automation` | `true` for `rule` and `workflow`; `false` for the other sources. |
| `executionOrigin.executionMode` | `user` or `system`, taken from the persisted rule/workflow execution mode or the background task's explicit identity contract. |
| `executionOrigin.ruleId`, `executionOrigin.ruleExecutionId` | Positive persisted rule and execution IDs, present only for `rule`. |
| `executionOrigin.workflowId`, `executionOrigin.workflowRunId` | Positive persisted workflow and run IDs, present only for `workflow`. |

The source vocabulary is `rule`, `workflow`, `campaign_dispatch`, `delivery_webhook`,
`delivery_unsubscribe`, `document_acceptance`, `document_signature_webhook`,
`document_delivery_lifecycle`, `document_delivery_expiry`, `approval_reconciliation`,
`report_delivery`, `ai_generation`, `lead_response_sla`, and the compatibility fallback
`background_task`. System-actor attribution alone does not make an operation a rule or workflow run.
Public recipient operations and provider callbacks keep their own source names and never invent a run ID.
Historical rows are unchanged: absence of `executionOrigin` in rows written before #1934 does not
establish that the actor acted manually.

`runAs` installs immutable origin metadata and restores the previous scope on success or failure,
including nested executions. The overload without an explicit origin is a compatibility path for
background work; rule/workflow callers must supply their persisted IDs and mode.
`runAsObserver` without an origin leaves pure observations untagged. The explicit-origin observer
overload is for observers that also mutate: the SLA sweep records `lead_response_sla` while leaving
rule-trigger publishing enabled.

Deferred `AuditService` entries retain call-time origin, actor, and request metadata through transaction
completion. `SecretStore` keeps its rewrap-result metadata lazy until commit or rollback, but captures
the use-time execution scope and authentication at the secret call and restores them only for the
deferred audit. It restores the completion-time scope and authentication even if the append fails.
This also prevents a manual secret use from inheriting a later automation's origin.

The existing integrity payload already covers `context`; the signature format and schema are
unchanged. Workspace, entity, and organization CSV exports include the origin through their existing
`context` column. Sensitive secret-store and AI projections retain only the validated origin envelope
and approved error token; extra fields in that envelope are excluded. Support bundles continue to
exclude context bodies as specified in [SUPPORT_BUNDLE.md](../SUPPORT_BUNDLE.md).

## Subject disclosure release (#1733)

`appi.subject_request.disclosure` retains its organization scope (null workspace), actor, request
label, summary and request/subject identifiers. Its strict independent control append commits
between two synchronous serialization passes through the same tenant snapshot and before the
servlet target opens. The approved metadata adds `fingerprintVersion: 1`, `noteCount`,
`notesSha256`, `jsonByteLength` and `jsonSha256`. Counts and SHA-256 fingerprints contain no note
text. Version one hashes exact compact UTF-8 JSON before HTTP content coding, with the notes
fingerprint covering the ordinary array including `[` and `]`.

The event proves complete assembly and an authorized release attempt. It does not prove delivery
or client receipt. It survives a later serialization/transport failure; committed output is then
left truncated without an error suffix or closing delimiters. An explicit retry creates another
event. [LOCKING.md](LOCKING.md) defines suspension, catalog restoration, snapshot resumption and
the two-connection budget; [API_SURFACE.md](API_SURFACE.md) defines integrity headers and failures.

## CSV export evidence and bounded audit selection (#2260)

A completed CRM CSV assembly appends `<entity>.export` for `person`, `company`, `deal` or
`product` before returning its CSV. Smart Segment deal exports use the same `deal.export`
action and identify segment use with a boolean. The actor comes from the authenticated
principal, and the workspace and organization come from the resolved tenant context. The
approved metadata is `format`, `rowCount`, `scope`, the canonical `memberScope` mode, and
`filtered`/`segment` flags. It contains no record values, search text, selected member IDs,
filter values, segment definitions, or customer labels. Empty successful exports record zero
rows. Query, rendering and step-up failures create no completed-export event.

Workspace audit CSV assembly appends `audit.export`; organization audit assembly appends
`organization.audit.export` with explicit null workspace scope and the authorized organization.
Their metadata contains only the CSV format, actual row count, scope, selection/entity-scope
flags, and clamped limit/offset. Selected audit IDs are never copied into that event.
All completion appends are strict: an audit persistence failure prevents returning the CSV.
The evidence proves successful assembly and an authorized release attempt, not network delivery
or client receipt. The append follows read-only visibility callbacks; those callbacks remain
free of writes. Existing access policy, privacy exclusions, CSV redaction/formula guards and
recent-WebAuthn step-up are unchanged.

The existing workspace and organization `/audit/export` endpoints accept optional comma-separated
`ids`. A present selection must contain between 1 and 200 positive IDs before deduplication;
empty, invalid and oversized selections are rejected. SQL intersects IDs with the resolved
workspace or authorized organization, plus the existing complete workspace entity scope when
supplied. Organization selection retains the org-plane-only and historical-reference guards;
workspace events never become organization-plane events merely because a workspace was deleted.
Without `ids`, the existing export limit (at most 10,000), offset bound and ordering remain in
place. The audit UI explicitly exports up to 200 currently loaded rows matching its filters,
using server CSV generation and the existing authority/step-up path.

## Report and workspace-archive completion (#2260 follow-up)

Live and frozen report CSV assembly appends one strict `report.export` event after rendering.
The approved metadata is `format=csv`, `scope=workspace`, actual appendix `rowCount`,
`documentCount=1`, `widgetCount`, and a `snapshot` flag. CSV formula escaping is unchanged.
Report names, dates, source IDs, labels, values, and definition contents are excluded.

Both CSV exports use explicit POST requests. Snapshot export at
`/api/reports/{id}/snapshots/{snapshotId}/export.csv` requires the existing CSRF protection,
rendered-workspace pin, report/domain read permissions and recent-WebAuthn step-up. GET and
HEAD are no longer mapped to export assembly: response-body inspection, prefetch or other safe
read replays cannot append another completion. Each authorized POST renders the frozen document
and appends exactly one event; a separate intentional POST is a separate export.

`POST /api/reports/{id}/export-completion` accepts exactly `{"format":"png"}` and returns
204 after a strict `report.export` append. Unknown body fields, including client counts and
names, are rejected. The existing `REPORT_READ`, current-workspace report visibility, saved
report domain-read permissions and recent-WebAuthn step-up apply. The browser assembles its
PNG first, calls this endpoint, and releases the image only after the append succeeds.
Metadata contains `format=png`, `scope=workspace`, `imageCount=1`, `documentCount=1`, a saved
configuration `widgetCount`, and `clientPrepared=true`. No row count is claimed for an image
whose rendered period or snapshot is unavailable to the server. This records an authorized
client-prepared image release attempt; it does not verify image contents or client receipt.

Workspace ZIP authorization appends `org.workspace.export_started` with outcome `attempt`
and only `declaredTableCount`. It is deliberately not a completed export. The synchronous
boundary captures an opaque single-use completion envelope with the original authenticated
actor, request metadata, organization scope and execution origin. The asynchronous writer
uses that envelope without changing or inventing a security context. Only after all table
and object writes, the manifest, ZIP finalization and output close succeed does it append
one strict `org.workspace.export` success. Its metadata is `format=zip`, `scope=workspace`,
actual table-row `rowCount`, actual `tableCount`, and verified `objectCount`. Table names,
object paths and exported values are excluded. Query, object, output and cancellation failures
before complete assembly produce no success event. Completion evidence remains organization
scoped with null workspace scope, matching the existing export authority. Neither stage
changes grant redemption, admission, deadline, privacy-publication or object-verdict controls.
A completion-audit persistence failure propagates and releases the export resources, but cannot
retract ZIP bytes already streamed. The durable pre-stream attempt remains available in that case;
this streaming path does not promise the pre-release withholding available to in-memory CSV/PNG.
