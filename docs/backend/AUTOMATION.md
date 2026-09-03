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
revision and retains `status=triggered`; binaries from before this feature can map the non-null
snapshot id but cannot select or mutate the new status during a rollback. The delivery unique key
makes retries idempotent while the contact id is non-null. A prior delivery for the
same contact and revision is permanent evidence: dispatched, failed, and skipped rows are all returned
as an idempotent replay and are never reset to pending. In particular, a delivery skipped by the 24-hour
frequency cap cannot be retried with that revision. Its `skipped` status and `frequency_capped` reason
remain visible in campaign recipient history.

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
the delivery `(workspace_id, send_id, person_id)` key remain final defenses: duplicate-key collisions
are caught and re-read, while unrelated uniqueness failures still propagate. Creating the long-lived
send and enrolling a contact write separate bounded audit events.

The `connex.workflows.triggered-send.enabled` rolling fence defaults off. Roll forward with the fence
closed on every node, then open it everywhere only after all nodes understand `send_message`. To roll
back, close it everywhere before replacing binaries. While closed, validation and enrollment reject
the action, worker selectors exclude triggered sends, and the dispatch service re-checks the fence
before each provider call so already-pending rows remain intact.

`connex.workflows.triggered-send.recipient-limit` defaults to 200 and must be between 1 and 500.
Scheduled send-message enrollment stops at that ceiling, persists a diagnostic, and writes a strict
audit event. `connex.workflows.triggered-send.dispatch-page-size` defaults to 200 and must be between
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
