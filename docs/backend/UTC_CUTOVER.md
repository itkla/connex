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
   without workflow tables is not a repair target.
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
   Run the SQL below once per catalog. It freezes a database UTC cutoff, previews candidates, and
   leaves the transaction open for verification. Do not use a client mode that continues after errors.
5. Commit only when the safety flag is `1`, changed-row counts match the candidate counts, all three
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

## Eligible columns and deliberately preserved data

The runtime step table is `workflow_step_run`. Nonterminal means exactly `queued`, `running`, or
`waiting`. A step is eligible only while its parent run is also nonterminal.

| Column | Writer and treatment |
|---|---|
| `workflow_run.started_at` | Java run creation in `WorkflowRuntimeClaimService`; shift only a future value. |
| `workflow_run.cancel_requested_at` | Java cancellation in `WorkflowRunOperationService`, added by V144; shift only a future value. |
| `workflow_step_run.started_at` | Java creation/reservation in `WorkflowStepTransactionService` and `WorkflowActionAttemptReservationService`; shift only a future value. |
| Both tables' `finished_at` | Java completion/failure/cancellation writers supply these, but V143 requires them to be NULL on nonterminal rows. Validate and leave unchanged. |
| `workflow_run.updated_at` | Database `CURRENT_TIMESTAMP(6)` with automatic updates. Preserve explicitly during the repair. |
| `workflow_run.resume_at`, `workflow_run.lease_until` | Database-clock delay/retry and lease deadlines. Legitimately future; never shift them. |

The Java-supplied start/completion bindings and cancellation bindings are in
[`WorkflowRunMapper.xml`](../../backend/src/main/resources/mappers/WorkflowRunMapper.xml). Their
state and timing constraints are in
[V143](../../backend/src/main/resources/db/migration/tenant/V143__workflow_runtime.sql) and
[V144](../../backend/src/main/resources/db/migration/tenant/V144__workflow_durability.sql).

The three eligible columns record an event happening now, not a scheduled future event. With all
writers stopped and synchronized clocks, valid UTC event values cannot exceed the frozen database
UTC cutoff. A value in `(cutoff, cutoff + old offset]` therefore self-identifies the remaining
positive-offset cutover hazard. Subtracting the old offset puts it at or before the cutoff.
The upper-bound guard refuses values that would still be future after one subtraction.
Consequently a second execution while writers remain stopped cannot shift a corrected value again.

Older historical rows **stay shifted and are not rewritten**. This includes terminal runs, terminal
steps, steps under terminal runs, `workflow_step_attempt`, other Java-written tables, and active
values that are no longer future. Those values cannot reliably be distinguished from valid UTC
history. An untouched active start at or before the cutoff can accept a later UTC completion without
violating the timing check, although its historical duration may remain inaccurate.

## Per-catalog SQL

The example offset is staging's `+09:00`. Replace it only with the verified positive old offset.
The safety flag gates both updates. Temporary snapshots preserve the candidate values for exact
postflight comparisons; they disappear when the maintenance connection closes. Start with a
fresh connection and no open transaction. Commit or roll back an execution before rerunning it;
`START TRANSACTION` can implicitly commit an earlier transaction on the same connection.

