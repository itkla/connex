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
