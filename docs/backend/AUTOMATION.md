# Automation, Workflows, and Condition Model

This document is authoritative for smart segments, the reusable condition model, legacy rules, and canonical workflow execution. Lock ordering lives in `docs/backend/LOCKING.md`.

## Reusable condition model

Smart-segment evaluation is the shared state-matching language used by automation.

`SegmentDefinition` combines `SegmentCondition` entries with `match = all|any`. Conditions are either:

- graph-aware predicates such as `warm_intro_available`, `open_deal`, `cooling`, or `no_activity`; or
- field comparisons such as industry/name/tag, optionally negated.

Evaluation returns workspace- and current-user-scoped record ids through `SegmentService`/`SegmentMapper`. Segments describe current state; automation adds a trigger/transition plus actions. Do not create a second condition language for workflows, campaigns, or adjacent features.

## Catalog is the vocabulary

`SegmentCatalog` declares the supported record types, field kinds/value sources/operators, predicate applicability/bounds, enum options, and definition shape limits.

- `SegmentService` uses it for validation/evaluation dispatch.
- `GET /api/segments/catalog?recordType=` exposes the workspace-independent cacheable vocabulary.
- Workspace value options remain on the separate fields endpoint.
- Add a field/predicate in the catalog plus a workspace-scoped parameterized `SegmentMapper` query; the frontend renders the catalog generically and needs EN/JA labels.
- Keep persisted keys additive. Do not rename/remove a key that existing segments/rules/campaigns may store.
- `Kind` is a closed frontend/backend enum. A new kind is a deliberate cross-layer change.
- Predicate record-type applicability remains explicit rather than a global assumption.

## Rule shape and execution identity

Automation layers:

- a trigger (`entity_change` after-commit event or schedule cadence);
- optional `WHEN` using `SegmentDefinition` where the record type supports conditions;
- `THEN` actions.

Trigger/condition/action JSON is validated by the established definition validator/service.

A rule/workflow runs as either:

- a current run-as workspace member (`user` mode); or
- the seeded non-login `SystemActor` (`system` mode), authorable only through the established admin gate and limited to its fixed narrow permission set.

Off-request execution installs explicit SecurityContext and TenantContext through the automation executor. Actor permission failure is an execution failure; do not bypass existing tenant/RBAC-enforcing services in an action implementation.

## Adding trigger sources

A mutation publishes a validated trigger token from the same transaction after its write through the existing trigger publisher. `WorkflowTriggerIntake` records bounded matching targets before commit; the runtime drains them after commit.

- A mutating source without a transaction must not publish; mandatory propagation exists to prevent an after-commit loss window.
- Automation-triggered mutations run inside `AutomationScope`, which suppresses recursive publishing. Do not declare a source that is reachable only through automation and therefore silently suppressed.
- Add every canonical workflow record type to the record-availability guard/`SegmentMapper.entityIdInWorkspace`; unknown record types fail closed as unavailable.
- Update the validated trigger vocabulary and tests in the same change.

## Adding actions

Add a new action to the closed validated vocabulary and `RuleActionExecutor`, delegating to the existing domain service rather than reproducing domain logic.

- Map the action to its required permission in the validator/guard.
- Extend `SystemActor`'s permission set only when system mode must legitimately perform the action.
- Classify retry behavior explicitly; unknown actions default to no automatic retry.
- Preserve the existing transactional/deduplicated effect semantics.

### Triggered campaign delivery

`send_message` is a person-only, deduplicated action. Its configuration pins
`campaignMessageId` and `campaignMessageVersion`; both optional bean fields are omitted from JSON
when null so definitions created before this action retain their canonical bytes and SHA-256 hash.
Authoring and runtime preflight require `CAMPAIGN_MANAGE`, `CAMPAIGN_SEND`, and `CONSENT_MANAGE`.
System-mode definitions are rejected because the fixed system actor does not have both campaign
permissions.

