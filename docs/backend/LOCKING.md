# Backend Locking and Transaction Contract

Connex has multiple concurrent aggregates whose lock orders deliberately interoperate. This document centralizes the contracts that previously lived in the always-loaded backend agent guide.

Read the relevant section before adding/changing `FOR UPDATE`, transaction isolation, multi-aggregate writes, lifecycle writes, duplicate-decision behavior, leases/retries, or network/provider work around transactions.

## Global principles

- Discover keys without locks when the contract says to do so; sort deterministic key sets in Java before acquiring exact locks.
- `ORDER BY ... FOR UPDATE` is not a substitute for an explicitly ordered series of exact lock acquisitions when a contract requires deterministic ordering.
- Revalidate the exact locked rows before deriving authorization or performing writes. Pre-lock permission/state snapshots are preliminary only.
- Acquire broader/root locks before child/aggregate locks according to the owning contract; do not reacquire a broader root later in the transaction.
- Keep provider/network I/O outside database transactions unless a subsystem contract explicitly requires and bounds otherwise.
- Read control-plane data that a tenant write needs only for its response — deal-collaborator profile hydration, for example — after that write's transaction has completed. Suspending a routed tenant transaction to read the control catalog borrows a second pooled connection while the write still holds its row locks and the workspace audit-chain head, so under `catalog-per-placement` enough concurrent requests exhaust the pool and hold those locks for a whole connection timeout. Control-plane state a write must consult before it commits (quiet-hours evaluation) keeps the suspend-and-read shape, and those paths budget two pooled connections per concurrent request.
- Changes to lock order or transaction isolation are Tier 3/high-risk and receive focused concurrency/correctness review.

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
mutations — person owner change, deal, company, task, saved views, AI chat turn persistence — lock
the `workspace_member` row before reaching the root in that trailing audit. An exclusive root at
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

The built-in administrator exception (`WorkspaceService.requireBuiltInAdministrator`) is not a grantable permission, so any overlay denies it whatever that overlay contains. Transactions that act on the exception while holding record locks must take the exception's authorization snapshot — user root `FOR SHARE`, active workspace root `FOR SHARE`, exact membership `FOR UPDATE` — through `isLockedBuiltInAdministrator` **before** their first record lock, and assert it later with `requireLockedBuiltInAdministrator`. Report deletion, report-snapshot deletion, deal deletion, and deal-document deletion take it at the top of the transaction, ahead of `lockDefinitions`, the duplicate-decision mutex, and the deal/document row locks respectively; approval cancellation asserts it in place because `lockApprovalMutationRecipients` already holds those exact rows. The plain non-locking form remains correct for the read-only member-scope analytics gates, which take no lock afterwards.

Custom-role creation uses `lockRoleCreationAuthorization`, which takes no role id and checks the grant ceiling after locking current role-management authority. Existing-role updates use `lockRoleMutationAuthorization` with a non-nullable role id and always enforce owner authority, retained recovery, and the grant ceiling after locking. Custom-role update/deletion locks the exact role after the common roots. Assigned custom roles cannot be deleted until members are reassigned through the permission-ceiling-checked mutation.

Member removal/leave/invitation decline also lock the departing user root. The target user's globally ordered membership set is acquired at the documented point during removal so notification cleanup cannot invert membership lock order.

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
5. Per workspace: delete the pending row, and only when that delete claimed it, delete the recipient's notification baselines and notifications and write the scoped audit event.
6. Bump the recipient's notification state version once, after all deletions.

Discovery is deliberately unfiltered by `lifecycle_state`, so a grant parked in a suspended or tearing-down tenant is revoked too. The account root is held throughout, and every invite-grant writer locks the recipient root first, so no new pending row can be committed behind the sweep.

The sweep runs single-catalog: it issues each workspace's tenant-plane notification deletes on the request's own connection without installing that workspace's placement, which is inert while every workspace lives in one database. Before placement routing is enabled it must run each workspace's deletes inside `tenantWorkScope.withWorkspacePlacement`.

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