```sql
SET time_zone = '+00:00';
SET @utc_cutover_offset_seconds = 32400;
SET @utc_cutover_as_of = UTC_TIMESTAMP(6);
SET @utc_cutover_ceiling = TIMESTAMPADD(SECOND, @utc_cutover_offset_seconds, @utc_cutover_as_of);

DROP TEMPORARY TABLE IF EXISTS utc_cutover_run_before, utc_cutover_step_before;
START TRANSACTION;

SET @utc_cutover_safe =
    @utc_cutover_offset_seconds BETWEEN 1 AND 50400
    AND NOT EXISTS (
        SELECT 1 FROM workflow_run
        WHERE status IN ('queued', 'running', 'waiting')
          AND (finished_at IS NOT NULL
               OR started_at > @utc_cutover_ceiling
               OR cancel_requested_at > @utc_cutover_ceiling)
    )
    AND NOT EXISTS (
        SELECT 1 FROM workflow_step_run s
        JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.workflow_run_id
        WHERE r.status IN ('queued', 'running', 'waiting')
          AND s.status IN ('queued', 'running', 'waiting')
          AND (s.finished_at IS NOT NULL OR s.started_at > @utc_cutover_ceiling)
    );

CREATE TEMPORARY TABLE utc_cutover_run_before ENGINE=InnoDB AS
SELECT workspace_id, id, status, started_at, cancel_requested_at, finished_at,
       updated_at, resume_at, lease_until
FROM workflow_run
WHERE status IN ('queued', 'running', 'waiting')
  AND (started_at > @utc_cutover_as_of OR cancel_requested_at > @utc_cutover_as_of);

CREATE TEMPORARY TABLE utc_cutover_step_before ENGINE=InnoDB AS
SELECT s.workspace_id, s.id, s.status, s.started_at, s.finished_at
FROM workflow_step_run s
JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.workflow_run_id
WHERE r.status IN ('queued', 'running', 'waiting')
  AND s.status IN ('queued', 'running', 'waiting')
  AND s.started_at > @utc_cutover_as_of;

SELECT DATABASE() AS catalog, @utc_cutover_as_of AS cutoff_utc,
       @utc_cutover_offset_seconds AS old_offset_seconds, @utc_cutover_safe AS safe_to_shift;
SELECT COUNT(*) AS expected_run_rows,
       COALESCE(SUM(started_at > @utc_cutover_as_of), 0) AS run_start_values,
       COALESCE(SUM(cancel_requested_at > @utc_cutover_as_of), 0) AS cancellation_values
FROM utc_cutover_run_before;
SELECT COUNT(*) AS expected_step_rows FROM utc_cutover_step_before;

UPDATE workflow_run
SET started_at = CASE
        WHEN started_at > @utc_cutover_as_of AND started_at <= @utc_cutover_ceiling
        THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, started_at)
        ELSE started_at END,
    cancel_requested_at = CASE
        WHEN cancel_requested_at > @utc_cutover_as_of AND cancel_requested_at <= @utc_cutover_ceiling
        THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, cancel_requested_at)
        ELSE cancel_requested_at END,
    updated_at = updated_at
WHERE @utc_cutover_safe = 1
  AND status IN ('queued', 'running', 'waiting')
  AND (started_at > @utc_cutover_as_of OR cancel_requested_at > @utc_cutover_as_of);
SET @utc_cutover_changed_runs = ROW_COUNT();

UPDATE workflow_step_run s
JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.workflow_run_id
SET s.started_at = TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, s.started_at)
WHERE @utc_cutover_safe = 1
  AND r.status IN ('queued', 'running', 'waiting')
  AND s.status IN ('queued', 'running', 'waiting')
  AND s.started_at > @utc_cutover_as_of
  AND s.started_at <= @utc_cutover_ceiling;
SET @utc_cutover_changed_steps = ROW_COUNT();

SELECT @utc_cutover_changed_runs AS changed_run_rows, @utc_cutover_changed_steps AS changed_step_rows;

SELECT COUNT(*) AS run_mismatches
FROM workflow_run r
JOIN utc_cutover_run_before b ON b.workspace_id = r.workspace_id AND b.id = r.id
WHERE NOT (r.started_at <=> CASE WHEN b.started_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, b.started_at) ELSE b.started_at END)
   OR NOT (r.cancel_requested_at <=> CASE WHEN b.cancel_requested_at > @utc_cutover_as_of
           THEN TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, b.cancel_requested_at)
           ELSE b.cancel_requested_at END)
   OR NOT (r.status <=> b.status)
   OR NOT (r.finished_at <=> b.finished_at)
   OR NOT (r.updated_at <=> b.updated_at)
   OR NOT (r.resume_at <=> b.resume_at)
   OR NOT (r.lease_until <=> b.lease_until);

SELECT COUNT(*) AS step_mismatches
FROM workflow_step_run s
JOIN utc_cutover_step_before b ON b.workspace_id = s.workspace_id AND b.id = s.id
WHERE NOT (s.started_at <=> TIMESTAMPADD(SECOND, -@utc_cutover_offset_seconds, b.started_at))
   OR NOT (s.status <=> b.status)
   OR NOT (s.finished_at <=> b.finished_at);

SELECT
    (SELECT COUNT(*) FROM workflow_run
     WHERE status IN ('queued', 'running', 'waiting')
       AND (started_at > @utc_cutover_as_of OR cancel_requested_at > @utc_cutover_as_of))
    +
    (SELECT COUNT(*) FROM workflow_step_run s
     JOIN workflow_run r ON r.workspace_id = s.workspace_id AND r.id = s.workflow_run_id
     WHERE r.status IN ('queued', 'running', 'waiting')
       AND s.status IN ('queued', 'running', 'waiting')
       AND s.started_at > @utc_cutover_as_of) AS remaining_future_rows;
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
