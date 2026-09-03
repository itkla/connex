# Backend Locking and Transaction Contract

Connex has multiple concurrent aggregates whose lock orders deliberately interoperate. This document centralizes the contracts that previously lived in the always-loaded backend agent guide.

Read the relevant section before adding/changing `FOR UPDATE`, transaction isolation, multi-aggregate writes, lifecycle writes, duplicate-decision behavior, leases/retries, or network/provider work around transactions.

## Global principles

- Discover keys without locks when the contract says to do so; sort deterministic key sets in Java before acquiring exact locks.
- `ORDER BY ... FOR UPDATE` is not a substitute for an explicitly ordered series of exact lock acquisitions when a contract requires deterministic ordering.
- Revalidate the exact locked rows before deriving authorization or performing writes. Pre-lock permission/state snapshots are preliminary only.
- Acquire broader/root locks before child/aggregate locks according to the owning contract; do not reacquire a broader root later in the transaction.
- Keep provider/network I/O outside database transactions unless a subsystem contract explicitly requires and bounds otherwise.
- Changes to lock order or transaction isolation are Tier 3/high-risk and receive focused concurrency/correctness review.

## Workflow lifecycle and account offboarding

Workflow lifecycle writes and permanent account offboarding share a hierarchy:

1. Discover identity-bound keys without locks.
2. Lock referenced `app_user` roots in ascending user id.
3. Lock candidate workspace roots in ascending workspace id (`FOR SHARE` or the existing stronger owner lock as required).
4. Lock required `workspace_member` rows in ascending user id.
5. Lock the actor's custom `workspace_role` root and exact permission row when current authorization depends on it.
6. Lock exact workflow → workflow-version → rule point keys in that order.
7. Revalidate locked state before writes.

Version/rule key discovery is non-locking and Java-sorted before individual exact `getByIdForUpdate` calls. Final workflow authorization uses the locked membership's current role/role id, not an earlier `WorkspaceService` snapshot.

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
5. Lock distinct actor/requested custom `workspace_role` roots in ascending id, then their complete permission sets with ordered locking reads.
6. Perform final authorization from the locked actor membership/role state.

Owner demotion locks exact workspace owner rows while the workspace root is already held; do not reacquire broader owned-workspace roots.

Custom-role update/deletion locks the exact role after the common roots. Assigned custom roles cannot be deleted until members are reassigned through the permission-ceiling-checked mutation.

Member removal/leave/invitation decline also lock the departing user root. The target user's globally ordered membership set is acquired at the documented point during removal so notification cleanup cannot invert membership lock order.

Invite grants lock creator/known-recipient users ascending, reject deletion reservations, then lock active workspace/organization, memberships ascending, and creator custom role when applicable before testing grantability. Claim paths repeat authorization immediately before the exact claim.

Pending-membership approval locks user, workspace exclusively, organization for share, and exact pending membership before domain/version-gated activation.

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

## Task board mutations

Task creation/full update lock the requested active membership first. Mutations that can change board positions run at `READ_COMMITTED` and acquire the exact tenant-plane `task_board_lock` workspace root through the atomic insert-or-update mapper statement.

- Create: membership → board root → insert.
- Full update: membership → board root → exact task rows.
- Completion/deletion/movement: board root → exact task rows.
- Due-date-only reschedule: exact task only; no board root.

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

## Duplicate review, record mutation, and imports

`DuplicateDecisionLockService` serializes candidate-affecting mutations across the same-organization visibility domain.

For interactive person/company/deal mutation, sharing, imports, OCR, and identity backfill:

1. Use `READ_COMMITTED` where the owning request contract requires it.
2. Lock actor user when present.
3. Lock every active workspace root required by the mutation in ascending id.
4. Lock required active memberships.
5. Lock the active organization shared.
6. Lock the organization duplicate-decision mutex exclusively.
7. For a CSV import that uses an auto-create custom-field mapping, lock the affected
   record-creation template set before writable record targets. Lock and revalidate
   `CUSTOM_FIELD_MANAGE` first when the definition is absent. This set row is a schema
   synchronization root, not dependency creation. After the set lock is held, recompute the
   set of absent definition keys and fail closed with a review conflict if it differs in
   either direction from the pre-lock reading, so a definition created or removed concurrently
   under `READ_COMMITTED` cannot be silently adopted or created twice.
8. Lock writable record targets ascending by record id.
9. Lock canonical identity groups in deterministic kind/value order.
10. Requery/revalidate current identities while locks are held.

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