Membership credential cleanup preserves each path's existing user, workspace, and membership lock
order. At the end of member removal, invitation decline, leave, and fresh-membership cleanup it
acquires the workspace root `FOR SHARE` in any lifecycle state, then that workspace's organization
root `FOR SHARE`, immediately before locking `api_credential` children `FOR UPDATE` in ascending
credential id, then deletes and audits them. Both root locks are unconditional: taking them only
when credentials exist would place them after the credential rows and defeat the ordering. This is
safe in the reverted-territory transactions it runs inside — member removal, decline, leave, invite,
invite link, and SSO just-in-time membership — because every one of those callers already takes the
same organization root `FOR SHARE` through its own trailing audit's foreign-key parents, after the
same set of tenant-plane child locks. Cleanup returns without locking children when either root row
is already gone, because the workspace foreign key has then already cascaded the credential children
away. A `tearing_down` root still owns live credentials, which are deleted and audited normally, so
the workspace lock used here carries no `lifecycle_state` predicate.

`notificationMapper.lockRecipientMemberships` X-locks the departing user's `workspace_member` rows
in every workspace before `detachMemberContent` reaches credential cleanup, so the new organization
root request sits behind cross-workspace member locks. That is safe today only because every caller
of `detachMemberContent` first takes the target `app_user` row `FOR UPDATE`;
`prepareFreshMembershipInWorkspace` is the one caller with no `app_user` lock, and it never reaches
an audit for the reason given above.

#### Account erasure

Account erasure locks the account root, discovers credential-referenced `(workspace_id,
organization_id)` pairs without locks, then takes exactly one ascending workspace-root pass over
owned roots, referenced roots, and the current tenant workspace when resolved. Owned workspace
roots are exclusive; referenced-only and current-only roots are shared. It next takes one ascending
organization-root pass over owned, referenced, and current tenant organizations with the same mode
rule before locking owner rows. Credential cleanup acquires no roots itself. It locks exact
credential children in `(workspace_id, credential_id)` order and deletes them, and returns the
retained audits unemitted. Nothing is re-checked afterwards.

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
each of those workspaces wait, where on `origin/main` the same transaction froze exactly one chain
scope.

There is no residual reference count, and re-adding one would be a defect rather than a belt. A
count issued after the deletions is a consistent read, so it still counts every planned row another
transaction deleted after this transaction's read view was created, and it aborts the erasure in
exactly the case the loop is built to tolerate — two erasures that plan the same credential, one
account having revoked the other account's credential, meet that case on every run. A locking count
is worse still: an equality scan of the non-unique creator and revoker indexes takes next-key locks
on neighbouring rows in workspaces the erasure holds no root for.

The plan needs no re-check because the reservation already closes it. `reserve` commits in its own
transaction — `UserService.delete` runs the catalog fan-out with `Propagation.NOT_SUPPORTED` — and
its `app_user` update waits behind the `FOR SHARE` that `lockedMemberAuthorization` holds on that
row for every in-flight issuance and revocation, so each of those has committed before the
reservation becomes visible. Once it is visible, `lockedMemberAuthorization` refuses the flagged
account, so no further `created_by_id` or `revoked_by_id` reference can be born. The erasure's read
view is created after the reservation committed, so `listByAccountReference` observes every
reference that will ever exist. A row that vanishes between that read and its `FOR UPDATE` was
deleted by another transaction — membership cleanup, a workspace or organization cascade, or
another account erasure — and needs no action here. Both account foreign keys (`ON DELETE CASCADE`
on the creator, `ON DELETE SET NULL` on the revoker) mean a stray row could not block the
`app_user` delete in any case.

Account erasure deletes every credential row the account created **or revoked**, so a revoked
credential created by somebody else is destroyed when its revoker is erased. The revoker foreign key
keeps `ON DELETE SET NULL` purely as the rollback backstop for an older binary that never ran the
service-level cleanup.

