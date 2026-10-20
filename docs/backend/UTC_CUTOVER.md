# UTC cutover on non-UTC backend hosts

This runbook accompanies #2168. The fixed backend pins UTC in its executable, seeder and WAR
entry points. Database sessions already use `+00:00`. Before the fix, Java clock reads could
write the host's local wall time into offsetless workflow timestamps.

**Staging is affected:** its old JVM used `Asia/Tokyo`, nine hours ahead of UTC. A run or step
started immediately before deployment can therefore appear to start after its new UTC completion.
V143's timing checks reject that completion. Future run starts also distort UTC history and
backlog filters. Repeated recovery dispatches can consume the existing dispatch budget.

This is an operator procedure, not a Flyway migration or a change to runtime semantics. Read
[deployment operations](../DEPLOYMENT.md), [staging deployment](../STAGING_DEPLOY.md),
[migration lineages](MIGRATIONS.md), and
[workflow locking](LOCKING.md#legacy-rules-and-canonical-workflows) first.

## Identify the original zone and offset

Before stopping the old JVM, record its actual zone. A JVM override can differ from the host:

```bash
timedatectl show --property=Timezone --value
jcmd OLD_JVM_PID VM.system_properties | sed -n '/^user.timezone=/p'
```

Use the original `user.timezone` when present, otherwise verify the zone inherited from the host.
Record the old process before deployment. The fixed entry points call `TimeZone.setDefault(UTC)`,
which changes the effective default without rewriting the `user.timezone` system property; that
property alone is not proof of the effective default in a fixed process.
Resolve the offset that applied when the affected rows were written, not just the zone's current
name. For staging, `Asia/Tokyo` has a fixed `+09:00` offset, or `32400` seconds.

The SQL below requires a **verified, positive, fixed old offset** for the affected writers in a
catalog. Clocks must be synchronized and the database UTC clock must be correct. Compare
`SELECT UTC_TIMESTAMP(6)` with a trusted UTC clock before proceeding. Mixed non-UTC writer offsets,
DST transitions, clock skew, or timestamps supplied by another writer require individual analysis.
A negative old offset produces values behind UTC, which a future-value predicate cannot identify.
A zero candidate count does not establish that older or negative-offset data was written correctly.

## Pause, deploy, repair, resume

1. Inventory every affected tenant catalog: the shared/default catalog where it holds tenant tables,
   plus every dedicated placement catalog. Record the old offset for each. A control-only catalog
   uses the DSR review below; do not run tenant workflow SQL in a control-only catalog.
2. Pause workflow runtime and outbox delivery on **every** replica. The existing
   `connex.workflows.runtime.enabled=false` and
   `connex.workflows.runtime.scheduling-enabled=false` settings stop canonical runtime/outbox
   polling. Disabling canonical runtime activates the legacy rule path; legacy schedule polling
   has its own `connex.rules.scheduling-enabled=false` gate. None of these gates prevent every
   request/internal trigger, manual run, retry, cancellation, or already running worker.
   Close write ingress, drain in-flight work, and stop every old backend and maintenance process.
   Pause deployment timers and restart controllers too, including staging's deployment automation.
   Keep all writers stopped for the entire SQL transaction.
3. Deploy the fixed artifact while processes remain stopped. Do not mix old and fixed writers.
   Take a restorable backup of this final quiescent state before changing any data. For staging,
   use the two-quiesce sequence below instead: its normal deployment transaction needs live services.
4. Select one affected tenant catalog on a maintenance connection and verify `SELECT DATABASE()`.
   Run the workflow SQL below once per tenant catalog and the DSR review once in the control catalog
   (the same physical catalog in single-database mode). It freezes a database UTC cutoff, previews candidates, and
   leaves the transaction open for verification. Do not use a client mode that continues after errors.
5. Commit only when the safety flag is `1`, changed-row counts match the candidate counts, all
   mismatch/remaining counts are zero, and the preview agrees with the backup and expected workload.
   On any SQL error or unexpected result, roll back and keep writers paused. Do not disable checks,
   invent a different offset, or repair Flyway history to force this procedure through.
6. Start only the fixed artifact with runtime/outbox polling still paused and verify readiness/UTC.
   Restore the intended polling settings, observe a controlled workflow completion while external
   ingress and deployment automation remain closed, then reopen ingress and re-enable automation.
   Preserve the cutoff, offset and counts in the deployment record. Do not rerun this procedure after writers resume.

## Staging maintenance sequence

The normal [staging transaction](../STAGING_DEPLOY.md#deploy-pipeline) restarts the backend and
frontend, performs a localhost smoke, and can roll back both artifacts. Do not stop services and
then invoke it expecting them to stay stopped, or run the repair during its activation phase.
Use this sequence with operator access to systemd and the root-owned environment file:

1. Stop `connex-staging-deploy.timer`, let an existing deploy service finish, and require a coherent
   committed release before proceeding. Do not interrupt activation or manually erase a recovery
   record. Keep other deploy/restart controllers disabled and coordinate exclusive maintenance;
   the normal wrapper serializes deployment using `/tmp/connex-staging-deploy.lock`.
2. Record the old JVM zone/offset, close external write ingress, drain work, and stop the backend
   and frontend. Leave localhost access available for the deployment smoke. Stop maintenance
   writers too. Preserve a secure copy of `/etc/connex-staging/backend.env` outside the checkout.
3. In that existing environment file, preserve all existing `JAVA_TOOL_OPTIONS`, especially
   `-Djava.security.properties=...`, and append these exact properties without conflicting values:
   `-Duser.timezone=UTC`, `-Dconnex.workflows.runtime.enabled=false`, and
   `-Dconnex.workflows.runtime.scheduling-enabled=false`, and `-Dconnex.rules.scheduling-enabled=false`.
   Restart the prior backend/frontend so
   the deploy preflight can validate the prior pair. Check the backend PID's `jcmd VM.system_properties`
   output for all four values and confirm there is no higher-precedence Spring argument or
   environment override enabling polling. This temporary UTC override also protects a prior-artifact
   rollback during deployment; it does not repair existing rows. Request/internal triggers can
   still execute through the legacy path while these processes are live. Such pre-cutoff activity
   is tolerated only because these writers are UTC and a second full stop follows. Do not deliberately
   exercise workflows yet.
4. Run `/usr/local/bin/connex-staging-deploy` in the foreground as the normal deploy account.
   Require a successful committed deployment of the reviewed fixed revision. Verify the live
   `/api/version`, JAR identity, `.staging/deployed-sha`, `.staging/frontend-release`, and running
   frontend identity all agree as described in the staging runbook. There must be no pending
   nonterminal `.staging/deploy-transaction`. If deployment fails or rolls back, keep maintenance
   active and resolve it through the normal deployment recovery before attempting SQL. Never edit
   markers to make an uncommitted release appear committed.
5. **Quiesce a second time:** stop the frontend and backend again, confirm every writer is stopped,
   and take the restorable final backup now. Only now open the per-catalog maintenance sessions
   and freeze their UTC cutoffs using the SQL below. Correct UTC rows written by the fixed process
   during deployment are before those cutoffs and remain untouched. Review and commit each catalog.
6. Restart the verified fixed backend/frontend with all polling gates still false. Verify readiness
   and the four temporary JVM properties again. Keep external ingress and the timer closed.
   Restore the intended canonical/legacy polling properties in the environment file, retaining
   `-Duser.timezone=UTC`, restart the backend, and observe a controlled workflow completion.
   Reopen external ingress and restart `connex-staging-deploy.timer` only after this succeeds.
   A failure keeps maintenance active; do not restore the pre-fix artifact against repaired rows.

Retain the UTC override until both the live release and any permitted rollback release contain
this fix. A pre-fix rollback requires its own quiescence and retained UTC override; never silently
return to host-zone writers after repair. Do not rewrite release markers or deploy units as part
of this runbook. Remove only the temporary environment edits once the safe release set is verified,
then verify readiness and effective UTC behavior again. Preserve the original offset and SQL
results independently of the deployment's marker history.

## Complete converted-clock inventory

This inventory follows all **33** no-argument reads changed in `8596b6c35`, including values passed
through downstream services/mappers and independently timed writes on those paths. A Java-written
column below means a value bound by Java, including caller input; DB-authored columns and already-UTC
independent clocks are identified separately. It does not infer provenance from a timestamp's shape.
The source list is closed: the counts below total 33, rather than searching only workflow table names.

| Original sites | Source and use | Persistent timestamp/value lineage |
|---|---|---|
| 1 | `CampaignDispatchService`, quiet hours | Decision only; no affected timestamp persisted. |
| 2 | `ActivityService.resolveTimestamp` | `activity.timestamp`, copied into audit changes. |
| 3 | `AiProviderConfigService.attestedAt` | `ai_provider_config.attested_at`. |
| 4–5 | `DataSubjectRequestService`, disclosure generation / default receipt | Disclosure `generatedAt` in response JSON and its immutable audit fingerprint; `data_subject_request.received_at`. |
| 6–7 | `DealDocumentService.generate`, template date / generation time | Business text in document title/content; JSON `content.generatedAt`. |
| 8 | `DeliveryUnsubscribeService`, revocation capture | `contact_channel_consent.captured_at` through `ConsentService`. |
| 9 | `DeliveryWebhookService`, revocation capture | Same consent lineage. |
| 10–11 | `RuleActionExecutor`, task date / notification time | `task.due_date` and audit changes; `notification.triggered_at`. |
| 12 | `RuleEngineService.scheduleBucket` | Hashed legacy schedule identity; no timestamp column. |
| 13 | `SegmentService.today` | Read-only UTC query bounds, cached across pages. |
| 14 | `SequenceService.archive` | `sequence.archived_at`. |
| 15 | `WorkflowActionAttemptReservationService.reserve` | Step/attempt `started_at`, abandoned attempt `finished_at`. |
| 16 | `WorkflowDelayResumeService` | Step `finished_at`. |
| 17 | `WorkflowManualRunConfirmationTransaction.confirm` | Invocation expiry comparison; `confirmed_at` / expiry completion. |
| 18–20 | `WorkflowManualRunService`, cancellation / completion / preparation | Invocation `completed_at`, `created_at`, derived `expires_at`. |
| 21 | `WorkflowRunCancellationService` | Run/step/attempt completion times. |
| 22–24 | `WorkflowRunFailureService` | Run/step/attempt completion times; terminal diagnostic-step start. |
| 25–26 | `WorkflowRunOperationService` | Run/step/attempt completion; run `cancel_requested_at`. |
| 27 | `WorkflowRuntimeClaimService` | Run `started_at`. |
| 28–29 | `WorkflowRuntimeClaimTransaction` | Run/step/attempt completion times. |
| 30 | `WorkflowRuntimeRetentionService.purge` | DELETE cutoff only; no timestamp write. |
| 31 | `WorkflowService.archive` | Returned bean/audit state; stored archive time uses the DB clock. |
| 32–33 | `WorkflowStepTransactionService`, start / finish | Run/step/attempt start/completion bindings. |

### Inventoried rows requiring cutover action

| Table / Java-written columns on these paths | Cross-clock constraint or reader | Survives deploy / action |
|---|---|---|
| `workflow_run`: `started_at`, `finished_at`, `cancel_requested_at` | V143 `chk_workflow_run_timing` requires finish >= start; `WorkflowRunMapper` history uses DB `asOf`; `WorkflowOperationsMapper` backlog compares starts to `CURRENT_TIMESTAMP(6)`. | Any state survives, including succeeded history and intervention_required rows that manual retry reopens. Repair every future event cell together, in any status. |
| `workflow_step_run`: `started_at`, `finished_at` | V143 `chk_workflow_step_timing` requires finish >= start; state CHECK requires NULL finish while active. | Repair future start/finish cells in every status, regardless of the parent status; a later retry does not justify leaving future history behind. |
| `workflow_step_attempt`: `started_at`, `finished_at` | V144 checks lifecycle NULL/state but has no timing CHECK or timestamp claim filter. | Repair future event cells in every status to preserve durable start/finish ordering, including attempts under terminal parents. |
| `workflow_invocation`: `created_at`, `expires_at`, `confirmed_at`, `completed_at` | V145 `chk_workflow_invocation_timing` compares expiry/confirmation/completion to creation. Confirmation compares expiry to new Java now; pending dispatch sorts by confirmation. | Repair future creation/confirmation/completion events and their future expiry together in every status. Separately expire every prepared scope as described below. |
| `data_subject_request`: default/caller `received_at`; caller `identity_verified_at`, `due_at`, `responded_at`, `closed_at` | Java `validateChronology` requires lifecycle dates >= receipt. V74 has no temporal CHECK; mapper sorts receipt, with no automatic expiry/claim. | Every status except closed/refused, with closed_at NULL, is open; this includes responded. Such rows can reject a new UTC lifecycle date. Use the separately reviewed control-catalog receipt repair; never infer caller provenance from future time alone. |

Evidence: [`WorkflowRunMapper.xml`](../../backend/src/main/resources/mappers/WorkflowRunMapper.xml),
[`WorkflowOperationsMapper.xml`](../../backend/src/main/resources/mappers/WorkflowOperationsMapper.xml),
[V143](../../backend/src/main/resources/db/migration/tenant/V143__workflow_runtime.sql),
[V144](../../backend/src/main/resources/db/migration/tenant/V144__workflow_durability.sql),
[V145](../../backend/src/main/resources/db/migration/tenant/V145__workflow_operations_scope.sql),
[`DataSubjectRequestService`](../../backend/src/main/java/ooo/klae/connex/backend/services/DataSubjectRequestService.java#L383),
and [V74](../../backend/src/main/resources/db/migration/control/V74__data_subject_request.sql).

### Inventoried, no action needed

“No action” means no automatic cutover rewrite, not that old historical wall times become accurate.
This table includes transitive writers, so an independent UTC clock is not mistaken for a converted
host-zone value. Existing caller/business dates and immutable evidence remain intact.

| Table / Java-written values on these paths | CHECK / clock comparison / across deploy | Reason for no cutover action |
|---|---|---|
| `workflow`: no affected Java-written stored time; `archived_at` is DB-authored | V143 archive CHECK is enabled/nullability only; archived rows survive. | The converted read only updates the returned bean after the DB archive write. |
| `rule_execution`: no affected Java timestamp; `executed_at` is DB-authored | Legacy claims survive; schedule bucket contributes to a hashed dedupe identity, not a timestamp comparison. | DB clock was already UTC. Production scheduler already supplies its explicit UTC bucket; do not rewrite hashes. |
| `workflow_trigger_outbox`: independent UTC `occurred_at`; DB availability/lease/completion times | Pending/leased rows survive and are claimed against DB time. Converted retention read supplies a terminal-row DELETE cutoff only. | No active timestamp derives from the converted clock. Earlier retention deletions cannot be reconstructed by this procedure. |
| `workflow_runtime_workspace`, `workflow_invocation_record`, `workflow_intervention`: DB-authored timestamps | Gates, pending records and open interventions survive; lifecycle checks use state/NULL and DB-authored times. | No affected Java timestamp binding. Preserve DB dates. |
| `activity.timestamp` and its audit JSON copy | Persistent events have no terminal lifecycle. `ActivityMapper` and `SegmentMapper` compare it to `NOW()`/UTC bounds for history, analytics and upcoming meetings; no temporal CHECK/lease/claim. | Mixed caller/default provenance and intentionally future meetings prohibit blanket shifting. Old fallback events can temporarily misbucket; review individually if needed, and preserve audit evidence. |
| `ai_provider_config.attested_at`; independently UTC `zdr_attested_at` | Config survives. V167 checks attestation co-presence/version, not ordering; readiness uses booleans/configuration, no age/expiry comparison. | Historical attestation evidence; no deadline or claim. |
| `deal_document.title/content`, including JSON `generatedAt`; no Java binding for DB `generated_at` | Documents in every status survive; no temporal CHECK or clock gate over content. | Immutable/commercial evidence and workspace-calendar business text. DB generation ordering already uses UTC. |
| `contact_channel_consent.captured_at` | Consent survives until changed. V93 checks channel/status, not time; no expiry/deadline/claim comparison. | Mixed caller/event provenance; consent state is not time-gated. |
| `task.due_date` and audit JSON copy | Open tasks survive. DATE values drive business overdue/urgency comparisons; no timestamp timing CHECK or lease. | Workspace/user commitments, also caller-editable. An hour offset cannot safely identify or correct a business date. |
| `notification.triggered_at` | Unresolved notifications survive; affects display ordering, with no temporal CHECK or claim gate. | Historical ordering only. Mention notification clocks already explicitly used UTC; email claims use a separate DB clock. |
| `sequence.archived_at` | Archived rows are terminal. V198 only checks archived/null co-presence. | Terminal history; no ordering/deadline/claim predicate. |
| `audit_log.created_at`, changes/context; integrity head/checkpoint metadata | UTC injected `Clock` supplies creation (DB fallback); old business/event values may be copied into changes. Rows survive and are append-only. | The independent clock was UTC before this fix. Never rewrite signed/hashed audit evidence. |
| `notification_recipient_state.updated_at`: DB-authored | Notification delivery bumps a recipient state version via insert/upsert. The triggered time is not copied into this control table. | No affected Java timestamp value. |
| `secret_value.created_at` / `updated_at` / `rotated_at`: DB-authored metadata | Config save can replace stored material; rotation uses DB `CURRENT_TIMESTAMP`, not the attestation read. | No affected timestamp binding or expiry/claim lineage. |
| `entity_reference.created_at` | Reference rows survive; DB default, not the activity timestamp. | No affected Java clock value. |
| `person` / `person_lifecycle_pass`: first-response start/due/responded/breached times | Active SLA deadlines and lifecycle checks survive and compare UTC clocks. Activity response writes read `Instant.now(clock)` and the pass copies those values. | Independent injected clock was already UTC; the activity's fallback `timestamp` is not passed to the SLA writer. |
| `suppression_entry`, `contact_channel_consent_event`, `campaign_delivery_event`: DB creation times | Current suppression and historical event rows survive; consent/delivery state uses status, not the old capture timestamp. | Inserts do not copy converted capture time into their clocks. |
| `campaign_delivery` / `campaign_send` dispatch metadata; `notification.email_delivery_claimed_at` | Pending/dispatching rows survive; availability, lease, expiry and claim clocks are DB/independent UTC. Quiet-hours read itself writes no timestamp. | No affected timestamp lineage. Quiet hours are currently disabled; the read is reevaluated after deployment. |
| `campaign_delivery_unsubscribe_token`: no timestamp columns | Dispatch can create the hash/workspace/delivery lookup row (V227). | No time value to shift. |
| Disclosure generation and segment bounds | Generated time is returned in disclosure JSON and contributes to an audit JSON hash/byte count; segment bounds are read-only. No persisted recoverable old date or temporal predicate. | Preserve audit fingerprints; nothing to repair in segment bounds. Workspace-local segment dates and the existing `CURDATE()` overdue predicate remain a separate follow-up. |

Evidence for the non-workflow rows is in `ActivityMapper`, `AiProviderConfigMapper`,
`DataSubjectRequestMapper`, `DealDocumentMapper`, `ConsentMapper`, `TaskMapper`, `NotificationMapper`,
`SequenceMapper`, `EntityReferenceMapper`, `PersonMapper`, and `PersonLifecyclePassMapper` under
[`resources/mappers`](../../backend/src/main/resources/mappers). The inventory searched all migration
`CHECK (` bodies for these columns and all mapper clock comparisons (`NOW`, `CURRENT_TIMESTAMP`,
`UTC_TIMESTAMP`, Java `now`/`asOf` bindings); it did not treat the absence of a CHECK as the absence
of a Java chronology or ordering hazard.

## Repair scope and invocation policy

The runtime step table is `workflow_step_run`. **Workflow timestamp repair has no status or
parent-status filter.** A future Java event is impossible under a correct UTC clock in any state.
A supposedly terminal row can be reopened: `WorkflowRunOperationService.retry()` calls
`WorkflowRunMapper.scheduleManualRetry`, which reopens `intervention_required` to `waiting`, clears
`finished_at`, and preserves `started_at`. Even succeeded history must keep a coherent time pair.

| Column or state | Treatment |
|---|---|
| `workflow_run.started_at`, `finished_at`, `cancel_requested_at` | Select by any future event; shift every future cell by the same old offset in one UPDATE, in every status. |
| `workflow_step_run.started_at`, `finished_at` | Same future-only rule in every status, without a parent filter. |
| `workflow_step_attempt.started_at`, `finished_at` | Same future-only rule in every status, including durable failed/succeeded attempts. |
| `workflow_invocation.created_at`, `confirmed_at`, `completed_at` | Select by any future event and shift every future cell together, in every status. |
| `workflow_invocation.expires_at` | Shift a future expiry in that same event-selected row. Never select a row by expiry alone. |
| Prepared invocations | After timestamp repair, separately expire **every** prepared scope, including valid UTC scopes created during deployment smoke. Set `completed_at` to the frozen cutoff; users prepare a fresh preview after maintenance. |
| Database `updated_at`, run `resume_at`/`lease_until` | Preserve explicitly. Future database deadlines are legitimate. |

The manual preparation lifetime is fifteen minutes (`WorkflowManualRunService.invocation`). An
expiry can legitimately remain future after subtraction. Expiry alone cannot distinguish an old
scope whose creation is already nonfuture from a correct UTC scope, or make repeated subtraction
idempotent. The separately snapshotted prepared-expiry policy closes that ambiguity without
shifting expiry-only rows. It preserves confirmation identities and does not cancel confirmed or
running invocations or their linked runs. Prepared invalidation is the sole status-specific
lifecycle operation; status never limits timestamp repair.

The event candidates must lie in `(cutoff, cutoff + old offset]`. With all writers stopped and
clocks synchronized, a valid UTC event cannot be after the frozen cutoff. Every future event
then moves to at/before the cutoff, making the repair idempotent. Expiry alone is excluded from
both candidate selection and remaining event hazards because it is a legitimate future deadline.

**Fail closed on a boundary inversion.** A nonfuture lower timestamp and future upper timestamp
can straddle the cutoff. Subtracting only the upper value could put it before the unchanged lower
value. The guard validates the complete corrected run/step/attempt pairs and V145 invocation
chronology before changing any row. On failure, roll back and reconcile individually from the
backup and source evidence; do not clamp a value, rewrite a nonfuture cell, or disable a CHECK.
All timestamp assignments use frozen snapshots, so every future cell in a row shifts in the
same UPDATE without relying on assignment evaluation order.

Already nonfuture event cells stay unchanged. Future workflow events are repaired even in
terminal history; no state is an exclusion. Outside these workflow event columns, the inventory
above distinguishes unaffected DB/independent clocks and caller/business dates requiring provenance
review. Integrity-fingerprinted or immutable evidence, including audit rows and disclosure
fingerprints, is never rewritten.

## Per-catalog SQL

The example offset is staging's `+09:00`. Replace it only with the verified positive old offset.
The safety flag gates every update. Temporary snapshots preserve the candidate values for exact
postflight comparisons; they disappear when the maintenance connection closes. Start with a
fresh connection and no open transaction. Commit or roll back an execution before rerunning it;
`START TRANSACTION` can implicitly commit an earlier transaction on the same connection.

```sql
SET time_zone = '+00:00';
SET @utc_cutover_offset_seconds = 32400;
SET @utc_cutover_as_of = UTC_TIMESTAMP(6);
SET @utc_cutover_ceiling = TIMESTAMPADD(SECOND, @utc_cutover_offset_seconds, @utc_cutover_as_of);

DROP TEMPORARY TABLE IF EXISTS utc_cutover_run_before, utc_cutover_step_before, utc_cutover_attempt_before, utc_cutover_invocation_before, utc_cutover_prepared_before;
START TRANSACTION;

CREATE TEMPORARY TABLE utc_cutover_run_before ENGINE=InnoDB AS
SELECT t.*,
       CASE WHEN started_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, started_at) ELSE started_at END AS expected_started_at,
       CASE WHEN finished_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, finished_at) ELSE finished_at END AS expected_finished_at,
       CASE WHEN cancel_requested_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, cancel_requested_at) ELSE cancel_requested_at END AS expected_cancel_requested_at
FROM workflow_run t
WHERE started_at > @utc_cutover_as_of OR finished_at > @utc_cutover_as_of OR cancel_requested_at > @utc_cutover_as_of;

CREATE TEMPORARY TABLE utc_cutover_step_before ENGINE=InnoDB AS
SELECT t.*,
       CASE WHEN started_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, started_at) ELSE started_at END AS expected_started_at,
       CASE WHEN finished_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, finished_at) ELSE finished_at END AS expected_finished_at
FROM workflow_step_run t
WHERE started_at > @utc_cutover_as_of OR finished_at > @utc_cutover_as_of;

CREATE TEMPORARY TABLE utc_cutover_attempt_before ENGINE=InnoDB AS
SELECT t.*,
       CASE WHEN started_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, started_at) ELSE started_at END AS expected_started_at,
       CASE WHEN finished_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, finished_at) ELSE finished_at END AS expected_finished_at
FROM workflow_step_attempt t
WHERE started_at > @utc_cutover_as_of OR finished_at > @utc_cutover_as_of;

CREATE TEMPORARY TABLE utc_cutover_invocation_before ENGINE=InnoDB AS
SELECT t.*,
       CASE WHEN created_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, created_at) ELSE created_at END AS expected_created_at,
       CASE WHEN confirmed_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, confirmed_at) ELSE confirmed_at END AS expected_confirmed_at,
       CASE WHEN completed_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, completed_at) ELSE completed_at END AS expected_completed_at,
       CASE WHEN expires_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, expires_at) ELSE expires_at END AS expected_expires_at
FROM workflow_invocation t
WHERE created_at > @utc_cutover_as_of OR confirmed_at > @utc_cutover_as_of OR completed_at > @utc_cutover_as_of;

CREATE TEMPORARY TABLE utc_cutover_prepared_before ENGINE=InnoDB AS
SELECT workspace_id, id, confirmation_key, updated_at,
       CASE WHEN created_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, created_at) ELSE created_at END AS expected_created_at,
       CASE WHEN created_at > @utc_cutover_as_of THEN
           CASE WHEN expires_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, expires_at) ELSE expires_at END
           ELSE expires_at END AS expected_expires_at
FROM workflow_invocation WHERE status = 'prepared';

SET @utc_cutover_safe = @utc_cutover_offset_seconds BETWEEN 1 AND 50400
    AND NOT EXISTS (
        SELECT 1 FROM utc_cutover_run_before
        WHERE started_at > @utc_cutover_ceiling
           OR finished_at > @utc_cutover_ceiling
           OR cancel_requested_at > @utc_cutover_ceiling
           OR expected_finished_at < expected_started_at
    )
    AND NOT EXISTS (
        SELECT 1 FROM utc_cutover_step_before
        WHERE started_at > @utc_cutover_ceiling
           OR finished_at > @utc_cutover_ceiling
           OR expected_finished_at < expected_started_at
    )
    AND NOT EXISTS (
        SELECT 1 FROM utc_cutover_attempt_before
        WHERE started_at > @utc_cutover_ceiling
           OR finished_at > @utc_cutover_ceiling
           OR expected_finished_at < expected_started_at
    )
    AND NOT EXISTS (
        SELECT 1 FROM utc_cutover_invocation_before
        WHERE created_at > @utc_cutover_ceiling
           OR confirmed_at > @utc_cutover_ceiling
           OR completed_at > @utc_cutover_ceiling
           OR expected_expires_at < expected_created_at
           OR expected_confirmed_at < expected_created_at
           OR expected_completed_at < expected_created_at
    );

SELECT DATABASE() AS catalog, @utc_cutover_as_of AS cutoff_utc,
       @utc_cutover_offset_seconds AS old_offset_seconds, @utc_cutover_safe AS safe_to_shift;
SELECT COUNT(*) AS expected_run_rows,
       COALESCE(SUM(started_at > @utc_cutover_as_of), 0) AS run_start_values,
       COALESCE(SUM(finished_at > @utc_cutover_as_of), 0) AS run_finish_values,
       COALESCE(SUM(cancel_requested_at > @utc_cutover_as_of), 0) AS run_cancellation_values
FROM utc_cutover_run_before;
SELECT COUNT(*) AS expected_step_rows,
       COALESCE(SUM(started_at > @utc_cutover_as_of), 0) AS step_start_values,
       COALESCE(SUM(finished_at > @utc_cutover_as_of), 0) AS step_finish_values
FROM utc_cutover_step_before;
SELECT COUNT(*) AS expected_attempt_rows,
       COALESCE(SUM(started_at > @utc_cutover_as_of), 0) AS attempt_start_values,
       COALESCE(SUM(finished_at > @utc_cutover_as_of), 0) AS attempt_finish_values
FROM utc_cutover_attempt_before;
SELECT COUNT(*) AS expected_invocation_rows,
       COALESCE(SUM(created_at > @utc_cutover_as_of), 0) AS invocation_creation_values,
       COALESCE(SUM(confirmed_at > @utc_cutover_as_of), 0) AS invocation_confirmation_values,
       COALESCE(SUM(completed_at > @utc_cutover_as_of), 0) AS invocation_completion_values,
       COALESCE(SUM(expires_at > @utc_cutover_as_of), 0) AS invocation_expiry_values
FROM utc_cutover_invocation_before;
SELECT COUNT(*) AS prepared_scopes_to_expire FROM utc_cutover_prepared_before;

UPDATE workflow_run t
JOIN utc_cutover_run_before b ON b.workspace_id = t.workspace_id AND b.id = t.id
SET t.started_at = b.expected_started_at,
    t.finished_at = b.expected_finished_at,
    t.cancel_requested_at = b.expected_cancel_requested_at,
    t.updated_at = b.updated_at
WHERE @utc_cutover_safe = 1;
SET @utc_cutover_changed_runs = ROW_COUNT();

UPDATE workflow_step_run t
JOIN utc_cutover_step_before b ON b.workspace_id = t.workspace_id AND b.id = t.id
SET t.started_at = b.expected_started_at,
    t.finished_at = b.expected_finished_at
WHERE @utc_cutover_safe = 1;
SET @utc_cutover_changed_steps = ROW_COUNT();

UPDATE workflow_step_attempt t
JOIN utc_cutover_attempt_before b ON b.workspace_id = t.workspace_id AND b.id = t.id
SET t.started_at = b.expected_started_at,
    t.finished_at = b.expected_finished_at
WHERE @utc_cutover_safe = 1;
SET @utc_cutover_changed_attempts = ROW_COUNT();

UPDATE workflow_invocation t
JOIN utc_cutover_invocation_before b ON b.workspace_id = t.workspace_id AND b.id = t.id
SET t.created_at = b.expected_created_at,
    t.confirmed_at = b.expected_confirmed_at,
    t.completed_at = b.expected_completed_at,
    t.expires_at = b.expected_expires_at,
    t.updated_at = b.updated_at
WHERE @utc_cutover_safe = 1;
SET @utc_cutover_changed_invocations = ROW_COUNT();

UPDATE workflow_invocation i
JOIN utc_cutover_prepared_before b ON b.workspace_id = i.workspace_id AND b.id = i.id
SET i.status = 'expired', i.completed_at = @utc_cutover_as_of, i.updated_at = b.updated_at
WHERE @utc_cutover_safe = 1;
SET @utc_cutover_expired_prepared = ROW_COUNT();

SELECT @utc_cutover_changed_runs AS changed_run_rows,
       @utc_cutover_changed_steps AS changed_step_rows,
       @utc_cutover_changed_attempts AS changed_attempt_rows,
       @utc_cutover_changed_invocations AS changed_invocation_rows,
       @utc_cutover_expired_prepared AS expired_prepared_scopes;

SELECT COUNT(*) AS run_mismatches
FROM workflow_run t
JOIN utc_cutover_run_before b ON b.workspace_id = t.workspace_id AND b.id = t.id
WHERE NOT (t.started_at <=> b.expected_started_at)
   OR NOT (t.finished_at <=> b.expected_finished_at)
   OR NOT (t.cancel_requested_at <=> b.expected_cancel_requested_at)
   OR NOT (t.status <=> b.status)
   OR NOT (t.updated_at <=> b.updated_at)
   OR NOT (t.resume_at <=> b.resume_at)
   OR NOT (t.lease_until <=> b.lease_until);

SELECT COUNT(*) AS step_mismatches
FROM workflow_step_run t
JOIN utc_cutover_step_before b ON b.workspace_id = t.workspace_id AND b.id = t.id
WHERE NOT (t.started_at <=> b.expected_started_at)
   OR NOT (t.finished_at <=> b.expected_finished_at)
   OR NOT (t.status <=> b.status);

SELECT COUNT(*) AS attempt_mismatches
FROM workflow_step_attempt t
JOIN utc_cutover_attempt_before b ON b.workspace_id = t.workspace_id AND b.id = t.id
WHERE NOT (t.started_at <=> b.expected_started_at)
   OR NOT (t.finished_at <=> b.expected_finished_at)
   OR NOT (t.status <=> b.status);

SELECT COUNT(*) AS invocation_mismatches
FROM workflow_invocation t
JOIN utc_cutover_invocation_before b ON b.workspace_id = t.workspace_id AND b.id = t.id
WHERE NOT (t.created_at <=> b.expected_created_at)
   OR NOT (t.confirmed_at <=> b.expected_confirmed_at)
   OR NOT (t.completed_at <=> CASE WHEN b.status = 'prepared' THEN @utc_cutover_as_of ELSE b.expected_completed_at END)
   OR NOT (t.expires_at <=> b.expected_expires_at)
   OR NOT (t.status <=> CASE WHEN b.status = 'prepared' THEN 'expired' ELSE b.status END)
   OR NOT (t.confirmation_key <=> b.confirmation_key)
   OR NOT (t.updated_at <=> b.updated_at);

SELECT COUNT(*) AS prepared_mismatches
FROM workflow_invocation i
JOIN utc_cutover_prepared_before b ON b.workspace_id = i.workspace_id AND b.id = i.id
WHERE i.status <> 'expired' OR NOT (i.completed_at <=> @utc_cutover_as_of)
   OR NOT (i.created_at <=> b.expected_created_at)
   OR NOT (i.expires_at <=> b.expected_expires_at)
   OR NOT (i.confirmation_key <=> b.confirmation_key)
   OR i.confirmed_at IS NOT NULL OR NOT (i.updated_at <=> b.updated_at);

SELECT
    (SELECT COUNT(*) FROM workflow_run
     WHERE started_at > @utc_cutover_as_of OR finished_at > @utc_cutover_as_of OR cancel_requested_at > @utc_cutover_as_of)
    +
    (SELECT COUNT(*) FROM workflow_step_run
     WHERE started_at > @utc_cutover_as_of OR finished_at > @utc_cutover_as_of)
    +
    (SELECT COUNT(*) FROM workflow_step_attempt
     WHERE started_at > @utc_cutover_as_of OR finished_at > @utc_cutover_as_of)
    +
    (SELECT COUNT(*) FROM workflow_invocation
     WHERE created_at > @utc_cutover_as_of OR confirmed_at > @utc_cutover_as_of OR completed_at > @utc_cutover_as_of)
    +
    (SELECT COUNT(*) FROM workflow_invocation WHERE status = 'prepared') AS remaining_hazards;
```

After successful verification, finish the open transaction with `COMMIT;`. Otherwise use
`ROLLBACK;` and retain the paused state. Closing the session without committing also rolls back
the InnoDB data changes. Temporary-table definitions themselves are not rolled back. If the
connection is lost during `COMMIT`, keep every writer paused and rerun from a fresh connection;
idempotence resolves whether the first commit landed without applying the shift twice.

`updated_at = updated_at` prevents the database from replacing the original update time during
this repair. MySQL documents both [automatic timestamp updates](https://dev.mysql.com/doc/refman/8.4/en/timestamp-initialization.html)
and the [temporary-table exception to implicit commits](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html).
Do not substitute ordinary tables or `TRUNCATE` inside the transaction.

## Control-catalog DSR provenance review

`data_subject_request.received_at` is caller-supplied when present, otherwise the converted Java
clock supplies it. The lifecycle queries in `TenantLifecycleControlMapper` define open status as
`status NOT IN ('closed', 'refused')`; this repair additionally requires `closed_at IS NULL`.
This includes `responded` requests awaiting closure. An open request with an old future server-default receipt can reject a new UTC
identity-verification, response, or closure date through `DataSubjectRequestService.validateChronology`.
The column has no provenance flag: a future value cannot distinguish a server default from a
legitimate caller business date. Never blanket-shift it or edit its append-only audit evidence.

While every writer remains stopped, select the **control catalog**, verify `DATABASE()`, and run
this setup/preview. Include that catalog in the final backup. Inspect API/audit/source evidence for
every open returned request. The preview deliberately lists all future receipts, including closed/refused
rows, to make exclusions visible. Populate the temporary review table with its exact `(org_id, id, received_at)`
and `shift_received_at=TRUE` only when the old server default is verified. Use `FALSE` for a verified
caller date that must remain unchanged. If provenance is uncertain, keep maintenance active and
resolve the request individually; do not guess. Record each decision in the maintenance record.

```sql
SET time_zone = '+00:00';
SET @utc_cutover_offset_seconds = 32400;
SET @utc_dsr_as_of = UTC_TIMESTAMP(6);
SET @utc_dsr_ceiling = TIMESTAMPADD(SECOND, @utc_cutover_offset_seconds, @utc_dsr_as_of);
CREATE TEMPORARY TABLE IF NOT EXISTS utc_cutover_dsr_review (
    org_id INT NOT NULL,
    id BIGINT NOT NULL,
    reviewed_received_at DATETIME NOT NULL,
    shift_received_at BOOLEAN NOT NULL CHECK (shift_received_at IN (FALSE, TRUE)),
    PRIMARY KEY (org_id, id)
) ENGINE=InnoDB;
SELECT DATABASE() AS catalog, org_id, id, status, received_at,
       identity_verified_at, responded_at, closed_at
FROM data_subject_request
WHERE received_at > @utc_dsr_as_of
ORDER BY org_id, id;
```

Insert the reviewed decisions on this same connection before running the next block. It fails
closed if any future open request lacks a matching decision, if a shift approval is stale, or if
an approved event exceeds the verified offset. Deliberately preserved caller dates can exceed
that offset. Only a verified future server-default `received_at` is shifted; all caller lifecycle
dates, database timestamps, closed/refused requests, and requests with a non-null closure remain unchanged.
If a caller lifecycle date is itself future, shifting the receipt alone may not allow a new UTC
closure; resolve that caller date separately from verified source evidence before resuming. Completed prior shifts are
recognized on rerun. A new connection requires repeating setup and provenance review.

```sql
DROP TEMPORARY TABLE IF EXISTS utc_cutover_dsr_before;
START TRANSACTION;
SET @utc_dsr_review_complete = NOT EXISTS (
        SELECT 1 FROM data_subject_request r
        LEFT JOIN utc_cutover_dsr_review v ON v.org_id = r.org_id AND v.id = r.id
            AND v.reviewed_received_at = r.received_at
        WHERE r.status NOT IN ('closed', 'refused') AND r.closed_at IS NULL
          AND r.received_at > @utc_dsr_as_of AND v.id IS NULL
    );
SET @utc_dsr_approvals_current = NOT EXISTS (
        SELECT 1 FROM utc_cutover_dsr_review v
        LEFT JOIN data_subject_request r ON r.org_id = v.org_id AND r.id = v.id
        WHERE v.shift_received_at = TRUE
          AND (r.id IS NULL OR r.status IN ('closed', 'refused')
               OR r.closed_at IS NOT NULL OR v.reviewed_received_at > @utc_dsr_ceiling
               OR (r.received_at <> v.reviewed_received_at
                   AND r.received_at <> TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, v.reviewed_received_at)))
    );
SET @utc_dsr_safe = @utc_cutover_offset_seconds BETWEEN 1 AND 50400
    AND @utc_dsr_review_complete AND @utc_dsr_approvals_current;
CREATE TEMPORARY TABLE utc_cutover_dsr_before ENGINE=InnoDB AS
SELECT r.org_id, r.id, r.status, r.received_at, r.identity_verified_at, r.due_at,
       r.responded_at, r.closed_at, r.created_at, r.updated_at
FROM data_subject_request r
JOIN utc_cutover_dsr_review v ON v.org_id = r.org_id AND v.id = r.id
    AND v.reviewed_received_at = r.received_at AND v.shift_received_at = TRUE
WHERE r.status NOT IN ('closed', 'refused') AND r.closed_at IS NULL
  AND r.received_at > @utc_dsr_as_of AND r.received_at <= @utc_dsr_ceiling;
SELECT @utc_dsr_safe AS dsr_safe_to_shift, COUNT(*) AS expected_dsr_rows
FROM utc_cutover_dsr_before;
UPDATE data_subject_request r
JOIN utc_cutover_dsr_before b ON b.org_id = r.org_id AND b.id = r.id
SET r.received_at = TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, b.received_at),
    r.updated_at = b.updated_at
WHERE @utc_dsr_safe = 1 AND r.received_at = b.received_at
  AND r.status NOT IN ('closed', 'refused') AND r.closed_at IS NULL
  AND r.received_at > @utc_dsr_as_of AND r.received_at <= @utc_dsr_ceiling;
SELECT ROW_COUNT() AS changed_dsr_rows;
SELECT COUNT(*) AS dsr_mismatches
FROM data_subject_request r
JOIN utc_cutover_dsr_before b ON b.org_id = r.org_id AND b.id = r.id
WHERE NOT (r.received_at <=> TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, b.received_at))
   OR NOT (r.status <=> b.status)
   OR NOT (r.identity_verified_at <=> b.identity_verified_at)
   OR NOT (r.due_at <=> b.due_at)
   OR NOT (r.responded_at <=> b.responded_at)
   OR NOT (r.closed_at <=> b.closed_at)
   OR NOT (r.created_at <=> b.created_at)
   OR NOT (r.updated_at <=> b.updated_at);
SELECT COUNT(*) AS dsr_remaining_hazards
FROM data_subject_request r
LEFT JOIN utc_cutover_dsr_review v ON v.org_id = r.org_id AND v.id = r.id
    AND v.reviewed_received_at = r.received_at
WHERE r.status NOT IN ('closed', 'refused') AND r.closed_at IS NULL
  AND r.received_at > @utc_dsr_as_of AND (v.id IS NULL OR v.shift_received_at = TRUE);
```

Commit only when `dsr_safe_to_shift` is `1`, changed/expected counts match, and both postflight
counts are zero. Otherwise roll back and keep writers stopped. The same fresh-session,
ambiguous-commit, positive-offset and no-rerun-after-resume rules apply. Do not run this block in
a tenant-only catalog or turn a preserved caller date into a shift approval merely to clear a gate.

The DSR review-completeness and approval-currentness predicates intentionally use separate SQL
statements: MySQL [cannot reopen one temporary table twice in a query](https://dev.mysql.com/doc/refman/8.4/en/temporary-table-problems.html).
