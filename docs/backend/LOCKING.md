# Backend Locking and Transaction Contract

Connex has multiple concurrent aggregates whose lock orders deliberately interoperate. This document centralizes the contracts that previously lived in the always-loaded backend agent guide.

Read the relevant section before adding/changing `FOR UPDATE`, transaction isolation, multi-aggregate writes, lifecycle writes, duplicate-decision behavior, leases/retries, or network/provider work around transactions.

## Global principles

- Discover keys without locks when the contract says to do so; sort deterministic key sets in Java before acquiring exact locks.
- `ORDER BY ... FOR UPDATE` is not a substitute for an explicitly ordered series of exact lock acquisitions when a contract requires deterministic ordering.
- **A locking read selects its rows by equality on an index prefix, not by a range or `NOT IN` on a later key column (#1578).** MySQL may run such a multi-range read as a full index scan on a small table. A locking full scan must lock, and wait on, every row it reads before discarding it, including other roles' and other workspaces' rows. `RoleMapper.lockPermissions` therefore reads a custom role's `workspace_role_permission` rows by `workspace_role_id` alone, and its callers drop inert permissions in Java. MySQL 8.4 runs that read as a `ref` lookup of the role's own rows in every distribution measured (tiny, balanced and skewed), but a statement cannot force a plan, so that is the expected access path rather than an enforced one. Under REPEATABLE READ the lookup's end still takes a gap lock before the next role's first row; that blocks inserts into the gap, not another role's locking read. Members who share a role still serialize on its row.
- Revalidate the exact locked rows before deriving authorization or performing writes. Pre-lock permission/state snapshots are preliminary only.
- Acquire broader/root locks before child/aggregate locks according to the owning contract; do not reacquire a broader root later in the transaction.
- Keep provider/network I/O outside database transactions unless a subsystem contract explicitly requires and bounds otherwise.
- **A database rollback poisons its transaction (#1947).** MySQL answers some failures by rolling back the whole transaction, savepoints included, and then lets the next statement open a new implicit transaction on the same connection: a deadlock (1213), a full lock table (1206), and a lock-wait timeout (1205) on a server running with `innodb_rollback_on_timeout`.
  - `RolledBackTransactionGuard` receives every failure the MyBatis `SqlSessionTemplate` and the JdbcTemplate translator translate. When one of these arrives inside a Spring transaction, the guard makes that transaction fail its commit with the same exception the failure would have produced unswallowed, however it was handled in between: a deadlock or lock-wait timeout as the retryable lock failure, a full lock table as uncategorized.
  - So code that catches a deadlock and carries on cannot commit the work it did afterwards. Retry in a new transaction, never the same one.
  - Inside a caller's transaction, `AuditService` rethrows the `TransactionSystemException` from a `NESTED` append whose savepoint is gone, rather than recording it as an audit failure. Both workflow engines retry it, because the retry policy walks the deadlock it carries as its application exception.
  - Outside the guard, a swallowed rollback can still commit partial work:
    - Spring Session's JDBC repository;
    - `SeederBatchWriter`'s own `SqlSessionTemplate`;
    - `DataSubjectDisclosureReadTransaction`'s raw session;
    - cursor iteration in `TenantExportSnapshotTransaction`;
    - a failure swallowed inside another synchronization's `beforeCommit` or `beforeCompletion`, which registers too late for the commit it occurs in.
  - A lock failure that also crossed a participating `@Transactional` proxy has already marked the transaction rollback-only. Its commit therefore rolls back before any callback runs and fails with a cause-less `UnexpectedRollbackException`. Nothing is lost, but both engines classify that as permanent.
- Read control-plane data needed only for a response after the tenant write commits. Workspace
  entries prepare immutable record-visibility and active-only organization scopes before tenant
  transactions in both modes (#2165/#2176). Matching prepared reads borrow no connection. Routed
  lookups without a matching payload throw `TenantScopeSnapshotMissingException` before any query
  or connection checkout inside transactions, including shared placements with a null catalog.
  Single-database entries inside an actual transaction skip preparation and retain lazy boundary
  fallbacks. Ordinary sharing remains lazy in single-database mode. Control-plane **locks** needed
  through commit stay on the tenant transaction's own connection through `ControlCatalogRoutingInterceptor`;
  prepared scopes never replace authorization locks. Other control operations retain their documented
  connection budgets.
- **Scope reads and REPEATABLE READ read views (#2176).** A plain SELECT on an InnoDB transaction's
  connection establishes its consistent-read view if none exists. A locking read uses current state
  but does not refresh that view. Consequently a lazy organization-workspace scope read before a
  contended record lock can make later plain share/reference reads see a grant revoked while waiting.
  Prepare the scope before opening the transaction; when preparation is unavailable, resolve it
  outside the transaction or after its first required locking read. Do not move locks off their
  transaction connection or change lock order to satisfy scope preparation. Actual-transaction
  single-database entry intentionally retains the lazy fallback and its pre-lock snapshot behavior;
  `RecordVisibilityScopeReadViewIntegrationTest` characterizes it alongside prepared entry. Other
  pre-lock consistent reads, including deal-board discovery, still establish a view independently
  and are not repaired by scope preparation. The active-only boundary's lazy fallback already uses
  an independent control transaction and does not establish the caller's view. Destructive freshness
  is separate: restriction cache erasure retains its same-connection READ COMMITTED fresh scope read.
  A deferred record-visibility operation's first use checks the prepared payload before its
  transaction memo or lazy lookup; empty and skipped operations retain their no-read behavior.
- Company visibility and relationship validation resolve the unfiltered `RecordVisibilityScopeControlAccess` ceiling before the first authorization lock, duplicate-decision mutex or `FOR UPDATE`. This includes reference-hydrating Note/Task/Activity/Introduction writes, record creation and attachment target locks, transcript/turn preparation and document lifecycle activity callbacks. `CompanyMapper.getVisibleCompanyByIdForUpdate` and share-grant locks still run on the original tenant transaction connection; an invisible Company yields no row and cannot become an accepted relationship target. Compound reads bind `withWorkspaceScopeRead` when visibility work is mandatory and `withDeferredWorkspaceScopeRead` when existing guards can skip it. Operations with independent failure handlers, including Radar bootstrap reconciliation, bind `withDeferredWorkspaceScope` so ordinary scope failures reach those handlers; snapshot-invariant refusals still escape. Loops of independently committed mutations bind an explicit operation scope, capture one snapshot when needed, restore it on exit and retain every existing transaction boundary. Lock-taking mutation roots remain eager before their first authorization lock or mutex. Destructive cache purges retain the separately documented `getFreshForWorkspace` exception.
- Changes to lock order or transaction isolation are Tier 3/high-risk and receive focused concurrency/correctness review.

Interactive and public API requests return HTTP 409 for Spring pessimistic lock failures and exact
MySQL deadlock (1213), lock-wait timeout (1205), and NOWAIT (3572/HY000) failures, including causes
and `TransactionSystemException` application exceptions, only when no non-read-only Spring transaction
has committed during handler execution on the request thread. The final MVC interceptor arms the
window after authentication, MFA and tenant resolution; pre-handler bookkeeping commits do not
count. Read-only commits are ignored. Credential successful-use recording runs after the filter
chain returns, outside the handler's error-response decision. A `TransactionExecutionListener`
registered by Boot on the JDBC transaction manager (also with a tenant-routed datasource) records successful writable physical
commits in request attributes; nested savepoint releases and participating transactions do not mark
a commit. Handler-phase bookkeeping, `REQUIRES_NEW` and independent audit commits conservatively mark
the request, even if the outer transaction later rolls back. A marked request keeps the existing
HTTP 500 path. Spring MVC binds request attributes around `Callable` and `StreamingResponseBody`
workers, so their writable commits are tracked. Autocommit statements and commits on application
executors without bound request attributes are not tracked; without a bound request the marker is a
no-op. A handler that waits for writable work committed on such an executor must not rely on this
409: none does today (`AiGenerationService` returns its handle without waiting). Responses use a generic conflict message without SQL or lock details;
callers may retry with a fresh request and transaction within this tracking bound. There are no
in-request retries. Unclassified transaction failures, including a cause-less
`UnexpectedRollbackException`, retain their existing HTTP 500 response.

## Workflow lifecycle and account offboarding

Workflow lifecycle writes and permanent account offboarding share a hierarchy:

1. Discover identity-bound keys without locks.
2. Lock referenced `app_user` roots in ascending user id.
3. Lock candidate workspace roots in ascending workspace id (`FOR SHARE` or the existing stronger owner lock as required).
3b. Automation authoring that admits trigger capacity only: acquire the workspace's
   `workflow_trigger_admission` row with the single `WorkflowMapper.acquireTriggerAdmissionMutex`
   upsert. That `INSERT ... ON DUPLICATE KEY UPDATE` *is* the acquisition: it creates the row on
   first use, and when it hits the existing primary key InnoDB takes an exclusive lock on the
   duplicate row and holds it to commit, so a concurrent author blocks on that statement. No
   trailing `SELECT ... FOR UPDATE` follows it — naming one would document a statement that never
   contends. This is the mutex for aggregate trigger-capacity admission; nothing outside
   `WorkflowPrincipalLockService` may take it, and the remediation paths (disable, pause, archive,
   restore), standalone draft authoring, legacy delete, and runtime-owner cutover/rollback pass
   `admitTriggerCapacity = false` so they neither wait on it nor write to it. Legacy-rule
   replacements that disable the rule or strictly shrink its trigger events are exempt the same way
   (`LegacyRuleWorkflowService.requiresTriggerAdmission`), and the aggregate lock revalidates the
   discovered rule before the exemption is honoured.
   Any transaction that will publish must take this mutex in its first principal-lock pass,
   before membership or role locks, and hold it through publication. Recipe installation therefore
   passes `true` during draft creation; its later publication reacquires the already-held mutex.
4. Lock required `workspace_member` rows in ascending user id.
5. Lock the actor's custom `workspace_role` root and exact permission row when current authorization depends on it.
6. Lock exact workflow → workflow-version → rule point keys in that order.
7. Revalidate locked state before writes.

Version/rule key discovery is non-locking and Java-sorted before individual exact `getByIdForUpdate` calls. Final workflow authorization uses the locked membership's current role/role id, not an earlier `WorkspaceService` snapshot.

The workspace root at step 3 stays `FOR SHARE` for workflow lifecycle writes, and taking it
exclusively there is a defect. Every audited transaction in the workspace takes that same row
`FOR SHARE` at its end (`AuditIntegrityService.lockForeignKeyParents`), and membership-first record
mutations — person owner change, deal, deal-collaborator replacement, company, task, saved views, AI
chat turn persistence — lock the `workspace_member` row before reaching the root in that trailing
audit. Deal-collaborator replacement locks every requested member's active membership `FOR UPDATE`
in ascending user-id order, then the deal row `FOR UPDATE`, then reads the deal's `deal_collaborator`
rows `FOR UPDATE` and replaces them, before its trailing audit — the order `DealService.updateOwner`
uses — so a member offboarded concurrently is never written back as a collaborator (#1793). The owner
changes (`DealService`, `PersonService` and `CompanyService.updateOwner`) lock the new owner's membership,
then read the record row `FOR UPDATE`, and take the audited old owner and the `owner_changed` decision
from that row rather than from their unlocked existence check (#1948). Each read takes exactly the
`PRIMARY` record lock its `UPDATE` already took, so the lock set is unchanged. For a deal that needs
`getDealByPrimaryKeyForUpdate` rather than `getDealByIdForUpdate`, which would also lock
`uq_deal_workspace_id`. A composite-foreign-key child insert, such as an approval request, holds that
entry shared and then takes the workspace audit head or the workflow gate. A legacy delivery's
`assign_owner` reaches the owner change holding the gate, and the audit head too once an earlier
action has audited. Either one would close a deadlock if the owner change locked that entry. The
other single-column deal updates, `reschedule` and `updateRiskExcluded`, read the deal through the
same primary-key lock for the same reason (#1958). The contact and company tag replacements
(`PersonService` and `CompanyService.replaceTags`) and the contact evaluation opt-out
(`PersonService.updateEvaluationExclusions`) also read the record row `FOR UPDATE` first, refuse an
archived row under it, and audit from what they read after it, at `READ_COMMITTED` (#1968). The deal
and attachment tag replacements do the same (#1980): the deal through `getDealByPrimaryKeyForUpdate`,
whose `PRIMARY`-only lock is the record a `deal_tag` foreign-key check takes, refusing a deleted deal;
the attachment through `getMetadataByIdForUpdate`, re-checking its type and note-target visibility
under that lock. A tag replacement then reads the record's `person_tag`, `company_tag`, `deal_tag` or
`attachment_tag` rows `FOR UPDATE OF` the association table, driven from its primary-key prefix,
before clearing them. A concurrent `addTag` blocks on the
record row at its foreign-key check, and a concurrent `removeTag`, which takes no record lock, blocks
on the association row, so the audited previous tags are exactly the rows the replacement deletes. A
tag deletion that cascades into a record while a replacement re-adds the same tag can still deadlock,
as it could before (the replacement holds the association row and waits on the tag row for its
insert's foreign-key check); InnoDB rolls one back with a retryable error. The collaborator path is in the #1582 class
below, as the trailing audit's `FOR SHARE` roots follow its membership locks. An exclusive root at
step 3 would therefore both barrier every audited write in the tenant for the duration of an
authoring transaction and close a deadlock cycle against those mutations (issue #1582's inversion
class). Mutual exclusion for the trigger-capacity count comes from step 3b instead, which is scoped
to the counter it protects because no other path locks that row.

The activation entry points that perform trigger-capacity admission — `WorkflowService.publish`,
`enable`, and `resume`, `RuleService.create` and `update`, and `WorkflowRecipeService.install` —
declare `Isolation.READ_COMMITTED`. Without it the recount after waiting on the step-3b mutex would
be served from the transaction's pre-lock snapshot and both contenders would be admitted. `install`
carries the isolation because it wraps `publish` inside its own transaction, so the inner method's
annotation never applies. `RbacEnforcementArchTest` pins all six.

`LegacyWorkflowBackfillTransaction` is exempt from both step 3b and trigger-capacity admission: it
runs at startup under the exclusive workspace root (`:62`) and only mirrors pre-existing legacy
`enabled` state onto its canonical pair (`:321`), so it creates no new fan-out; a workspace already
over the limit stays bounded by the retained intake backstop.

Account offboarding takes the union of owner/workflow workspace roots in one ascending pass before membership rows, then disables affected paired/unpaired rules before principal redaction.

## Post-delete assignment recovery (#1941)

Initial per-catalog erasure can commit before a locking assignment writer finishes. The final
control-plane transaction now retains every deleting-user membership before organization and audit
locks; its `app_user` cascade reuses those locks. Its commit remains the assignment barrier.
`UserService` then runs a fresh sweep, including when an absence read
confirms that an ambiguous deletion commit succeeded, before expiring sessions. Sweep failures
leave recovery pending and do not replace the deletion result or prevent session revocation.

Coverage is ten references: company, person and deal ownership; deal collaborator seats; task
assignment; intervention and campaign ownership; lifecycle-pass ownership; and report-goal ownership
and creator attribution. A successful sweep removes those references in every catalog returned by
`PlacementRegistry.activeCatalogs()`, across
all workspace and record lifecycle states. It does not promise cleanup for unavailable or unserved
catalogs, approvers (#1953), or self-only asynchronous writers.

Discovery returns numeric keys without locks or an ambient snapshot. Bounded account-existence
reads use `UserMapper.findExistingIds` through `unroutedRead` on the control plane; existing accounts,
including reserved accounts, are preserved. Each catalog is pinned before opening a separate
`REQUIRES_NEW`, `READ_COMMITTED` transaction for each candidate. The sweep locks the exact parent
through `PRIMARY`, then conditionally clears the unchanged user pointer; missing records and changed
pointers are successful no-ops. Collaborators lock deal X before deleting the exact seat and its
secondary-index entries, matching replacement's parent-before-child ordering. Person and lifecycle
pass units lock person X, clear that person's matching owner, then clear matching owners on its
passes in the same transaction. The sole lifecycle owner-snapshot writer,
`PersonLifecycleService.recordPass`, follows `applyStage`'s `getOwnedPersonByIdForUpdate`, so a raced
insertion, milestone or closure commits before that person lock or snapshots the cleared owner.
Intervention ownership clearing increments `source_version` only when it changes the pointer.
Account-scoped goal discovery can use V226's user-leading departure indexes, and the periodic pass
ranges over the primary key as the other kinds do. Both kinds lock the
exact `report_goal` row through `PRIMARY`, then conditionally delete the personal goal for an
unchanged orphaned owner or clear an unchanged orphaned creator. Each catalog pass visits the owner
kind before the creator kind, matching erasure's delete-before-detach ordering. Their bounded pages
advance independently; neither path has child rows to delete or acquires another root after the
goal row.

These recovery units acquire no account, workspace, membership, role, workflow-gate, or audit-head
roots and reuse no full-erasure transaction. Existing #1582 cycles remain unchanged. A failed unit
rolls back before siblings continue; no retry runs inside a poisoned transaction. Failures log only
catalog and exception type and increment `connex.account.assignment.sweep.failures` with bounded
`mode=immediate|periodic` and `phase=catalogs|page|existence|record` tags.

Immediate recovery drains matching pages. Periodic recovery visits one page per catalog/kind per
run (default 200). Each traversal captures a fixed scalar `MAX(id)` watermark, advances past failed
records, and wraps on an empty bounded page. Collaborators instead use their complete
`(workspace_id, deal_id, user_id)` primary-key tuple as cursor and greatest-key watermark with
`cursor < key <= watermark`. An empty page wraps even if the watermark row was deleted. Appends
cannot extend a traversal and starve an earlier failure; a restart discards cursors and safely
rediscovers residual references. Recovery remains contingent on later successful catalog access and
record transactions.

## Sequence template authoring

Sequence mutations acquire and revalidate the actor's permission locks before the exact sequence row. The account-deletion reservation check in that locked authorization path is itself a locking read so it cannot establish a stale consistent-read snapshot before later contention. Update and archive then re-read the locked sequence and enforce personal-versus-shared visibility before writing. Publish retains the sequence root, reads draft steps and content with shared locks, and allocates the next version number with a locking maximum query before inserting its immutable definition and hash. Draft replacement remains inside the same transaction.

The `sequence_version` row is physically insert-only: its canonical definition, hash, sequence identity, version number, and creation timestamp are never updated. Publisher attribution lives in `sequence_version_publisher`, a separate mutable pointer whose user reference may be cleared during account erasure without changing any version-row byte.

Sequence aggregate reads, version reads, and preview are non-locking and perform no writes. Each complete authorization-and-payload load runs in one read-only `REPEATABLE_READ` transaction so a visibility change cannot combine stale shared metadata with newly private draft or version content. Preview proves the contact through the workspace-owned, caller-`MemberScope`-filtered person statement before reading any contact field; shared-in, unassigned, other-owner, archived, suspended, and provision-ceased contacts fail closed.

Method-security checks are preliminary because they run before transactional advice. Every sequence
read repeats active-membership and `SEQUENCE_VIEW` authorization as its first consistent database
check inside the read transaction, before sequence visibility and payload loading.

## Workspace member and role mutations

After preliminary authorization:

1. Lock actor and target `app_user` roots in ascending id.
2. Lock the exact workspace exclusively.
3. Lock actor and target memberships in ascending user id.
4. Revalidate actor active and target state.
5. When a role is addressed (edit/delete), lock every assignee of that role through `lockRoleAssignees` (`FOR UPDATE NOWAIT`). When the mutation can affect an owner — the target is an owner, or the addressed role has an active-owner assignee — additionally lock the active owner memberships through `lockActiveOwnerMembers` (`FOR UPDATE NOWAIT`). Both phases precede every role root. Mutations that cannot touch an owner (role creation, and member-scoped changes against a non-owner target) take neither, so an unrelated `workspace_member` row lock — `NotificationService.markAllRead` holding an owner's membership, for example — cannot fail them.
6. Lock distinct actor/requested/active-owner custom `workspace_role` roots in ascending role id, then their complete permission sets with ordered locking reads.
7. Perform final authorization from the locked actor membership/role state.

The owner/assignee snapshots abort contention with 409 instead of waiting: departure cleanup can already hold a membership before requesting the workspace root. Waiting while holding that root would close a cycle. Only MySQL NOWAIT error 3572 with SQLSTATE HY000 is translated; other failures propagate. There is no retry: the caller resubmits.

An owner is a *recovery owner* when they are active, carry no live account-deletion reservation, and either hold no custom overlay or hold one that still grants the complete grantable catalog — reassigning oneself the built-in owner role clears the overlay and passes the grant ceiling, so both cases can restore full authority. Overlay assignment and role edits may not narrow any active owner's effective permissions unless a recovery owner remains afterwards; a change that takes nothing away is always allowed, so a no-op or repairing edit is never blocked by a workspace that already has no recovery path. Built-in owner demotion, member removal, departure, and account deletion must leave at least one active owner, and — when the departing user is an active owner — a recovery owner among the rest. Raw ownership still governs who may change another owner. Owner demotion uses the existing exact workspace root; it must not reacquire broader owned-workspace roots. Departure takes the NOWAIT owner snapshot after its existing ordered workspace and recipient-membership locks. Account deletion takes that snapshot after its workspace-root pass, once per owned workspace and in the same ascending order. Both exclude the departing account before any cleanup or deletion reservation.

Recovery candidates come from locked memberships, and their overlays' permission sets from the role roots locked in step 6. `AccountDeletionReservationRead` reads only their reservation flags in a fresh read-only `REQUIRES_NEW` / `READ_COMMITTED` transaction, without acquiring user locks after workspace locks; account deletion collects the candidates across every owned workspace and issues that read once. Reservation creation and renewal take the user and ascending owned-workspace roots before updating the lease, retaining those roots through commit. This prevents an uncommitted renewal from extending a lease after another transaction has counted its old expiry as available. `AccountDeletionReservationFenceArchTest` is the rot alarm on that coupling. Renewal rejects expired leases; expiry/release only restore availability. The fresh read avoids an old repeatable-read snapshot counting a reserved owner as available.

Final account deletion takes the account root exclusively before proving reservation ownership and
database-clock expiry. `isAccountDeletionReservationOwner` uses `FOR UPDATE` on that already-held
row, flushes the MyBatis session cache, and bypasses the second-level cache, so an earlier
REPEATABLE READ snapshot or cached answer cannot authorize deletion under a replaced reservation.
It refuses before erasure or audit side effects when the reservation no longer holds (#2059).

The built-in administrator exception (`WorkspaceService.requireBuiltInAdministrator`) is not a grantable permission, so any overlay denies it whatever that overlay contains. Transactions that act on the exception while holding record locks must take the exception's authorization snapshot — user root `FOR SHARE`, active workspace root `FOR SHARE`, exact membership `FOR UPDATE` — through `isLockedBuiltInAdministrator` **before** their first record lock, and assert it later with `requireLockedBuiltInAdministrator`. Report deletion, report-snapshot deletion, deal deletion, and deal-document deletion take it at the top of the transaction, ahead of `lockDefinitions`, the duplicate-decision mutex, and the deal/document row locks respectively; approval cancellation asserts it in place because `lockApprovalMutationRecipients` already holds those exact rows. The plain non-locking form remains correct for the read-only member-scope analytics gates, which take no lock afterwards.

Custom-role creation uses `lockRoleCreationAuthorization`, which takes no role id and checks the grant ceiling after locking current role-management authority. Existing-role updates use `lockRoleMutationAuthorization` with a non-nullable role id and always enforce owner authority, retained recovery, and the grant ceiling after locking. Custom-role update/deletion locks the exact role after the common roots. Assigned custom roles cannot be deleted until members are reassigned through the permission-ceiling-checked mutation.

**Confinement check and isolation (#1534).** `changeMemberRole`, `assignCustomRole` and `updateRole`
run at `READ COMMITTED`. After step 7 they read which affected accounts are privileged in the
organization; after the write, and before the success audit, they read it again together with
passkey approvals there, and refuse an unacknowledged edit that makes members newly privileged
without one (see [PRIVILEGED_MFA.md](../PRIVILEGED_MFA.md#acknowledging-confinement-in-role-edits)).
A refusal rolls the write back with the transaction. `updateRole` reads its assignees from the
memberships `lockRoleAssignees` holds.
- Those reads are plain, so the isolation is what keeps them current. Under `REPEATABLE READ` they
  would read the snapshot taken by the transaction's first read, before it waited on these locks,
  and miss an approval committed meanwhile.
- The authorization, recovery and last-owner checks already use the locking reads above, which see
  the latest committed rows at any isolation.
- `READ COMMITTED` drops next-key gap locks, which these mutations do not rely on. Every writer
  that inserts or updates a workspace membership or a role's permissions first takes the exact
  workspace root, exclusively or shared: role mutations, SSO JIT provisioning
  (`ensureActiveMember`), invite grants, invite and link acceptance, and pending-membership
  approval. So no assignee or owner can appear while a mutation holds the root.
- The callers are non-transactional controllers. A caller that wraps one of these methods in its
  own transaction must open it at `READ COMMITTED`.

Member removal/leave/invitation decline also lock the departing user root. Removal expands the
recipient's membership set at that user's position in the ascending actor/target pass under the
workspace X root, before owner/assignee NOWAIT probes and role roots. Owner leave retains owned
workspace roots X ascending, then its recipient memberships before owner NOWAIT recovery scans.
Non-owner leave and decline enter departure cleanup after their account X lock. The shared cleanup
keeps main's workspace S → organization S prefix, then takes recipient memberships X before
ownership-offer, capture/provider and domain cleanup. Owner/assignee probes remain explicit
nonwaiting exceptions: only MySQL 3572/HY000 becomes 409, without an in-transaction retry.

Catalog erasure retains the locking membership scan so it waits for matching uncommitted inserts.

Invite grants lock creator/known-recipient users ascending, reject deletion reservations, then lock active workspace/organization, memberships ascending, and creator custom role when applicable before testing grantability. Claim paths repeat authorization immediately before the exact claim.

Token-invite acceptance checks the current address under the recipient root and authorizes only that invitation. It does not write `app_user.email_verified` or emit a global email-verification audit event: the creator receives the token and can control the workspace SMTP sender. Global registration verification requires the separate instance-delivered token consumed by `RegistrationVerificationService`.

Pending-membership approval locks user, workspace exclusively, organization for share, and exact pending membership before domain/version-gated activation. Its mailbox-proof and organization-domain checks read the account through the locked `app_user` row, not the pre-transaction membership projection.

#### Verified email change as a pending-grant writer

Every account email-change mutation uses the same account-before-token order. Request validation
and token lookup are preliminary, non-locking reads. `requestChange` then locks `app_user` exclusively
before invalidating or inserting `email_change_token` rows; the token's foreign key must never cause
a request to acquire its user lock after holding an old token row. After invalidation clears the
MyBatis read cache, request validation repeats against the locked account and current uniqueness
and rate-limit reads. A refusal rolls back the invalidation. `exchangeToken` likewise discovers the
account without locks, locks that root, then conditionally claims the still-redeemable token; this
also governs the self-claim in programmatic `confirmChange`. Browser `confirmChangeByHash` discovers
the exchanged token without locks, locks the account, rechecks uniqueness, then conditionally
consumes the still-redeemable token before applying the change. No request or confirmation may lock
an email-change token and then acquire its account root.

`EmailChangeService.confirmChangeByHash` is a second writer of `workspace_member` pending rows: a pending grant is an offer to one mailbox, so moving the account off that mailbox revokes it. It runs from the account side rather than a workspace path, and follows the same hierarchy:

1. Lock the `app_user` root exclusively (already held for the email write).
2. Discover the account's pending grants without locks and take them in ascending `workspace_id`.
3. Lock each of those workspace roots exclusively in that order.
4. Lock the recipient's globally ordered `workspace_member` set (`NotificationMapper.lockRecipientMemberships`) — the same point invitation decline takes it, so notification cleanup cannot invert membership lock order.
5. Per workspace: delete the pending row, and only when that delete claimed it, write the scoped audit event.
6. Bump the recipient's notification state version once, after all deletions.

Every statement in that transaction is control-plane, so the revocation commits atomically with the email change. The revoked grants' notification baselines and notifications are tenant data in each workspace's own catalog, which that transaction cannot install. They are cleared afterwards by `RevokedGrantNotificationCleanup` (#1708), one transaction per revoked workspace in ascending id. Each unit pins the workspace's catalog with `inLifecycleWorkspace` (so suspended and tearing-down workspaces resolve too) before opening its transaction, then locks the `app_user` root, the workspace root and the recipient's ordered membership set, the same order as step 1–4 and as decline. It skips a workspace where any membership row, pending or active, exists again, so a re-invitation or acceptance committed since the change keeps its notifications. Taking the account row before the membership set, as the sweep and a member's leave do, places each unit in the #1582 class with membership-first record mutations that end in an audit. InnoDB resolves that cycle by rolling one side back: a unit that loses is caught like any other failed unit, while the other side's request fails and must be retried. `EmailChangeConfirmationService` runs the confirmation and then the cleanup, and refuses to start inside a caller's transaction, before consuming anything. The cleanup is one best-effort attempt per workspace: a failed unit is logged and leaves that workspace's rows in place, possibly indefinitely, since account erasure visits only `PlacementRegistry.activeCatalogs()`. Those rows stay out of the inbox, which requires a membership the revocation removed, and a fresh membership purges them.

Discovery is deliberately unfiltered by `lifecycle_state`, so a grant parked in a suspended or tearing-down tenant is revoked too. The account root is held throughout, and every invite-grant writer locks the recipient root first, so no new pending row can be committed behind the sweep.

The following boundaries run at `READ_COMMITTED` rather than the default isolation and must stay that way:

- `EmailChangeService.requestChange` — after acquiring the account root and invalidating tokens,
  repeated uniqueness and request-count reads must observe requests committed while it waited.
- `EmailChangeService.exchangeToken` — when a conditional claim fails after waiting for the account
  root, the same-browser retry check must observe a concurrent replacement's token invalidation.
- `EmailChangeService.confirmChange` / `confirmChangeByHash` — the pending-grant discovery in step 2 happens after waiting for the account lock. Under `REPEATABLE_READ` it would read the transaction's opening snapshot and miss a grant another transaction committed while this one queued, leaving a revoked-address grant behind.
- `OneTimeLinkFlowService.consumeEmailChange` — the browser-flow entry point that opens the surrounding transaction for the confirm operation. Its isolation is what the service method actually joins, so leaving it at the default would silently restore the stale snapshot.

The request token only names a candidate address. Delivery is dispatched off-thread and best-effort
by `MailService.sendInstance`; confirmation re-locks the account and rechecks address uniqueness
before applying the change.

### Workspace teams

Team mutations lock the exact `team` parent before any `team_member` child row. Manager replacement,
seat removal, workspace-member removal, fresh-membership residual cleanup, and account erasure all
use that order. Offboarding discovers affected teams without locks, Java-sorts them by
`(workspace_id, team_id)`, acquires each exact team lock in that order, and only then deletes seats
or clears manager references. Tenant teardown deletes the team parent and relies on its cascading
child foreign key, so it follows the same parent-before-child hierarchy.

Path-addressed membership removal performs its preliminary path-workspace authorization before
placement resolution, then installs that path workspace's tenant identity and catalog before the
transaction. The locked authorization recheck remains inside the transaction. Active removal,
pending-invitation decline, and leave all use this boundary so audit attribution and tenant-plane
cleanup cannot inherit the active-header workspace.

Fresh membership follows the same route-before-transaction rule. `InviteService` creation,
existing-user grants and both acceptance entries, `InviteLinkService` redemption entries, and
`SsoLoginService.resolve` use `FreshMembershipTransaction.execute` before their `READ_COMMITTED`
unit. `WorkspaceService.addPendingMember` and `ensureActiveMember` are also public transaction
entries: `executeWithPreparedScopes` prepares their target before opening the unit in both modes,
including when it differs from the request workspace. Their residual-cleanup call to
`UserOffboardingService.prepareFreshMembership` then reuses that prepared route under the locks.
An already-open single-database transaction reuses an exact prepared target when available;
otherwise it skips preparation and retains the existing lazy reads.
An already-open routed transaction may only reuse an exact prepared target. It cannot discover a
different target or suspend to prepare one. Nested provisioning restores the caller's identity,
catalog and prepared data.

### Bounded goal and approval departure cleanup (#1953)

The sole named approver guard uses fresh non-locking MyBatis reads (`flushCache="true"`) inside each
existing departure transaction, after its existing lock prefix and before destructive work. Routing
replay is pure Java; named routes are distinct from permission-filtered decision eligibility. It
adds no policy, document, deal, or goal aggregate lock and no writer fence. Each read first discovers
candidate step ids through user-leading indexes on frozen/policy approvers and assignment users or
delegators, then loads every routing fact only for those steps in the same statement snapshot.
Steps whose routing facts never mention the departing user cannot make that user their sole named
approver and are omitted; unrelated catalog approval traffic is not materialized.

Catalog goal-erasure discovery projects only row and workspace ids and unions separate owner and
creator equality reads, each served by a user-leading covering index. The union deduplicates goals
that reference the account in both roles. These discovery reads remain non-locking and flush the
MyBatis cache.

Workspace departure captures personal-goal, policy-approver and frozen-document-approver row ids
through the existing fresh non-locking reads before the sole named approver guard runs. Cleanup
uses only that captured set at every isolation level, including READ COMMITTED; it never rediscovers
references at the cleanup tail. References committed after discovery remain for deadline and
unsatisfiability reconciliation instead of being deleted without a guard verdict. Removal,
pending-invitation decline, leave and leave-and-select-next share this rule. Catalog account erasure
retains its existing transaction isolation and identity-scoped discovery/cleanup protocol.

Goal and policy/frozen approver cleanup uses conditional single-table statements keyed exactly by
workspace, captured row id, and departing user. It runs last, after the workspace-cleanup and catalog-erasure
tails (workflow, version, rule, team, deal and existing audit locks); leave-and-select-next also
finishes its selection update first. No earlier lock class is acquired after this cleanup within the
transaction body. The only later acquisition is the commit-time tail every writer shares:
`NotificationStateVersionService` runs its queued `bumpStateVersions` upserts in `beforeCommit`,
locking `notification_recipient_state` rows after all domain rows. Departure therefore keeps the
same domain-rows-then-notification-state order as the policy, approval and goal writers, and adds no
inversion against them. A concurrent aggregate edit can deadlock with these final statements:
propagate the failure and retry the operation in a fresh transaction, never retry in place.

The guard is exact for references committed before departure. A concurrently created request naming
the departed member can survive departure and follows existing reconciliation in its existing order:
its deadline rules apply first; expiration records `expired`, while escalation appends `any_approver`
and clears the deadline and can still pass with enough surviving approvers. Only when quorum remains
impossible after those rules does the bounded sweep terminate it as `unsatisfiable`, cancel other
open steps, and return the document to `draft`.

A concurrently created personal goal remains until account erasure removes it. After the account's
control-plane deletion commits, immediate and periodic orphan recovery cover `report_goal` owner
and creator references (#2069). Numeric discovery and control-plane absence checks precede each
separate catalog-pinned transaction, which locks only the exact goal row through `PRIMARY` before
conditionally deleting an orphan-owned personal goal or clearing an orphaned creator. Live owner
and creator pointers, including those of reserved accounts, are preserved.
[Governance deletion and retention](../GOVERNANCE_DELETION_AND_RETENTION.md) defines the product bound.

### Public API credentials

#### Canonical class order

Every transaction that touches an `api_credential` row acquires locks in this class order, and never
acquires a lock from an earlier class after one from a later class:

1. `app_user` — target `FOR UPDATE`, actor `FOR SHARE`.
2. `workspace` roots, ascending id (exclusive wins on overlap).
3. `workspace_member` / workspace owner rows.
4. `organization` roots, ascending id (exclusive wins on overlap).
5. `org_member` owner rows.
6. `api_credential` rows, ordered by `(workspace_id, id)`.
7. `audit_log_integrity_head` rows, ordered by chain-scope type rank (workspace 0, organization 1,
   system 2) then scope id.
8. The audit inserts and head advances themselves.
9. The `app_user` cascade delete.

Class 7 exists because `AuditIntegrityService.appendChained` locks the head after its foreign-key
parents. Ordinary transactions append into exactly one chain scope and therefore take exactly one
head, so only a transaction with two or more heads can cross with another. Account erasure is the
only such transaction on this plane: membership cleanup audits into the workspace it was given, and
the fresh-membership paths never reach an audit at all, because `fk_api_credential_membership
(workspace_id, created_by_id, membership_id) ... ON DELETE CASCADE` (V202) means a missing
`workspace_member` row has already cascaded every credential for that pair away, so
`deleteForMembership` finds nothing to delete or audit. `PublicApiCredentialMigrationArchTest`
pins that foreign key: narrowing it to `(workspace_id, created_by_id)` would make fresh membership
a second multi-head transaction and reintroduce the crossed-head deadlock.

#### Management paths

Issuance and revocation reach `api_credential` only through `WorkspaceService`'s locked member
authorization, which takes the account root `FOR SHARE`, the workspace root `FOR SHARE`, then the
organization root `FOR SHARE`, and only then the `workspace_member` row and the custom role. The
organization root is taken there because both paths request it again later — issuance through the
`fk_api_credential_organization` check on its `INSERT`, both through the audit's foreign-key
parents — and a transaction must never hold a credential row while waiting for a root.

`ApiCredentialService.recordSuccessfulUse` is a deliberate, tested exception: it takes one
credential row `FOR UPDATE` to stamp `last_used_at` and then acquires nothing at all, so it can
only wait, never close a cycle. Taxing the hottest path in the plane with two root locks buys no
ordering. If a future change adds an audit or any root acquisition there, it immediately becomes a
root-first path and must take both roots first.

#### Membership cleanup

Departure cleanup retains workspace S (reusing any stronger X), organization S, then recipient
memberships X before ownership-offer invalidation, capture/provider cleanup and tenant mutations.
The workspace read supplies the organization id without a lifecycle predicate. Removal and owner
leave already retain the recipient set before entering this helper under their existing workspace X
prefix. Fresh cleanup keeps its exact current membership read after workspace S; only absence
permits residual cleanup, which retains organization S before offer/provider/domain rows. Invite
authorization keeps its existing account X → workspace X → organization S → exact membership order.
A teardown holding workspace X can acquire organization X and commit while departure waits for
workspace S. The root-order drill observes that exact workspace-record wait.

`ApiCredentialLifecycleService.deleteForMembership` reuses the caller's retained workspace and
organization roots and adds no direct root-lock reads. It locks credential children X in ascending
credential id, deletes them and appends strict scoped audits, whose parent requests repeat retained
roots. Empty fresh-membership cleanup emits
no credential audit because the generation-bound foreign key already removed those credentials.
A tearing-down workspace still owns live credentials, which are deleted and audited normally.
The removal drill pauses at the exact credential read, verifies that the helper adds no root
acquisition, and observes an organization-X writer queued behind removal's retained organization
while removal completes its credential and membership audits.

InnoDB queues a new shared request behind a waiting exclusive request. Moving recipient M before
the shared helper's first organization S would add an M → O wait to non-owner leave/decline: an
invite in another workspace can retain O S and wait on that M while a queued O X prevents the
departure's O S. Keeping O before M avoids that new cycle. Fresh acceptance does not expand its
recipient's membership set into other workspaces: under its already-held O S, that new foreign-M
wait could cycle with an ordinary membership-first writer's late audit O S and a queued O X.
Credential cleanup reuses retained roots. Existing late audit roots and their #1582 cycles remain.

Removal, decline and leave retain the target account X before cleanup. Fresh cleanup keeps its
existing caller authorization and routed transaction boundary. Path-workspace routing remains
outside `WorkspaceMembershipRemovalTransaction`'s transaction; cleanup, membership deletion,
scoped audits and captured-reference cleanup commit or roll back together. Final control-plane
account deletion retains its complete membership set before its organization and audit tail.

#### Account erasure

Final control-plane erasure (`UserDeletionTransaction.delete`) runs at READ COMMITTED; reservation,
renewal and tenant-catalog erasure retain their existing isolation. A joined transaction with another
isolation is refused before membership discovery. This is necessary because a REPEATABLE READ
completeness scan can retain the next user-index gap: an unrelated invite can hold organization S
and wait to insert into that gap while erasure requests organization X, adding a new two-party cycle.
The neighboring-insert drill pins the absence of that cross-account gap fence.

Under deleting-account X, discover all workspace and organization memberships without status,
ownership or lifecycle predicates. The membership user foreign keys fence concurrent insertions:
an already-inserted, uncommitted membership holds account S, so deletion waits for its commit before
discovery. Add every membership workspace to the single ascending root union of owned, referenced,
offer and current scopes. Owned workspaces stay X; all others stay S, with X winning overlaps.
Lock exact deleting-user memberships X in user-major, workspace-minor order, using NOWAIT for owned
scopes. The existing owner scans revalidate those already-retained X locks without an upgrade or
new waiting acquisition. Each exact workspace-membership read forces the user-leading index,
matching mark-all and the cascade's secondary-index-before-primary-row order. Primary-first exact
reads followed by the user-index completeness scan would add a new two-party index-order cycle
with notification mark-all, which takes no account root. A cache-flushed current locking read checks the complete deleting-user
membership set before the owner-role phase; an unplanned scope aborts instead of extending the
acquired root union. Completeness scans force the baseline user-leading indexes to avoid locking
unrelated accounts on a small-table full scan. They do not substitute an optimizer-dependent
multirow scan for the canonical exact acquisitions.

Next acquire the single ascending organization-root union, owned X and other S, including every
organization membership. Retain owner rows, exact non-owner organization memberships X, and check
completeness through a current locking read before offer rows, credentials or heads. The account
cascade therefore meets only retained workspace and organization memberships. No S-to-X root or
membership upgrade is introduced, and owner contention keeps exact 3572/HY000 to 409 translation
without an in-transaction retry.

Credential cleanup acquires no roots itself. It locks exact children in `(workspace_id, id)` order,
deletes them all and returns their retained audits unemitted. The complete order is account X →
sorted workspace X/S union → exact deleting-user memberships → per-workspace owner NOWAIT snapshots,
overlays and permissions → sorted organization X/S union → organization memberships → offer rows →
sorted credential children and deletions → sorted audit emission/heads → account cascade.

The FIFO cycle comparison with phase 2 is explicit:

- **A:** account X is unchanged. The writer actor is distinct from the erased account; no new
  account acquisition is added after membership retention. Insertions and membership-authority
  writers naming the erased account are already fenced by its account root.
- **W:** deletion can now hold non-owned workspace S while waiting on that account's exact
  membership held by writer T1. If T3 queues workspace X, T1's late audit workspace S queues
  behind T3, producing deletion → T1 → T3 → deletion. This accepted three-party FIFO residual
  replaces main's direct two-party organization/head-to-membership cycle. Owned roots remain X
  and their owner scans remain NOWAIT. Other owners retain main's per-workspace snapshot/role
  sequence: moving all snapshots before roles could add a distinct cycle with a passkey operation
  holding an earlier overlay role and waiting on a later owner membership. Only deleting-user
  rows move ahead of all roles; passkey operations by that user are already fenced by account X.
- **O:** every workspace membership precedes organization acquisition, removing deletion's late
  cascade wait against a membership holder's organization audit. Organization-membership holders
  already retain the affected account or organization root. READ COMMITTED avoids introducing a
  neighboring-insert gap cycle before organization acquisition.
- **H:** complete memberships and all credential deletions precede sorted heads. The account
  cascade adds no membership wait after a head, removing the direct head-to-membership cycle;
  scope sorting and head modes are unchanged.

`AccountDeletionMembershipTailConcurrencyIntegrationTest` observes exact membership waits before
organization, credential and head acquisition, checks retained membership locks at the cascade
boundary, exercises both account-reference roles and inactive memberships, and validates committed
audit chains and assignment recovery. No new cycle beyond the accepted W FIFO residual is intended.

Because the erasure appends into one chain scope per credential workspace plus the scope of its own
`user.delete` row, all of those appends are emitted in one pass sorted by class 7's order rather than
in deletion order. Two concurrent erasures therefore request the same heads in the same sequence and
cannot cross. The `user.delete` audit uses the explicit tenant-context scope so the emitting call
site knows which head it will take. No runtime check enforces the sorting: converting a rare,
retryable deadlock into a deterministic abort of a compliance-critical erasure would be a worse
trade. `UserDeletionAuditOrderTest` captures the emitted scope sequence instead.

The price of that is a widened audit-write freeze: between its first credential audit and commit —
across the whole `app_user` cascade — the erasure holds an exclusive `audit_log_integrity_head` row
for every workspace in which the erased account created or revoked a credential, so audit writes in
each of those workspaces wait. This multi-head freeze was introduced before the membership-tail
prelocks and remains unchanged by them.

No residual reference count is needed: the reservation closes reference creation and each exact
locked credential is revalidated before deletion. Already-vanished rows remain skippable, including
when two erasures discover the same credential because one account revoked the other's credential.
Completeness belongs to the reservation and exact-row protocol, rather than a second reference-index
scan after deletion.

The credential-reference plan needs no re-check because the reservation already closes it. `reserve` commits in its own
transaction — `UserService.delete` runs the catalog fan-out with `Propagation.NOT_SUPPORTED` — and
its `app_user` update waits behind the `FOR SHARE` that `lockedMemberAuthorization` holds on that
row for every in-flight issuance and revocation, so each of those has committed before the
reservation becomes visible. Once it is visible, `lockedMemberAuthorization` refuses the flagged
account, so no further `created_by_id` or `revoked_by_id` reference can be born. The final transaction reads after the reservation committed, so `listByAccountReference` observes
every reference that will ever exist. A row that vanishes between that read and its `FOR UPDATE` was
deleted by another transaction — membership cleanup, a workspace or organization cascade, or
another account erasure — and needs no action here. Both account foreign keys (`ON DELETE CASCADE`
on the creator, `ON DELETE SET NULL` on the revoker) mean a stray row could not block the
`app_user` delete in any case.

Account erasure deletes every credential row the account created **or revoked**, so a revoked
credential created by somebody else is destroyed when its revoker is erased. The revoker foreign key
keeps `ON DELETE SET NULL` purely as the rollback backstop for an older binary that never ran the
service-level cleanup.

#### Ordering rules recorded here so round 17 does not reopen them

- **No path may take an `organization` root EXCLUSIVELY and then lock a `workspace_member` row.**
  Final account deletion has no carve-out: it retains every `workspace_member` row of the deleting
  account before its organization phase, then each organization root before that account's
  `org_member` row, and all of them before the credential and audit-head phases, so the `app_user`
  cascade meets only retained rows (#2156). `AccountDeletionMembershipTailConcurrencyIntegrationTest` proves that
  runtime footprint against real MySQL, including pending, suspended, inactive-scope and
  concurrently inserted memberships; the file-scoped grep cannot see it across services.
  Management takes the organization root before the membership row, while removal and owner leave
  already retain memberships before organization S. `OrganizationRootLockOrderArchTest` enforces
  the explicit exclusive-root rule; it does not prove safety against queued exclusive requesters
  and late shared roots across multiple transactions.
- The pre-existing `workspace_member` ordering inconsistencies remain tracked under #1582.
  Removal/owner leave acquire memberships before organization S; credential authorization retains
  organization S before its exact membership. Shared modes alone do not prove these paths safe
  against a queued organization X requester. Membership-first record mutations still acquire
  audit actor, workspace and organization S late. Final erasure now retains deleting-user
  memberships before organization/head acquisition. This change adds no early account/workspace/
  organization S prefix to those writers. The first-head `ensureHead` upsert and existing
  aggregate/reference cleanup inversions remain separate retryable races.
- Deal-collaborator replacement (#1793) takes every requested membership `FOR UPDATE` in ascending user-id order, then the deal row `FOR UPDATE`, then the collaborator rows, then its trailing audit. It replaced a collaborator-rows-first order (the clear, then the deal and membership rows `FOR SHARE` inside an `INSERT ... SELECT`) that inverted against `updateOwner`, deal deletion and offboarding. Through its trailing audit it stays in the #1582 class above, and its membership locks are now exclusive and held across the wait for the deal row: a replacement queued behind a stage `move`, which locks whole stage columns, blocks the listed members' own writes until it completes. A cap on the size of the collaborator list, still a follow-up, would bound that.
  - The replacement reads the collaborator rows `FOR UPDATE` through the primary key twice: before the clear, and again after the clear and any insert. That makes the audited and returned lists current reads rather than the snapshot that its unlocked existence check opened before any lock was held (#1942).
  - The first read takes the deal's clustered-row locks, and the gap lock on the next primary-key record, one statement before the clear. Its locks are a subset of the clear's, so it adds no wait-for edge. It does lengthen three existing deadlock windows:
    - **A member dropped while being offboarded.** Offboarding's `removeCollaboratorFromWorkspace` and `removeCollaboratorAnywhere` lock a member's secondary-index entry before its clustered row. The replacement locks the clustered rows first, and reaches the secondary entries only in the clear.
    - **Account erasure.** Erasure deletes the user's collaborator rows, with their `idx_deal_collaborator_user_only` gaps, before it clears deal ownership. That is the reverse of the replacement's deal row, then collaborator rows. The cycle needs the replacement's insert to wait on erasure's user-index gap, and it predates #1942.
    - **Replacements sharing a gap.** The gap before the next primary-key record is shared. For a deal with no collaborators it spans every collaborator-less deal up to the next row, so two deals receiving their first collaborators at once can deadlock on their inserts. In a pooled catalog the gap can end at another workspace's first row or at the supremum, so the other side can be a different tenant's replacement. That costs availability; it leaks nothing.
  - InnoDB rolls back the lighter transaction, usually but not always the replacement. No handler maps either side to a conflict, so the losing request gets a 500 and must be retried.

## Lifecycle, APPI requests, and organization SSO

Control writes share this root order:

1. Discover without locks.
2. Lock referenced `app_user` roots ascending.
3. Lock workspace or cleanup-tombstone roots ascending without joining the organization.
4. Lock the organization.
5. Lock exact `org_member` rows ascending by user id.
6. Lock operation/request rows.
7. Revalidate every root before side effects.

Linked APPI request create/update mutations perform preliminary workspace/person validation outside the write, then retain actor user, all previous/requested subject workspace roots ascending, and the shared organization lock while locking the requested tenant person.

Linked mutations reserve an independently owned JDBC-backed MyBatis tenant session before control locks. It obtains its connection from the routed data source after placement installation, without reusing a Spring-bound control connection retained by `NOT_SUPPORTED`. Preliminary person proof is nonlocking on the owned session; after control roots are held, `FOR SHARE` reads current person state and retains that lock through the control commit. Roll back the reserved transaction and restore its original auto-commit mode before release. Restore auto-commit only after successful rollback, so cleanup cannot commit an unrolled-back reservation. The admitted mutation can temporarily hold three pooled connections when its outer synchronization retains a control session: that session, the owned tenant reservation, and the control-root transaction. The existing fair admission permit bounds concurrent reservations.

Updates lock the exact organization-scoped request after roots and compare persisted state to the preliminary snapshot before deriving/writing the diff.

Disclosure assembly is a synchronous CSRF-protected POST (#2004, #1733); it does not use the
linked-mutation person-lock path. `DataSubjectRequestService` suspends ambient transactions:

1. Control preparation opens its own read-only transaction, locks the matching
   `org_member(orgId, actorId)` rows `FOR SHARE`, validates step-up, then reads the request,
   workspaces and audit trail without additional locks. The membership lock is released before
   tenant assembly.
2. Admission allows `MAX_CONCURRENT_DISCLOSURES = 2` per instance and
   `MAX_CONCURRENT_DISCLOSURES_PER_ORGANIZATION = 1`. The synchronized admission map contains only
   admitted organizations, so it has at most two keys; requests never wait for admission. Excess requests and pools
   configured below two connections receive the existing 503 before opening a tenant snapshot.
   The effective global capacity is the smaller of two and the configured pool size divided by
   the per-disclosure two-connection budget: pools of two or three admit one, leaving its audit
   connection available instead of allowing snapshots to exhaust the pool before either audit.
   For these single-slot pools, capacity refusals from other organizations retain their first
   refusal timestamps in a bounded insertion-ordered set
   (`MAX_REFUSED_DISCLOSURE_ORGANIZATIONS = 128`); saturation evicts the oldest entry to record a
   newcomer, while repeat refusals keep their position. When the holder releases, including on
   deadline expiry, the oldest retained organization receives the freed slot exclusively for
   `DISCLOSURE_TURNOVER_RESERVATION = 30 seconds`. Every other organization, including the former
   holder, receives the existing 503 before a snapshot. The reservation is consumed by admission
   or lapses after 30 seconds; other recorded refusals are cleared at turnover so stale requests
   cannot chain idle reservations. Pools of at least four retain two slots without reservations.
   `DataSubjectDisclosurePlacementRead` completes workspace/organization/catalog discovery in a
   separately proxied short control read, before installing the subject route and opening the
   snapshot. Its MyBatis session and connection are released even when the outer `NOT_SUPPORTED`
   service has empty transaction synchronization, including different-active-workspace and
   unresolved callers. No placement lookup resource spans the snapshot.
   One explicitly read-only `REPEATABLE_READ` tenant transaction retains the subject route and
   connection through both serialization passes and synchronous delivery. The non-note envelope,
   labels, workspace names, audit metadata and generated timestamp are fixed once. Pass one
   discards bytes while counting and hashing the complete compact UTF-8 JSON and its notes array.
   Note ID keyset pages and exact-ID body pages are capped at 100, do not use second-level caches,
   and flush the MyBatis session cache before every query so previous bodies cannot accumulate.
3. `DataSubjectDisclosureAuditAccess` suspends the tenant transaction with `NOT_SUPPORTED`
   **before** entering `TenantWorkScope.unrouted`. It calls
   `AuditService.recordStrictIndependentScoped`, whose `REQUIRES_NEW` append takes actor
   `app_user FOR SHARE`, target `organization FOR SHARE`, then the organization integrity head
   exclusively (`ensureHead` upsert then `lockHead FOR UPDATE`). The audit INSERT and conditional
   head advance commit independently. Organization events have null workspace scope. The control
   catalog override is restored before the same physical tenant connection/transaction resumes.
4. Only after that commit does pass two open the servlet target. It writes on the request thread
   inside the retained snapshot and route. Both full-document and notes count/digest/byte-length
   results must match pass one before the writer releases the final document byte. The snapshot
   then ends and the caller context is restored on every exit. Pass two has an absolute deadline
   from `connex.data-subject-disclosure.delivery-timeout-ms`
   (`CONNEX_DSR_DISCLOSURE_DELIVERY_TIMEOUT_MS`, default `120000`). The writer checks before/after
   page queries, before page writes and at most every 8 KiB of output, including before/after
   blocking writes/flushes. The transaction-bound connection holder supplies the remaining budget
   to subsequent MyBatis statements (JDBC query timeouts have second granularity).
5. One deadline task per admitted delivery may only close its captured Tomcat socket; it never
   serializes, queries, changes tenant context or completes the transaction off-thread. Expiry and
   disarm share a monitor, so cancellation finishes before request/connection reuse; cancelled tasks
   are removed from the timer queue. Tomcat NIO's explicit `server.tomcat.connection-timeout`
   (`CONNEX_TOMCAT_CONNECTION_TIMEOUT`, default `30s`) initializes the native blocking write timeout.
   Closing the socket prevents trickling progress from resetting it. A blocked native write may
   wait one such timeout before unwinding, giving a delivery bound of deadline plus one connector
   write timeout (150 seconds with defaults), followed by normal cleanup. The synchronous failure
   rolls back the read, invalidates notes, restores route/context and releases organization/global
   admission. A live server lacking this HTTP/1.1 cancellation binding fails closed before output.

No preparation, person, workspace or integrity-head lock is carried by the tenant read into the
control audit. Its non-locking reads preserve actor → organization → integrity-head lock order.
The retained snapshot occupies one pooled connection while the audit needs another: budget at
least two connections per admitted disclosure, four for both slots, plus capacity for ordinary
traffic. Preparation/preflight precede the delivery deadline. The snapshot remains open during a
bounded slow download, retaining a request thread and InnoDB history;
other disclosure sections remain materialized and one note page can still be large.

Authority, step-up, request linkage or lifecycle can still change between preparation and audit;
no revalidation or serialization is added. The first-head initialization deadlock documented above
remains possible. Preparation/preflight failures prevent the success audit. Audit exceptions return
503 without disclosure bytes and roll back INSERT and head advance together. A committed audit
records complete assembly and an authorized release attempt, not client receipt, and survives a
later stream failure. Jackson `AUTO_CLOSE_CONTENT` and `AUTO_CLOSE_TARGET` are disabled for this
writer. After response commitment, failures leave truncated JSON with no error suffix or closing
delimiters; before commitment the buffer is cleared and the existing 503 shape is used. Reject
incomplete framing, invalid JSON, or integrity-header mismatches and retry explicitly. Every retry
assembles/audits again; transaction rollback failures require a fresh request/transaction. Wait
for an active same-organization download to finish/expire after overload. With a single-slot pool,
retry a refused organization's request during its turnover reservation; a non-reserved organization
must wait for that reservation to be used or its 30-second window to lapse. Discard a deadline-aborted
download and retry on a connection able to complete within the limit; there is no continuation.

SSO returning/existing-user paths discover non-locking then lock user, stored workspace when applicable, and organization before revalidation. New JIT paths without a user lock stored workspace and organization before revalidating absence/inserting.

### Organization membership (#1534)

`OrgMemberService.setMember`, and `setMemberByEmail` that delegates to it, run at
`READ COMMITTED`. They lock in this order:
1. actor and target `app_user` rows, shared and ascending;
2. the organization exclusively, and only while it is active (`lockActiveById`);
3. the owner rows;
4. the target's own `org_member` row.

Before any of these locks, one non-locking, organization-scoped read
(`isEligibleForMembership` / `findEligibleUserIdByEmail`) refuses a target that is neither a member
nor a tenant. Unknown accounts and outsiders therefore cost the same and are refused alike. The
answer is revalidated under the locks: when the target's own row is absent, the tenancy check that
a new membership needs is a plain read after these locks. It is current because:
- every writer that removes or deactivates a workspace membership (removal, leave, decline, account
  deletion) takes the target's `app_user` row exclusively, so it has committed before the read, or
  waits for this transaction;
- writers that add an active membership can only make a racing add refuse spuriously. SSO JIT
  provisioning takes no account lock.

No new lock is taken, so the account → workspace → organization order is unchanged.

`setMember` checks confinement the same way as the workspace role edits above: it reads the
baseline after step 4 and before the upsert, and checks it after the upsert and before the audit.

`OrgMemberService.leave` takes the same order for the caller alone:
1. the caller's `app_user` row, shared;
2. the organization exclusively while active;
3. the owner rows;
4. the caller's own row.

It then deletes the row and appends a strict scoped audit in the same transaction. Two owners leaving
at once, or one leaving while a co-owner's account is deleted, serialize on the organization row.
Account deletion's sole-owner guard takes that row exclusively too, so an organization always keeps
an owner.

## Legacy rules and canonical workflows

The legacy `/api/rules` write surface is a compatibility projection over canonical workflows, not an independent aggregate.

- Create transactionally creates the paired workflow and immutable version 1.
- Update locks the complete pair through the hierarchy above, preserves active run-as identity, creates no version for semantic no-op/enabled-only changes, and creates one deterministic version for a semantic change.
- Delete archives/disables rather than deleting historical versions/runs/steps/executions/links.
- Restore clears archive state while leaving runtime surfaces disabled.
- Startup backfill follows non-locking sorted discovery → principal roots → workspace root exclusively → workflows → active versions → rules → final locking enumeration proving completeness.

Canonical execution pins an immutable `workflow_version` per run. Node effects/checkpoints use the established `REQUIRES_NEW`, `READ_COMMITTED` transaction model. Resume verifies the pinned definition and revalidates membership, record visibility/restrictions, and action permission before mutation. Disable/archive stops new claims but does not silently cancel an already claimed run.

`runtime_owner` and the opposite runtime ledger are checked under the same workflow root lock. Preserve the outbox/lease/wait/attempt/cancellation architecture; do not reconstruct traversal from flattened metadata.

Durable outbox delivery performs non-locking discovery of the outbox target, workflow, and pinned
version, then locks the pinned actor's authorization roots before any runtime or tenant-record root:
actor user → workspace → membership → custom role/permissions → workflow runtime workspace gate →
outbox row → workflow row. The locked principal and exact permission snapshot are passed through the
legacy compatibility execution path; `send_message` enrollment consumes that snapshot and must not
reacquire authorization after the outbox/workflow locks. This order is shared with canonical step
execution and prevents offboarding's user-before-workflow order from forming a cycle.

## Task board mutations

Task creation/full update lock the requested active membership first. Mutations that can change board positions run at `READ_COMMITTED` and acquire the exact tenant-plane `task_board_lock` workspace root through the atomic insert-or-update mapper statement.

- Create: membership → board root → linked people → exact visibility grants → insert.
- Full update: membership → board root → exact task rows → linked people → exact visibility grants.
- Completion/deletion/movement: board root → exact task rows.
- Due-date-only reschedule: exact task only; no board root.

Assistant completion retains the board through `TaskService.lockBoardForUpdate()` before taking
its exact task through `TaskService.lockTaskForUpdate(int)`; assistant rescheduling takes only
that exact row. `getTaskByIdForUpdate` flushes the MyBatis first-level cache, so a target gate
following contention cannot reuse a pre-lock snapshot.

Task-history imports retain their authorization and duplicate-decision roots first, then acquire
that same board root before locking resolved people in ascending id order. Assistant task creation
also acquires the board before its processable-record target and restriction fence. These callers
must never enter task creation while holding a person lock acquired before the board. Existing
task rows precede people on updates; imports and creates insert new task rows after people while
holding the board mutex. Person visibility/processing locks use `FOR SHARE` for task links,
history imports, and activity updates, which do not mutate the person. The exact share grant is
retained through commit and permissions are rechecked after contention. Activity creation keeps
`FOR UPDATE` because recording the first-response timestamp can update the person; taking a
shared lock first would permit a concurrent lock-upgrade cycle.

After the board root is held, discover workspace task ids without locks, add the requested root, Java-sort the union, and lock exact `(workspace_id,id)` rows individually. Skip siblings that vanished before lock; fail closed if the requested root vanished. Derive ordering only from locked rows.

Do not replace this with an ordered range `FOR UPDATE` scan or return to non-locking board reads after waiting for exact locks. Compact affected status columns to contiguous zero-based positions before commit.

## AI assistant chat

Assistant-chat mutations run at `READ_COMMITTED`.

Ordinary turn/join/leave/sharing/presence-authorizing mutations:

1. Lock caller's exact active `workspace_member`.
2. Lock exact `(workspace_id,id)` `ai_chat_session` root `FOR UPDATE`.
3. Authorize from those locked rows.

Invitation/participant-removal paths lock caller/target active memberships ascending by user id before the session root; never acquire another membership after the session root.

The session row is the per-session mutex. V232 allocates every ordinary or mission message from its durable `message_seq_high_water`: lock the session, reject overflow, advance the counter, insert, and commit together. Never recompute from surviving messages; v1 privacy cleanup retains the counter and immutable first-mission taint boundary, while v2 erasure deletes the affected session. Summary updates and request replays do not allocate a sequence.

Account erasure retains all session roots in the routed catalog with one locking read ordered by
`(workspace_id, id)`, covering sessions created by the account and sessions containing its message,
tool-call, turn, or proposal-set requester provenance, including other members' shared sessions. The catalog erasure
transaction uses `READ_COMMITTED` so child-reference discovery stays fresh after authority-lock
waits. This chat-root phase precedes provider activity deletion and every provenance update;
the same roots remain held through domain cleanup and trailing mission privacy invalidation.

### Global privacy, restriction and audit order

Every transaction that combines these scoped resources uses this single acquisition order:

1. Content roots: exact session → run (missions) → all Java-sorted action/proposal-parent rows →
   all ordered canonical/tool-child rows → turn → segment.
   The session mutex serializes message/summary children and child-set discovery; their writes
   and private inserts under that retained mutex do not acquire another independent root.
2. Domain record/board roots and their enclosing duplicate mutex, in the domain's existing
   canonical order; attachment URL references/exact rows and deletion queues with subordinate receipts.
   Brief schedules are domain roots: delivery releases the pending schedule and cleanup clears its
   session projections before acquiring organization S, even when pending and delivered sessions differ.
   Delegated mutations must finish these root acquisitions too.
3. Local restriction fences sorted by workspace stripe, then organization publication fences.
   AI output-cache rows follow the retained restriction fence on both save and deletion paths;
   restriction completes its ordinary domain/identity work before fencing and deleting cached outputs.
4. Organization privacy fence S/X, ascending workspace barriers S/X, organization operation/placement
   rows, then exact tenant privacy-work rows.
5. Workspace audit-integrity head (and the same trailing-head rule for scoped organization audits).
6. Run lease rows, including release, takeover and settlement.

A path may omit resources. Updates to already locked roots are not new acquisitions. Private
child inserts and subordinate identity maintenance serialized by the retained duplicate mutex
are not fresh record/board roots. Transient epoch reads release their local lock immediately;
only a retained fence participates in later lock dependencies. Reentry
must use the exact retained lock without an upgrade; never use a trailing shared-lock request as
proof that an earlier lock was retained. No new earlier-ranked root may be acquired after a
later-ranked resource in this scoped hierarchy.

Authority/account/workspace/organization and audit foreign-key parents retain their existing owning
contracts: the assistant principal snapshot precedes chat roots, while the duplicate-decision
helper and audit appender retain their established shared-parent acquisitions. Those authority
classes are not a new rank in this hierarchy. Organization privacy authority is distinct from the
organization authorization row. Domain-specific prerequisite ordering remains unchanged.

In particular, **an audit-head or lease holder must never subsequently request organization or
workspace privacy authority, or a local restriction/publication fence**. Barrier X initialization, invalidation and completion must never acquire
new session/domain roots after the barrier. Thus a queued X request cannot sit between a holder's
initial S acquisition and a later S request while that holder retains a resource the first reader
needs. Combined initialization and completion take organization authority before workspace/work locks.
Independent v1 barrier installation takes only workspace X; v1 completion also retains organization S; v2 verification uses workspace S and exact
operation/work rows. Invalidation takes domain roots and local write fences before organization X,
and never locks chat roots. Cleanup takes content/storage roots before organization/workspace S. Do not combine those transactions or upgrade a batch's S to X.

Write execution and approval retain original organization authority and workspace S after the local restriction fence at the canonical
`MutationAdmission.beforeMutation` checkpoint, after **all** delegate record/board roots and before its
first audited mutation or successful no-op. Earlier epoch checks are non-retaining prechecks.
Approval and undo recheck their original expiry after fence/barrier contention; waits never extend a deadline. Admission runs once for a multi-effect
write, so subsequent effects cannot reacquire S behind an initializer. Undo uses the same checkpoint
inside the inverse's locked fingerprint predicate, before deletion/audit, and refuses a delegate
that skips admission. Replays and discard retain privacy directly without domain acquisition.
No successful write decision acquires either fence after the audited delegate returns.

### Durable record-review missions (V232/V235)

The complete privacy and rollout contract is [AI_MISSIONS.md](AI_MISSIONS.md). Mission claim, resume and publication use `READ_COMMITTED`: authorization roots in existing order, session X, run X, existing tool X if any, turn X, segment X, domain roots if applicable, local restriction fence, organization S, workspace barrier S, audit head if needed, lease last. Slice 1 admits read tools only. Root re-reads after locks flush MyBatis's transaction cache. Every execution write compares the exact active attempt and execution generation; a stale callback cannot settle or release its successor.

V235 retains this order for every runtime-2 transaction. At V237, every fence → barrier S entry
below includes organization S between the local fence and workspace barrier. Workspace-X privacy
work entries describe v1; v2 uses organization authority, workspace S and operation/work rows as
specified in [organization assistant privacy](#organization-assistant-privacy-v236v237).

| Transaction | Ordered atomic contents and ending |
|---|---|
| Create | Authorization → session X; private run/goal/taint inserts and initialized continuation; restriction fence → barrier S. Generated run ID binds initial checkpoint before commit. |
| Claim | Authorization → session X → run X; strict codec/allowance checks; private fresh turn/segment and bounded root reservation; fence → barrier S → lease acquisition LAST. |
| Bookkeeping replay | Authorization → session X → run X → exact existing tool X → turn X → segment X; compare screened canonical arguments and current identity; fence → barrier S. No lease operation. |
| Plan commit | Same roots and existing-tool-before-turn order; result-size admission precedes mutation; screened call/result plus plan, immutable fact ledger, plan/checkpoint revisions and state version commit together; fence → barrier S. No lease operation. |
| Dispatch reservation / usage | Authorization → session X → run X → turn X → segment X; exact ordinal/generation and uncertainty predicates; fence → barrier S. Overrun settles truthful usage and releases the changed attempt lease LAST; normal callbacks perform no lease operation. Definite 400/422 protocol rejection uses this same exact-dispatch settlement with zero tokens before any fallback reservation; ambiguous failures retain uncertainty. |
| Checkpoint | Authorization → session X → run X → existing tool X if any → turn X → segment X; screened replay, checkpoint/memory, cursor +1, accounting, terminal attempt/turn, queued state and generation commit together; fence → barrier S → exact lease release LAST. |
| Final report | Existing report settlement order, with known/uncertain accounting and unchanged continuation; fence → barrier S → lease release LAST. |
| Pause/cancel/expiry | Existing owner/maintenance roots → session X → run X → active turn/segment X; preserve continuation and original caps/deadlines, settle allowance and invalidate generation; changed expected attempt lease release LAST. Explicit pause over interrupted updates member reason/version; member pause alone is idempotent. |
| Orphan settlement | Maintenance session X → run X → turn X → segment X; terminal attempt/accounting and paused/interrupted state; expected lease takeover/release LAST. No reconstruction, provider work or requeue. |
| Interrupted requeue | Discovery excludes exhausted next-segment attempts before its page limit. Fresh authorization → session X → run X; revalidate stopped discovery version/generation, compatibility, allowance, attempts and privacy; queued state/version; fence → barrier S. No lease operation or provider call. |
| Readiness backoff | Maintenance session X → run X; exact discovered state/version, original deadline unchanged, next-attempt +30 seconds. Interrupted backoff preserves interruption reason. No lease operation. |
| Continuation purge/residual repair | Existing content session/run/tool/turn/segment order → barrier S → exact work X → audit if required → lease release LAST; clear all continuation columns without decoding, including purged residue. |
| Purge completion | Barrier X → exact work X → fresh unconditional nonlocking residual counts. No content roots, provider work or leases. |

Plan-only checkpoint-revision changes do not advance the logical segment cursor. Only a
successful checkpoint increments it exactly once. All new mutation transactions use
READ COMMITTED; root locking/current-state mapper reads flush local cache. Discover keys
outside mutation transactions and revalidate under their roots. Private inserts beneath
the retained session/run mutex add no independent root. Deadlock retries, when used, start
fresh bounded transactions; no REQUIRES_NEW helper nests while mission locks are retained.
Provider/network work is outside transactions. Publications occur only after commit.

Restriction invalidation has the opposite **authority direction**, not an inverted lock sequence: under its domain roots it retains sorted local write fences, takes barrier X, then work X, advances generation and records bounded cleanup work in the restriction transaction. It never takes mission/session roots. Cleanup discovers IDs without locks, then locks session/run/tool/turn/segment roots, barrier S and work X. Deletion and expected work-revision advancement commit together. Final verification takes barrier X then work X, counts all enrolled residuals without discovery-watermark limits, and unblocks only after all incomplete work and all current-cutoff residuals are gone. Privacy cleanup never requires the departed owner's AI permission or an available provider.

Ordinary admission retains actor/workspace/organization audit parents before membership and session roots, then the local restriction read fence and privacy barrier S, then appends its administrative-read audit. The workspace audit head must never precede either the restriction fence or privacy barrier. Turn polling joins one READ COMMITTED transaction through the complete DTO projection: session/turn roots, privacy barrier S, then fresh provenance validation and content projection. Lazy expiry retains its restriction fence before that barrier and releases the lease last.

Admission discovers ordinary active turn IDs without locks under the retained session mutex,
then locks every discovered turn by exact key in ascending Java order before expiring any of
them. A locking status-range scan can encounter another session's retained turn before filtering
it, blocking admission before its privacy barrier; this is especially visible against an immediate
write, which retains its originating turn while approval does not. Complete all exact turn locks
before expiry can acquire a restriction fence or privacy barrier.

Ordinary derived writers carry the admission token `(workspace, session, privacy generation, nullable original taint sequence)` and retain barrier S through commit. Do not replace its generation when persisting an old result. Null-to-nonnull taint changes invalidate pre-mission derived jobs. Terminal maintenance may clear stale content without execution authority; retaining content requires current provenance. Ordinary expiry and orphan settlement now finish their content writes before releasing/taking over leases. No content root may be acquired afterward.

### AI identifier key reconciliation

Queue-time identifier resolution remains inside the caller-membership → exact chat-session
critical section above. Current canonical keys reduce returned rows and round trips; the SQL
containment prefilter still examines the visible corpus. Stale rows deliberately keep legacy
lookup cost until reconciliation finishes. No new queue locks or network work are introduced.

`AiIdentifierKeyBackfillRunner` runs on a dedicated single-thread scheduler with a fixed delay,
outside startup readiness. An invocation resumes at most four pages and installs `TenantWorkScope` placement before
each independent transaction. A changed organization/catalog invalidates the saved cursor instead
of reusing it in a different data plane. Each `AiIdentifierKeyBackfillTransaction` page
uses `READ_COMMITTED` with a ten-second transaction deadline, reads a non-locking owned-id window
limited to 100 rows before freshness/processing checks, then CAS-updates at most 100 exact parent
rows of **one table**, in ascending id order. It touches only derived aliases/snapshot/version and
explicitly retains `updated_at`; it publishes no events and reads/writes no identity groups,
duplicate-decision roots, share grants or other aggregates. Consequently it takes no domain mutex
and cannot acquire an ancestor after a parent lock. It never mixes person/company/deal locks in a
transaction or uses a range `FOR UPDATE` scan.

The update rechecks owning workspace, original name byte-for-byte, archive/processing state
and bundle freshness after contention. A rename, deletion, restriction or competing reconciliation
wins safely; skipped rows remain stale. Each CAS has a five-second query timeout: contention
rolls back the page and retries on a later traversal. These timeouts apply to backfill statements
and transactions, without changing pooled connection session settings. The dedicated scheduler
keeps such waits away from other scheduled maintenance; a real scheduled-job test holds a CAS lock
and observes an unrelated scheduled job executing before the query timeout. Every examined owned
row advances the cursor, including current, archived, restricted and nameless rows. Per-page logs
and workspace totals accumulate pending observations without a per-page full-corpus count. The
traversal wraps to revisit races behind the cursor; restart also safely begins from zero. Restored
archived/restricted rows retain compatibility lookup until a current writer or a later sweep repairs
them. Oversized or malformed-source aliases have a processed unkeyed marker and remain on fallback
without repeated backfill attempts. Committed two-connection tests observe a CAS waiting behind rename/restriction
writes, and a separate connection reacquiring a previous page's locks before the next page starts.

CSV/import and volume-seeder name inserts are split by row/payload budgets inside the same enclosing
transaction, preserving the established aggregate lock order and generated-id ordering. Chunking
adds no commit boundary, root acquisition, duplicate decision or event publication.

### Assistant write tools

Task targets declare `TASK_ROW` and `TARGET_FINGERPRINT` together. Completion declares the
`task_board_lock` root as well: authority → session → tool call → board root → exact task.
Rescheduling declares no board: authority → session → tool call → exact task. The task service
retains its canonical completion hierarchy and assignee-only rule when the delegate re-enters.
The framework's owner-scope gate reads the task and reauthorizes its linked person and deal
through their scoped domain getters after the task lock, before the delegate writes.

Task proposal freshness compares `targetVersion`, the SHA-256 from
`TaskService.assistantStateVersion`, with the locked canonical row. It includes id, description,
completed, status, due date, assignee, person and deal; it excludes position and updatedAt.
Completing a sibling can compact positions without invalidating the proposal, but reassignment
invalidates it. Card snapshots use the same hash before reference hydration redacts display text.
Canonical snapshot reads flush cached hydrated rows, and an idempotent proposal replay retains
its original stored fingerprint without taking a new baseline.

Every mutating assistant tool decision — immediate execution, approval, rejection, undo — runs at
`READ_COMMITTED` in `AiAssistantWriteToolService`, the write framework. A write tool is one
`AiAssistantWriteTool` bean, and every catalog write tool has exactly one —
`AiAssistantWriteToolRegistry` refuses to start otherwise — so the order below is the only path a
write takes: the framework keeps no per-tool arm and no fallback lock. The tool
declares which locks it needs — `Lock(taskBoard, target)` — and the framework takes them; **a write
tool takes no lock of its own**, reaches no mapper, and never re-resolves a member.
`AiAssistantWriteToolSpiArchTest` backs all three structurally by holding a tool's injected
dependencies to an explicit allowlist of domain services and helpers — no mapper, no
`WorkspaceService`, no member or permission source — and lexically by refusing locking-method,
permission-read and lifecycle-mutator names in tool source; a new dependency is a reviewed edit to
that allowlist. Before any lock, an approval resolves the principals the write will name
(`AiAssistantWriteTool.principals`, against the member directory the framework hands it — today
only `assign_owner`'s owner), and those same objects reach the write: `Execution` carries no member
lookup, so `assign_owner` writes exactly the owner id locked at step 1. A confirm-tier proposal
also pins, when it is prepared in the turn, what its principals and its value resolved to: the
`principals` ids and the `resolution` field and id are stored as additive siblings of the stored
arguments, and a name that resolves to no single row is refused before any proposal is stored. A
step that reaches the write again after its proposal was stored under the step's key resolves
nothing: the loop first reads that stored row, without a lock, and the call carries its pins
verbatim, so a rename or an offboarding in between cannot turn the replay into a reused-key or
unresolved refusal, and only what the model asked for is compared with the stored row. The
approval compares its own pre-lock resolution with the pins — the principals before step 1, on the
proposal read unlocked, so a drifted member's authorization rows are never locked and its own 403
never answers first, and again once step 3 has read the locked proposal; the value just before
step 5, before any board or target lock — and refuses any difference with `Assistant proposal
target changed`, so a rename or an offboarding between the proposal and the approval can never hand
a record to a member, move a deal to a stage, or remove a tag, that the card did not name — a tag
deleted and re-created under the reviewed name is another row, and `remove_tag` refuses it. The comparison reads
no row and takes no lock. Tag operations additionally lock the resolved exact definition `FOR SHARE` before the target,
revalidate its identity under that lock, and lock existing associations through association-only
`FOR UPDATE OF` reads. `remove_tag` retains its stored identity pin; scalar `add_tag` remains AUTO
without introducing a stored pin.
The card resolves the stored name exactly as the approval does and names only the pinned row, so a pinned member or stage merely renamed since the proposal is unresolved on
the card and refused at approval with the same 404 as before pinning, rather than labelled by its
id.

The pins protect only the proposals that carry them, on a build that reads them:

- A proposal stored before pinning carries neither sibling and is approved exactly as before.
  It keeps resolving its names at approval while its original seventy-two-hour review window
  remains open; the #1865 drift remains possible for that row only within that window.
- Earlier binaries ignore the pins and do not enforce the review deadline. V231 therefore raises
  the semantic rollback floor: after the expiry cutover, only a binary carrying V231 and the
  complete expiry implementation may serve. Staging checks the pinned target's floor before
  automatic rollback and interrupted-deployment recovery; an incompatible previous JAR forces
  forward recovery. Drain older instances before the cutover, as documented in
  [UPGRADING.md](../UPGRADING.md#v231-assistant-proposal-review-expiry).

The framework acquires its locks in exactly this order:

1. **Locked authorization roots**, through one `WorkspaceService.lockAndRequirePermissionsSnapshot`
   covering the actor, who must hold `AI_USE`, and, on an approval whose write names principals
   (today only `assign_owner`'s owner), each named principal, who carries no requirement. That call takes the user roots `FOR SHARE` ascending by user id, then the
   active workspace root `FOR SHARE`, then the memberships `FOR UPDATE` ascending by user id, then the
   custom-role row and its `workspace_role_permission` rows `FOR UPDATE` ascending by role id. Roles
   are locked only for users with a non-empty requirement, so the actor's custom role is locked and
   the owner's never is. An owner whose account deletion is reserved is refused here with `User N is
   not a member of this workspace` even while its membership row is still active.
2. Exact `(workspace_id,id)` `ai_chat_session` root `FOR UPDATE`.
3. Exact `ai_chat_tool_call` row `FOR UPDATE`.
4. Immediate tier only: exact `ai_chat_turn` row `FOR UPDATE`. The real order is session → tool call
   → turn, not session → turn → tool call.
5. Immediate execution and approval only: the `task_board_lock` workspace root, when the tool
   declares it (task creation). The value the write moves its target to — a deal's new stage — is
   resolved by non-locking reads just before this step, because the stage-change lock needs it.
6. For `DUPLICATE_DECISION_RECORD_UPDATE` (only person/company edits),
   `DuplicateDecisionLockService.lockCurrentOrganization()` re-enters the step-1 roots, then retains
   the organization `FOR SHARE` and `organization_duplicate_decision_lock` mutex. Only then take
   the target record row. `PersonService.update` and `CompanyService.updateCompany` re-enter the
   mutex; taking the target first would invert the interactive-edit hierarchy below. No path takes
   this mutex and then an `ai_chat_*` row. The reciprocal interactive-person-edit/field-approval
   integration test pauses the editor after acquiring the mutex, observes the other actor's
   approval waiting on that mutex, and proves the editor commits while approval refuses stale.
7. Immediate execution and approval only: the target record row — a person `FOR SHARE` when the
   tool only links to it (task creation on a person), otherwise the person, company or deal
   `FOR UPDATE`, or the ordered stage-change rows toward the resolved stage for a deal stage move.
   An `assign_owner` write's `updateOwner` then re-acquires the owner's membership, if it names
   one, and this row, all already held.
   `TargetLock.NONE` skips this step entirely: `create_person` and `create_deal` use the company
   only as a visible anchor. Workspace-targeted `create_company` and `create_report` also skip
   a target lock. Company creation uses the same guided hierarchy; report creation retains the
   delegate's workspace report-definition locks after the assistant locks and the framework's
   REPORT_CREATE/REPORT_READ assertion. Guided record creation re-enters the authority roots, then takes the
   organization duplicate mutex, identity groups and template set/reference hierarchy in the
   existing guided-create order. No path takes that mutex before an `ai_chat_*` row.

Before the target lock, tag tools declare an exact tag-definition shared lock through the SPI.
After the target lock, they declare existing association locks; locking re-reads flush MyBatis's
local cache. Deal owner assignment retains existing collaborator rows before its first write.
No new authority root is acquired after domain locks; revalidation uses the held permission snapshot.

All sixteen write delegates carry `MutationAdmission` to their final pre-mutation boundary. The
callback is bound to the originating decision transaction and original creation deadline. Its first
call retains the restriction fence and privacy barrier after all record/board roots, then
reads the injected clock after contention; at or after seventy-two hours it throws
and rolls back. An admitted action may finish and commit afterward. Successful no-ops also admit.
The framework verifies admission before persisting any execution receipt. Coordination upserts
remain transactional and roll back on refusal. There is no outer batch transaction or proposal parent. The privacy barrier remains shared through commit.

| Delegate | Locks and final checkpoint |
|---|---|
| Owner assignment | Principal → target → existing deal collaborators; validate locked target, admit immediately before owner write. |
| Add/remove tag | Exact definition shared → target exclusive → existing associations exclusive; admit before association mutation or no-op. |
| Complete task | Board mutex → Java-sorted exact task rows; validate assignee/state, admit before completion or completed no-op. |
| Reschedule task | Exact task only; admit after existence validation before due-date write. |
| Response due | Owned person → open lifecycle-pass IDs, discovered under the person root and locked by exact workspace/person/ID ascending; admit after assessability validation, before clock write/no-op. Hours start from the admitted instant. |
| Record fields | Person/company duplicate hierarchy → target → canonical validation; admit before first record write. Deal value/date writes share one admission for the atomic action. |
| Draft document | Deal root → snapshot/template/version reads; admit before `insertNextVersion`, with no template-content pin or new lock. |
| Stage change | Canonical ordered stage/deal board locks and `LockedStageChange` validation; admit before first stage/outcome/position write. |
| Activity creation | Framework target → linked person exclusive and required visibility grant → permission validation; admit before insert. Failure audits defer until the decision transaction completes, so they cannot re-lock authority roots behind a writer waiting on that transaction. |
| Task creation | Assignee membership → board → linked person shared and exact grant, or held deal → permission validation; admit before insert. |
| Note creation | Framework target → linked-record validation; admit before insert. |
| Guided person/company/deal creation | Canonical duplicate/identity → template set/root → sorted custom-field/tag/relationship locks (deal stage before pipeline); admit after final validation immediately before the core insert, not before augmentation preparation. |
| Report creation | Workspace report definitions exclusive → count validation; admit before definition insert despite `TargetLock.NONE`. |

Discard stops at session/tool-call locks, validates requester/trust/CONFIRM/pending state, then reads
the clock immediately before the conditional rejection write. It never resolves a target or checks
domain edit permission. Already-rejected retries remain idempotent. Undo retains its independent
inverse permissions and ten-minute window, with no proposal review check. Its locked inverse
fingerprint guard retains restriction then privacy fences before deletion and audit.

Prepare-time duplicate checks are advisory reads with no mutex and no proof issuance. They hold
no domain lock across a model call. Person preparation includes the selected template's effective
email/phone defaults only in transient preflight state. Company preparation likewise checks its
effective website and template phone default from the same selected preset snapshot. Approval's duplicate recheck under the mutex is authoritative,
and the pinned template version/set revision is validated by guided creation both preliminarily
and under the template hierarchy. A new candidate or changed template refuses without a create.
Deal creation submits the proposal's pinned pipeline id and carries its exact reviewed stage name
through server-only augmentation. After locking the stage, canonical creation compares that name
and refuses drift before insertion; stage-to-pipeline validation retains the pinned pipeline under
the existing stage-before-pipeline hierarchy. No new lock or lock-order edge is introduced.

After step 7 the framework checks the restriction epoch without retaining its fence; for
confirm-tier tools it compares their declared freshness
(`AiAssistantProposalFreshness` timestamps or the task semantic fingerprint). `Freshness.NONE`
requires `TargetLock.NONE`, no writable anchor fields, and either a duplicate probe or an exclusive
workspace target. Workspace requests carry no handle; the server fills the workspace id and
refuses mismatches on replay and approval, then checks active membership before the delegate.
Record creates instead rely
on the canonical duplicate recheck and pinned template contract. The framework then re-asserts the tool's permissions from the step-1 snapshot; runs the
owner-scope target gate through the member-scoped person, company or deal getter, which refuses a
target the actor cannot see before the tool runs; calls the tool's `apply`, whose canonical
admission retains restriction then privacy fences after its remaining domain locks; compares the identifier
the write returned with the one resolved before the lock, recording any divergence as a
`verification` sibling of the stored outcome (`create_person` compares company id and `create_deal`
compares the pinned stage id on the delegate's returned record; for the existing immediate creates
that comparison is structural:
`TaskService.create`, `ActivityService.create` and `NoteService.create` return the very bean the tool
built, so its link cannot diverge from the target while that holds, and nothing is re-read from
the database; it is structural for `add_tag` too, whose record services' `addTag` reports only
whether it created the association, so the tool declares `ReadBack.structural`, comparing the tag
id it resolved with itself and verifying nothing, for `remove_tag`, whose `removeTag` reports
only whether it removed the association and compares the pinned tag id with itself, and for
`set_response_due`, whose `LeadResponseSlaService.startFirstResponseClock` reports only whether it
started a clock and compares the contact id with itself;
`assign_owner` compares the owner id on the record `updateOwner` returns with the principal
locked at step 1, `null` on both sides for a removal; `draft_document` compares the pinned template
id with `templateId` on the generated document DTO); and writes the tool-call status fail-closed. The one
read a tool is handed beyond its own domain services is the framework's non-locking schedule read,
`Execution.scheduleConflicts`, which the framework binds to the row's own person target (a tool
names only the window, and the read refuses any other target kind), and which the activity tool
makes inside `apply` for a meeting on a person, after the owner-scope gate and before it writes, so
the new meeting is never its own conflict.

The owner-scope gate is a read of committed state, not a replay of the value-resolution read. The
stage tool reads its deal through the same scoped getter before step 7 to resolve the stage, and
inside one transaction MyBatis answers an identical select from its first-level cache. Every target
lock statement the framework takes at step 7 — `getVisiblePersonByIdForShare`,
`getVisiblePersonByIdForUpdate`, `getOwnedCompanyByIdForUpdate` and `getDealByIdForUpdate` —
therefore declares `flushCache="true"`, so the gate's getter goes to the database after the lock.
`AiAssistantWriteTargetGateCacheIntegrationTest` pins this against MySQL for each of them. Do not
remove the flag, and give any new target lock statement the same flag.

Rejection takes no lock after step 3. Undo takes none of its own after step 3; the domain `deleteIf`
it calls takes what that service documents — for a task the board root and then the exact task rows
(see Tasks above), for a note or an activity the exact row `FOR UPDATE`. Domain services on this
path may re-acquire a membership step 1 already holds (`lockAndRequireMember` for the actor in
`TaskService.lockBoardForCreation`, for the owner in `updateOwner`); that re-acquisition adds no
edge. `set_response_due`'s `startFirstResponseClock` likewise re-reads its contact `FOR UPDATE`, the
very person row step 7 already holds, and then updates that contact's open `person_lifecycle_pass`
row — person before pass, the order the workflow engine's `set_response_due` action takes through
the same method.

`DealDocumentService.generate`, used by `draft_document`, reacquires the same actor user,
workspace, membership, custom-role permission rows and deal row already held at steps 1 and 6;
its snapshot revalidation and document insertion introduce no new authorization or deal lock edge.
The template is resolved without a lock and pinned by id, not by content version: template edits
that retain that identity use the template's content as read by generation.

**The authority is the step-1 snapshot, and its rows stay locked until commit.** The tool's own
permissions become known only after step 3 has read the durable proposal, so the service asserts
them in memory against the snapshot through `LockedPermissionSnapshot.effectiveFor(actorId)`. It
asserts them the same way again after the record lock; that second assertion reads the same
immutable snapshot and cannot fail once the first has passed, so it is a structural check, not the
protection. The protection is the held rows: a revocation that arrives mid-decision — a `DELETE` of a
role permission, a role change, a membership removal — waits until the decision commits, and one
committed before step 1 is seen by it. `AiAssistantWriteToolConcurrencyIntegrationTest` pins this
for immediate execution, approval and undo by observing the revocation waiting in
`performance_schema.data_lock_waits`.

Rules that keep this sound:

- **No permission read before step 1 in the same transaction.** An unlocked `permissionsFor` read
  populates the MyBatis first-level cache, and every later identical read in that transaction —
  including a domain service's own `@RequirePermission` check — is then answered with the pre-lock
  result. `preliminaryPrincipals` resolves which principal rows to lock and deliberately takes no
  permission read. An `AiAssistantWriteTool.principals` implementation is handed only the request
  and a member directory — a plain `WorkspaceService.getMembers` read — and may not reach anything
  else: `AiAssistantWriteToolRegistryTest` proves, for every declared tool, that principal
  resolution touches none of its injected dependencies, so it cannot call a domain getter whose
  `@RequirePermission` check would be the pre-lock read. A caller without `AI_USE` therefore meets its own-proposal refusals first (an
  unknown tool call, an unparseable proposal, an owner that no longer resolves) and the locked 403
  after them.
- **After step 1, no new authorization lock edge; permission checks stay in the domain layer.** The service's
  own assertions read the snapshot. The domain services it calls still run their `@RequirePermission`
  and `requirePermission` checks, which issue non-locking `permissionsFor` reads after step 1 and
  after the record lock — `TaskService.lockBoardForCreation` and `TaskService.create`,
  `ActivityService.create`, `NoteService.create`, the `addTag`, `removeTag` and `updateOwner` methods,
  `DealService.changeStage`, `LeadResponseSlaService.startFirstResponseClock`, and every `deleteIf`
  undo uses. Those reads add no lock edge, and while
  the snapshot's rows stay locked they cannot see a different membership, role or permission answer.
  The document-generation snapshot reacquires only locks step 1 already retains, as above.
  Do not turn another check into a new locking read, and do not route an assistant write through a
  domain method that has no permission check on the assumption that the snapshot makes it redundant.
- **Organization lifecycle is not in the snapshot.** `lockAndRequirePermissions` checks the
  workspace's lifecycle, not the organization's. An organization entering teardown is refused by the
  unlocked entry gate (`WorkspaceService.isMember`, which joins `organization.lifecycle_state`) and,
  for built-in roles, by the domain layer's `permissionsFor` read; one that enters teardown between
  the entry gate and step 1 is not refused by the snapshot. No write path may rely on the snapshot
  alone to prove the organization is active.

**Contention.** Per decision the service holds, until commit, the actor's `app_user` row (S), the
workspace row (S) and the actor's membership (X) — plus the owner's on `assign_owner` — and, for a
custom-role actor, that role's `workspace_role` row and every one of its `workspace_role_permission`
rows (X). Assistant decisions by different members who share one custom role therefore serialize on
that role row, and they also queue behind every other `lockAndRequirePermissions*` or
`lockedPermissionsFor` caller for that role (note update and delete, document approvals, chat
attachment uploads and the rest). Built-in-role members still serialize only per user. Memberships
are always locked before any role, each ascending, so two such callers cannot form a cycle.

### AI run leases

`ai_run_lease` is a leaf. The lock order is `ai_chat_session` → `ai_chat_turn` → `ai_run_lease`, and
the lease row is always the last lock any chain takes. Two rules keep that true, and each is
checkable by reading one file rather than by convention:

1. `AiRunLeaseMapper` contains no reference to any subject table. A cross-subject question — is this
   turn still running, settle this orphan — travels through the `AiRunLeaseSubjectHandler` SPI,
   implemented in the subject's own package. A subquery against `ai_chat_turn` inside a lease
   statement would invert the order and is a defect.
2. The heartbeat's subject-liveness read is a non-locking `SELECT`. The heartbeat therefore takes
   exactly one lock, the lease leaf, and a lone leaf lock cannot close a cycle against a chain that
   takes that same leaf last.

`AiRunLeaseService.acquireInCurrentTransaction` takes the lease row `FOR UPDATE` and reads it under
that lock in a transaction that has not read it before, so the MyBatis first-level cache cannot
serve a pre-lock answer. It joins the caller's claim transaction and
`releaseHeldInCurrentTransaction` joins the caller's durable terminal transaction; neither opens its
own. A claim that rolls back therefore leaves no lease row, and a terminal write that changed no row
releases nothing.

#### Settlement is one transaction, for every lease subject

For **any** lease subject, the settlement takeover and the subject's terminal write are **one
transaction**, and it takes its locks in the order subject parent → subject → lease. For a chat turn
that is `ai_chat_session` → `ai_chat_turn` → `ai_run_lease`; a future `agent_run` subject owes the
same shape with its own parent. This is the authoritative statement of the rule — the
`AiRunLeaseSubjectHandler` and `AiChatTurnOrphanSettlementService` Javadoc points here rather than
restating it.

The reason is that the epoch bump is not the fence. What stops a revived owner is the subject's own
terminal status: every owner-side write is predicated on the subject still being running, so the
owner learns it lost only once the settler's terminal status is committed. A settler that committed
`takeOverForSettlement` in one transaction and the terminal write in a later one would therefore open
a **revived-owner window** between the two commits, in which the epoch has already moved but the
subject still reads as running. In that window the owner the settler just fenced out can settle the
subject itself, and then release the lease by key on its way out — retiring the settler's takeover —
so the pass that was recovering the run has instead handed it back to a dead owner.

`AiRunLeaseService.takeOverForSettlement` therefore declares `MANDATORY` propagation. A handler that
calls it without its subject-terminal transaction already open is refused with
`IllegalTransactionStateException` rather than getting a standalone takeover that commits on its own,
so the one-transaction requirement fails closed for a subject handler nobody has written yet.

## Disqualification vocabulary materialization

Disqualification-reason settings mutations and lifecycle transitions into `DISQUALIFIED` share the
exclusive workspace row as the workspace-level materialization mutex. They run at the caller's
transaction isolation and acquire locks in this order:

1. Lock the actor's `app_user` root.
2. Lock the active workspace root exclusively.
3. Lock and revalidate the actor's membership, custom role, and required permission.
4. Read or lock the workspace's disqualification-reason catalog.
5. For lifecycle changes, lock and revalidate the exact owned person only after the reason decision.

Settings mutators require `WORKSPACE_SETTINGS`; lifecycle changes require `PERSON_UPDATE`. A row-less
catalog may be synthesized only while the workspace mutex is held. First-edit materialization holds
the same mutex, so it cannot commit between a lifecycle miss and the row-less check. Persist the code
returned by the locked resolution, never a separately compared caller value.

## SMTP configuration

A workspace's own SMTP transport (`workspace_mail_config`) has two sides with deliberately different
lock sets.

Mutations — `WorkspaceMailConfigService.saveConfig` and `deleteConfig` — take the standard settings
order and re-assert `WORKSPACE_SETTINGS` after locking:

1. Lock the actor's `app_user` root and check its account-deletion reservation.
2. Lock the active workspace root exclusively (the workspace mutation mutex).
3. Lock and revalidate membership, custom role, and the required permission.
4. Re-read the exact `workspace_mail_config` row `FOR UPDATE`; the pre-lock read is preliminary only.
5. Write the config and its secret in that same transaction.

The credential is bound to its destination: a blank submitted password may reuse the stored one only
when host, effective port, username, and the STARTTLS/SSL/AUTH transport-security settings are all
unchanged. Any other change requires re-entry, so a settings delegate cannot redirect a stored
password to a new endpoint. Port comparison uses the resolved effective port, so a client that echoes
the instance default for a stored `NULL` port is not treated as an endpoint change.

Resolution — `MailConfigResolver.resolveForWorkspace` and `resolveWorkspaceOnly` — and readiness —
`canSendForWorkspace` and `readinessForWorkspace` — lock the authenticated actor's `app_user` root
`FOR SHARE` when a `User` principal is present, then the workspace root `FOR SHARE`. Readiness never
decrypts: it only checks, with a non-locking primary-key read after those roots, that a selected
override's stored password still resolves. Resolution holds these roots across the configuration and
secret reads so the endpoint and password come from one generation. `SecretStore.get` reacquires those same roots in that order;
acquiring the actor root first avoids an inversion with a queued exclusive user lock. Background
resolution without an actor takes only the workspace root. Callers already holding roots must follow
the same actor-before-workspace order. Resolution performs no provider I/O; the SMTP connection is
made after the resolving transaction. A missing actor row or workspace root resolves to `null` — "sending
disabled" — so fire-and-forget senders keep their contract instead of seeing an exception escape.

`DeliveryProviderConfigService.describeClaimTarget` takes the same actor/workspace roots and then
`delivery_provider_config FOR SHARE` as resolution. `MailConfigResolver.describeForWorkspace`
uses the same root-first selection as SMTP resolution (managed instance settings need no workspace
lookup). Both descriptions are non-sendable and perform no decrypt or secret-use audit. Metadata
checks cannot detect ciphertext or key-material corruption or lazy rewrap failures. These locks do
not by themselves make recovery's replay decision correct: dispatch rechecks the claimed target
before egress.

## Campaign mutations

Campaign update, live-audience replacement, snapshot creation, and send creation acquire current
authorization before the exact campaign row. The permission read locks the actor's user row while
checking its account-deletion reservation, then the active workspace, exact membership, custom-role
root, and permission rows. These are locking reads and do not establish a consistent-read snapshot.
Final authorization is derived from those locked rows, then the campaign row is the per-campaign
mutex and the first aggregate row lock.

These mutations keep the default `REPEATABLE_READ` isolation. No consistent, non-locking read may
run inside the transaction before the campaign mutex is acquired. The first ordinary read after the
mutex establishes one snapshot newer than every campaign change that previously committed under
that mutex, and every later segment-condition and eligibility query shares it. Missing audience or
snapshot-version rows are ordinary reads, not locking reads, so unrelated campaigns' first child-row
inserts do not acquire conflicting index-gap locks.

- Campaign creation and update lock actor and requested-owner memberships in one ascending union
  before role authorization; update then takes the campaign mutex. The requested owner's membership
  uses `FOR UPDATE NOWAIT`;
  creation first retains the actor's account root and active workspace shared, then locks the
  membership union and actor authority before insertion and audit. This prevents a role change from
  holding the workspace while waiting for the owner's membership as creation reaches its audit.
  No target account root or target reservation fence is added: active reserved targets remain
  admitted until the cascade.
  Only MySQL 3572 / SQLSTATE `HY000` becomes the existing 409 membership-changing conflict; callers
  resubmit and the transaction never retries. Waiting while holding the workspace would close the
  three-transaction campaign → target-membership/task → task-actor/role → workspace cycle.
  Ordinary lifecycle, parent-campaign and other validation reads remain after the campaign mutex.
  Invalid owners retain 400, missing campaigns 404, and insufficient actor authority 403.
- Live-audience replacement reads the previous audience ordinarily after the mutex, then upserts
  and audits the transition under the same mutex.
- Snapshot creation ordinarily reads the live audience and next snapshot version after the mutex,
  then evaluates every segment condition and eligibility source in that same consistent snapshot.
  The campaign mutex serializes allocation of the unique campaign-local snapshot version.
- Send creation reads its message, immutable revision, and immutable snapshot `FOR SHARE` after the
  mutex. `authService.getCurrentUser()` then refreshes `app_user` with the ordinary read that
  establishes the transaction's consistent snapshot. Snapshot members are subsequently read
  `FOR SHARE`, and the later person/address reads share that post-mutex snapshot while the immutable
  inputs remain locked.

Audience export uses three transactions around provider egress. Transaction A locks and revalidates
the actor's `CAMPAIGN_MANAGE` and `CONSENT_MANAGE` authority, locks the campaign mutex, classifies
the snapshot members, and persists the export with that exact member-id set and a bounded running
lease. Outside locks, the complete provider destination (endpoint, credential, and external list) is
then resolved from one connector-configuration row together with that row's id and generation.

Transaction B is the last database work before egress. It repeats locked authorization and the
campaign mutex, reloads the persisted member set, rechecks restrictions, channel suppressions,
consent, and addressability, records the exact ids that will be placed in the provider request,
refreshes the lease, and finally rechecks the connector row id/generation with a current locking read.
No application database
work occurs between B's commit and the connector invocation. Authorization or eligibility changes
that commit before B starts affect this export. More precisely, an eligibility change must commit
before B's first consistent read, the restriction read, to enter B's repeatable-read snapshot;
connector changes that commit before the final locking fence abort the export before egress.

The provider push runs only after B commits. Its idempotency key binds the snapshot/version, a stable
hash of the fenced connector configuration identity and external list, a stable hash of the final
ordered member ids and outbound member fields, and the persisted attempt. B allocates attempt one plus
the count of prior requests for the same snapshot, connector, and external list while holding the
campaign mutex. A retry of the same ambiguous export row therefore retains its request key, while a
replacement after a definite failure advances the attempt. Transaction C records the confirmed
outcome. It locks current authorization, the campaign mutex, and the export row in that order before
its guarded write. Current `CONSENT_MANAGE` authority decides whether its response may include
detailed counts.

| Connector or operator observation | Provider outcome | Persisted classification | Export state |
|---|---|---|---|
| Final revalidation leaves no eligible member to send | `DEFINITE_NO_SIDE_EFFECT` | `no_eligible_members` | `failed` |
| Local validation, serialization, DNS, or connection refusal before the request body can be sent | `DEFINITE_NO_SIDE_EFFECT` | `definite_no_side_effect` | `failed` |
| Provider-specific response whose documented atomic contract proves non-acceptance | `DEFINITE_NO_SIDE_EFFECT` | `definite_no_side_effect` | `failed` |
| Any generic non-2xx response after the request body was sent | `AMBIGUOUS` | `ambiguous` | `running` with `reconciliation_required_at` set |
| Hard wall-clock deadline abort after the request started | `AMBIGUOUS` | `ambiguous` | `running` with `reconciliation_required_at` set |
| Post-send transport failure or incomplete/inconsistent 2xx counters | `AMBIGUOUS` | `ambiguous` | `running` with `reconciliation_required_at` set |
| Complete, consistent 2xx counters with any accepted member | `CONFIRMED` | `confirmed_delivery` | `completed` |
| Complete, consistent 2xx counters with no accepted member | `CONFIRMED` | `confirmed_no_delivery` | `failed` |
| Operator confirms delivery | N/A | `operator_delivered` | `completed` |
| Operator confirms no delivery | N/A | `operator_not_delivered` | `failed` |

The generic HTTP list connector has no provider-specific atomic non-acceptance contract. Its
`400`, `401`, `403`, `404`, and `422` responses therefore remain ambiguous even when their bodies do
not report acceptance; receiving an HTTP response does not prove that the provider applied none of
the submitted members.

Provider/network I/O must not be moved under B's locks.
That prohibition leaves an inherent final-revalidation-to-provider-acceptance window. For locked
authorization and the connector generation it starts when B commits; for eligibility it starts at
B's first consistent read, the restriction read. The later address, suppression, and consent reads
share that snapshot, so a change committed after the restriction read is outside B even if it commits
before the corresponding later query. The remaining in-process reads, stage write, fence, commit, and
handoff are normally milliseconds. Immediately before B refreshes the lease, the service captures one
monotonic provider-budget anchor and passes its absolute deadline unchanged through the connector
handoff, so payload hashing, B's remaining work and commit, request serialization, DNS, connect, TLS,
request, and bounded response reading all consume the same budget, 18 seconds by default. The initial
lease and every refresh are written as
`DATE_ADD(UTC_TIMESTAMP(6), INTERVAL #{leaseMicros} MICROSECOND)`, and lease expiry is evaluated only in
SQL against `UTC_TIMESTAMP(6)`. The application never derives a lease timestamp from its wall clock.
The monotonic budget duration is the hard provider deadline and is strictly shorter than the
database-clock lease by the configured safety margin. The guarantee that SQL cannot classify the
lease as expired while the monotonic provider budget remains live holds only while forward database-
clock adjustments during the lease stay below that margin. Configure
`connex.delivery.audience-export-lease-safety-margin-ms` (environment variable
`CONNEX_DELIVERY_AUDIENCE_EXPORT_LEASE_SAFETY_MARGIN_MS`) above its 30-second default and enforced
floor if the database host's clock discipline cannot meet that bound. Production database hosts must
use NTP slewing and must not apply forward clock steps while leases are active. The connector derives
remaining time from the original absolute anchor at every stage and never re-anchors it. If it is
exhausted before egress, the connector returns a definite no-side-effect failure without resolving or
contacting the provider. A
scheduled abort sets Apache hard cancellation on a started request, cancels it, and immediately closes
its client/socket at the deadline; the connection and response timeouts (3 and 15 seconds by default)
remain subordinate inactivity bounds, not duration claims. Timed-out DNS futures are canceled and
run on a fixed two-thread executor. Each active resolver owns one of two permits until its task returns,
including when it ignores cancellation; a caller waits at most 50 milliseconds for a permit before a
definite pre-send resolver-saturated failure. Two resolver tasks that never return retain both permits
for the process lifetime, so all subsequent audience exports on that instance fail closed with
`failure_reason=resolver_saturated` until the process restarts. If either timed-out task eventually
returns, its permit restores capacity without a restart. Saturation emits the fixed WARN marker
`AUDIENCE_EXPORT_RESOLVER_SATURATED` and increments
`connex.delivery.audience_export.resolver_saturated`. A restart discards the stuck executor and
permits; the usual readiness and connector checks then govern recovery. Automatic connector retries
are disabled. The running lease is the hard provider deadline plus the configurable clock-adjustment,
handoff, and persistence margin. Startup refuses a margin below 30 seconds, nonpositive transport
bounds, either inactivity timeout longer than the hard deadline, or a deadline plus margin beyond the
five-minute maximum. An
authorization, eligibility, or connector-generation change committed inside its respective window
cannot retract an already-started request. It is honored on the next export; provider-side unsubscribe
synchronization is the immediate removal path for an in-flight disclosure. Rotation inside the window
is the same residual class and must not be described as an unconditional pre-egress abort.

Every export written by the current backend carries a lease while `running`. Ambiguous outcomes and
expired nonnull leases stay `running` and set `reconciliation_required_at` while clearing the lease.
The same write classifies an expired lease as `ambiguous`. Only a `running` row with a null lease may
carry the reconciliation flag, and `ambiguous` is legal only on that flagged running shape. This shape
remains visible to the previous backend's
`draft|running|completed` duplicate fence after an application rollback. The current backend projects
the separate flag as the “needs reconciliation” history label and continues to block silent re-export.
A `running` row with both a null lease and a null reconciliation timestamp is a legacy in-flight write
left possible for application rollback compatibility; it is never treated as stale, is presented as
in flight, and remains active for duplicate prevention. V199 leaves every pre-existing `running` row
and every pre-existing `failed` row unchanged. Ambiguity in legacy failures is pre-existing data and
outside this migration's scope. A null classification is permitted for a draft/running row with no
outcome and for a migrated legacy terminal row whose two member-identity fields are both null. A new
terminal row has recorded member identities and must carry one of the terminal classifications in the
outcome table.
Transaction B persists the exact idempotency key with the attempt before egress. If transaction C's
guarded outcome update affects no row, the locked row is an idempotent replay only when its attempt,
persisted, supplied, and attempted idempotency keys, status, total/pushed/failed counts, and bounded
outcome classification code exactly equal this attempt's reported outcome; C then returns the
persisted state without another marker or audit. The classification code is one of
`no_eligible_members`, `confirmed_delivery`, `confirmed_no_delivery`,
`definite_no_side_effect`, `ambiguous`, `operator_delivered`, or `operator_not_delivered`. The bounded
failure reason remains diagnostic metadata and is not part of replay identity. Any different
persisted state is a late outcome unless the row is terminal with an operator classification and the
provider status and total/pushed/failed counts exactly agree. Agreement appends the strict
`campaign.audience_export.late_outcome` audit event with `agreement=true` and does not persist a
late-outcome marker because no operator attention is required. A disagreement persists the provider
classification in `late_outcome` and appends the same strict audit event with `agreement=false`, the
export id, attempt, idempotency key, provider outcome, and current persisted state. Confirmed results
distinguish `confirmed_delivery` from `confirmed_no_delivery` in history. The history DTO exposes the
marker without member identities or recipient data, so a provider result that arrived after operator
action cannot disappear.

An operator holding locked `CAMPAIGN_MANAGE` and `CONSENT_MANAGE` authority can resolve a flagged
`running` export or a legacy null-lease, unflagged `running` export only after provider confirmation.
Delivered preserves trustworthy recorded request identities/counts and completes the export; legacy
running placeholders become unknown counts because those rows predate request-identity recording. Not
delivered records a definite zero-push failure and unblocks a replacement export. The same compare-
and-set persists `operator_delivered` or `operator_not_delivered` together with the terminal status
and counts. All source states use the same audited, idempotent compare-and-set, and no resolution
retries the provider request.
Historical rows whose exact prepared or pushed identities were not recorded retain null member-id sets
rather than fabricated empty arrays. Callers without `CONSENT_MANAGE` may read only the stable prepared
total; known final pushed/not-pushed counts remain consent-gated.

Triggered single-recipient enrollment participates in the workflow step's `READ_COMMITTED`
transaction. The step first discovers the immutable run/version without a lock, then acquires actor
user, workspace, membership, custom role, and permission roots in canonical order. Only after those
authorization roots are held does it lock and revalidate `workflow_run`. The action may make a
non-locking observation of the revision's triggered send, but never treats absence as authoritative;
it then discovers the campaign through a workspace-scoped message read, locks the campaign mutex,
re-reads the message and revision under shared locks, classifies restriction before contact address,
and finally creates the synthetic snapshot, triggered send, and delivery. The campaign mutex
serializes cooperating writers. The unique triggered-send key and catch-and-re-read remain the final
race defense across mixed-version writers. Missing child-row locking reads never substitute for the
campaign mutex. No provider dispatch occurs in this transaction.

Campaign dispatch mints a fresh random 64-character lowercase hexadecimal unsubscribe bearer for
one rendered attempt; materialization and triggered enrollment mint none. Only its SHA-256 digest
enters `campaign_delivery_unsubscribe_token`. Every attempt's digest, including migrated legacy
digests and unused attempts, remains valid until its delivery is deleted. The bearer lives only in
the rendered message and provider request; export contains digests alone. Provider diagnostics are
scrubbed of the exact attempt bearer and all 64-hex runs before truncation, error/event persistence,
or logging. Native Angus and Connex transport package loggers scrub SMTP diagnostics before
forwarding to handlers.
The native Angus transport suppresses MIME DATA tracing when its `data()` stream returns after
354, then serializes the original message once without a copy or pre-egress serialization. The
`bdat()` stream is protected too, while `mail.smtp.chunksize=0` disables chunking. DATA's dot
terminator resumes final-response diagnostics; under BDAT, fail-safe suppression lasts until send
cleanup and also hides BDAT commands and replies. Send/close finally blocks clear suppression.
Provider identifiers share a digest correlation form: every case-insensitive 64-hex window is
hashed after lowercasing, including windows inside longer runs. Matching recorded digests replace
the bearer window; unrelated identifier bytes, including opaque 64-hex ids, survive. Dispatch uses
the resolved workspace's digest history plus the current attempt's digest, including when a replay
returns the original receipt. Webhook message lookup and event dedupe use the same workspace scope.
Correlation rescans replacement output for at most four replacement passes, then checks for residual
bearers. An unusable id becomes NULL at dispatch (with an identifier-free WARN) or event persistence,
or no match at webhook message lookup. Unusable event ids give up provider-key deduplication;
ordinary missing event ids keep the existing fallback. Free-text webhook reasons redact all 64-hex
runs. Each pass collects all candidate digests before `findUnsubscribeTokenHashes` resolves uncached
membership with one workspace-scoped `token_hash IN (...)` query. Positive and negative membership
is cached per identifier call only, so an identifier needs at most five queries, including the final
residual scan. Dispatch's current digest is recognized explicitly without a lookup. These are plain
consistent reads without `FOR UPDATE` or shared locks, and they acquire no row locks even while
another transaction holds an exclusive child-row lock. Empty batches return no rows; short ids
perform no queries. They add no lock-order
edge to dispatch or the existing webhook transaction. Identifiers exceeding the 255-code-point
storage limit are rejected before persistence or probes; accepted receipts then require reconciliation.
Oversized webhook message ids instead return no match without probes; event id persistence still
rejects oversized ids. V228 rekeys historical identifiers case-insensitively to digests with
`UPDATE IGNORE`, skipping unique-key conflicts, then sets every event key still containing its
delivery's bearer to NULL. This covers collisions with existing canonical keys and between legacy
rows being rekeyed under the same workspace/provider, including rows from different deliveries. V228
also clears message ids still containing their own
bearer after replacement; foreign historical bearers remain outside its bounded guarantee, as
explained in `UPGRADING.md`. Runtime matching uses the workspace scope without locking reads.

After lease renewal and before frequency admission, the proxied
`CampaignUnsubscribeTokenRegistrationService.register` opens a short `READ_COMMITTED` transaction:
`campaign_delivery FOR UPDATE` → current claim/status/database-clock triggered lease validation →
child digest INSERT. The insert takes exclusive index locks and a shared composite-FK parent check.
It takes no workspace, campaign, send, authorization, or audit lock. Commit completes before dispatch
returns to the workspace → delivery frequency transaction, and provider I/O holds neither
transaction's locks. Recovery and deletion wait on the delivery; a waiting stale registrar rechecks
the owner after acquiring it. Cascade deletion follows parent → child. Duplicate-key contention can
still deadlock: rollback and fail closed without provider egress, with sanitized persistence errors.
No database retry encloses provider submission. Death after registration leaves an unused digest;
death after submission leaves a working emailed link even without terminal evidence. Earlier
submission uncertainty survives registration failure.

An opted-in idempotent replay keeps `send:{sendId}:{deliveryId}` but re-renders a fresh unsubscribe
link. A body-comparing provider refuses the changed body and the earlier acceptance remains
ambiguous for reconciliation; a body-insensitive provider returns its original receipt. Neither
provider submits a second email, and both registered links resolve. This also applies to migrated
in-flight claims whose legacy bearer is no longer reconstructable. See `DELIVERABILITY.md` §3 and
`UPGRADING.md`'s V227/V228 cutover. Backfill uses `INSERT ... SELECT` across every status; outside
`READ_COMMITTED` MySQL may take shared source next-key locks, so all writers must be stopped.

Triggered delivery dispatch uses a separate, short owner-fenced claim update. The claim stores a UUID
owner and a database-clock expiry whose duration is the bounded provider deadline plus its safety
margin. Provider I/O runs outside database locks. Immediately before provider egress the
worker captures one absolute monotonic deadline, renews its still-live owned lease, and checks the
startup-bound rollout fence again; a closed instance releases the claim to `pending`. Every provider
receives that same absolute deadline. HTTP hard cancellation closes the request client. SMTP hard
cancellation first closes every tracked raw socket, then closes the active JavaMail transport.
Workspace-supplied destinations track the validated pinned-address socket unless internal relays are
explicitly allowed; that opt-in and instance-default SMTP resolve within the absolute budget and use
the tracked non-pinned socket path. Closing the tracked socket interrupts a blocked Angus
`sendMessage` even while its synchronized transport monitor prevents `Transport.close()` from
entering. Remaining-budget connect and read timeouts stay subordinate inactivity bounds; the hard
socket close replaces Angus's write-timeout wrapper on this path. DNS, TCP connection, TLS handshake,
and authentication precede the submission boundary and therefore fail definitively. An error after
SMTP message submission starts is persisted as ambiguous failed-delivery evidence with
`reconciliation_required_at`, requires reconciliation, and is not automatically replayed. Each HTTP
ESP or SMS connector has an `idempotentSubmission` setting that defaults to false and may be enabled
only when that endpoint guarantees deduplication of the stable key sent as `Idempotency-Key`.
The generic adapters do not infer that guarantee. SMTP cannot declare it: its stable `Message-ID` and
`Idempotency-Key` headers are correlation metadata, not an equivalent provider-side deduplication
contract, and a relay may accept the same `DATA` more than once. Terminal writes require the same
owner and clear the lease. The claim persists a SHA-256 attempt-target fingerprint over the provider
id, configuration id/generation, and a hash of the endpoint/account identity plus opaque credential
reference; credential values never enter it. A workspace sweep may change an expired `dispatching`
row back to `pending` only when the currently resolved target has that exact fingerprint and its
connector configuration explicitly declares idempotent submission. Recovery preserves the fingerprint on `pending`, and the
next claim compare-and-set refuses a newly changed target and records it as ambiguous. Expired SMTP,
changed-target, or unknown-provider claims become terminal ambiguous `failed` rows with
`reconciliation_required_at`; scheduler discovery includes workspaces whose only work is either
expired shape. This prevents replay on a non-idempotent transport while an obsolete owner cannot
complete a replacement claim. SMTP is consequently a best-effort campaign transport. Because the
fence is captured at startup, rollback must follow the quiescence procedure in
`docs/backend/AUTOMATION.md`; editing an environment file does not close a running instance.

Audience delivery dispatch claims with a `pending` → `dispatching` compare-and-set that also writes
the attempt's own lease end time, `audience_lease_until` (#1773), and stores no lease owner or
attempt-target fingerprint. The `dispatch_lease_*` columns stay a triggered claim's, so
`dispatch_lease_owner IS NULL` still identifies an audience row to every statement, including the
previous release's, which never reads the new column. An audience row is never returned to `pending`,
so no other worker can hold it and an owner would fence nothing; the worker renews the end time once
just before egress, by a compare-and-set that does not require the lease to be live, because the only
actor that can take the row is a recovery sweep and the row's status decides that race. The lease end
time ages an attempt that never reserved; for one that did, the anchor is the frequency reservation,
which the worker writes as database time plus the hard provider deadline in
`CampaignFrequencyAdmissionService`'s short workspace → delivery transaction before egress, after
capturing that deadline. A worker that dies between reservation and its terminal write therefore
leaves a `dispatching` row whose reservation would cap the contact/channel for the whole frequency
window. The same workspace sweep that recovers triggered claims also selects unleased audience rows
that are still `dispatching`, have no `submitted_at`, and whose `frequency_reserved_at` is older than
the delivery lease safety margin (lease duration minus provider deadline, which covers
database-clock adjustment and the post-return terminal write). It never returns such a row to
`pending`: `submitted_at` is written only after the provider returns, and without an owner fence or
fingerprint a replay could double-send. Each row instead becomes a terminal ambiguous `failed` row
with `deadline_ambiguous` and `reconciliation_required_at`, keeping `frequency_reserved_at` so the
cap stays in force until an operator resolves it. The sweep is one auto-commit compare-and-set per
row on the delivery joined to its send for `origin` — the same statement shape and lock footprint as
the triggered expired-claim sweep. It never runs inside the reservation transaction, holds no
workspace root, and adds no lock edge. Send status is not filtered, because a completed, paused, or
cancelled send can own the stranded row; scheduler discovery includes workspaces whose only work is
such a row. That discovery arm is driven by the `dispatching` delivery rows rather than by every
audience send, because a stranded attempt usually belongs to a send that already completed, so a
send-driven branch would cost one index probe per historical audience send on every tick.
`idx_campaign_delivery_unleased_reservation` answers it as `status` and `dispatch_lease_owner`
equalities plus a `frequency_reserved_at` range, so a tick with nothing to recover reads no delivery
rows; the statement runs once per catalog with no workspace predicate, so no index leading with
`workspace_id` could be seeked for it. Every statement stays correct without that index and only the
scan returns, so `DeliveryRecoveryIndexArchTest` pins its leading columns against a later index
consolidation. The same pass then settles every audience send that is still
`running` with nothing `pending` or `dispatching`: it refreshes the counters, completes the send,
and refreshes them again after a completion, without resolving a provider, so a connector disabled
after the worker died cannot keep the send running. The first refresh precedes the completion so a
refresh that fails, or a process that dies before completing, leaves the send `running` and found
again. The completion is a single compare-and-set that proves the absence of `pending` and
`dispatching` rows in the same statement that writes `completed`, so a live worker's terminal write
cannot land between the proof and the completion; because no delivery can return to `pending` or
`dispatching` afterwards, the refresh that follows a completion reads every delivery in its final
state. A death between the completion and that second refresh loses only terminal writes that landed
after the first refresh, and each such write's own worker refreshes after it, so the counters stay
stale only if that worker dies too. A `dispatching`
row may belong to a live worker, possibly on another instance, so that send stays `running`. The
dispatch loop settles through the same compare-and-set (#1773), so whichever settlement follows the
last terminal write completes the send, and a running send with nothing `pending` is settled there
without resolving its provider, so waiting on another attempt is never reported as a failed run. An
attempt its worker abandons, before or after reserving, is swept and settled by a later pass. Before
#1773 the loop completed a send once nothing was `pending`, which could complete it under a peer's
in-flight attempt. That send is
selected by a durable predicate, not remembered from the sweep, because a marked row no longer
matches the sweep: a settlement that fails is found again on a later pass, and scheduler discovery
already returns a workspace that owns a `running` send. The predicate deliberately carries no
reconciliation-row condition, because a provider webhook (`applyProviderStatus`) and an operator
resolution (`resolveReconciliation`) both clear `reconciliation_required_at`, and the webhook also
moves the row out of `failed`; a send whose worker has died must not depend on a marker another actor
may clear. It is answered from `idx_campaign_send_status` plus one index probe per running send, so
its cost follows the workspace's running sends and never its delivery history.

A send that is already `completed` when the sweep marks one of its rows owes only its counters, and
those are refreshed from the sweep's own result in the same pass rather than by a
counter-disagreement selector: comparing `failed_count` with a `COUNT(*)` of failed rows is a
dependent subquery over every audience send's delivery history, which the scheduler would pay on
every tick, and putting the reconciliation `EXISTS` first does not bound it, because
`reconciliation_required_at` is not in `idx_campaign_delivery_send_status` and the probe therefore
reads the same failed rows from the clustered index. The bound that leaves: if a pass dies after a
sweep's compare-and-set and before the matching counter refresh of an already `completed` send,
that send keeps a `failed_count` missing every row the pass marked, up to one page; a `running` send
is refreshed before its completion, so it is found again instead. Resolving an ambiguous row's reconciliation
refreshes the counters, but a definitive abandonment (below) creates no reconciliation row, so its
under-report remains. A pass that dies between a compare-and-set and its event append also loses that
row's `failed` event. The delivery rows themselves are terminal throughout, and the ambiguous ones stay
reconcilable. Settlement takes the same single-row auto-commit writes on
`campaign_send` the dispatch loop already runs, so it adds no lock edge. A slow but live worker that writes after the
sweep loses its `status = 'dispatching'` compare-and-set and leaves the row reconcilable. It then
attaches its provider id and message id to that swept row through a second single-row
compare-and-set, and changes neither status, reconciliation state, nor the reservation, so provider
bounce and complaint webhooks still resolve to the row and record suppression and consent revocation.
That statement accepts a swept row with no provider id whether it is still awaiting reconciliation or
an operator has already resolved it — a resolved row keeps neither the sweep's `last_error` nor its
reconciliation marker, so that branch is anchored on the operator outcome, and an audience row is
never returned to `pending`, so no later attempt can own the correlation it writes. A slow triggered
worker whose owner-fenced terminal write loses to the expired-claim sweep attaches its message id
through the matching statement on the triggered path. A triggered claim persists the provider id and
the attempt target fingerprint, so that statement identifies the attempt by both instead of by the
absence of a provider id, and it fences on an absent lease owner plus a settled status rather than on
the sweep's `last_error`: a triggered row can be returned to `pending` and re-claimed, so a `pending`
row is left to the replay that records its own correlation, a re-claimed row belongs to the attempt
holding the lease, and a row the sweep re-queued that a later attempt drove back into terminal
ambiguity still accepts the only message id the provider ever accepted for it. The statement also
accepts a `dispatched` row that carries no message id, which is what `markTriggeredDispatched` leaves
behind when the replay's own receipt named none: it writes that null over the column unconditionally
and clears the lease, so the row settles correlation-free and only the late attempt's id can resolve
its webhooks. Both attempts submit the same `Idempotency-Key`, so on a connector an administrator
marked idempotent that id names the one message the provider kept. Many triggered items still reach
reconciliation with no message id — a worker that never returns, a replay that never ran, a late
return refused because a newer attempt held the claim, a terminal write or late attach that could
not be persisted, and a receipt that names no message id from either attempt —
so their webhooks still match no row and operators check the provider by hand for every triggered
item (`docs/DELIVERABILITY.md` §3.1).

Audience attempts abandoned with no frequency reservation are swept by their lease (#1773): a person
row whose worker died before reserving, or after releasing its reservation before egress, and a row
with no person, which never reserves. A row is due once its `audience_lease_until` has been expired for
the same safety-margin grace. A row an older version claimed carries no lease and is due once it has
been idle, by its own `updated_at` clock, for three times the maximum lease. That is a heuristic rather
than proof the older worker is gone, because older code bounds the provider call but not the
preparation before it. What holds regardless: a person row's late worker can no longer reserve, since
reservation requires `dispatching`, so it cannot send and the definitive outcome below stays true; the
cost is a delivery that fails instead of going out late. A person-less row's late older worker may
still reach the provider; the row is held for reconciliation either way, but that worker's
late-correlation statement matches only the reservation sweep's marker, so its provider id is not
attached and a bounce cannot resolve to the row. Both need an older worker stalled past that idle time
while a newer version runs beside it, which a restart deploy of the single backend never allows. A person
row with no reservation never reached the provider — reservation precedes egress and is released only
where egress provably did not happen, including a rejection the adapter proves preceded egress — so it
becomes a definitive `failed` row with no
reconciliation and no frequency cap. A row with no person may have reached the provider, so it becomes
an ambiguous `failed` row requiring reconciliation, like an expired reservation, and a late worker's
provider correlation still attaches to it. Neither is replayed. The statements have the reservation
sweep's shape and lock footprint — one auto-commit compare-and-set per row on the delivery joined to its
send for `origin` — and the owning sends are settled the same way. Scheduler discovery gains a matching
row-driven arm, which reads `idx_campaign_delivery_unleased_reservation` with `status`,
`dispatch_lease_owner` and `frequency_reserved_at` as equalities, so it reads only unreserved
outstanding attempts.

Operator reconciliation takes locked membership permission roots first and requires both
`CAMPAIGN_MANAGE` and `CONSENT_MANAGE`, then locks campaign, send, and delivery in that
order. Audience and triggered deliveries use the same compare-and-set, which accepts only `failed`
rows carrying `reconciliation_required_at` and no
prior resolution. The same resolution is idempotent; the opposite resolution conflicts. Confirmed
`delivered` becomes `dispatched`. Confirmed `not_delivered` remains failed with
`operator_not_delivered`, which preserves the evidence while excluding that historical row from the
active send/person uniqueness key; only triggered enrollment uses that state to create a replacement. The reconciliation
write and strict audit share one transaction, and no resolution invokes a provider. Recipient and
reconciliation DTOs expose only the bounded reason-code set `provider_timeout`, `provider_rejected`,
`deadline_ambiguous`, `delivery_target_changed`, and `relay_error`; raw provider failure text remains
internal evidence.

The rollback-readable triggered snapshot deliberately has zero members and purpose label
`Triggered (system)`. A pre-feature UI therefore shows a labelled zero-member audience and may create
only a zero-recipient draft/export from it; its old send badge may render the raw `triggered` token.
Those display limitations are accepted during rollback because no data is lost and no provider send
can result from the synthetic snapshot.

## Duplicate review, record mutation, and imports

`DuplicateDecisionLockService` serializes candidate-affecting mutations across the same-organization visibility domain.

For interactive person/company/deal mutation, sharing, imports, OCR, and identity backfill:

1. Use `READ_COMMITTED` where the owning request contract requires it.
2. Lock actor user when present.
3. Lock every active workspace root required by the mutation in ascending id.
4. Lock required active memberships.
5. For person updates (including processing-restriction changes) and person/company sharing,
   lock the actor's custom `workspace_role` root and permission rows when applicable. Validate
   `PERSON_UPDATE` for person updates or `SHARE_MANAGE` for share grants and revocations from
   the locked authority before proceeding.
6. Lock the active organization shared.
7. Lock the organization duplicate-decision mutex exclusively.
8. For a CSV import that uses an auto-create custom-field mapping, lock the affected
   record-creation template set before writable record targets. Lock and revalidate
   `CUSTOM_FIELD_MANAGE` first when the definition is absent. This set row is a schema
   synchronization root, not dependency creation. After the set lock is held, recompute the
   set of absent definition keys and fail closed with a review conflict if it differs in
   either direction from the pre-lock reading, so a definition created or removed concurrently
   under `READ_COMMITTED` cannot be silently adopted or created twice.
9. Lock writable record targets ascending by record id.
10. Lock canonical identity groups in deterministic kind/value order.
11. Requery/revalidate current identities while locks are held.

Duplicate-review dismissal and reopen are terminal decision writes within this hierarchy. They lock
and revalidate both the actor's `REPORT_READ` gate and type-specific update permission before entering
the organization mutex, then lock exactly one current `duplicate_review_decision` row. They take no
record or canonical
identity lock. The shared organization mutex serializes the decision against identity maintenance;
after obtaining it, the service rechecks the evidence fingerprint and current visibility before
writing. This places the decision row after the organization mutex without introducing an ordering
edge against record targets or identity groups.

Record-visibility and active-org scopes in both modes are prepared with the workspace route, before any
tenant transaction or authorization, organization mutex, provider connection, or record lock.
Preparation uses a short read-only control transaction: one unfiltered sharing statement also backs
the separately typed record-visibility scope, and one active-only statement backs read ceilings.
The connection and session are released before tenant work, including when an enclosing suspended
operation has empty synchronization. `DuplicateReviewService.list` retains its count/page transaction;
`WarmPathService` signal detection, queue-time identifier resolution and nested workflow actions can
read the prepared payload inside their existing transactions without borrowing.

Same-workspace `runAs` nesting reuses preparation; identity-only switches never load it. A switch to
another identity retains usable data only when the prepared workspace, organization and catalog all
match. Otherwise a later transactional scope lookup fails fast in routed mode; single-database mode
retains its lazy fallback. Missing snapshot invariant failures
escape notification preload degradation; ordinary preload failures retain existing pass isolation.

In single-database mode, explicit operation scopes take precedence over matching prepared data,
then the transaction memo and finally the lazy lookup. Explicit supplied scopes remain authoritative
for batch mapper arguments and nested reads; routed supplied scopes must equal the prepared payload.
The lazy `RecordVisibilityScopeControlAccess` path memoizes one successful snapshot per workspace
within an actual synchronized transaction in `single-database` mode only. A durable outbox delivery
page is one `REQUIRES_NEW`, `READ_COMMITTED` transaction, so its per-record segment checks and repeated
legacy checks share one control read; simulation conditions likewise share their read-only
transaction's snapshot. The memo is unbound on transaction suspension, restored only on resume, and
discarded on commit or rollback before deferred completion callbacks can open independent work.
`REQUIRES_NEW` and nontransactional work cannot reuse a suspended transaction's memo. Cached scopes
still enforce the resolved workspace/organization identity. In `catalog-per-placement` mode, every
call bypasses the memo entirely: routed reads are served by the memory-only prepared snapshot
(#2165), with the boundary validating workspace, organization, and catalog on every read. A missing
or stale prepared payload must fail even after an earlier successful read in the same transaction.

Pipeline visibility uses the same unfiltered record scope. PipelineService passes one scope through
owned-parent, sibling and replacement validation; full pipeline nesting carries that scope in a
private projected SQL column without changing the owning workspace parameter. Template mutations
prepare it before membership authorization and set/root locks, then reuse it for stage, pipeline
and share locks and the final availability check. Augmentation keeps the stage → pipeline → share
order and its submitted-stage validation remains owned-only. Guided creation prepares the scope
before authorization; deal creation prepares it before duplicate-review mutex admission, deal
updates/reopen before authorization and row locks, and deal imports before authorization and
template fences. Assistant write admission and workflow step/outbox execution prepare it before
their outer authorization locks so nested Pipeline reads reuse the transaction memo or #2165's
prepared routed snapshot. No authorization or `FOR UPDATE` lock moves off its original connection,
and no transaction boundary or isolation level changes.

Deal outcome lookups and person-junction statements consume their operation's captured unfiltered
scope; no authorization or record lock changes connection or order. Compound GET projections use
`RecordVisibilityRead` boundaries, while mutation roots retain the supplied-scope `Supplier`
boundary. Segment and risk reads defer acquisition past null/empty/ineligible paths, retaining one
snapshot across nested groups and warmth batches. Lifecycle conversion resolves only in its existing
transitioning-to-CONVERTED branch after the required person lock; transaction-first fallback reads
therefore remain after that lock. Notification reconciliation preloads before its existing
REQUIRES_NEW transaction when either introductions or Deal risk need the ceiling; an ordinary
preload failure leaves those passes unmanaged without disabling other reminder passes, and typed
snapshot invariant refusals propagate. Historical projections reuse their caller's pre-lock scope.

Bulk stage changes carry one unfiltered scope into every independently committed Deal mutation and
its stage-history write. Ordinary creation, reviewed/guided creation, update, reopen and move also
pass their pre-lock scope to history instead of loading it again after mutation. Deal imports pass
one scope through name resolution and both initial and transition history writes within their
existing import transaction.

Workflow traversal, immediate workflow dispatch, legacy rule dispatch and scheduler workspace sweeps use the explicit
`RecordVisibilityScopeControlAccess.withWorkspaceScope` operation boundary. In `single-database`
mode it reuses the caller's immutable snapshot across nested calls and independent step/outbox
transactions on that thread. It restores an enclosing operation on every exit, clears the binding
after the outermost operation, and rejects workspace, organization or catalog drift. It does not
propagate into asynchronous work. A scheduler or legacy listener may enter with only a catalog pin;
installing an automation identity later reuses that scope only for the same workspace, organization
and catalog. An identity resolved at operation entry must remain resolved. Transaction propagation,
per-record commits and authorization locks remain unchanged. This is explicit operation reuse,
separate from the default transaction memo: ordinary `REQUIRES_NEW`, completion callbacks and
nontransactional reads outside that boundary use matching prepared data when present and otherwise
retain the existing lazy-read contract. In routed mode every boundary read still validates the prepared
context payload before considering any memo, so a missing or stale payload still fails inside an
open transaction without a control query. Assistant parallel tool batches reject writes; one
auto/approved write is one transaction, and its nested Pipeline reads share the existing memo.

Restriction-driven AI output-cache erasure uses `getFreshForWorkspace` immediately before its one
set-based `AiOutputCacheMapper.deleteForPerson` call, after the existing authority/person locks and
ordinary domain/identity work. `invalidateAssistantOrganization` retains the restriction write fences
before advancing organization privacy authority; the fresh scope lookup and cache DELETE follow,
with those fences held through transaction completion. In `single-database`, this is an explicit
exception to scope-before-locks: the unfiltered lookup runs on the same default-catalog transaction
connection, without suspension or another checkout, and neither reads nor replaces the prepared
payload, explicit operation scope or transaction memo (#2162). Its dedicated
control statement also flushes MyBatis's local cache, without changing ordinary sharing reads. At
`READ_COMMITTED` it sees workspace creation committed since an earlier memoized read. The only
remaining discovery gap is between this adjacent control SELECT and tenant DELETE; it is not an
atomic statement-level workspace snapshot. The active-only workspace list used for restriction epoch
fencing stays unchanged and is not the cache ceiling. Person-row-backed saves still lock contributors
ascending before cache writes; restriction invalidation retains its local write fences before cache deletion
through transaction completion. No authorization or person lock moves off the transaction connection.

In routed mode the fresh method requires the exact #2165 prepared payload, even outside a
transaction, and performs memory-only validation with no suspension or checkout. Workspace creation
or deletion after preparation can make a destructive ceiling stale: a newly created workspace's
cache can escape erasure, while a deleted workspace can remain in the set. A race-safe destructive
scope contract is the routing flag-enable prerequisite **#2172**. This cache conversion does not
implement that fence or enable catalog routing; operation-level freshness must not be claimed as
complete erasure coverage until #2172 is resolved.

`getFreshForWorkspace` also never consults the explicit `withWorkspaceScope` operation boundary, so a restriction purge reached inside a workflow or scheduler operation still reads the current organization workspace set rather than the operation's reused snapshot.

The locked call chains are:

- `NotificationReconciliationService.reconcileWorkspace`: preload the scope before opening its
  `REQUIRES_NEW` transaction and before `lockBackgroundOrganization`, then pass it into scoped
  introduction ranking. Keeping the preload outside that transaction also preserves the first
  repeatable-read snapshot after the mutex wait. A failed preload
  keeps the intro pass unmanaged while the other reminder passes retain their existing isolation.
- `InteractionHistoryImportService.commit`: resolve after claiming the one-use proof and before
  `lockCurrentOrganization`, task-board or person locks. Reuse it for actual and counterfactual
  notification snapshots before and after all three kinds of history import.
- `ProviderCapturePagePersistence.commit`: resolve the initial-history scope after nonlocking
  owner discovery and before the provider connection `FOR SHARE`, organization mutex or sync-state
  `FOR UPDATE`. Preload even when initial sync appears complete because that preliminary flag can
  change before the locked recheck. Reuse it across the historical notification snapshots.
- `ProviderCaptureReviewService.decide` and `approve`: resolve before opening their mutation
  transaction and pass it through explicit and automatic historical projection. The opaque
  historical before-state retains the scope for persistence and counterfactual checks.
- `DuplicateReviewService` dismissal and reopen: resolve before authorization and decision locks,
  as described below.

The no-lock introduction entry points resolve their own scope. Cancellable AI ranking checks
interruption immediately after that lookup and before loading candidates. Warm-path signal
detection completes before signal replacement acquires write locks. Reverse-intro report figures
are generated before manual or delivery snapshot persistence locks; the scheduled occurrence claim
commits before generation. Neither read chain inherits those subsequent write locks.

Report generation and KPI reads resolve one unfiltered record-visibility scope before collecting
inputs. Full generation resolves before the restriction-epoch read's brief JVM lock, then reuses
the scope across every widget, both periods, network accounts and reverse-introduction candidates.
Cached, synchronous and asynchronous narratives consume the already-frozen figures. Manual and
delivery snapshot persistence retain their existing definition/schedule locks after generation;
the scheduled occurrence claim still commits first. No Report transaction boundary or lock
connection changes for the mapper separation. In routed mode, scope reads use the context-installed
snapshot and fail-fast guard described above (#2159 / #2165).

Note page/count, global note search, and relationship-evidence reads resolve one unfiltered snapshot
per operation. In routed mode these scope reads are memory-only from the prepared context snapshot.
The page/count pair also reuses its author-ID snapshot; both note-search candidate
streams share the scope across all batches. Evidence resolves only after the existing subject
visibility check. These read paths hold no authorization, record, or organization lock. Note
mutation authorization and `getVisibleNoteByIdForUpdate` locks do not reach these visibility reads
and stay on their existing transaction connection; no transaction boundary is split for Note reads.

Before authorization or duplicate-decision locks, dismissal and reopen obtain one unfiltered
`RecordVisibilityScopeControlAccess` view for their visibility projections. In routed mode this is a
memory-only read of the context-installed sharing snapshot, as are history-import and provider-page
lookups. Record-visibility scopes in both modes use matching prepared data without suspending the
tenant transaction. Ordinary single-database sharing and unprepared record-visibility fallbacks
keep their existing default-catalog transaction snapshot. Authorization locks and
`lockCurrentPair` remain on the tenant transaction's own connection until commit. Count/page retain
one repeatable-read transaction; summary counts need no control snapshot.

Prepared data lasts for one workspace operation and is never refreshed under tenant locks. Workspace
creation, deletion, renaming and lifecycle changes after preparation become visible on the next
operation. Inactive or missing anchors are recorded without blocking trusted lifecycle/tombstone
cleanup; `getForWorkspace` enforces the original refusal when that kind is actually requested.
Captured lifecycle routes carry their payload across control-root deletion without another lookup.
All identity, selection, override and payload state is restored together; root job spans discard their
route cache and request completion/async handoff clears the thread. Plain `ThreadLocal` state is never
inherited or copied to executors. Validated control-only selections have no usable snapshot.

Routed provisioning's `AFTER_COMMIT` callback submits initialization without waiting, because that
callback still retains the provisioning connection. The worker resolves its own route before tenant
transactions. Its dedicated managed executor has one worker and a bounded queue; saturation and
shutdown reject rather than running initialization on the callback thread. Rejection is logged and
periodic reconciliation rediscovers the committed workspace. Handoff or initialization failure keeps
global mission readiness closed until reconciliation succeeds. Accepted work drains at shutdown with
a 30-second termination wait.
Single-database provisioning stays synchronous.

CSV commits claim their one-use review proof first, then lock and revalidate the actor's create
permission before entering the duplicate-decision hierarchy. This retains the actor's custom-role
root and permission rows before the organization mutex, so later dependency permission checks are
reentrant and never acquire role locks after tenant record locks.

Person/company sharing retains source and target workspace roots before the mutex. Principal-free backfill begins at its active workspace.

Do not introduce dependency-first locking for CSV imports; ordinary record mutation is duplicate-mutex then target-first, and reversing it creates an interactive/import deadlock cycle.
The template-set synchronization root above is the narrow exception: actual custom-field
definitions, tags, and referenced companies are still created only after writable targets and
canonical identities have been locked and revalidated.

One-use duplicate review proofs are claimed before database lock acquisition according to the owning import contract. Candidate results/acknowledgements are bound to the exact workspace/request snapshot and cannot be reused across changed inputs.

Interaction-history imports use the same proof/hierarchy, lock resolved people ascending, and write bounded direct mapper batches. They must remain inert: no per-row service/rule/mention/audit/notification publishing. Notification baseline/counterfactual logic must preserve the existing concurrency checks and lifecycle cleanup.

Guided person/company/deal creation retains that duplicate hierarchy. It resolves the submitted
template without locks for preliminary validation and performs canonical duplicate review. Before
inserting the core record, it locks the record-creation template set and workspace-template root,
then referenced custom fields, tags, and relationships. Relationship locks remain
child-before-parent: stage before pipeline and person before company, with each id set acquired
ascending. The locked set revision and template version are revalidated before the core insert;
custom values, tags, and audit follow that insert in the same transaction.

Custom-field definition create/update/delete locks the actor membership, then the affected
record-creation template set, then the exact definition where one exists. The schema write and the
single affected set-revision advance commit together. Business-card imports lock their complete
create/attachment permission set before entering the duplicate hierarchy so their nested canonical
creates compose with the same membership-first order.

## Object storage lock interaction

Detailed storage behavior lives in `docs/backend/OBJECT_STORAGE.md`. Lock-order highlights:

- Metadata replacement/removal enqueues the old object for deletion in the same DB transaction; provider deletion happens after commit.
- Profile-image replacement holds the user-row lock before shared backlog admission/object write.
- Cleanup/retry workers lock/revalidate exact queue/tombstone identities before provider I/O.
- Preserve deletion-queue → quota → audit ordering, including business-card binary storage before company/person/audit writes.

## Account recovery and emailed credential tokens

Password reset and verified email change are one hierarchy, not two: a reset exists to evict the
holder of the old password and an email change moves the recovery mailbox, so each must evict the
other family's outstanding tokens. The class order is:

0. `one_time_link_flow` — the browser grant held by `OneTimeLinkFlowService.consume` (or
   `consumePasswordReset`) across the confirm/reset operation.
1. `app_user` — the account root, exclusive (`FOR UPDATE`) for every credential write and every
   token claim; shared (`FOR SHARE`) for reset issuance, which only re-reads the mailbox.
2. `password_reset_token` / `email_change_token` — the two emailed token families.
3. The audit integrity head and its foreign-key parents.

Source-token exchange and browser-grant issue run in separate transactions.

Both token families are locked under the same exclusive account root, so their relative order inside
one transaction is unconstrained; do not rely on that, and never acquire either one before the
account root.

- `PasswordResetService.requestReset` takes `app_user FOR SHARE` and re-reads the mailbox under that
  lock before issuing, so a verified email change committing concurrently cannot leave the link
  addressed to the mailbox it just moved away from.
- Both public exchange endpoints (`PasswordResetService.exchangeToken`,
  `EmailChangeService.exchangeToken`) resolve the token's owner without a lock, then take
  `app_user FOR UPDATE` before claiming. A same-owner retry re-reads the claimed token `FOR SHARE`,
  because its `REPEATABLE READ` snapshot predates the invalidation it waited behind.
- `PasswordResetService.resetPasswordByHash` and `EmailChangeService.confirmChangeByHash` hold the
  exclusive account root across the credential write and both `invalidateForUser` calls.
- `EmailChangeService.requestChange` proves the current password through the shared throttled
  confirmation, then re-reads the account under the exclusive root and refuses when the password
  hash or `session_epoch` moved since the proof.
- `EmailChangeService.requestChange` also gates privileged accounts on an enrolled passkey and a
  fresh WebAuthn step-up (#1506) and, once attestation is enforced, on a passkey approved in every
  organization where the account is privileged (#1534). It evaluates the gate first before taking
  the account root and
  audits a refusal there. Under the root, after the `session_epoch` check, it locks the account's
  assigned custom roles `FOR SHARE` through `lockAssignedCustomRoleIds`, as
  `PasswordResetService` and `WebAuthnService.finishRegistration` do, and evaluates the gate again
  against committed state. That statement is mapped with `flushCache="true"`: the request is one
  MyBatis session, so without the flush the re-check would return the standing answer cached by
  the pre-lock evaluation. A refusal that appears only under the root is audited once the
  transaction completes (see below).
- `WebAuthnService.finishRegistration` takes the account root exclusively (`lockById`), checks the
  `session_epoch`, locks the assigned custom roles `FOR SHARE`, and then evaluates the first-passkey
  confirmation fence (#1506) against committed state. `WebAuthnController` audits that refusal
  strictly at both ceremony phases, before any lock. A refusal that appears only under the root is
  recorded with the same row through `AuditService.deferFailureScoped` and appended once the
  transaction completes, because an immediate independent append would wait on the request's own
  exclusive lock (#1995). It is best-effort, with the bounds of the step-up deferral (#1986).
- Once attestation is enforced, `finishRegistration` also re-authorizes an additional passkey under
  the same locks, before `registerCredential`. The step-up proof behind the ceremony must still be
  fresh, its passkey still the account's (`findOwnedRowId`) and, for a privileged account,
  sufficient. These reads are current for the same reason as the provenance reads below.
- Under the same locks, after `registerCredential`, `finishRegistration` records the new passkey's
  provenance (#1534). It reads the recovery restamp grant before clearing it, then writes founder
  coverage, then either inherited coverage and assurance (for a fresh step-up by a passkey the
  account still holds) or break-glass assurance (for the recovery session itself). Every row it
  writes belongs to the new credential. Its reads of `org_member`, the source passkey's coverage and
  the grant are non-locking at `READ COMMITTED`. They are still current, because every writer of
  that state takes this account's root first:
  - founding (`createWorkspace`, owner row `FOR SHARE`);
  - org role changes and removal (`lockMembershipUserRoots`);
  - passkey deletion and recovery.

  Organization teardown is the exception. It removes `org_member` and coverage rows through the
  organization's cascade, holding its actor and the organization rather than this account. Coverage
  rows are children of `organization`, so each insert's foreign-key check takes the organization
  row shared. Only organizations still active at the registration's read get new coverage, which
  keeps registration out of a teardown whose fence committed first. A teardown that commits its
  fence after that read still meets the insert:
  - coverage written for an organization being torn down goes with its cascade;
  - an insert that reaches a final deletion still in flight waits for it;
  - if that deletion commits first, the foreign-key check fails and the whole registration rolls
    back as a retryable failure.

  This is accepted rather than locking candidate organizations inside every registration: the
  outcome is benign and rare, and the lock would couple passkey enrollment to the tenant lifecycle.
- Founding an organization (`OrgMemberService.addFoundingOwner`, under `createWorkspace`'s owner row
  `FOR SHARE`) inserts the owner row flagged as founder. It then covers the owner's existing passkeys
  with an `INSERT … SELECT`, which at the default isolation is a locking read. A passkey that a
  registration committed while founding waited on the account row is therefore covered. In the
  other order, the registration waits on the shared account row, then reads the committed founder
  flag. `PrivilegedCredentialProvenanceIntegrationTest` drives both commit orders against real lock
  waits.
- Attestation codes (#1534) take the authority locks of the role they attest before they touch a
  grant. Issuing, revoking, redeeming and burning one member's codes therefore serialize on that
  member's account row.
  - **Workspace grants** reuse the role-mutation order (`WorkspaceService.lockAttestationAuthority`
    over `lockRoleMutation`):
    1. accounts ascending, `FOR UPDATE`;
    2. the workspace row exclusively;
    3. both memberships;
    4. the custom-role roots involved, ascending, then their permission sets;
    5. the workspace's organization, shared and after the workspace, as teardown takes them.
  - **Org grants** reuse `setMember`'s order (`OrgMemberService.lockAttestationAuthority`):
    1. accounts shared, ascending;
    2. the organization exclusively, before the owner rows, so nothing upgrades a shared lock.
       It is locked only while active (`lockActiveById`), so a teardown fence that commits while
       the request waits refuses it;
    3. the owner rows;
    4. exactly the member's own `org_member` row (`lockExactRole`, with no join).
  - **Deletion reservations** of both accounts are read with locking reads once the accounts are
    locked.
  - **Isolation:** issuing, revoking and redeeming run at `READ COMMITTED`. Each asserts on entry,
    before any read, that it runs in an active `READ COMMITTED` transaction, because a method's
    isolation annotation cannot change a caller's transaction that it joins.
  - **Grantor re-checks** apply whatever the attestation enforcement switch and read after the
    authority locks:
    - issuing and revoking read whether the grantor's step-up passkey is still the grantor's and
      covered in the grant's organization;
    - redeeming reads whether the grantor still holds any such passkey.

    Those reads are current: the locks hold the grantor's account row, which every writer of that
    passkey's ownership and coverage takes first, and an organization's cascade needs the
    organization row these locks hold.
  - **After the authority locks**, the grant row (supersede, claim), then the coverage insert, whose
    foreign-key checks take the passkey and organization rows shared. The scoped audit comes last.
  - **Redemption** runs the passkey assertion in its own earlier transaction. Verifying advances the
    passkey's counter, and inside the redemption it would take the credential before the account
    root, against deletion and recovery's account-then-credential order.
  - **The refusal count** runs in a third transaction once the redemption has rolled back:
    1. the member's account row, `FOR UPDATE`;
    2. their redeemable grants.

    It takes no tenant parent. Its denial audit is appended afterwards at account scope, so it can
    neither wait on the redemption it follows nor invert teardown.

- `WebAuthnService.delete` runs at `READ COMMITTED`: the account root exclusively (`lockById`), its
  assigned custom roles `FOR SHARE` (flushing the MyBatis cache), then its credential rows
  `FOR UPDATE`. Once attestation is enforced (#1534), it then reads whether the target is the
  account's last sufficient passkey. Those reads are current, because each input's writers wait on
  a lock this transaction holds:
  - coverage is written by founding, registration and redemption, which take this account's root;
  - org and workspace memberships and roles change under this account's root
    (`lockMembershipUserRoots`, `lockRoleMutation`);
  - the permissions of its assigned custom roles change under those role rows.

  Teardown removes privilege and coverage together. It can only shrink the set of organizations a
  passkey must cover, so at worst it makes a refusal stricter than needed.
- `ScheduleService.create`, `update`, and `delete` run at `READ COMMITTED` (#1897): audited
  step-up pre-check → `app_user FOR SHARE` (`lockByIdForShare`) → `workspace FOR SHARE`
  (`lockWorkspaceForShare`) → assigned custom-role rows `FOR SHARE`
  (`lockAssignedCustomRoleRowsForShare`, flushing the MyBatis session cache) → privilege/step-up
  re-check with its refusal audited after completion → existing schedule mutation and transactional
  success audit.
- `ReportService.delete` runs at `READ COMMITTED` (#1897): unlocked schedule/privilege pre-check
  and audited step-up → actor `app_user FOR SHARE` →
  `isLockedBuiltInAdministratorWithAssignedRoles` (account shared, active workspace shared,
  authorization membership exclusive, then the existing assigned custom-role rows shared with
  cache flush, then HTTP actor confinement) → `lockDefinitions` (workspace
  report definitions exclusive) → `getDefinition` and not-found check → schedule/privilege
  re-check and step-up, its refusal audited after completion → existing deletion policy, snapshot
  checks, cascade and transactional success audit. The definition locks block schedule inserts
  through their parent foreign-key check.

These schedule/report account roots are **shared**, unlike email-change, passkey and
password-reset credential flows. Report writers (scheduled delivery, report creation and manual
snapshots) run as the report's user, lock report definitions or schedule rows, then audit, taking
`app_user` shared. An exclusive account root here would deadlock against those writers. The shared
root still conflicts with every invitee-timed activation, which locks the grantee's `app_user`
`FOR UPDATE`; the custom-role row locks also conflict with `lockRole`. These gates use
`lockAssignedCustomRoleRowsForShare` (`FOR SHARE OF` the role rows) rather than
`lockAssignedCustomRoleIds`, which also share-locks every custom-role membership the account holds:
with a shared account root, two of the account's own report deletions in different workspaces each
hold their own membership exclusively (`isLockedBuiltInAdministrator`) and would then deadlock on
each other's. The account root is the only one of these locks that waits for a built-in role
promotion in another workspace: privilege is account-wide, and the role-row lock reaches memberships
only through their custom roles (`PrivilegedGateConcurrencyIntegrationTest` pins both). The
workspace root must precede custom-role locks to match `lockRoleMutation`. Both the unlocked
pre-check and the under-lock re-check audit their refusals, but only the pre-check appends
immediately. The independent audit takes the account row `FOR SHARE` on its own connection, and
InnoDB queues that request behind any exclusive request already waiting on the operation's shared
root, such as a role change or an invite acceptance for the same account. That writer waits on the
operation, and the operation would wait on the audit in the application, where InnoDB can't see the
cycle; the refusal used to stall until the writer's lock wait timed out. The under-lock refusal is
therefore built at the call and appended after the transaction completes
(`AuditService.deferExportStepUpRefusal` / `deferScheduleDeleteStepUpRefusal`, #1986). That removes
the cycle but is best-effort: the append's own waits are not bounded, and a failure or a crash before
it commits loses the row, as for the integrity-head deferral (#1879). Like every chained row, it takes
its `created_at` and `integrity_actor_id` at append time. It can therefore trail the refusal by the
queued writer's own transaction, where the immediate append trailed it by that writer's whole lock
wait. And if that writer is the account's own deletion (self-service only), the row keeps the actor in
its signed `entity_id` and its labels but not in `actor_id`, as any append after the deletion does.
The re-check catches a
promotion, a newly committed schedule (G7), or a step-up that expired while the operation waited
for its locks.

Residuals: two promotions are not serialized by these shared roots. `OrgMemberService.setMember`
locks the grantee only `FOR SHARE`, and self-service workspace creation
(`WorkspaceService.createWorkspace`, #1982) holds the creator's account row only `FOR SHARE`. Both
are harmless here: nothing that authorizes the gated writes reads `org_member` or a membership the
write does not already hold (`permissionsFor`, `lockAndRequirePermissions`,
`isLockedBuiltInAdministrator`, `DeletionPolicy`), so a write that escapes the re-check is one the
account could have made without the step-up a moment earlier.

### Servlet-filter confinement recheck (G1/G5)

`PrivilegedMfaLockedAuthorization` reasserts confinement at the completed request-actor
authorization boundaries in `WorkspaceService`: all four permission-lock variants, combined
member permissions/memberships and wrappers, member authorization for credentials, built-in
administrator authorization, approval membership authorization, workspace identity, invite grants,
and role mutations. It uses the retained active membership and custom-role permission rows for
privilege **in this workspace**, not another account-wide membership scan. Built-in owner/admin
without an overlay requires no custom-role lookup. Before returning false for built-in qualification,
the ordinary administrator helper retains the request actor's local role root and permissions
**exclusively**. Workflow retry and manual dispatch then call `lockedMemberPermissionsFor` in the same
transaction; acquiring X initially prevents two enrolled actors sharing a custom role from deadlocking
on an S-to-X upgrade. The report-specific assigned-role variant keeps its shared role and permission
fence. An empty permission requirement remains membership-only for custom-role targets; their
authority is not resolved by that entry.

For a non-exempt HTTP actor with enforcement enabled the order is:

1. The helper's existing account roots and reservation checks, ascending as before.
2. Existing workspace root, memberships, role roots and permission rows, in their existing order.
3. If those rows show no local privilege, `hasWorkspaceOrganizationPrivilege` checks owner/admin
   membership in this workspace's active organization. This distinct, cache-flushed statement is
   nonlocking and uses the same transaction's isolation. The guard adds no organization lock,
   neither here nor in a new parent phase. Retaining organization S before aggregate rows could
   cycle with a queued organization writer and an aggregate holder's trailing audit; NOWAIT at the
   initial acquisition cannot prevent that later cycle.
4. `lockedWorkspaceMfaPasskeySufficiency` reads owned
   credentials and, only with attestation enforced, coverage in that workspace's organization,
   shared and NOWAIT. Handle, credential and coverage locks follow authority, matching the
   role-before-credential order of registration/deletion and attestation redemption. The query's
   workspace lock is already retained by step 2. Only privilege from the retained workspace authority
   or the organization lookup triggers credential reads. Actors ordinary in both scopes take no
   added credential locks.
5. Existing aggregate locks and business writes, only if confinement is satisfied.

Report deletion uses the assigned-role administrator variant to retain its existing account-wide
role fence before the new credential/coverage locks. Acquiring those shared role roots after a
coverage-gap lock could invert against attestation redemption's role-then-coverage order. The
ordinary administrator helper performs no assigned-role lookup. This moves ReportService's
existing acquisition into the authorization helper without adding global membership locks or
changing the step-up actor.

For the ordinary administrator helper the full order is account → workspace → membership →
local role X → permissions X → credential/coverage S NOWAIT → aggregate → writes → audit. Report
deletion instead retains account → workspace → membership → assigned roles S → local permissions S
→ credential/coverage S NOWAIT → definitions → writes → audit, without a later role upgrade.

These control-plane statements run through control-catalog routing on the **same connection**.
Both new statements are cache-flushed (`flushCache="true" useCache="false"`). The credential
statement uses current locking reads, so an old REPEATABLE READ snapshot or MyBatis answer cannot
resurrect a deleted passkey. A nonlocking credential read would be unsafe: a transaction can open
its snapshot while the actor is ordinary, then observe a promotion under locks after that account
deleted its last passkey. The credential query does not open a consistent-read snapshot. The
organization lookup is a consistent read, performed only when workspace authority is ordinary;
campaign authoring's privileged permissions take the workspace branch without that read, preserving
its first consistent read after the campaign mutex. No nested transaction or second pooled
connection is added to admission.

NOWAIT is necessary because assertion updates can already hold a credential row before their
trailing audit requests the account root shared. Waiting for that credential while retaining a
shared account root can cycle through an exclusive account writer queued ahead of the audit.
The existing exact MySQL 3572/HY000 translation returns the workspace-conflict retry response;
other SQL failures propagate. No exclusive organization root, cross-workspace membership lock,
assigned-role scan or new account-root mode is introduced. Disabled enforcement, filter exemptions,
non-HTTP/system execution and automation skip this credential check entirely.

The refusal audit uses `AuditService.deferStrictFailureIndependentScoped` from the **outer
mutation transaction**, after returning from all reads. The entry and limiter admission are
captured there; only append and admission release run after completion. Immediate independent
auditing can wait on the holder's own exclusive root or queue behind an account writer waiting
on its shared root. Delivery is best-effort under `deferFailure`'s existing contract; a failed
capture/append releases the limiter admission and never changes the refusal. Completion is
synchronous, so the HTTP response can wait on an already-running writer until that writer commits
or the database lock timeout fires. The refusing transaction has already rolled back and owns no
locks; the append cannot cycle back through it. No background delivery queue is added.

This is a local authority fence, not a global account-wide barrier. Remote workspace promotions
after the filter check (including custom-role permission grants outside this workspace),
organization grants committed after the transaction's REPEATABLE READ snapshot was established
and before the guard, grants committed after the guard, and self-service workspace founding remain
outside it. Organization grants committed before that snapshot are seen; at READ COMMITTED the
organization statement sees grants committed before it runs. The shared grantee root does not
serialize organization grants against the reader. Direct-lock ordinary record writes, mutations
without a locked-authorization helper (including existing `TagService` administration), and reads
receive no new recheck. Several operations
can already be in flight, and a bulk request can contain several writes; their existing workspace
authorization still governs them. See
[the confinement contract](../PRIVILEGED_MFA.md#confinement-recheck-during-locked-workspace-authorization)
for exact scope, response shapes, exemptions, audit limits and residuals.

Operator break-glass recovery (`MfaRecoveryService.recover`) spends its token in the same
hierarchy (#1532). Its order is:

1. `app_user` exclusive (`lockById`).
2. `privileged_mfa_recovery_redemption`: an `INSERT IGNORE` of the token's ledger row. Zero rows
   inserted means the token is already spent, and the ceremony is refused before anything is
   removed.
3. The account's `webauthn_user_entity` / `webauthn_credential` rows, through
   `WebAuthnService.recover`. That call re-takes the `app_user` lock it already holds.
4. The audit integrity head.

The token digest is bound to one account id, so only that account's root can reach a given ledger
row. The account root therefore already serializes concurrent redemptions, and the primary key is
the backstop. The ledger row belongs to the recovery transaction, so any later failure rolls it back
with the credential removal and leaves the token unspent.

The audit head sits below `app_user` in this order, and an independent audit append re-acquires the
actor's `app_user` row shared. `AuthService.requireCurrentPassword` therefore writes no audit of its
own: `MfaRecoveryService.recover` calls it while holding that row exclusively, so an append there
would wait on the caller's own lock until the InnoDB timeout, lose the event, and pin a second
pooled connection. Callers that are not already holding the account root —
`EmailChangeService.requestChange` — record the confirmation outcome themselves, before acquiring it.
The same rule places the `auth.email_change.refused` audit ahead of `lockById`, through
`AuditService.recordFailureScoped`. Both refusal paths use explicit null workspace and organization
scope on the `system` chain because privilege is account-wide (#1997). The under-lock re-check of
the privileged gate audits its refusal through `AuditService.deferFailureScoped` instead: the
entry, with its explicit null scope, actor and request metadata, is built at the call and appended
after the request's transaction completes, once its own exclusive lock is released (#1993). That append is
best-effort with the bounds of the step-up deferral (#1986): `created_at` is the append time, and a
row appended after its account was deleted keeps the actor only in `entity_id` and its actor label.

The breached-password decision in `PasswordResetService.resetPasswordByHash` follows the same rule.
The corpus lookup runs before any lock, but the fail-open decision reads account privilege under
the exclusive account root so a promotion that commits while the reset waits is observed; do not
hoist that read above `lockById`. Only the decision's audit moves.
`PasswordCredentialService.encodeScreened` never appends it independently while a transaction is
open:

- `fail_open` is appended in the caller's transaction from a `beforeCommit` synchronization, so it
  takes the audit head after `markConsumed` and both `invalidateForUser` calls (class 3 after class
  2), and a failed append aborts the credential write with it.
- `fail_closed` is appended independently from an `afterCompletion` synchronization, after the
  rollback its own exception causes has released the account root. A failure there is logged, not
  thrown, because the refusal already stands.
- Outside a transaction, either decision is appended independently at once.

## Connected-provider credentials

Provider credential transitions lock the owning `app_user` shared before the exact
`provider_connection`. Revocation egress occurs only after that transaction commits. Final local
credential destruction repeats the same user → connection order and generation-checks the exact
`revoking` row before retaining its credential-free `disconnected` tombstone.

Legacy account-deletion cleanup keeps the same root order and its separate
`disconnecting`/`purge_failed` all-catalog erasure protocol. Current-workspace capture erasure locks
the caller's exact active membership before deleting tenant-scoped capture rows and never acquires a
provider-connection lock.

## Review checklist

For any transaction/locking change:

- Relevant owning section and neighboring tests read first.
- All lock keys and ordering are explicit and deterministic.
- Authorization/state is revalidated from locked rows.
- No broader root is acquired after child locks in violation of the contract.
- Network/provider I/O is outside locked transactions unless explicitly required.
- Isolation level matches every transactional caller that wraps the mutation.
- Failure/retry/idempotency behavior is preserved.
- Targeted concurrency/architecture tests pass.
- Independent correctness/concurrency review attempts to find deadlocks, stale authorization, lost updates, and partial side effects.

## Retained attachment malware scanning

Scan claims use READ COMMITTED and short REQUIRES_NEW transactions. Discover the URL without locks,
lock all same-workspace attachment references using the established URL lock ordering, then lock
and revalidate the selected row and due predicate. One UUID/database-clock claim covers every
reference to the object. Provider reads and scanner calls happen outside the transaction. Decision
writes take the same reference locks and require the exact still-live owner; stale completions cannot
release successor state. Administrative quarantine uses permission roots before the same attachment
reference locks and revalidates held permission authority after the target lock. Never acquire
membership roots after claiming an object. See `docs/MALWARE_SCANNING.md` for expiry/recovery limits.

Ordinary attachment deletion never removes a reference on the strength of its unlocked discovery
read. The generic route takes no membership root. When the discovery row already needs quarantine
authority, the route delegates to the quarantine service before taking any attachment lock; that
service keeps its permission-roots-first order and re-reads the row under lock. Otherwise the route
locks the URL references, re-reads the exact row with a locking read, and refuses with 409 when the
row now needs quarantine authority. It does not delegate at that point, because delegating while it
holds attachment rows would take membership roots after them. The assistant route already holds the
caller's membership and session roots, so it re-reads the exact row the same way and checks
quarantine authority in place.

## Ownership consent (#1534 slice 4o)

Existing-scope owner promotions create offers; acceptance grants ownership. The HTTP, MFA, locking, control-plane, notice and cleanup contracts are in [Ownership offers](OWNERSHIP_OFFERS.md). Founding, admin changes and last-owner checks retain their existing behavior. The self-offer card is authoritative on every catalog layout; ordinary inbox delivery retains the existing cross-catalog limitation.

Offer closure must not rewrite an account FK's supporting index after scope roots. V229's only offeree-leading index is immutable `(offeree_user_id,id)`; pending lookups use a state-leading index. Nullable closure attribution has no FK and is explicitly cleared before erasure audit heads. Party cascades remain. This preserves deleting-account X → workspace roots → organization roots → guards → offer rows → audit heads without acquiring counterpart accounts after roots.


Account deletion keeps its reservation throughout every guarded tenant-catalog erasure transaction.
Only after all catalog transactions commit does `eraseAfterCatalogs` discover and erase control
ownership offers and closure attribution in the existing account → workspace → organization → offer
→ audit order. A catalog guard refusal skips control offer erasure and its routed notice resolution; earlier
catalog commits retain their existing behavior. Routed offer closure
accepts an already-deleted tenant notice, because catalog erasure may have removed it first; it
never recreates that notice or releases the reservation. Per-catalog erasure keeps its existing
isolation.

## Organization assistant privacy (V236/V237)

Organization privacy and workspace mission privacy use separate generations. The scoped acquisition
order is content roots (session, mission, Java-sorted proposal parents, ordered tool children, turns,
segments), domain/storage roots (including URL references and exact attachments), existing local
restriction stripes, organization publication fence, organization database fence S/X, ascending
workspace barriers, operation/placement rows, tenant work rows, audit heads, then run leases last.
Never acquire content/domain roots after privacy authority. Cleanup discovers identities before
opening its ordered transaction. Read projection retains current authority through DTO construction.
Mutation admission retains it only after delegate roots, then rechecks deadlines. Approval waiting
past its 72-hour deadline must refuse even if generation did not change. Cancellation, expiry and
erasure retain terminal/lease settlement atomicity and conservative provider accounting.

Control locks remain on the transaction-bound connection through control-catalog routing.
Organization invalidation retains the local publication write fence through commit or rollback.
New-organization provisioning creates only its fence and bootstrap operation before the first
workspace insert. It must not enumerate the empty workspace range with `INSERT ... SELECT`:
under REPEATABLE READ, concurrent registrations can retain shared next-key locks on the same gap
and deadlock on their first workspace inserts. The after-commit initializer installs placement
intents, and completion re-enumerates coverage. See [MySQL's statement locking rules](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html).
Startup and periodic reconciliation also page incomplete organizations independently of workspace
roots. Organization completion runs on the control plane under READ COMMITTED and organization X,
re-enumerates current workspaces and retains all historical pending receipts. This completes empty
V236 bootstraps and retries completion after the last workspace disappears without inventing evidence.

Notification source predicates hydrate only active recipient memberships, optionally the exact
requested workspace; workspace maintenance hydrates that workspace alone. Suspend any tenant
transaction before installing the control route for each fresh authority read. Notification reads
and updates acquire no publication stripes: brief delivery already retains database organization S,
and acquiring publication S there would invert invalidation's publication X → database organization
X order. The SQL generation predicate still hides pointers invalidated at the captured generation.
Brief notifications contain only fixed titles, `{kind, sessionId}` data and a session action link;
they carry no assistant-derived text. A read racing invalidation can reveal only that a brief
existed, including temporary page/count skew. Session/transcript content reads retain their full
privacy authority through projection. The brief payload guard test must change before any content
can be copied into notifications, and such a change requires revisiting this bounded guarantee.

Actual realtime, export and chat-file output holds its read fence and performs a fresh control read
on a separate READ COMMITTED transaction; an export snapshot is never that authority. Do not hold
database privacy locks across an export or object transfer. One serving JVM remains mandatory.
Realtime broker messages carry immutable internal organization authority through the outbound queue.
The delivery executor checks it and captures it on each encoded frame before Spring's concurrent
session buffer. The factory-installed concurrent session preserves Spring's limits and overflow
behavior. Its delegate below the buffer takes the local publication read fence, performs the fresh
authority check, and retains the fence through the actual socket write. Stale or unavailable frames
are dropped at this boundary even when another thread flushes them after the encoding handler has
returned. No publication fence spans a handler or a buffer drain; each assistant write has its own
fence, and heartbeats/non-assistant frames acquire none. Thread-local encoding provenance is never
the authority for a frame flushed on another thread.

The committed invalidation listener runs after the earlier completion callbacks release local
fences and suspends completed transaction resources before placement routing. Workspace withdrawal
fanout pages control-plane member IDs and rechecks live recipient access. It carries only cutoff
metadata and depends on no session roots or content locks; ordinary content publication keeps the
full source guard.

Object receipts are subordinate to the exact deletion queue row. Preserve queue → quota → audit
order; receipts never acquire privacy/work/content roots. Every chat-reference removal either
transfers responsibility atomically to accepted erasure work or defers to privacy cleanup, including
quarantine administration. Queue absence never proves final deletion.

A workspace already fenced as `tearing_down` reuses v1 offboarding work for lifecycle-authorized
full local cleanup. It retains the existing mission cutoff and uses bounded full-workspace discovery
and uncapped local residual/object checks. It does not advance organization authority or create
a competing v2 generation. Accepted organization operations finish and record evidence first.

V2 DSR recovery, progress projection and C2 may replace tenant work evidence with the exact durable
verified control receipt only when the workspace root is absent. The receipt must retain verification
time and work revision. Active workspaces still route through enrollment and residual checks,
including restoration recovery. Missing or pending receipts remain blocking. C2 takes live workspace
roots in ascending order, uses READ COMMITTED, and reconciles complete operation coverage under
organization X before appending its strict audit; deleted roots require their receipt at that boundary.

Reconciliation preserves an exact verified receipt while its workspace is terminally
`tearing_down`, even after the tenant sweep removes local work. Teardown retries skip that
operation's tenant batch and still run full local lifecycle cleanup and residual verification.
V2 C2 refuses a surviving terminal workspace root; only root deletion enables receipt-only
completion. Active workspace restoration still reopens stale local evidence.

### Mission singleton proposals (V238)

All mutation units use READ COMMITTED. A is the existing assistant authority order:
actor/named users S ascending, workspace S, memberships X ascending, then custom-role and
permission rows X ascending, with organization lifecycle validation. Discover principal/source
keys outside the mutation transaction and revalidate exact identities under locks.

P is the privacy admission sequence after all content/domain roots: existing local restriction
fence, organization publication fence when publishing, organization database fence S, then workspace
barrier S. It retains the session's original organization generation as well as the mission's
workspace generation; a workspace barrier alone never authorizes runtime-3 content. Omitted
resources do not change the global order. The publication fence never follows organization S.

| Operation | Acquisition order and atomic contents |
|---|---|
| Allocate | A → session X → run X → existing actions X; allocate slots; P; claim lease last if applicable. |
| Bind/replay | A → session X → run X → action X → existing tool X → current turn X → segment X → required domain roots → P; bind/canonical insert/revision together; no lease. |
| Bookkeeping/checkpoint | A → session X → run X → affected actions X → tools X → turn X → segment X; preserve action/source inventory; P before exact lease release last. |
| Freeze/report | A → session X → run X → all actions X by Java ID order → all canonical calls X by Java ID order → turn X → segment X → source validation → P → report/membership/accounting/state → lease release last. |
| Approve one | A including named principals → session X → run X → action X → canonical call X → existing domain hierarchy → final MutationAdmission P/deadline → audit → result/state; no lease. |
| Discard one | A → session X → run X → action X → call X → P → deadline/rejection/state; no domain-edit locks or lease. |
| Undo | Discover source before legacy transaction; A → session X → run X → action X → call X → inverse hierarchy → final MutationAdmission P/deadline; no waiting requirement or lease. |
| Waiting cancel | A → session X → run X → all actions X → all calls X → withdrawal/state → applicable P; no attempt/lease acquisition. |
| Running cancel/fail | Authority/maintenance roots → session X → run X → all actions X → all calls X → turn X → segment X → withdrawal/accounting/state → applicable P → exact lease release last. |
| Review expiry | Maintenance session X → run X → all actions X → all calls X → applicable P → conditional withdrawal/failed review_expired; independent of owner permission/provider and lease. |
| Exact output/metadata | A → session S → run S → organization S → workspace barrier S; retain original authority through frozen membership/card projection. |
| Mission page | Discover without a transaction; each item uses A → its session S → run S → organization S → workspace barrier S in an independent transaction; project locked revisions and canonical counts together, then release before the next item. |
| Realtime enqueue | A → exact session S → run S → P; revalidate current requester, source version and both privacy generations through local broker enqueue after the mutation commits. |
| Privacy pruning | Session/run → all actions → all calls → attempts only when settling → storage roots if needed → organization S → workspace barrier S → operation/placement rows for v2 → exact privacy-work X → bounded deletion/cursor revision; lease last only when settling. |
| Privacy completion | Organization authority → workspace barrier X for v1, S for v2 → operation/placement rows for v2 → work X → unconditional fresh residual reads; no content roots/leases. |

Acquire every action before any tool, never alternating pairs. All action/canonical writes
retain root X. Locking selects flush MyBatis cache and disable cached results. Do not seed a
mutation transaction with permission reads before A. Domain roots precede retained fences;
MutationAdmission checks deadline after remaining lock/fence contention, immediately before
mutation or admitted no-op. No trailing privacy fence follows audited delegate execution.
The audit-integrity head still precedes lease release and follows restriction/privacy fences.
Mission participants are proxied MANDATORY helpers; never enter reactive REQUIRES_NEW
publication under mission roots. Approval/discard batches have no enclosing transaction.
Direct mission tool execution, proposal preparation and decision coordination enter the workspace
route before tenant work. Singleton publication and report freeze prepare the route before their
independent REQUIRES_NEW, READ_COMMITTED transactions, so source revalidation under content locks
uses the installed snapshot. Proposal output, item and version reads, plus each mission metadata
transaction, prepare and install the workspace snapshot before opening their READ_COMMITTED
transaction in both modes. Read-only route and selected-record callbacks remain separate from
mutation callbacks; enclosing placement and scope state are restored on every exit.
Explicit source-reading content requests and approvals resolve record visibility before A;
already-authorized metadata participants resolve it before privacy/content projection. Approval batches
share a deferred operation scope across their independent item transactions. Discard prevalidation
does not resolve domain targets. Missing or inconsistent tenant snapshots escape metadata and
batch degradation handlers; ordinary lookup failures retain the existing per-item handling.
Retry deadlocks only from fresh bounded transactions. Provider/network work stays outside
transactions. Review projections retain A → exact session S → root S → organization S →
workspace barrier S through all DTO materialization; they are not database-read-only because
A includes membership locks. List discovery holds no transaction. Each item enters its own
proxied REQUIRES_NEW projection and releases its authority/session/run/barrier locks before the
next item. No transaction retains one item's privacy authority while acquiring a later item's
content roots. Expected content-access refusals settle inside the metadata projection participant,
without crossing a REQUIRED proxy and marking the enclosing metadata read rollback-only;
safe metadata and cancellation remain available.

Mission frames preserve immutable organization authority through the same outbound interceptor,
encoded-frame provenance and concurrent-session delegate as ordinary assistant frames. The final
socket write takes publication S, freshly checks control authority in a separate READ COMMITTED
transaction and retains publication S through the write. Broker enqueue validation alone does not
authorize a buffered frame after organization invalidation.