#### Ordering rules recorded here so round 17 does not reopen them

- **No path may take an `organization` root EXCLUSIVELY and then lock a `workspace_member` row.** The one carve-out is the `app_user` cascade at the end of account erasure, which X-locks `workspace_member` rows after the owned organization roots were taken exclusively; that path is identical on `origin/main`, is invisible to the file-scoped `OrganizationRootLockOrderArchTest`, and is tracked under #1582 rather than by this rule.
  Management takes the organization root before the membership row, while member detachment takes
  membership rows before the organization root its trailing audit acquires. That inversion is inert
  only while every organization root involved is shared. `OrganizationRootLockOrderArchTest`
  enforces the rule.
- The pre-existing `workspace_member` ordering inconsistencies are tracked in GitHub issue #1582 and

  were deliberately not changed by the public API credential increment. Specifically:
  the organization-root inversion inside `removeMemberInTransaction`, which takes the workspace root
  exclusively first (`lockRoleMutation` → `lockWorkspaceMutationRoot`,
  `WorkspaceService.java:1296`) and therefore does not invert on the workspace root at all, but
  reaches the organization root only after X-locking active owner rows (`lockActiveOwnerMembers`, `:1509`)
  and entering credential cleanup, whose `lockWorkspaceOrgIdForShare` and `lockByIdForShare` pair
  consequently runs after those owner rows — the reverse of the erasure's
  organization-roots-then-owner-rows pass; the
  actor-versus-target erasure cycle created by `lockForeignKeyParents` taking the actor's `app_user`
  row `FOR SHARE` after the roots; `ensureHead`'s `INSERT ... ON DUPLICATE KEY UPDATE` deadlock on a
  first-ever concurrent head for one scope; and `lockRecipientMemberships`' cross-workspace
  `workspace_member` X-locks preceding credential cleanup. Every one of these shapes already
  exists on `main` through the same transactions' trailing audits; credential cleanup only makes
  the last two explicit, and none of them is introduced or worsened here.
- Membership-first record mutations (`lockAndRequireMember` → audit) racing a member's leave (`lockById` → `lockRecipientMemberships` → membership delete): the ordering is `origin/main`'s and unchanged here; this branch only appends the shared-root credential tail to the leave. A dedicated drill for that race was retired from this branch because it asserted a root-first leave design that was reverted; it belongs with #1582.

## Lifecycle, APPI requests, and organization SSO

Control writes share this root order:

1. Discover without locks.
2. Lock referenced `app_user` roots ascending.
3. Lock workspace or cleanup-tombstone roots ascending without joining the organization.
4. Lock the organization.
5. Lock exact `org_member` rows ascending by user id.
6. Lock operation/request rows.
7. Revalidate every root before side effects.

APPI mutations perform preliminary workspace/person validation outside the write, then retain actor user, all previous/requested subject workspace roots ascending, and the shared organization lock while locking the requested tenant person.

Linked mutations use the established reserved tenant-connection pattern: reserve non-auto-commit tenant connection before control locks, prove the person through a separate short session, then join the final control write to the root-lock transaction while retaining the person lock through commit. Roll back the reserved transaction before release.

Updates lock the exact organization-scoped request after roots and compare persisted state to the preliminary snapshot before deriving/writing the diff.

SSO returning/existing-user paths discover non-locking then lock user, stored workspace when applicable, and organization before revalidation. New JIT paths without a user lock stored workspace and organization before revalidating absence/inserting.

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

The session row is the per-session mutex. Allocate message sequence with the established `MAX(seq)+1` calculation while holding the session root, insert, and update `last_message_at`. Do not lock the message aggregate or use `MAX(seq) ... FOR UPDATE`.

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