The action uses one long-lived `campaign_send` with `origin=triggered` per message revision and one
`campaign_delivery` per contact. Each send points to a real zero-member synthetic snapshot for the
revision and retains `status=triggered`. The synthetic snapshot uses the compatibility purpose label
`Triggered (system)` so a pre-feature audience reader presents a harmless, identifiable row rather
than an ordinary marketing audience. During application rollback, the old send badge displays the raw
`triggered` token and the old audience selector displays that labelled zero-member snapshot. An old
operator can create a draft or export from it, but its empty member set produces no sends. This is the
accepted rollback residual: no data is lost and no message is sent. Old lifecycle selectors still
cannot claim or mutate the new status. The generated active-delivery unique key makes retries
idempotent while a contact's attempt remains authoritative. Dispatched, unresolved failed, and
skipped rows are returned as stable outcomes and are never reset to pending. In particular, a
delivery skipped by the 24-hour frequency cap cannot be retried with that revision. Its `skipped`
status and `frequency_capped` reason remain visible in campaign recipient history. An operator-
confirmed `not_delivered` attempt remains permanent evidence but leaves the active uniqueness set,
so a later enrollment may create a replacement row.

Triggered sends use the fixed `marketing` consent purpose. Dispatch remains asynchronous and reuses
the ordinary per-recipient restriction, address suppression, person suppression, consent, frequency,
and quiet-hours checks. The enqueue audit says “Queued campaign delivery”; no action path claims that
the provider sent it. A contact is classified in restriction, address, suppression, and consent order
before any delivery row is written. An excluded contact creates neither a send nor a delivery, and the
exclusion reason is strict-audited. An unsubscribe or other mandatory-stop signal skips that delivery
but does not cancel later workflow nodes in this increment.

Triggered sends remain `triggered` after their pending queue drains so later contacts can enroll. Dormant
triggered sends are excluded from worker workspace/send scans until another pending delivery exists.
Their recipient total is refreshed from delivery rows. The campaign API returns them with the
synthetic `snapshotId` and `origin=triggered`; the campaign UI labels them as workflow-managed and exposes no
queue, pause, or cancel controls. The lifecycle service also rejects those mutations for triggered
sends.

Concurrent get-or-create calls are serialized by the campaign mutex. The unique triggered-send key and
the delivery's generated active `(workspace_id, send_id, person_id, dedupe_active)` key remain final
defenses:
duplicate-key collisions are caught and re-read, while unrelated uniqueness failures still propagate.
Only historical evidence confirmed as `operator_not_delivered` has a null dedupe key, allowing a later
enrollment without rewriting that evidence. Creating the long-lived send and enrolling a contact write
separate bounded audit events.

The `connex.workflows.triggered-send.enabled` rolling fence defaults off and is captured when each
backend instance starts. The repeated in-process checks protect an instance that started closed; they
do not observe later environment-file edits. Roll forward with every instance started closed, then
restart or recreate every replica with the fence open only after all replicas understand
`send_message`. Open means new triggered-delivery claims are allowed. A closed instance does not
select triggered sends and releases an owned claim that has not crossed its final provider-egress
check. `/api/capabilities.workflowTriggeredSend` exposes that same startup-bound value; workflow
authoring fails closed and does not offer the action when the capability cannot be resolved or the
fence is closed.

