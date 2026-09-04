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
Workspace-supplied destinations track the validated pinned-address socket; instance-default SMTP
tracks a normal hostname-resolving socket without pinning. Closing the raw socket interrupts a blocked
Angus `sendMessage` even while its synchronized transport monitor prevents `Transport.close()` from
entering; remaining-budget connect, read, and write timeouts stay subordinate inactivity bounds. A pre-egress
deadline is definitive; a post-egress abort is persisted as ambiguous failed-delivery evidence with
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