Resolution — `MailConfigResolver.resolveForWorkspace` and `resolveWorkspaceOnly` — locks the
authenticated actor's `app_user` root `FOR SHARE` when a `User` principal is present, then the workspace
root `FOR SHARE`. It holds these roots across the configuration and secret reads so the endpoint and
password come from one generation. `SecretStore.get` reacquires those same roots in that order;
acquiring the actor root first avoids an inversion with a queued exclusive user lock. Background
resolution without an actor takes only the workspace root. Callers already holding roots must follow
the same actor-before-workspace order. Resolution performs no provider I/O; the SMTP connection is
made after the resolving transaction. A missing actor row or workspace root resolves to `null` — "sending
disabled" — so fire-and-forget senders keep their contract instead of seeing an exception escape.

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

- Campaign update performs any membership, parent-campaign, and other ordinary validation reads
  only after the campaign mutex.
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

Audience delivery dispatch claims with a bare `pending` → `dispatching` compare-and-set and stores
no lease owner, lease expiry, or attempt-target fingerprint. Its only persisted age anchor is the
frequency reservation, which the worker writes as database time plus the hard provider deadline in
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
such a row. The same pass then settles every audience send that is still `running` with nothing
`pending` or `dispatching`: it completes the send and refreshes the counters, without resolving a
provider, so a connector disabled after the worker died cannot keep the send running. The completion
is a single compare-and-set that proves the absence of `pending` and `dispatching` rows in the same
statement that writes `completed`, so a live worker's terminal write cannot land between the proof
and the completion; because no delivery can return to `pending` or `dispatching` afterwards, the
counter refresh that follows a completion reads every delivery in its final state. A `dispatching`
row may belong to a live worker, so that send stays `running`: the worker's own settlement completes
it after its terminal write, an attempt it abandons after reserving is swept and settled by a later
pass, and one abandoned before reserving is left to the dispatch loop's own settlement. That send is
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
reads the same failed rows from the clustered index. The bound that leaves: if a pass dies between
the sweep's compare-and-set and its counter refresh, an already completed send under-reports
`failed_count` until an operator resolves the reconciliation row the sweep created, which refreshes
the counters itself. The delivery row is terminal and reconcilable throughout, so nothing is lost
except the send-level counter. Settlement takes the same single-row auto-commit writes on
`campaign_send` the dispatch loop already runs, so it adds no lock edge. A slow but live worker that writes after the
sweep loses its `status = 'dispatching'` compare-and-set and leaves the row reconcilable. It then
attaches its provider id and message id to that swept row through a second single-row
compare-and-set, and changes neither status, reconciliation state, nor the reservation, so provider
bounce and complaint webhooks still resolve to the row and record suppression and consent revocation.
That statement accepts a swept row with no provider id whether it is still awaiting reconciliation or
an operator has already resolved it — a resolved row keeps neither the sweep's `last_error` nor its
reconciliation marker, so that branch is anchored on the operator outcome, and an audience row is
never returned to `pending`, so no later attempt can own the correlation it writes. An expired
triggered claim marked ambiguous stores no message id, so its webhooks match no row; operators apply
those by hand (`docs/DELIVERABILITY.md` §3.1). Audience rows stranded before any reservation, and
rows without a person (which are never reserved), have no age anchor and are not swept.

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
  fresh WebAuthn step-up (#1506). It evaluates the gate first before taking the account root and
  audits a refusal there. Under the root, after the `session_epoch` check, it locks the account's
  assigned custom roles `FOR SHARE` through `lockAssignedCustomRoleIds`, as
  `PasswordResetService` and `WebAuthnService.finishRegistration` do, and evaluates the gate again
  against committed state. That statement is mapped with `flushCache="true"`: the request is one
  MyBatis session, so without the flush the re-check would return the privilege and passkey answers
  cached by the pre-lock evaluation. A refusal that appears only under the root is not audited (see
  below).

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
The same rule places the `auth.email_change.refused` audit ahead of `lockById`. The under-lock
re-check of the privileged gate throws without auditing, because any append there would block on
the request's own exclusive lock.

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