Every triggered claim has an owner-fenced database-clock lease. Immediately before renewing that
lease, the worker captures one absolute monotonic provider deadline and passes that exact value
through the dispatcher. The lease is the provider deadline plus its configured safety margin.
HTTP providers schedule hard request cancellation and immediate connection closure at the deadline.
SMTP tracks every transport socket and closes it before closing the active JavaMail transport at the
deadline. Workspace-supplied destinations use the validated pinned-address factory unless internal
relays are explicitly allowed; that opt-in and instance-default SMTP use the tracked non-pinned path
after deadline-bounded hostname resolution. Admission control on that resolution covers
workspace-supplied hosts only, and the resolver pool reserves a thread for the exempt
instance-default path. The tracked socket is always the raw TCP socket and the mail library layers
TLS over it, because closing a TLS layer must first take the record lock a parked TLS write already
holds, while closing the raw socket fails that write immediately; closing the socket first is also
what lets the abort proceed while `sendMessage` owns the transport monitor. Remaining-budget connect
and read timeouts stay subordinate inactivity bounds; the raw close is the only bound on a body
write on this deadline-bound path, which is why Angus's separate write-timeout wrapper — and the
per-send scheduled executor it allocates — is left off here. DNS, TCP, TLS, and authentication must complete before submission begins, so a
failure in any of those phases is definitive. A deadline or transport failure after submission begins is
recorded as `AMBIGUOUS` failed delivery evidence with `reconciliation_required_at`; it requires
provider reconciliation and is never automatically replayed. HTTP ESP and SMS connector
configuration exposes `idempotentSubmission`, which defaults to false. An administrator may enable
it only when that endpoint guarantees deduplication of the stable `Idempotency-Key`; the generic
adapters do not infer that guarantee. SMTP cannot enable it. SMTP still sends that header and a stable `Message-ID` for
correlation, but a conforming relay may accept repeated `DATA` submissions and neither header is
provider-side deduplication. SMTP campaign submission is therefore best-effort. Before egress, the
claim persists an attempt-target fingerprint that binds the provider id and configuration id/generation
to a SHA-256 of the endpoint/account identity and opaque credential reference; credential values never
enter it. Because the secret store keeps one row per workspace and purpose, rotating a send credential
returns the same opaque reference, so the save path reports the rotation to the configuration upsert
and the generation advances; rotating an inbound webhook token deliberately does not advance it. SMTP carries the exact resolved mail configuration from claim selection into dispatch. A
workspace sweep returns an expired claim to `pending` only when the currently resolved target has the
same fingerprint and that exact connector configuration has `idempotentSubmission=true`. A changed target, SMTP target, or
unknown provider instead becomes terminal `failed` evidence with `reconciliation_required_at`, and
workspace discovery includes either expired shape even when no pending row exists. A target changed
after recovery cannot replace the persisted fingerprint on the pending row; its next claim attempt
becomes ambiguous instead of sending through the new account.

An unresolved ambiguous delivery stays the idempotent enrollment result
`delivery_reconciliation_required`; it is not described as an ordinary deduplication. An operator
with both `CAMPAIGN_MANAGE` and `CONSENT_MANAGE` must verify the relay or provider result, then call
`POST /api/campaigns/{campaignId}/recipients/{deliveryId}/reconcile` with `delivered` or
`not_delivered`. Authorization is revalidated under membership locks before campaign, send, and
delivery locks. The endpoint accepts any delivery belonging to that campaign, whether audience or
triggered. The operation is strict-audited and idempotent for the same resolution. `delivered`
records the dispatched terminal state. `not_delivered` preserves the failed row as operator-resolved
evidence; only triggered enrollment uses that outcome to permit a later replacement delivery. The reconciliation call itself
never sends or retries anything. A definitive authenticated provider webhook may also clear an
unresolved reconciliation marker. Recipient APIs expose only `provider_timeout`,
`provider_rejected`, `deadline_ambiguous`, `delivery_target_changed`, or `relay_error`; raw provider
errors remain internal diagnostic evidence and never enter these DTOs.

Rollback quiescence requires instance replacement because the fence is startup-bound. Set
`CONNEX_WORKFLOWS_TRIGGERED_SEND_ENABLED=false`, drain and restart or recreate every current-version
backend replica, verify that no running instance remains enabled, and then wait one full database
lease plus one provider-deadline interval before replacing binaries. This procedure is also linked
from `docs/UPGRADING.md`.

`connex.workflows.triggered-send.recipient-limit` defaults to 200 and must be between 1 and 500.
Scheduled send-message enrollment stops at that ceiling, persists a diagnostic, and writes a strict
audit event. Both legacy and canonical schedule runtimes ask the shared segment evaluator for exactly
the ceiling plus one result; candidate and predicate SQL remain scoped to bounded pages, and the extra
row is truncation evidence rather than a materialized full segment.
`connex.workflows.triggered-send.dispatch-page-size` defaults to 200 and must be between
1 and 1,000 so a worker sweep never selects an unbounded delivery set.

## Document automation fence

A `document` workflow subject is a `deal_document` id, not its parent deal.

