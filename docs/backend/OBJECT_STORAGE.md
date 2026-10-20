# Backend Object Storage Contract

This document is authoritative for Connex-managed object storage, deletion/reconciliation, and legacy-upload migration behavior.

## Scope

Attachments, contact pictures, company logos, user profile pictures, and business-card source images are stored through the backend `ObjectStorage` abstraction. Never write user uploads into the frontend image/public directories.

Managed object URLs are opaque authenticated backend endpoints, not public filesystem paths.

## Providers and durable identity

The default local filesystem provider stores under `${user.home}/.connex/object-storage`. The deployment bundle mounts its durable object volume under `/var/lib/connex/objects`. S3-compatible storage uses the deployment settings documented in `docs/DEPLOYMENT.md`.

The first configured backend storage identity is immutable control-plane state. Startup inserts-or-verifies the provider identity plus normalized provider addressing (filesystem root or S3 coordinates) and aborts on mismatch before reconciliation/readiness. Do not silently point an existing installation at a different storage backend/root.

## Filesystem admission and reconciliation

- Filesystem writes reserve their declared size against `CONNEX_OBJECT_STORAGE_FILESYSTEM_MIN_FREE_BYTES`; readiness fails when the configured free-space floor cannot be preserved.
- Temporary writes use the established `.connex-object-*.tmp` convention.
- Startup/scheduled reconciliation removes only expired managed temp files and never follows symbolic links.
- Retention/cadence remain configuration properties; reconciliation failures are logged without leaking stored content.

## Replacement and deletion

Metadata replacement/removal does not synchronously delete the old provider object inside the request transaction.

1. Enqueue the old object in the appropriate durable deletion queue in the same database transaction as the metadata change.
2. After commit, the bounded background reconciler performs provider I/O.
3. Retry workers lock the selected id/key and recheck due/current state before deletion so a stale selection cannot delete newly committed bytes.

Tenant-owned keys use the tenant catalog's `object_deletion_queue`; control-plane user-profile keys use `user_object_deletion_queue`.

The reconciler's bounded executor isolates provider latency from shared scheduling and keeps control/tenant retries separated. Overlapping sweeps coalesce rather than queueing indefinitely. Tenant cleanup preserves fair workspace rotation/slicing rather than allowing one workspace to monopolize capacity.

Service tests keep the retry timer delayed and drain deterministically through the real deletion
executor after each test transaction ends. `AbstractServiceTest` selects at most 10 tenant-queue
rows from the shared `default` workspace under its resolved route and at most 10 control-plane
user-profile tombstones under an unrouted scope. The user queue is drained even when no default
workspace exists. A row becomes eligible only when its `next_attempt_at`
is at least ten minutes overdue, with that cutoff rechecked under the row lock. Fixtures such as
`ManagedObjectRollbackIntegrationTest` are therefore never raced within their tombstone delay
plus this margin; tests stalled beyond that interval are accepted as out of scope. A later drain
completes a two-pass tombstone after its rescheduled backoff and the same margin.

The hook catches failures per task and continues, leaving failed rows for a later drain so another
test's quota-lock contention cannot fail an unrelated teardown. Ten tasks per queue bound each
call's lock exposure; drains per run far outnumber leaked rows, keeping growth bounded as tasks recover.
Direct regression-test calls use an explicit cutoff and propagate failures. Tenant cases drive
both passes in a test-owned workspace committed before the test transaction begins, preserving
routing visibility and isolation from other forks. User cases use future-dated rows and restrict
their future-cutoff selection to the fixture's own row while retaining real transactional deletion
and storage. This avoids claiming other fixtures in the global user queue. Fixture cleanup never
sleeps or sweeps other tenant workspaces.

## Tombstones and ambiguous writes

Before provider I/O that participates in replacement/cleanup, preserve the established delayed two-pass cleanup tombstone protocol:

- Persist the tombstone in an isolated transaction.
- Lock/revalidate the exact id/key in the metadata transaction before quota/provider work.
- Cancel only the exact locked identity after the provider confirms the write.
- Retry/deletion passes revalidate exact due state before destructive provider calls.

For S3, tombstone and inter-pass delays must exceed the bounded sequential versioning-preflight plus object-operation timeout envelope. Do not replace the protocol with an immediate/single delete that can race a late or ambiguous provider write.

## Lock order

Storage operations participate in wider backend lock-order contracts. Read `docs/backend/LOCKING.md` before changing transactional storage behavior.

Preserve deletion-queue → quota → audit ordering. Business-card persistence stores binary bytes at the documented point before company/person/audit writes. Profile-image replacement holds the user-row lock before shared backlog admission and object write.

## Legacy public-upload migration

Legacy public-upload migration is an explicit non-web maintenance command, never normal server startup behavior.

Preserve:

- control-plane workspace enumeration and tenant routing through `TenantWorkScope.inWorkspace`;
- deterministic replay-safe object tokens;
- read-after-write integrity verification;
- compare-and-set metadata rewrite;
- source-file retention;
- dry-run quota projection;
- explicit apply confirmation.

Maintenance mode defaults off and must require the centralized exact maintenance/application-type posture. Schedulers, async execution, bootstrap, and unrelated secret-rewrap runners remain absent from the migration application context.

The operator procedure lives in `docs/DEPLOYMENT.md`.

## Normal startup guard

Normal web startup performs metadata-only fail-closed detection of retired public URL prefixes across control and routed tenant catalogs. It does not read/migrate legacy bytes. Remaining legacy references or an unservable catalog abort startup.

Deployment readiness is published only after application runners complete. Deployment sequencing must not destroy old writable-layer media before this guard succeeds; follow `docs/DEPLOYMENT.md`.

## Review checklist

For object-storage changes:

- User bytes remain backend-managed and authenticated, never frontend-public.
- Provider identity cannot drift silently across restart/deploy.
- Filesystem quota/free-space admission remains fail-closed.
- Temp cleanup cannot follow symlinks or delete unrelated files.
- Metadata change and deletion-queue enqueue are atomic.
- Provider deletion occurs after commit and stale workers revalidate exact identity.
- Tombstone/two-pass behavior still covers process exit and ambiguous provider failure.
- Lock order matches `docs/backend/LOCKING.md`.
- Legacy migration remains explicit non-web maintenance mode.
- Relevant storage, lifecycle, deployment, and failure-recovery tests pass.

## Attachment malware decisions

Managed attachment downloads and tenant exports require a fresh persisted CLEAN verdict across all
same-workspace references to the object URL when scanning is enabled. The unconfigured dev/eval
exception for synthetic disabled proofs is confined to disabled mode and documented in
`docs/MALWARE_SCANNING.md`. The retained-object scan worker has a separate bounded
internal read path for denied bytes; it never returns that stream to a controller. Legacy migration
requires a CLEAN proof before its deterministic write. Lifecycle states, administrative operations,
and rollback restrictions are specified in `docs/MALWARE_SCANNING.md`.

## Assistant erasure obligations (V237)

Before removing an affected chat reference, ordered cleanup locks all same-workspace URL references
and the exact attachment. A valid independently retained CRM reference records a retained
disposition with its exact id. Otherwise deletion enqueue and an operation-bound pending obligation
commit together. The exact queue executor records deleted only with final successful deletion,
quota settlement and queue removal. Provider failure, rollback, canceled/missing queue rows and
ambiguous completion remain pending. A crash after provider deletion retries safely.

Quarantine administration defers chat-owned deletion to privacy cleanup so it cannot discard the
association before discovery. Actual chat-byte delivery checks original organization authority at
each output write. Export filters row/object candidates and independently guards live output.
