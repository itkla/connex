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