- Documents do not use segment conditions/schedules/manual-run scope unless the shared model explicitly gains support.
- Task/activity/note actions resolve the parent deal through the established mapper; deal-mutating actions remain excluded unless guards are redesigned for document identity.
- The rolling-deployment feature fence defaults off and blocks authoring, publication, simulation/runtime revalidation, and trigger intake until every node supports document automation.
- Roll forward with the fence closed on all nodes, then open it everywhere. Roll back by closing it everywhere before replacing binaries.
- Remove the fence only when no supported rollout window can include a pre-document-automation binary.

Future fenced record types use the same centralized gate instead of scattered checks.

## Legacy rules are a compatibility projection

The legacy `/api/rules` surface is not an independent aggregate.

- Create transactionally creates the paired canonical workflow and immutable version 1.
- Semantic updates create exactly one deterministic new version; no-op or enabled-only changes do not.
- Delete archives/disables while retaining workflow versions, runs, steps, rule executions, and legacy links.
- Restore clears archive state but does not silently enable either runtime.
- Startup backfill follows the documented deterministic discovery/lock/completeness-verification protocol.

`runtime_owner` on the locked workflow is the database-authoritative predicate for which runtime may claim work. Both runtimes check the opposite ledger under the same root lock.

## Dedupe and rolling compatibility

One replay-stable trigger envelope derives the dedupe identity used by legacy and canonical ledgers.

- Preserve established legacy-rule identity for paired claims so rolling older binaries observe new claims.
- Canonical-only workflows use workflow identity; genuinely unpaired rules use rule identity.
- Do not derive replay identity from process time or JVM-local ownership.
- Schedule keys use the UTC cadence bucket.
- Attaching a first legacy projection to canonical history remains fenced while the legacy runtime/dedupe protocol is supported.
- Follow workflow-before-run-history lock order; see `docs/backend/LOCKING.md`.

## Schedule enrollment

A schedule trigger immediately enrolls records selected by its first condition. Enrolled runs record that condition as `yes`; its `no` edge is not traversed. Later conditions branch normally. Entity-change runs evaluate every condition normally.

Do not blur pre-enrollment selection with ordinary runtime branching.

## Durable canonical runtime

- `workflow_trigger_outbox` pins workflow generation/version at source commit.
- `workflow_runtime_workspace` coordinates fair alternating trigger/run claims per workspace.
- Owners are UUIDs; leases use database time, `SKIP LOCKED`, and owner-fenced checkpoints so stale multi-instance workers are harmless.
- Publish, enable/disable, archive, and restore advance runtime generation, invalidating pending intake without cancelling already claimed runs.
- Delay/retry waits release their lease and resume through the due-run claimant.
- Each run pins an immutable `workflow_version` and verifies its definition hash on resume.
- Every resume revalidates actor membership, record availability/visibility/restrictions, references, and action permission before mutation.
- A later authorization/reference failure becomes fixed-code run evidence, not a silent skip.

Node effects/checkpoints use the established `REQUIRES_NEW`, `READ_COMMITTED` transaction shape: reserve an attempt before effect execution; commit the database mutation, attempt/step success, and next-node checkpoint together.

## Cancellation and retry

- Queued/waiting cancellation terminates immediately.
- Running cancellation is cooperative and checked before every node effect.
- Automatic retry is limited to the reviewed transient lock/serialization/query-timeout classes and actions allowed by `WorkflowActionRetryPolicy`.
- Unknown actions default to `none`.
- Database-backed schema-v1 actions are transactional; notification effects use the stable workflow/run/node dedupe key.

## Off-request context

The engine does not rely on request-thread context. Pass `workspaceId` and `userId` explicitly to mappers and session-free evaluation services. Never assume an HTTP request's tenant/security context remains available on a scheduler/worker thread.

## Review checklist

- Uses the shared condition/catalog vocabulary; no second condition language.
- Persisted catalog/trigger/action keys remain additive/compatible.
- Trigger publication is transactional and loop suppression is understood.
- Execution identity and permission are explicit and revalidated.
- Record-availability guards support every canonical record type.
- Dedupe identity is replay-stable and rolling-version compatible.
- `runtime_owner`, generation, leases, checkpoints, retry, and cancellation remain database-authoritative.
- Lock changes follow `docs/backend/LOCKING.md`.
- Segment/catalog, authoring, trigger intake, parity, durable runtime, cancellation/retry, and rolling-deployment tests pass.
