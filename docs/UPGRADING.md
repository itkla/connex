# Upgrade & version policy

How Connex versions releases and how deployments — especially customer-operated on-prem, which
upgrades on its own cadence — move between them safely (issue #500, epic #502; delivers the
migration-discipline part of #87 §9 / #102).

> The specific support window below is a **proposed** policy; confirm it before it is published to
> customers.

## AI identifier lookup keys (tenant V230)

V230 adds four nullable derived columns to each of person, company and deal. Each ALTER explicitly
requires `ALGORITHM=INSTANT` and fails instead of falling back to a core-table rebuild. No index,
generated-column or trigger DDL is added. Canonical key format 3 caps each alias at 512 Unicode code
points and detects malformed UTF-16 before canonicalization. Oversized or malformed source names
store an explicit unkeyed marker and retain legacy lookup. The version bump invalidates earlier
bundles, including keys that could differ from the UTF-8-persisted name after surrogate replacement.
DDL still needs metadata locks; deploy outside long-running transactions.

Flyway only adds columns. `AiIdentifierKeyBackfillRunner` is a scheduled background worker on every
ordinary `dev`, `test`, SaaS, silo and on-prem backend, regardless of the AI feature switch. It never
blocks startup or readiness. Its fixed delay defaults to 30 seconds with a 30-second initial delay
(`connex.ai.identifier-backfill.delay-ms` / `initial-delay-ms`). Each invocation processes at most
four sorted 100-row owned-id windows. The owning-workspace/id index bounds each window before
freshness and processing checks, and every examined row advances the cursor. It keyset-pages
workspace discovery through the control plane and pins each placement on its dedicated
single-thread scheduler before starting a transaction. Each transaction has a ten-second deadline
and each CAS a five-second query timeout; contention rolls back the page for a later traversal
without blocking unrelated scheduled maintenance or changing pooled connection session settings.
Maintenance modes other than `off` (including `seeder`) do not instantiate it. In single-database
mode its null catalog is the default database; catalog-per-placement mode reconciles each active placement. Tests keep the bean
and delay automatic ticks for one hour so explicit sweeps do not race other fixtures.

The sweep is restartable and safe with multiple replicas: byte-exact compare-and-set skips changed,
deleted, restricted or already-current rows, and preserves `updated_at`. Workspace data failures
leave that workspace on compatibility lookup and log its id and exception class; unservable or
changed placements fail closed and retry on a later traversal. Per-page logs report
scanned/written/skipped and observed pending rows; workspace completion totals accumulate those
bounded observations. No page runs a full pending count. These totals are not point-in-time counts
under concurrent writes; the optional exact `pendingCount` diagnostic is a separate full scan.
In-memory cursors resume between invocations and wrap after traversal; a restart safely begins
from zero. During a rolling deployment, old binaries can create pending rows or rename current ones. After draining them, wait for reconciliation
and confirm pending=0 in each servable workspace. Archived/restricted rows and processed unkeyed
rows are excluded from that count. Restored rows retain fallback until reconciled. Unkeyed names
keep fallback; subsequent name changes can supply a keyed bundle.

CSV name-writing inserts and volume-seeder name flushes use ordered chunks of at most 100 rows and
a conservative 1 MiB estimated payload. Chunking keeps the enclosing transaction and bean/id order;
it does not split import atomicity. Bound lookup/CAS text is converted to UTF-8 before binary
comparison, including on a Cp1252 JDBC connection. No connection-encoding setting is weakened.

Current bundles use the Java canonical prefilter; stale/missing bundles retain legacy lookup with
its possible corpus-sized pagination. No coverage or admission cap changes. The existing rollback
floor remains `tenant/V228`: older binaries ignore the nullable additions and safely retain their
own identifier lookup. Changing canonical forms or eligibility requires bumping the key format
version, keeping fallback and rerunning reconciliation. See [AI_SECURITY.md](backend/AI_SECURITY.md#ask-connex-record-identifier-prefilter-tenant-v230)
and [LOCKING.md](backend/LOCKING.md#ai-identifier-key-reconciliation).

## AI exposure-gated output upgrade (#2056)

The all-kind demasking change bumps generated-output cache hashes to `v4-dispatched-exposure`.
Older deal briefs, risk/introduction rationales, report narratives and composer outputs are invalid
and regenerate lazily when requested. There is no stale fallback and no eager regeneration or data
backfill. Normal admission and quotas still apply, so these panels can temporarily be unavailable
or rate limited until fresh generation succeeds. Deterministic CRM/report data remains available.

Plan for a temporary rise in provider demand and context-assembly load after deployment. Existing
rows bypass the deal brief's early admission precheck: even when quota is exhausted, requests with
old rows load CRM context, mask and hash it before the generation admission refuses. Concurrent
requests for the same identity and hash join the existing single flight within one JVM, and
non-refresh leaders recheck for a newly published output before generating. Multiple replicas retain
the existing replica-local coordination limitation. Monitor ordinary quota/admission and database
capacity rather than bypassing those gates to warm caches.

Persisted transcript answers and summaries are already demasked and remain displayable. Replay
remasks them into fresh tokens; persisted provenance never grants exposure. Legacy masked artifacts
that need demasking without dispatch evidence from the current run fail closed and disposable
artifacts must regenerate. There is no persisted token map or exposure backfill. The detailed
contract is in [AI_SECURITY.md](backend/AI_SECURITY.md#exposure-enforcement-across-callers-and-upgrades).

## Versioning

- **SemVer**, one product version per release, stamped as a **version-locked backend + frontend +
  OCR + ClamAV image set** (see [RELEASE.md](RELEASE.md)). The components are never upgraded
  independently, even when a low-resource deployment explicitly opts out of OCR; the running
  backend version is at `GET /api/version`.
- On-prem and air-gapped installs verify the tag-bound release manifest and pin its exact image
  digests. Registry version and SHA tags are convenience pointers and are never the deployment
  integrity boundary.

## Supported upgrade paths (proposed)

- **Sequential minor upgrades within a major are supported** (e.g. `1.3 → 1.4 → 1.5`). Skipping
  intermediate minors is best-effort; skipping across a **major** requires stepping through the last
  minor of the prior major first.
- Every upgrade is **forward-only**. Downgrades are not supported (migrations are not reversed);
  recover by restoring the pre-upgrade backup taken with the shipped backup tooling
  (`deploy/backup/`, see [BACKUP_RESTORE.md](BACKUP_RESTORE.md)).

## Migration discipline

- **Shipped migrations are immutable.** A migration that has been released is never edited, renamed,
  or deleted — doing so breaks Flyway checksum validation on any database that already applied it.
  Change is always a **new** migration. This is enforced in CI (`ci.yml` → *Migrations — forward-only
  guard*): pull requests and direct `main` pushes may only **add** files under
  `backend/src/main/resources/db/migration/`.
- **Expand-contract with delayed drops.** The destructive half of a change lags the additive half by
  several releases, so a version-skipping customer never meets a `DROP` before their data has moved
  off the old shape:
  - **Add** the new column/table (nullable/backfilled) → deploy.
  - **Backfill** and cut over reads/writes → deploy.
  - **Drop** the old column/table **several releases later**, once no supported version still uses it.
  - Renames follow the same shape: add-new → backfill → drop-old-later, never an in-place rename.
- Migrations are **non-destructive and defensive** wherever possible, and are tested against
  realistic data volumes before release, not just empty schemas. The
  [deterministic volume seeder](VOLUME_SEEDER.md#migration-timing-evidence) logs fresh-schema
  per-migration and total Flyway timing, then establishes the populated fixture used for separate
  upgrade-drill measurements.
- The control/tenant **plane split** (`db/migration/{control,tenant}`) and version monotonicity are
  additionally enforced by the migration arch tests.
- **Declare the staging rollback floor.** Raise
  [`deploy/staging/rollback-floor`](../deploy/staging/rollback-floor) in the **same PR** as any
  migration that earlier binaries cannot survive. Keep the highest such migration as one path
  relative to `backend/src/main/resources/db/migration/`, such as
  `tenant/V231__ai_proposal_review_expiry_cutover.sql`; blank lines and `#` comment lines
  are allowed. Staging records `backend_starting` before copying the target JAR into the live path.
  The floor has one exception: a matching transaction still in `prepared` or `frontend_stopped`,
  with **both** the live JAR and serving backend identified as the prior release, proves the target
  backend never started, so restoring the prior pair is safe. Otherwise, before restoring artifacts,
  staging reads the pinned target commit's floor and requires that migration in both the target and
  previous backend JARs. An unreadable or invalid declaration, or a missing migration in either JAR,
  refuses rollback and durably records `forward_only` before attempting recovery. That state never
  retries the previous release: the same run and later timer runs activate and health-gate the target
  backend, then activate its frontend, run smoke, and commit. Until commit, failures retain
  `forward_only` and emit an `ALERT` with `gate=rollback_floor` and `rollback_state=refused_floor`.
  An unhealthy target leaves its frontend stopped for operator forward recovery; the previous
  frontend is never started alongside it. Recovery stops the frontend before credential checks,
  and reboot startup validates the transaction: once the target backend may have run, only its
  frontend can start, while malformed or unreadable transactions block all startup.
  Without a frontend marker, legacy startup requires no transaction or a valid `prepared` or
  `frontend_stopped` phase; even `rollback_live` requires the verified prior release's marker.
  Authorized rollback records `rollback_starting` to block startup until the previous backend is
  healthy, then `rollback_live` to allow its frontend, retaining the transaction until both are
  restored. Older target commits with no floor file retain the
  previous rollback behavior. This guard does not reverse migrations or replace the coordinated
  restart requirements below.

### V232 mission privacy and message-sequence cutover

V232 requires a coordinated drain of every old backend, scheduled worker, and message writer before migration. Keep message writes stopped through backfill and deploy only binaries that use the session's durable `message_seq_high_water` allocator for every message insertion. Deleting messages never resets that counter or the immutable first-mission taint boundary.

The rollback floor is `tenant/V232__ai_agent_runs.sql`. Once this backend may have run, a pre-V232 binary cannot safely read or write enrolled chat holdings, even with missions disabled. Use forward recovery. Returning below this floor requires an explicit drain, terminal settlement, privacy purge and residual verification; disabling the flag alone is insufficient.

Keep missions disabled until routed initialization has verified every workspace barrier, recovered legacy null-token turns and unowned mission attempts, and confirmed incomplete privacy work remains blocked. Missing barriers with mission evidence require repair, never generation-1 recreation. Complete the [mission acceptance gate](backend/AI_MISSIONS.md) and record synthetic staging evidence on #2101 before enabling exact provider declarations. Slice 1 requires a drained single serving JVM; mixed-version or multi-JVM operation is not supported by the local dispatch fence.

### Malware-scanning cutover — start the sidecar before the backend

The release that introduces upload malware scanning (issue #1240) adds a required ClamAV sidecar.
There is **no schema change**, but the backend hard-depends on the scanner: with a deployment
profile configured it refuses to start unless scanning is enabled and configured, and while the
sidecar is unavailable every upload returns 503. Order the rollout:

1. Add to `deploy/.env`: `CONNEX_CLAMAV_DIGEST` from the release manifest, a freshly generated
   32+ character `CONNEX_CLAMAV_SERVICE_TOKEN`, `CONNEX_MALWARE_SCANNING_ENABLED=true`,
   `CONNEX_CLAMAV_BASE_URL=http://clamav:8091`,
   `CONNEX_CLAMAV_PLAIN_HTTP_PRIVATE_HOST=clamav`, and `COMPOSE_PROFILES=ocr,clamav`.
2. Confirm the host has **3 GiB of memory free for the sidecar plus a 512 MiB scan tmpfs**. The
   sidecar refuses to start on an undersized scan mount, and an undersized memory limit presents
   as an OOM-killed container that looks like a scanner outage.
3. Start and wait for the sidecar **first**:

   ```bash
   docker compose --profile clamav up -d clamav
   docker compose ps clamav          # wait for healthy; first start loads the signature database
   ```

4. Only then roll the backend. Rolling the backend first makes uploads 503 for the whole window.

Rollback is clean because there is no migration: revert the backend image and the sidecar is simply
unused. See [MALWARE_SCANNING.md](MALWARE_SCANNING.md) for the signature-update procedure, which is
operator-driven and required at least every 30 days.

### V170 one-time-link security cutover

`V170__one_time_link_sessions.sql` is intentionally not rolling-deploy compatible. It backfills
SHA-256 digests for the legacy raw workspace-invite bearers and then drops the raw `token` columns
and their indexes in the same migration. An older backend process still reading or writing those
columns will fail after V170 applies.

Treat the release containing V170 as a coordinated restart: close ingress, stop every old backend
replica, then start the new backend so Flyway can apply V170 with no old binary still serving. Do
not run old and new backend versions concurrently across this migration. The standard Compose
runbook below already quiesces all writers and starts one backend with ingress closed; custom
multi-replica deployments must enforce the same all-replicas-down boundary before migration.

### V231 assistant proposal review expiry

`tenant/V231__ai_proposal_review_expiry_cutover.sql` is an executable compatibility marker (`DO 0`).
It changes no application table, stored timestamp, trust marker, or durable status. Ship it together
with the seventy-two-hour guards on legacy approval, discard and AUTO execution, all delegate
admission checkpoints, read projections, pending discovery and frontend disarming. A marker-only
intermediate binary is not a compatible release.

Drain every incompatible backend before serving the cutover; close ingress during the coordinated
restart. Existing trusted pending proposals whose original `created_at` is at least seventy-two
hours old immediately become non-actionable. Their history remains readable under existing
authorization and retention rules. Completed outcomes and the independent ten-minute undo window
are unchanged. Expiry is not deletion, archival, or a retained-chat privacy repair.

**Rollback floor:** only a release carrying V231 and the complete expiry contract may serve after
this cutover. Older binaries can execute known expired proposal IDs despite the unchanged schema.
Staging automatic rollback and interrupted-deployment recovery therefore refuse a prior JAR lacking
V231 and use the existing forward-recovery procedure. Do not remove the marker or relabel timestamps
to permit rollback. A compatible V231 release remains eligible for ordinary paired rollback.

V232 is reserved for Phase 2 slice 1. This cutover introduces no proposal-set storage or producer.

### V227/V228 campaign unsubscribe cutover

The 2026-10-12 owner decision authorizes build and merge when green; staging's next deployment is
the coordinated restart. This supersedes the earlier merge hold and needs no additional restart
window approval. V227 creates the tenant-plane `campaign_delivery_unsubscribe_token` digest history
and backfills **every delivery, irrespective of status**. On one automatic boot, Flyway applies V227
and V228 together with all writers stopped; there is no manual checkpoint between them. V228 first
reasserts completeness with an `INSERT ... SELECT ... WHERE NOT EXISTS` for each missing digest,
then uses the still-present bearer to replace historical copies case-insensitively. Free-text delivery
errors and event details use `[redacted]`; provider message and event ids use the lowercase digest,
joining events on both workspace and delivery ids. V228 rekeys event ids with `UPDATE IGNORE`,
which skips any row whose digest correlation form would violate the workspace/provider event key.
This covers collisions with existing canonical keys and between multiple legacy rows being rekeyed,
including rows belonging to different deliveries. A second update sets every event key still
containing its delivery's bearer to NULL. One canonical key survives each collision; multiple NULL
keys are permitted, and webhook redeliveries still deduplicate against the surviving canonical key.
V228 also clears any provider message id still containing its owning delivery's bearer after the
single replacement pass, including self-overlap recreated by that replacement.
These updates can run again after a failed history row.

V228 rewrites each delivery's **own** bearer. A different delivery's bearer embedded in a historical
identifier can exist only if a provider echoed foreign bearers before the upgrade; those copies are outside
V228's guarantee. Matching every workspace token against every historical identifier would require
a quadratic scan. Runtime dispatch and webhooks instead use workspace-scoped digest matching.
Runtime correlation rescans replacements for at most four passes and fails closed if a recorded
bearer remains: NULL message/event id storage, or no match on webhook message lookup.
After the upgrade, incoming raw provider ids use the same correlation form before lookup and dedupe,
so historical message ids still correlate. Only 64-hex windows whose lowercased SHA-256 digest is
recorded in the resolved workspace match; unrelated identifiers stay byte-identical. Digest probes
are plain primary-key consistent reads and acquire no row locks.
V228 then drops both token indexes, the generated hash column, and the raw bearer column in one ALTER.
Existing fragment links keep working for their delivery's lifetime, without expiry, through the preserved digests. Historical backups
and previously generated export ZIPs are outside this remediation.

1. Close ingress and stop **every old backend replica and writer**, including scheduler workers.
   Drain bounded provider calls where possible. Do not run old and new replicas concurrently; the
   previous binary reads and writes the removed columns. The coordinated Compose restart boundary
   used for V170 applies here too.
2. Inventory every tenant catalog (pooled and dedicated) and take the normal pre-cutover backup.
   With writers stopped, record each catalog's delivery count, maximum delivery id, and a protected
   inventory of `(workspace_id, id, unsubscribe_token_hash)` for post-cutover comparison. Preserve
   that inventory with the backup; it contains digests, never raw bearers.
3. With writers stopped, run the tenant lineage through V228 for every inventoried catalog other
   than the configured default, using the normal catalog migration tooling. Each run applies V227
   and V228 together. Activate only the new release: its automatic startup applies both migrations
   together in the configured default catalog; it does not enumerate dedicated catalogs. V228
   enforces the backfill before removing its source. Keep ingress closed until verification finishes,
   and keep every old writer stopped. In **each catalog**, verify both token
   columns and indexes are absent and the composite cascading FK is present. Substitute the recorded
   maximum id in the post-cutover query below and require zero missing deliveries. Confirm the
   surviving delivery count within that boundary equals the recorded count, and compare every
   legacy digest and its workspace/delivery ownership with the protected inventory. New deliveries
   legitimately have no digest until dispatch, and later attempts can add digests, so an unbounded
   delivery count or exact total child count is not a completeness check after activation.

   ```sql
   SELECT COUNT(*) AS missing_legacy_deliveries
   FROM campaign_delivery d
   WHERE d.id <= <recorded_max_delivery_id>
     AND NOT EXISTS (
       SELECT 1 FROM campaign_delivery_unsubscribe_token t
       WHERE t.workspace_id = d.workspace_id AND t.delivery_id = d.id
     );
   SELECT COUNT(*) AS surviving_legacy_deliveries
   FROM campaign_delivery
   WHERE id <= <recorded_max_delivery_id>;
   ```

   Verify unsubscribe exchange/preview/confirmation, then reopen writers and ingress. Do not touch
   suppression, consent, or recovery evidence during migration.

**No previous-binary rollback is supported after V228.** Recovery must move forward with the new
binary. Once its backend may have run, staging enforces the declared rollback floor above: after
an activation failure it persists forward-only recovery and retries the target instead of restoring
a backend whose JAR lacks V228. Such a backend cannot run campaign materialization, dispatch, or
unsubscribe lookups against V228's schema.
Operators must not keep a pre-V228 backend serving; close ingress and stop it, then restore service
with a release containing V228 using forward recovery.

A migrated claim in flight has no reconstructable bearer: an explicitly opted-in idempotent
replay keeps its submission key but renders a fresh link. A body-comparing provider rejects it and
the delivery becomes ambiguous, requiring the existing provider/suppression reconciliation check;
a body-insensitive provider returns the original receipt. No automatic replay is enabled for SMTP
or connectors without the explicit deduplication guarantee. Previously emailed links still resolve.

**Partially committed DDL:** keep all writers and ingress stopped, inspect physical schema and Flyway
history independently, and preserve the digest table. MySQL DDL commits independently of Flyway's
history bookkeeping; `repair` alone never undoes it. If V227 created the child but backfill/history
failed while both legacy columns remain, the raw-derived source still exists: drop only that new
child table, repair the failed V227 entry using the normal migration tooling, then rerun the
automatic migration boot. V228 reasserts the backfill and repeats the idempotent diagnostic and event-key cleanup
before its ALTER; require the post-cutover
catalog-wide checks above before reopening service. Do not drop the child once legacy columns
are gone.

If V228's ALTER committed but history recording failed, first verify the preserved child against the
recorded inventory and verify that V227 is recorded successful. Recreate **empty removable columns
and indexes only**, while every writer remains stopped, then repair the failed V228 entry and rerun
V228 with the new binary:

```sql
ALTER TABLE campaign_delivery
  ADD COLUMN unsubscribe_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN unsubscribe_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (LOWER(SHA2(unsubscribe_token, 256))) STORED,
  ADD UNIQUE KEY uq_campaign_delivery_token (unsubscribe_token),
  ADD UNIQUE KEY uq_campaign_delivery_token_hash (unsubscribe_token_hash);
```

These temporary NULL columns reconstruct no bearer and cannot serve an old binary; all lookup
continues from the preserved child. V228 ignores these NULL source hashes while preserving all
existing child rows. Its `NOT EXISTS` backfill also makes a retry safe if the INSERT committed but
the ALTER or history recording failed. If the ALTER did not commit, leave its original columns alone
and repair/rerun the failed V228 entry. If schema/history or the recorded digest inventory do not
match either state, keep the deployment closed and recover from the pre-cutover backup in an isolated
catalog through the new lineage; never guess at missing digests or start the previous binary.

### V185 workspace-invite authorization cutover

`V185__workspace_invite_grant_authorization.sql` adds the protocol marker that lets the new backend
reject pending workspace memberships created before locked grant-ceiling authorization. The new
backend also revalidates the original creator when an email invite or shareable invite link is
redeemed. A backend that predates this cutover ignores the marker and does not perform that creator
check, so it must not serve traffic after V185 applies.

Treat the release containing V185 as a coordinated restart: close ingress, stop every old backend
replica, then start the new backend so Flyway can apply V185 with no old binary still serving. Do
not run old and new backend versions concurrently across this migration. The standard Compose
runbook below already quiesces all writers, and the staging deploy replaces its single backend
process before reopening the frontend; custom multi-replica deployments must enforce the same
all-replicas-down boundary.

### V186 provider-disconnect retention cutover

`V186__provider_disconnect_retention.sql` introduces the internal `revoking` and `disconnected`
states used by the new credential-only disconnect lifecycle and removes the obsolete
`provider_connection.last_sync_at` column. A backend that predates this cutover still reads that
column and treats an ordinary disconnect as authorization to erase captured provider data from every
workspace. If an old backend serves a disconnect after V186 applies, the new backend cannot
distinguish that legacy request from account-deletion cleanup and will finish the destructive flow.

Treat the release containing V186 as a coordinated restart: close ingress, stop every old backend
replica, then start the new backend so Flyway can apply V186 with no old binary still serving. Do
not run old and new backend versions concurrently across this migration. The standard Compose
runbook below already quiesces all writers, and the staging deploy stops its single backend process
before the replacement applies migrations; custom multi-replica deployments must enforce the same
all-replicas-down boundary.

### V191 session index cutover

`V191__reindex_sessions_by_account.sql` re-keys the session index. Sessions were filed in
`SPRING_SESSION.PRINCIPAL_NAME` under the signed-in username; they are now filed under the immutable
account id (`uid:<app_user.id>`). A username is self-service editable, so a session that outlived a
rename stayed filed under a name no later lookup could reconstruct, and password reset and MFA
recovery silently revoked nothing for that account. Spring Session rewrites that column on every
session save, so a backend that predates this cutover re-files any session it serves back under the
username.

Treat the release containing V191 as a coordinated restart: close ingress, stop every old backend
replica, then start the new backend so Flyway can apply V191 with no old binary still serving. Do
not run old and new backend versions concurrently across this migration — a session last touched by
an old replica is filed under a username the new replica does not look up, which is the defect this
release closes. The standard Compose runbook below already quiesces all writers; custom
multi-replica deployments must enforce the same all-replicas-down boundary.

**This cutover has a user-visible effect the earlier ones did not.** The migration deletes every
authenticated session row, because a historical username cannot be mapped back to an account — no
username history is kept. Every signed-in user is signed out once and must sign in again, and open
step-up windows end with their sessions.

Anonymous sessions are preserved, so an in-flight **password reset** or **email verification** —
both of which are completed without signing in — survives the cutover.

**In-flight workspace invitations and email changes do not, and cannot be resumed.** Both pin their
one-time link to the session that first opened it, and both are completed from a signed-in session,
so the lineage lives in a row this migration deletes. The pin is written once and never rewritten
(`exchange_owner_hash = COALESCE(exchange_owner_hash, …)`), so re-opening the emailed link after the
cutover fails rather than re-binding to the new session.

Before upgrading, check for invitations that have been opened but not yet accepted:

```sql
SELECT id, workspace_id, email FROM workspace_invite
WHERE status = 'pending' AND exchanged_at IS NOT NULL AND expires_at > UTC_TIMESTAMP();
```

Re-issue those invitations after the upgrade, and ask anyone mid-way through an email change to
start it again. Both are administrator actions; neither recipient can recover the link themselves.

### V192 session epoch cutover

`V192__session_epoch.sql` adds `app_user.session_epoch` and makes session revocation fail closed.

Revocation was enumerate-and-expire, which fails open: a login whose session row is written after a
revocation has already enumerated is never seen and stays live. The session write happens after the
request completes and outside every application transaction, so no lock can prevent it. An attacker
holding a password could therefore sign in as the owner reset it and keep the session.

A login now records the epoch it authenticated against, revocation advances the account's epoch, and
every request compares the two — so a session established during that race is refused on its next
request instead of surviving silently.

Treat the release containing V192 as a coordinated restart, for the same reason as V191: an old
replica neither stamps nor checks, so a session it serves is invisible to the new behaviour. Close
ingress, stop every old backend replica, then start the new backend.

**No additional forced logout beyond V191's.** Sessions carrying no epoch stamp are refused rather
than grandfathered, and V192 repeats V191's sweep so none remain to refuse. Any database upgrading
across both migrations has already run V191's sweep; the repeat covers only databases sitting at
exactly V191 — staging and developer clones. Anonymous sessions are spared, as in V191.

### V203/V204 emailed-link fragment cutover (document acceptance, campaign unsubscribe)

`V203__campaign_delivery_unsubscribe_token_hash.sql` and
`V204__one_time_link_flow_routing_workspace.sql` are both expand-only and rolling-deploy safe: an old
binary neither reads nor writes `campaign_delivery.unsubscribe_token_hash` (a STORED generated column)
or `one_time_link_flow.routing_workspace_id`.

The **link contract** is what breaks. Emailed document-acceptance links change from
`/document-acceptance/{token}` to `/document-acceptance#token={token}`, and emailed campaign
unsubscribe links change from `/api/delivery/unsubscribe/{token}` to `/unsubscribe#token={token}`.
The browser exchanges the fragment bearer once at `POST /api/document-acceptance/exchange` or
`POST /api/delivery/unsubscribe/exchange` for a purpose-bound `HttpOnly` grant cookie, and every
other endpoint reads only that cookie. Following the V170 precedent there is **no compatibility
shim**: the old `/document-acceptance/{token}` page and the old `/api/delivery/unsubscribe/{token}`
API answer 404 with no lookup and no state change.

Operator actions after the target deployment is healthy:

- **Re-send outstanding document deliveries.** A document-acceptance token stays valid until its
  delivery's `expiresAt`, which is nullable and may never expire, so the outstanding population can be
  arbitrarily old. Use the existing per-recipient resend, which regenerates the token and the email.
- **Campaign unsubscribe links in already-delivered mail stop working.** They never expire and are
  legally load-bearing, so honour any opt-out request that arrives through support and re-send the
  affected campaigns so recipients get a working link. Suppression state itself is unaffected.
- **Update the Cloudflare skip and rate-limit expressions** per
  [EDGE_DEFENCE.md](EDGE_DEFENCE.md): the HTML routes `/document-acceptance` and `/unsubscribe` no
  longer carry a credential, and the API prefixes to exclude are `/api/document-acceptance` and
  `/api/delivery/unsubscribe` with no trailing-slash requirement. **Keep the retired
  `/document-acceptance/` frontend prefix in the no-logging skip rule and in
  `CF-CONFIG-01-COMPATIBILITY`** until the re-send above is complete. Already-emailed
  `/document-acceptance/{token}` links still reach the edge, and their bearers stay redeemable at
  `POST /api/document-acceptance/exchange`, so dropping the prefix early would write live bearers
  into edge logs even though the path itself now 404s.
- **Reverse proxies** must route the bare `/document-acceptance` path; the bundled Caddyfile matcher
  covers both the bare path and the retired subtree.

Both API prefixes are now CSRF-protected because the grant cookie is the authority. Any custom client
calling them must bootstrap `GET /api/auth/csrf` and echo the CSRF header on every mutation. The final
requests — `POST /api/document-acceptance/accept`, `POST /api/document-acceptance/decline` and
`POST /api/delivery/unsubscribe` — also carry a JSON body echoing the `flowId` the preview returned; a
body whose `flowId` does not name the grant the browser currently holds is refused without any state change.

### V212 AI budget reservation cutover

`V212__ai_budget_reservation_state.sql` introduces durable `reserved`, `dispatched`, and `settled`
states. Older backends delete expired dispatched reservations without charging them and count
retained settled rows as reserved capacity. The release is therefore **not rolling-deploy safe**.

Treat the release containing V212 as a coordinated restart: close ingress, stop every old backend
replica and AI worker, apply the migration, then start only the new version. Resume AI admission
only after every instance has been upgraded. Existing reservations are conservatively backfilled
as `dispatched` because the previous schema has no reliable dispatch evidence.

Rollback requires quiescing AI work and reconciling dispatched and settled reservations with
`organization_ai_budget_usage` before restoring older code. Reverting the application alone is not
budget-safe; follow the full backup/restore policy below if reverting the database as well.

The new sweeper charges expired dispatched work and releases expired pre-dispatch work in batches
of 100, up to 10,000 reservations per run. Settled tombstones are purged in separate batches of 100
(up to 10,000 per run) seven days after the later of lease expiry and settlement. Late settlement
of a purged id remains a no-op. Provider deadlines are capped at one hour and leases include a one-minute settlement margin.
The daily usage breakdown labels ledger consumption absent from successful-call audits as
`Conservative / unattributed charges`; this can also include successful settlements awaiting audit.

### V224/V225 privileged-MFA cutover

`V224__privileged_coverage_grandfathered_source.sql` admits per-organization
`GRANDFATHERED` coverage. `V225__privileged_mfa_cutover.sql` implements owner decision 1 (2026-09-27, trust the passkeys of
accounts privileged at cutover) as scoped by decision 2 (2026-10-08, per active organization where
they are privileged). In one transaction it recomputes every `org_member.founder` flag to
`org_role = 'owner'`, deletes coverage outside that population regardless of source, inserts
missing population pairs as `GRANDFATHERED` without replacing provenance or grant references,
and revokes every open approval code without an account revoker. Pending workspace memberships,
inert role overlays, and workspaces or organizations undergoing teardown confer no coverage.
Account-wide `BREAK_GLASS` assurance is untouched. The durable cutover marker is the coverage's
source/time plus Flyway history; startup posture records `grandfatheredCoverageCount`. A SQL
migration cannot append the Java-computed audit integrity chain.

Treat this release as a **coordinated restart**, as for V185: close ingress, stop every old backend
replica and other writers, then start the new backend and let Flyway finish before reopening.
Do not serve an old binary while founder flags and coverage are reset. Grantor vouching applies
with `attestation-enforced` either false or true in the V225 release, which shipped enforcement
with a `false` default. The [switch-on release](#privileged-mfa-attestation-switch-on-1534) changes
that default. No second approval-code revocation is needed at switch-on. Before enabling enforcement,
drain `attestationRequiredCount` for accounts promoted or enrolled after the cutover.

**Rollback floor:** after V225, no backend older than this release may serve. Roll back only to a
release that carries V225 and its switch-independent grantor checks. Coverage is readable by older
code, but its dark-period issuance/redemption can manufacture trust and its role writes can leave
stale founder flags. Application compatibility alone is insufficient. Restoring the database as
well uses the full backup/restore policy below.

**Failure recovery:** V224's drop/re-add CHECK and comment change are one atomic, re-runnable DDL.
If a process dies before its Flyway history write, the next start can execute it again without
repair. V225 contains only DML: process death rolls its transaction back; if no history row was
written, restart to retry. A caught SQL failure on MySQL leaves a failed Flyway row: keep ingress
closed, inspect the failed statement and confirm rollback, fix the cause, run `flyway repair`
using the shipped locations and database configuration, then restart. Do not remove successful
history or reverse either migration. If death happened after commit, inspect history and rows;
a completed version is not re-applied, and repeating V225 after a rolled-back attempt is safe.

**Read-only preflight.** Run against the control catalog before sizing the window. These are exact
counts, including all redeemed/revoked grant history, rather than estimated `TABLE_ROWS`. Run on a
representative snapshot if the scans would disrupt a serving database. The population predicate
is V225's pinned three branches; retain it when interpreting this release's workload.

```sql
WITH privileged AS (
    SELECT om.user_id, o.id AS org_id
    FROM org_member om
    JOIN organization o ON o.id = om.org_id AND o.lifecycle_state = 'active'
    UNION ALL
    SELECT wm.user_id, o.id AS org_id
    FROM workspace_member wm
    JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
    JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
    WHERE wm.status = 'active'
      AND wm.role_id IS NULL
      AND wm.role IN ('admin', 'owner')
    UNION ALL
    SELECT wm.user_id, o.id AS org_id
    FROM workspace_member wm
    JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
    JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
    JOIN workspace_role_permission wrp ON wrp.workspace_role_id = wm.role_id
    WHERE wm.status = 'active'
      AND wrp.permission IN (
        'API_CREDENTIAL_MANAGE', 'AUDIT_READ', 'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND', 'CONSENT_MANAGE',
        'CUSTOM_FIELD_MANAGE', 'DOCUMENT_MANAGE', 'MEMBER_MANAGE', 'PIPELINE_MANAGE',
        'PRODUCT_MANAGE', 'ROLE_MANAGE', 'RULE_MANAGE', 'SEQUENCE_MANAGE',
        'SHARE_MANAGE', 'TAG_MANAGE', 'TEAM_MANAGE', 'WORKSPACE_SETTINGS'
      )
), population_passkeys AS (
    SELECT DISTINCT c.id AS credential_row_id, p.org_id
    FROM webauthn_credential c
    JOIN webauthn_user_entity e ON e.id = c.user_entity_user_id
    JOIN privileged p ON p.user_id = e.user_id
)
SELECT 'organization' AS input_table, (SELECT COUNT(*) FROM organization) AS total_rows,
       'scan' AS step, NULL AS rows_to_change
UNION ALL SELECT 'workspace', COUNT(*), 'scan', NULL FROM workspace
UNION ALL SELECT 'org_member', COUNT(*), 'recompute founders',
    COALESCE(SUM(founder <> (org_role = 'owner')), 0) FROM org_member
UNION ALL SELECT 'workspace_member', COUNT(*), 'scan', NULL FROM workspace_member
UNION ALL SELECT 'workspace_role_permission', COUNT(*), 'scan', NULL FROM workspace_role_permission
UNION ALL SELECT 'webauthn_user_entity', COUNT(*), 'scan', NULL FROM webauthn_user_entity
UNION ALL SELECT 'webauthn_credential', COUNT(*), 'population passkey pairs',
    (SELECT COUNT(*) FROM population_passkeys) FROM webauthn_credential
UNION ALL SELECT 'privileged_credential_attestation', COUNT(*), 'delete outside population',
    (SELECT COUNT(*) FROM privileged_credential_attestation a
     WHERE NOT EXISTS (SELECT 1 FROM population_passkeys p
                       WHERE p.credential_row_id = a.credential_row_id AND p.org_id = a.org_id))
    FROM privileged_credential_attestation
UNION ALL SELECT 'privileged_credential_attestation',
    (SELECT COUNT(*) FROM privileged_credential_attestation), 'insert missing coverage', COUNT(*)
    FROM population_passkeys p
    WHERE NOT EXISTS (SELECT 1 FROM privileged_credential_attestation a
                      WHERE a.credential_row_id = p.credential_row_id AND a.org_id = p.org_id)
UNION ALL SELECT 'privileged_mfa_attestation_grant', COUNT(*), 'revoke open codes',
    COALESCE(SUM(redeemed_at IS NULL AND revoked_at IS NULL), 0) FROM privileged_mfa_attestation_grant;
```

List active workspace-only owners with no passkey, per organization. Owners whose custom overlay
removes privileged permissions are excluded. Without a vouching co-owner, their approval path is
**operator break-glass recovery**: the recovery session enrolls a replacement passkey carrying
`BREAK_GLASS`, satisfying standing everywhere. V225 grants them no organization founder flag.

```sql
SELECT DISTINCT wm.user_id, w.org_id, wm.workspace_id
FROM workspace_member wm
JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
WHERE wm.status = 'active' AND wm.role = 'owner'
  AND (wm.role_id IS NULL OR EXISTS (
      SELECT 1 FROM workspace_role_permission wrp WHERE wrp.workspace_role_id = wm.role_id
        AND wrp.permission IN (
          'API_CREDENTIAL_MANAGE', 'AUDIT_READ', 'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND', 'CONSENT_MANAGE',
          'CUSTOM_FIELD_MANAGE', 'DOCUMENT_MANAGE', 'MEMBER_MANAGE', 'PIPELINE_MANAGE',
          'PRODUCT_MANAGE', 'ROLE_MANAGE', 'RULE_MANAGE', 'SEQUENCE_MANAGE',
          'SHARE_MANAGE', 'TAG_MANAGE', 'TEAM_MANAGE', 'WORKSPACE_SETTINGS')))
  AND NOT EXISTS (SELECT 1 FROM org_member om WHERE om.user_id = wm.user_id AND om.org_id = w.org_id)
  AND NOT EXISTS (
      SELECT 1 FROM webauthn_user_entity e
      JOIN webauthn_credential c ON c.user_entity_user_id = e.id WHERE e.user_id = wm.user_id)
ORDER BY wm.user_id, w.org_id, wm.workspace_id;
```

**Rehearsal threshold and startup deadlines.** When any preflight input-table count or changed-row
count exceeds the corresponding rehearsed 10% population below, rehearse at that scale on
production-class storage before closing ingress. Include all grant history, even when almost
every grant is already redeemed or revoked.
Do not extrapolate only from the changed rows: V225 also scans membership, credential and history
inputs. The supplemental empty-coverage run measures the insertion step separately.

**Measured 10% rehearsal (2026-10-13 UTC).** These measurements used a development host whose disk
was saturated by other work, MySQL 8.4.10, Flyway 12.4.0, a 128 MiB InnoDB buffer pool, and enabled
ROW binary logging. The full amended population could not be generated on this host: its earlier
million-row membership and historical-grant loads took 18,568.883 and 19,399.063 seconds,
respectively, about 54 and 52 rows/s. The orchestrator stopped that run during its next load;
it produced no full-scale migration measurements.

The 10% rehearsal uses real `SHOW CREATE TABLE` definitions, retaining CHECK constraints, indexes,
and foreign keys between its copied tables. Its synthetic population spans the organization,
built-in workspace and custom-role privilege branches; custom roles each hold two privileged
permissions. The additional ordinary workspace memberships and closed grant history remain
present during every timed cutover pass.
Generation was set-based, with 50,000-row committed batches for the large tables and one
whole-table batch for smaller tables. Loading 390,100 application rows took 342.273 seconds,
or **1,139.733 rows/s**, including numbers-table setup and statistics refresh. The two ordinary
membership batches achieved 4,377.592 and 3,988.166 rows/s; the four historical-grant batches
achieved 2,429.539, 1,744.275, 2,672.754 and 1,112.570 rows/s. Generation time is separate from
the migration outage. The scratch catalog was dropped after all ten count assertions passed.

| Input or changed population | Rows |
| --- | ---: |
| Organizations / workspaces | 1,000 each |
| Users / workspace roles | 10,100 / 1,000 |
| Organization memberships | 4,000 |
| Privileged workspace memberships | 6,000 |
| Ordinary workspace memberships | 100,000 |
| Total workspace memberships | 106,000 |
| Workspace-role permissions | 2,000 |
| WebAuthn user entities / credentials | 10,000 / 20,000 |
| Existing coverage / outside the population | 30,000 / 10,000 |
| Approval grants / open grants | 205,000 / 5,000 |
| Redeemed or revoked grant history | 200,000 |
| Founder flags recomputed | 2,000 |
| Eligible passkey/organization pairs | 20,000 |
| Missing coverage inserted in the main / supplemental pass | 0 / 20,000 |

| Timed operation | Measured at 10% | Full amended population: linear estimate (10×) |
| --- | ---: | ---: |
| V224 ALTER, Flyway execution time | 22.968 s | 229.680 s |
| V225 transaction, Flyway execution time | 9.932 s | 99.320 s |
| V224 / V225 Flyway wall time | 31.565 / 10.247 s | 315.650 / 102.470 s |
| Same four V225 statements before rollback | 8.565 s | 85.650 s |
| ROLLBACK of those writes | 0.258 s | 2.580 s |
| Supplemental empty-coverage transaction, including commit | 5.722 s | 57.220 s |
| V224 ALTER repeated over grandfathered coverage | 25.036 s | 250.360 s |

The rollback pass's four statements took 0.011, 7.987, 0.346 and 0.220 seconds, respectively;
its active transaction reported 17,000 modified rows. Existing coverage remained unchanged by
the insert: JDBC's matched-row count includes its 20,000 no-op duplicates. The supplemental pass
inserted 20,000 genuinely missing `GRANDFATHERED` rows.

**The full-scale numbers are estimates, not measurements or an outage bound.** Simple linear
extrapolation does not account for changing working sets, index sizes, row widths, fixed Flyway
overhead, or competing I/O. The full population still requires a rehearsal on production-class
storage under the threshold rule above.

The following storage snapshots exclude numbers-table helpers and include Flyway history once
created. Data/index lengths are `information_schema.TABLES` sums across the copied input tables;
file lengths are the catalog's `INNODB_TABLESPACES.FILE_SIZE` sum. All values are bytes.

| Snapshot | Data length | Index length | Tablespace file length |
| --- | ---: | ---: | ---: |
| Populated, before V224 | 49,037,312 | 82,542,592 | 180,649,984 |
| After V224 | 48,005,120 | 82,542,592 | 178,683,904 |
| After V225 commit | 47,742,976 | 82,149,376 | 178,683,904 |
| Restored workload, before / after ROLLBACK | 48,791,552 | 82,165,760 | 180,781,056 |
| Before supplemental empty-coverage pass | 46,432,256 | 79,413,248 | 180,781,056 |
| After supplemental empty-coverage pass | 48,005,120 | 81,444,864 | 180,781,056 |

V224's observed catalog file-length delta was −1,966,080 bytes; V225, the rollback pass and the
supplemental pass each added zero file bytes. The supplemental pass reused existing allocation
while data/index lengths grew by 1,572,864 / 2,031,616 bytes. Allocated file-size snapshots tracked
the file lengths above plus 45,056 bytes. Deletion and rollback do not imply files will shrink.

Undo files stayed allocated at 33,554,432 and 50,331,648 bytes (80 MiB total; zero growth).
History-list-length snapshots were 22 → 11 → 14 across V224/V225; the rollback pass was
16 → 18 → 16. The active-undo-size metric returned zero throughout and did not measure generated
undo bytes. Observed binary-log size deltas were +5,422 bytes for V224, +1,464,492 for V225,
+2,255 across the rollback pass, and +1,805,071 for the supplemental pass. Undo and binary-log
counters are server-wide, include concurrent work and bookkeeping/statistics refresh, and cannot
isolate the cutover's undo or binlog volume on this host.

Budget readiness/startup gates for V224 plus V225, normal startup/backfills, and an operational
margin above the measured duration. The linear full-scale migration-only estimate is 329.000
seconds of Flyway execution, or 418.120 seconds using observed wall time, before normal startup
and margin. Both exceed the nominal 300-second window provided by the standard Compose runbook's
`--wait-timeout 300` and the backend healthcheck's 60 retries at 5-second intervals. Raise those
budgets (for example with a backend `start_period` override) using the production-scale
rehearsal and normal startup timings before the window.
The checked-in Flyway configuration sets no statement-duration deadline for this migration; any operator-set JDBC
socket timeout, database/proxy query limit or Flyway process deadline must cover the entire single
transaction. Connection retries and schema-history lock retries do not extend a statement deadline.
Keep ingress closed until `/api/health/ready` succeeds; TCP availability is insufficient.

**Reconciliation if the rollback floor was broken.** Keep approval issuance and redemption closed
on every replica (closing ingress and stopping backends is sufficient). Find the earliest moment
any older binary served, widened for clock uncertainty and millisecond coverage timestamps; that is
`@violation_start`. Reconciliation has no end bound. Coverage written after the older binary stopped
can still descend from window coverage, through inheritance or a grantor's vouch, so everything
attested since the start is re-approved. With all writers stopped, run the following in the control
catalog. Preserve the first query's output for re-approval and incident evidence.

Reconciliation only narrows trust:
- It deletes every `GRANTOR`, `INHERITED` and `FOUNDER` row attested since the start.
- It clears every founder flag. An older binary's role writes can leave a stale flag that no query
  can tell apart, so no founder is re-derived. Owners' new passkeys then need an approval, or
  inherit coverage through a step-up from a covered passkey.
- It keeps `GRANDFATHERED` rows, which only V225 writes, before any violation, and account-wide
  `BREAK_GLASS` assurance.
- Never re-run V225's population statements: they would grandfather accounts privileged now but
  not at the cutover. Never edit or re-run a successful Flyway version either.

```sql
SET @violation_start = '2026-10-01 00:00:00.000';
SELECT DISTINCT e.user_id, a.org_id, a.source, a.attested_at
FROM privileged_credential_attestation a
JOIN webauthn_credential c ON c.id = a.credential_row_id
JOIN webauthn_user_entity e ON e.id = c.user_entity_user_id
WHERE a.source IN ('GRANTOR', 'INHERITED', 'FOUNDER')
  AND a.attested_at >= @violation_start
ORDER BY e.user_id, a.org_id;

-- BEGIN RECONCILIATION
START TRANSACTION;
UPDATE privileged_mfa_attestation_grant
SET revoked_at = UTC_TIMESTAMP(6)
WHERE redeemed_at IS NULL AND revoked_at IS NULL;
DELETE FROM privileged_credential_attestation
WHERE source IN ('GRANTOR', 'INHERITED', 'FOUNDER')
  AND attested_at >= @violation_start;
UPDATE org_member
SET founder = FALSE
WHERE founder = TRUE;
COMMIT;
-- END RECONCILIATION
```

Restart only a release at or above the rollback floor. Have affected accounts re-approve through a
vouching grantor, or use operator break-glass recovery where that route is unavailable, before
reopening the affected approval flows. No earlier binary may serve during reconciliation or afterwards.

### Privileged-MFA attestation switch-on (#1534)

This release makes `CONNEX_PRIVILEGED_MFA_ATTESTATION_ENFORCED=true` the default. Privileged accounts
with passkeys but no single passkey approved in every privileged organization are now confined;
privileged operations also require a step-up bound to a sufficient passkey. There is **no schema
change**, no new grandfathering and no second approval-code revocation.

**Fresh read-only preflight:** immediately before enabling, check **every upgraded control catalog**
(SaaS, silo and on-prem), with membership and passkey changes quiesced until the switch takes effect.
Run the query below against that catalog; it lists the affected account ids, so zero rows means
`attestationRequiredCount = 0`. Its predicate is the expansion of
[`UserMapper.countAttestationRequiredAccounts`](../backend/src/main/resources/mappers/UserMapper.xml),
including `privilegedOrgBranches` and `sufficientPasskey`, used by
`PrivilegedAccountService.attestationRequiredAccountCount`. Keep this query in step with that SQL.
It reads no credential material and makes no changes.

```sql
WITH privileged AS (
    SELECT om.user_id, o.id AS org_id
    FROM org_member om
    JOIN organization o ON o.id = om.org_id AND o.lifecycle_state = 'active'
    UNION ALL
    SELECT wm.user_id, o.id AS org_id
    FROM workspace_member wm
    JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
    JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
    WHERE wm.status = 'active'
      AND wm.role_id IS NULL
      AND wm.role IN ('admin', 'owner')
    UNION ALL
    SELECT wm.user_id, o.id AS org_id
    FROM workspace_member wm
    JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
    JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
    JOIN workspace_role_permission wrp ON wrp.workspace_role_id = wm.role_id
    WHERE wm.status = 'active'
      AND wrp.permission IN (
        'API_CREDENTIAL_MANAGE', 'AUDIT_READ', 'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND', 'CONSENT_MANAGE',
        'CUSTOM_FIELD_MANAGE', 'DOCUMENT_MANAGE', 'MEMBER_MANAGE', 'PIPELINE_MANAGE',
        'PRODUCT_MANAGE', 'ROLE_MANAGE', 'RULE_MANAGE', 'SEQUENCE_MANAGE',
        'SHARE_MANAGE', 'TAG_MANAGE', 'TEAM_MANAGE', 'WORKSPACE_SETTINGS'
      )
)
SELECT u.id AS account_id
FROM app_user u
WHERE u.username != '__connex_system__'
  AND EXISTS (SELECT 1 FROM privileged p WHERE p.user_id = u.id)
  AND EXISTS (
    SELECT 1
    FROM webauthn_user_entity e
    JOIN webauthn_credential c ON c.user_entity_user_id = e.id
    WHERE e.user_id = u.id
  )
  AND NOT EXISTS (
    SELECT 1
    FROM webauthn_user_entity e
    JOIN webauthn_credential c ON c.user_entity_user_id = e.id
    WHERE e.user_id = u.id
      AND (c.privileged_assurance = 'BREAK_GLASS'
        OR NOT EXISTS (
          SELECT 1
          FROM privileged p
          WHERE p.user_id = u.id
            AND NOT EXISTS (
              SELECT 1
              FROM privileged_credential_attestation pca
              WHERE pca.credential_row_id = c.id AND pca.org_id = p.org_id
            )
        ))
  )
ORDER BY u.id;
```

Alternatively, start **this release** with the attributed staging opt-out below immediately before
switching on, and read its `auth.mfa.policy.configured` system event for that catalog. Require a
successful inventory with `attestationRequiredCount = 0`; record `unenrolledPrivilegedCount` as the
separate enrollment workload, already subject to `CONNEX_PRIVILEGED_MFA_ENFORCED`. Inventory is
sampled only at startup: an old event or silence in the WARN log is not a current preflight.
A missing, `"unavailable"` or nonzero attestation count, or a failed query, means **keep the attributed
opt-out until the population is drained and freshly rechecked**. Recheck if memberships or passkeys
change between the inventory and enabling.

**Drain:** have a covered grantor issue an approval code in each organization where the account is
privileged, and redeem every code using the **same passkey**. Coverage split across different
passkeys does not satisfy standing. The workspace route serves workspace-only owners when a covered
co-owner is eligible. Where no eligible grantor exists, use
[operator break-glass recovery](PRIVILEGED_MFA.md#break-glass-recovery); its replacement passkey carries
account-wide assurance. Removing every source of privilege is the other exit. Re-promotion after
enrolling still requires approval before administrative access resumes.

**Staging opt-out:** before starting an upgrade whose population is unresolved, set:

```dotenv
CONNEX_PRIVILEGED_MFA_ATTESTATION_ENFORCED=false
CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR=security-change-1534
```

Replace the actor with the accountable operator or approved change identifier. Startup refuses the
opt-out with an absent, blank or `configuration-default` actor. Grantor vouching remains required
during the exception. After draining and the fresh preflight, set attestation enforcement to `true`
and restart all backend replicas with the attributed change.

**Rollback:** policy rollback uses the same `false` plus accountable actor and a backend restart.
It needs no schema rollback. The **V225 rollback floor is unchanged**: no binary older than the
release carrying V225 and its switch-independent grantor checks may serve.

**What confined users see:** Security and onboarding show the passkey-approval confinement card,
with approval-code entry and the organizations still needing approval. A covered grantor can issue
the code; users can also leave the listed privileged workspaces or organizations, or sign out.
They remain confined until the same passkey covers every privileged organization or no privilege
remains. The browser API answers `403 PRIVILEGED_MFA_ATTESTATION_REQUIRED`; public credentials answer
`401 privileged_mfa_attestation_required` under the default policy.

### Ask Connex member masking (#1822): rollback compatibility

This release has no schema change and is rollback-safe. Raw member provenance is stored in the
separate `memberIdentifiers` metadata field on answers, user messages and summaries. Older
releases ignore that field and continue replaying without member-name masking, exactly as they
behaved before #1822. Existing `identifiers` arrays retain only their previously supported kinds.
No writer flag, reader-first rollout or MEMBER-specific rollback floor is required.

## Triggered-send rollback quiescence

The triggered-send fence is captured at backend startup; changing an environment file does not close
running replicas. Before replacing binaries with a version that does not understand triggered sends,
set `CONNEX_WORKFLOWS_TRIGGERED_SEND_ENABLED=false`, drain and restart or recreate every
current-version backend replica, verify no running instance remains enabled, and wait one full claim
lease plus one provider-deadline interval. The authoritative provider-boundary and reconciliation
contract is [Automation: triggered campaign delivery](backend/AUTOMATION.md#triggered-campaign-delivery).

## On-prem upgrade runbook

1. **Preflight legacy media before any recreate** — if the installed version predates private object
   storage, or any database row still refers to the old public `attachments`, `contact-pictures`,
   `company-logos`, or `profile-pictures` paths, do not use the generic commands below. First stage
   the writable-layer files from the still-running old frontend and follow
   [Migrating legacy public uploads](DEPLOYMENT.md#migrating-legacy-public-uploads). Pulling an image is
   safe; recreating the old frontend before staging can permanently erase those files.
2. **Quiesce writers** — stop external ingress plus the frontend, backend, and OCR services. Keep the
   database running. Confirm no application or maintenance container remains able to create or delete
   records or objects.
3. **Back up the quiesced set and exact deployment inputs** — snapshot the database (a
   `connex-backup-full.sh` run — see [BACKUP_RESTORE.md](BACKUP_RESTORE.md)), private object
   storage, and any staged legacy media as one recovery point. Download and verify the currently
   deployed release manifest, signature bundle, and exact `connex-<version>-deploy.tar`; preserve the
   extracted bundle without modification. Copy the mode-0600 `deploy/.env` byte-for-byte into the
   recovery set, retain its mode, and record SHA-256 hashes for both the deploy archive and environment
   file. Do not proceed without a verified, restorable data backup plus these exact prior deployment
   inputs; there is no automatic rollback of a migration.
4. **Pin and pull the complete target set** — verify the exact tag-bound `release-manifest.json` as
   documented in [RELEASE.md](RELEASE.md), then set `CONNEX_BACKEND_DIGEST`,
   `CONNEX_FRONTEND_DIGEST`, and `CONNEX_OCR_DIGEST` in the mode-0600 `deploy/.env` to the 64
   lowercase hexadecimal characters after `sha256:` for each image. Run `docker compose pull`.
   Low-resource deployments using the documented OCR opt-out must additionally run
   `docker compose --profile ocr pull ocr` while keeping the persisted `COMPOSE_PROFILES` value
   empty. Require the backend, frontend, and OCR digest to be locally available before starting the
   target version so every deployment stages the complete signed image set.

   This upgrade does not require recreating the Compose `edge` or `app` networks. The target bundle
   does not pin their subnets or container addresses: the backend trusts only the `caddy` and
   `frontend` service names and refreshes their Docker DNS addresses after service recreation.
   Keep the existing networks in place and use the ordinary service-by-service `docker compose up`
   commands below. If an unreleased preview added `CONNEX_EDGE_SUBNET`, `CONNEX_CADDY_IP`,
   `CONNEX_APP_SUBNET`, or `CONNEX_FRONTEND_APP_IP` to `.env`, remove those obsolete variables;
   set `CONNEX_SECURITY_TRUSTED_PROXIES=caddy,frontend`, replacing any prior IP or CIDR value.
   Do not run `docker compose down` or delete a network to take this upgrade.
5. **Migrate backup prerequisites, then refresh the tooling** — before rerunning the installer,
   update the preserved `/etc/connex-backup/backup.env` and mode-0600 client defaults files:

   - **TLS is mandatory for each source, verify, and restore profile.** Put a trusted, absolute
     `ssl-ca` or `ssl-capath` in the `[client]` section of each profile's defaults file; verify and
     restore inherit the source file unless configured separately. The certificate must match the
     configured host. CA paths must exist inside the DB container in `exec` mode or be mounted
     read-only in `run` mode. The previous password-only defaults are insufficient, including on
     loopback. If a profile deliberately uses plaintext over literal `localhost`, `127.0.0.1`, or
     `::1`, explicitly set its own `CONNEX_BACKUP_SOURCE_ALLOW_LOOPBACK_PLAINTEXT=true`,
     `CONNEX_BACKUP_VERIFY_ALLOW_LOOPBACK_PLAINTEXT=true`, or
     `CONNEX_BACKUP_RESTORE_ALLOW_LOOPBACK_PLAINTEXT=true` in `backup.env`. No exception is
     inherited between profiles; remote/private addresses and the Compose host `db` require TLS.
   - **Migrate the PITR image reference.** Replace the old sample
     `CONNEX_BACKUP_DOCKER_BINLOG_IMAGE=percona/percona-server:8.4` with an independently approved
     `percona/percona-server@sha256:<64 lowercase hex digits>` reference. Substitute the digest
     approved by the release owner; this repository does not supply an approved Percona digest.
     Stage or mirror that exact image before recovery is needed. An empty image setting is only
     appropriate when a native `mysqlbinlog` is installed and verified instead. Mutable tags are
     rejected both during installation and by the runtime shim; see
     [BACKUP_RESTORE.md](BACKUP_RESTORE.md#install-operator-once).

   From the target deployment directory, rerun the shipped installer before Compose recreates the
   database. It preserves operator-owned settings, migrates a legacy `<project>_default` Docker
   network value to automatic discovery, and installs the matching shims:

   ```bash
   sudo ./backup/install.sh
   ```

   The installer exits 64 with migration instructions if either prerequisite is missing, before
   replacing installed programs or enabling timers. Correct the named profile or image setting and
   rerun it; do not proceed past a failed install. Existing timers are not stopped by this preflight.
   This is an offline configuration check; it does not test CA availability inside containers,
   certificate trust/hostname, image availability, or native client installation. After the database
   is available, verify a full backup and binlog archive and run the documented PITR drill.

   Do not skip this step when backups normally use `exec`. Database clients in `run` mode discover
   the configured DB container's actual Compose `db` network, including a project name selected
   with `-p` or `COMPOSE_PROJECT_NAME`. The local PITR decoder runs with networking disabled.
6. **Normalize object-volume ownership when required** — the backend runtime identity is permanently
   `10001:10001`. Before the first upgrade from a preview image that used a dynamic UID/GID, run the
   following idempotent preflight while writers remain stopped:

   ```bash
   docker compose run --rm --no-deps --user 0 --entrypoint sh backend -c '
     current="$(stat -c "%u:%g" /var/lib/connex/objects)"
     if [ "$current" != "10001:10001" ]; then
       chown -R 10001:10001 /var/lib/connex/objects
     fi
     chmod 0700 /var/lib/connex/objects
   '
   ```

7. **Start the data plane with ingress closed** — leave Caddy stopped. Start the database, default
   OCR service, and backend first, and require Compose health before touching the old frontend
   container:

   ```bash
   docker compose up -d --wait --wait-timeout 300 db ocr backend
   ```

   For an existing deployment that uses the documented low-resource OCR opt-out, start only
   `db backend`; do not add `--profile ocr` ad hoc because `COMPOSE_PROFILES` is the persistent
   operator contract.

   Flyway and the normal-startup legacy-reference guard finish before backend health becomes ready.
   If legacy references remain, the backend fails and the old frontend container is left intact so
   its writable-layer media can still be staged. After backend health succeeds, start the frontend
   without Caddy:

   ```bash
   docker compose up -d --wait --wait-timeout 300 frontend
   ```

8. **Verify internally before publishing ingress** — use the pinned Caddy image as a one-shot client
   on the private Compose network:

   ```bash
   docker compose run --rm --no-deps --entrypoint wget caddy -qO- \
     http://backend:8080/api/version
   docker compose run --rm --no-deps --entrypoint wget caddy -qO- \
     http://backend:8080/api/capabilities
   docker compose run --rm --no-deps --entrypoint wget caddy -q --spider \
     http://frontend:3000/auth/login
   docker compose ps
   ```

   Require the target version, the expected business-card capabilities, healthy OCR unless the
   deployment uses the explicit low-resource opt-out,
   exact configured image digests, and stable running containers on a second probe. Smoke a private
   media download with an authenticated test session.
9. **Publish ingress last** — only after every internal check passes, start Caddy and run the external
   smoke:

   ```bash
   docker compose up -d --no-deps caddy
   curl -fsS http://<host>/api/version
   ```

   Reopen upstream ingress or writers only after that smoke succeeds.
10. **Replace one-time links issued before V170** — the V170 security cutover intentionally provides
   no compatibility shim for links created by the previous version. After the target deployment is
   healthy, re-issue every outstanding emailed workspace invite and revoke and regenerate every
   outstanding shareable invite link. These are the material operator workload: emailed workspace
   invites remain outstanding for 14 days, while shareable invite links default to 14 days and
   unlimited uses. Registration-verification links remain valid for 24 hours in the previous version;
   affected registrants must request a fresh verification email. Password-reset links last only 30
   minutes, so there is effectively no outstanding population to migrate and no operator action is
   needed; a user with a rare in-flight reset must request a fresh link. Upgrades that cross V203/V204
   must additionally re-send every outstanding document delivery (acceptance tokens stay valid until
   each delivery's `expiresAt`, which may be unset), re-send campaigns whose unsubscribe links are
   still in recipients' inboxes because those links never expire and now 404, and update the
   Cloudflare skip and rate-limit expressions while retaining the retired `/document-acceptance/`
   prefix in the no-logging rules until that resend is complete — see the V203/V204 cutover section
   above.
11. **On pre-ingress failure** — keep Caddy and upstream ingress closed and stop the target application
   containers. **Exception: after the V227/V228 campaign unsubscribe cutover, use its forward-recovery
   procedure above; do not start the previous binary.** For other upgrades, Remove the target deployment directory, re-verify and extract the exact prior signed
   deploy archive, restore the prior mode-0600 `.env` byte-for-byte, and confirm both recorded hashes.
   Use that restored Compose bundle when you **restore the complete database, object, and legacy-media
   backup** and start the previous version. Changing only image digest variables is not a rollback
   because Compose structure and non-secret environment may have changed between releases. Restore is
   safe only while no post-backup writes have been admitted. If a failure occurs after ingress reopens,
   quiesce writers and assess the new writes before restoring. Never hand-edit
   `flyway_schema_history` or delete a partially-applied migration.

## CI coverage & follow-ups

The forward-only guard and its modify/delete/rename/type-change regression suite run on every pull
request and `main` push. `FlywayUpgradeIntegrationTest` migrates representative populated V73 media,
quota, deletion-queue, and import state through the current lineage. A full
**restore-a-previous-release image → migrate → smoke** gate remains a follow-up once stable released
version baselines exist.

## Ownership consent (#1534 slice 4o)

Existing-scope owner promotions create offers; acceptance grants ownership. The HTTP, MFA, locking, control-plane, notice and cleanup contracts are in [Ownership offers](backend/OWNERSHIP_OFFERS.md). Founding, admin changes and last-owner checks retain their existing behavior. The self-offer card is authoritative on every catalog layout; ordinary inbox delivery retains the existing cross-catalog limitation.
