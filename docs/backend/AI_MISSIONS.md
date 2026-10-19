# Durable read-only missions

Phase 2 slice 1 introduces `record_review_v1`, a single-record mission with one logical
segment and at most three attempts. The schema and privacy cutover are tenant migration
V232. V231 belongs to the independently integrated proposal-expiry lane; V232 does not
depend on proposal-set internals.

## Execution and scope

Create, claim, control, provider accounting, and report settlement use READ COMMITTED
transactions. Claims install a real linked chat turn, reserve the remaining root allowance,
and acquire only `AGENT_RUN` after content roots and the retained privacy barrier. Every
callback binds the exact run, segment, execution generation, turn, and privacy token.
A stale attempt cannot publish or release a successor. Settlement writes the canonical
report and terminal state and releases the lease atomically.

The root lifetime is at most 900 seconds and each attempt at most 180 seconds. Pause,
resume, and retry preserve the original deadline and cumulative caps; there are at most
three attempts. Every actual provider dispatch reserves a decision before egress through
`AiInvocationService`; known usage is monotonic, while uncertain dispatch consumes the
remaining reservation conservatively. Organization budget accounting and per-invocation
audit remain independent gates. The ordinary loop reserves its closing decision, and an
exhausted allowance refuses further provider calls. Mission repairs consult the remaining durable
dispatch allowance before assembling each request, including native repair retries, and force
the final-answer schema for its last decision. Ordinary parsed-step budgeting is unchanged.

Each attempt rebuilds a masked context from the bounded goal and a fresh read of the
selected record. Session history, summaries, attachments, private notes, replay state,
persisted token maps, and provider thought signatures do not enter it. Checkpoints,
durable plans, questions, proposal collections, and write execution belong to later slices.
Shared prompt serialization and envelope constants are unchanged.

The allowlist is exactly one person, company, or deal. Primary reads use exact ID and
workspace predicates; person reads also exclude archived, suspended, and provision-ceased
rows without joining their company. Bounded person/deal notes, tasks, and activities retain
their existing SQL privacy predicates but skip linked-record hydration. Internal references
outside the primary are redacted before output. Original member-label provenance remains
transient for masking. Company history currently reaches evidence through related people
or deals, so slice 1 returns no such history. Child rows with another person/deal parent
are also omitted, preserving masking without retrieving secondary record labels. This
can yield fewer rows than the bounded query requested; there is no unbounded refill.
Task evidence has no task argument handles.

Only `get_record`, `get_records`, `list_activities`, `list_tasks`, and restricted `find_tools`
are available. Retrieval, handle registration/resolution, tool dispatch, and durable tool
insertion independently enforce the ceiling. Known-ID ordinary decisions discover origin
before legacy roots and reject mission artifacts. No CONFIRM or AUTO write is reachable.

The scheduler uses one dedicated worker with no pending queue and visits at most 100
workspaces per pass, one candidate per workspace. Provider I/O never occupies the shared
maintenance scheduler. Readiness and capability refusals precede claim and consume no
attempt; execution expiry and privacy maintenance remain independent of the AI flag.

## Session API and presentation

The session create endpoint accepts only `record_review_v1`, the primary scope, bounded
goal/limits, and a UUID request key. A successful create returns 202 and a canonical
Location. Same-key replay preserves the original run and deadline; a changed body conflicts
and a consumed purged key returns 410. Controls require the decimal-string state version.
Create bodies are capped at 96 KiB; control and DSR initiation bodies at 4 KiB.

Browser mission creation and controls pin `X-Workspace-Id` to the rendered workspace,
including creation delayed by chat-session provisioning. The shared tenant interceptor
validates that pin; a workspace refusal preserves the goal and never resubmits into a
healed cookie selection. Organization-wide DSR initiation and progress retain the
organization-route selection contract and authorize the organization named in the path.

List/detail/output/control APIs require current requester membership, AI permission, and
session access including an active session creator. They never grant the session creator
control over another requester's mission. Withheld projections expose metadata and safe
controls only; output refuses stale, blocked, purged, and unknown-runtime content. Lists
use an owner/filter-bound keyset cursor independently of transcript limits. Reports have
an empty review collection in this slice.

Realtime messages are six-field metadata invalidations on the requester's private channel.
Fresh access, generation, and state-version checks run immediately before send; the client
refetches canonical state and ignores stale events. The scheduler publishes state invalidations
after execution expiry commits, including for paused missions that do not poll. EN/JA progress,
report access, and controls live inside the existing Ask Connex session. There is no separate page or rail.
DSR initiation and progress use the existing request detail surface. Landing vendors the
same frontend files through its normal sync script.

## Privacy authority

Each workspace owns one `ai_agent_privacy_barrier`. Its generation is durable and
monotonic. A missing barrier fails closed. Runtime generation and revision values use
positive Java `long` values and refuse increments at `Long.MAX_VALUE`, conservatively
below the SQL unsigned maximum. Generation restoration, deletion, retry, and restart
never reset the counter.

First mission creation records immutable `mission_taint_generation` and
`mission_taint_seq` under the session lock. Every ordinary and mission message writer
allocates through the session's durable `message_seq_high_water`; deleting messages does
not lower it. Generated chat holdings in a tainted session are enrolled in privacy
cleanup, including titles, summaries, partials, progress, tools, and source notifications.
A user message is enrolled when mission-owned or its immutable sequence is at or above
the taint boundary. Earlier ordinary user input remains unchanged.

Every execution retains its original generation and captured taint sequence. Current
privacy requires an unchanged taint identity, the current unblocked barrier generation,
current access, and an unwithheld mission source. Reader predicates apply before physical
erasure, including ordinary transcript, retained content, notification sources, and tenant
export. Transcript detail reads use READ COMMITTED, lock the actor/workspace/organization audit parents
shared before barrier S, append strict read audits after the barrier, and retain it through projection. They flush the
mapper cache at that lock, and reload session metadata afterward. Ordinary turn polling likewise holds
barrier S through partial, progress, and scope DTO construction, with fresh provenance validation
after acquiring the barrier. Admission retains audit parents early and appends its audit only after
the restriction and privacy fences. Browser invalidation discards
rendered and buffered text, including missing-turn reconnects and late responses. Canonical terminal
partials replace local text exactly. Withdrawal invalidates mission output snapshots before reload.
Export residual counting remains unconditional: privacy filtering cannot hide
remaining rows from teardown verification.

Restrictions invalidate all workspaces in the affected organization in the same
transaction as the person mutation. Existing authorization and person roots precede
stripe-ordered local write fences; exact workspace barriers lock in ascending order,
then work rows are inserted. No mission/session locks are acquired by invalidation.
Current placement must agree for every workspace. Tenant routing is installed before the
transaction starts; in-transaction control-mapper reads revalidate placement on the same connection
without switching the tenant work scope. Clearing a person restriction never
revives old content.

## Bounded erasure

`AiAgentPrivacyService` separates invalidation from physical cleanup.
`AiAgentPrivacyWorker` runs even when AI/provider features are disabled or an owner has
left. Its pass journal is instance-scoped outside tenant routing so cleanup cannot recreate
workspace journal rows during teardown. Each work item freezes cutoff generation and maximum committed IDs once. Its
revision and integer keyset cursors advance in the same transaction as deletion.

The run phase permanently withholds content, invalidates execution authority, settles
reserved allowance conservatively, severs report/active pointers, detaches terminal
attempts, deletes turn/segment children, and retains a minimal consumed-key tombstone.
The session phase settles ordinary active turns, removes tool payloads and enrolled
messages, removes terminal turns, resets generated titles to the static Ask Connex title,
recomputes preview timing, and removes notification/brief projections. Deletion batches
contain at most 100 rows; a settlement transaction handles one leased subject.

Mutation order follows [the global locking rule](LOCKING.md#global-privacy-restriction-and-audit-order):
session/run/content roots, domain roots if any, restriction fences if needed, barrier S,
exact work X, audit head if needed, lease last. No barrier or restriction fence follows an audit
head or lease. Existing authority-parent contracts remain unchanged. Initializers and completion hold barrier X without acquiring content roots afterward.
Discovery occurs outside the transaction. Changed work revisions abort a stale batch.
Failure preserves blocking and schedules metadata-only retries from one second up to
five minutes; exception payloads are not persisted.

Completion takes barrier X then exact work X and performs fresh residual queries without
watermark or cursor limits. A work item completes only with zero residuals. Unblocking
also requires every work item complete and no enrolled residual below the current
barrier generation. Completion of an older operation cannot clear a newer invalidation.

## Authorized DSR execution

A workflow status change never initiates deletion. Organization administrators explicitly
POST to `/api/orgs/{orgId}/data-subject-requests/{requestId}/mission-holdings-purge`.
First acceptance requires recent authentication, identity verification, a linked subject,
a current full-state `dsr1` snapshot token, a UUID key, and both exact acknowledgments:

- `organization_mission_holdings_v1`
- `contents_at_each_workspace_first_invalidation_v1`

The snapshot hashes the approved fixed-order representation with explicit nulls, exact
stored strings, and UTC database-precision dates. It is transient and never stored or
audited. Same-key replay resolves the existing accepted operation before considering the
submitted snapshot.

C1 reuses the linked-person proof and ordered control roots, serializes on the request,
and strictly appends the immutable acceptance envelope to the organization audit ledger.
The envelope contains only operation/key/request IDs, fixed policy identifiers, frozen
workspace IDs, schema version, and acceptance time. Stored JSON must equal the accepted
metadata after audit sanitization or the transaction aborts.

T1 runs after C1 commits. It uses only the frozen workspace list; each workspace installs
its cutoff exactly once under `(workspace_id,operation_key)` and operation fingerprint.
Initiation attempts T1 synchronously before projecting progress. C1's reserved linked-person
session owns an independent routed JDBC connection, so a surrounding nontransactional Spring
scope cannot lend it a retained control connection. It rolls back and restores connection state
before release. T1 and tenant progress reads install a restorable system tenant identity from
accepted authority; they do not require workspace membership. Progress reads open fresh
READ COMMITTED transactions after placement installation, including scheduled recovery after
control-ledger enumeration, and lifecycle progress continues to use lifecycle placement. Initiation and
scheduled recovery failures log the recovery job name and exception class without exception payloads.
T2 is the bounded privacy worker. C2 strictly records completion only after all accepted
workspaces have completed residual-verified work. Recovery scans the acceptance ledger
in bounded keyset pages. It does not reauthorize against mutable subject linkage or the
initiating administrator's later membership. API replays and progress reads still require
current administrator access.

The cutoff is established at each workspace's first T1 invalidation, not at control
acceptance. Workspaces created afterward are excluded. Domain records and effects are
not reversed. Missing lifecycle completion evidence is a refusal, never invented success. The current
teardown flow verifies residuals but deletes its final cleanup marker without retaining a
durable success receipt. An accepted DSR operation whose workspace disappears therefore
remains incomplete and refuses projection/recovery instead of reporting erased holdings.
#2101 must track a reviewed durable supersession contract and verified operator recovery;
missing roots or work rows cannot be used as a completion signal.
Acceptance/completion ledger records and operation tombstones must remain available until
workspace teardown; audit retention must preserve these records if introduced later.

## Initialization and lifecycle

Startup and periodic reconciliation enumerate control-plane workspace IDs, resolve each
placement, and initialize only an empty workspace. Existing barriers are preserved.
Missing barriers with run/segment/work/taint evidence fail readiness. An incomplete work
record under an unblocked barrier also fails readiness. Pre-V232 active ordinary turns
without durable tokens are settled without content before mission admission.

Self-service, signup/bootstrap, and volume seeding publish the same workspace-provisioned
event inside their control transaction. Its after-commit callback suspends the completed
transaction's resources before resolving placement, then installs the system actor's tenant
scope through `AutomationExecutor`. A catalog pin alone does not authorize scoped mapper
calls on an unresolved signup request thread. Tenant initialization commits separately. Legacy discovery pages and startup recovery reads use fresh
routed transactions after placement is installed, ending before each independent settlement; resumed control sessions from placement lookup
never supply their tenant reads.
A crash between commits leaves mission admission unavailable until startup/periodic
reconciliation completes; no cross-plane atomicity is claimed.

Ordinary transcript reads and new turn admission with a missing barrier return HTTP 503,
`ai_privacy_initialization_pending`, and `Retry-After: 60`. They neither disclose content nor
invent authority. Reconciliation initializes an empty workspace and a later retry succeeds;
mission/work/taint evidence keeps it unavailable for operator recovery. Existing execution
tokens still fail their original generation/taint checks and cannot adopt newly created authority.

Departure publishes an after-commit identity-only cancellation event and a bounded
maintenance sweep repairs missed events using current membership. Fresh membership
admission holds the existing absence lock and checks prior owner missions in a separate
READ COMMITTED metadata snapshot. It refuses with `mission_departure_cleanup_pending`
until old nonterminal missions settle, preventing a leave/crash/rejoin from reviving them.
This does not purge other members' holdings. Account erasure discovers owned
mission workspaces in the installed catalog, invalidates their holdings, and clears owner
references after content becomes a tombstone and the control account is absent.
Teardown finishes mission privacy work before registry preparation severs restrictive
cycles. Chat children delete before segments/runs; barrier latest-operation pointers clear
before work rows and barriers. Tenant registry residual verification remains mandatory.

## Deployment and verification

Missions remain disabled by default. Initial activation requires one serving application
JVM, initialized barriers, completed active-attempt reconciliation, and the full compatible
reader/writer rollout. Drain all older serving binaries and background message writers
before V232's one-time sequence backfill; keep writes stopped until the allocator cutover.
V232 is the rollback floor. Multi-instance activation requires the separately reviewed
#941 distributed fencing contract.

Focused privacy selectors are `AiAgentPrivacyIntegrationTest`,
`AiAgentPrivacyConcurrencyIntegrationTest`, `AiAgentPrivacyInitializationIntegrationTest`,
`AiAgentPersistenceContractTest`, `AiAgentPrivacyLifecycleIntegrationTest`,
`AiAgentDsrPurgeIntegrationTest`,
`AiAgentDsrPurgeHttpIntegrationTest`, and `DataSubjectRequestSnapshotTest`. Real database,
HTTP, suspended-writer, locking, and scripted trajectory evidence is required before
activation. Source inspection or compilation alone does not establish those guarantees.

Committed integration fixtures drive maintenance passes explicitly. The shared test configuration
defers the timers with `connex.ai.mission-privacy-work-initial-delay`,
`connex.ai.mission-privacy-reconcile-initial-delay`, and `connex.ai.mission-sweep-initial-delay`
so cached application contexts cannot advance another test's privacy cursors or settle its attempts.
Their production defaults remain 10, 60, and 30 seconds respectively; startup reconciliation and
direct maintenance calls still run normally.

`AiAssistantProvisioningScriptedTrajectoryTest` provisions through the production service
with unresolved HTTP request attributes before testing session create/read/turn completion.
Shared scripted trajectories also use production provisioning. Direct SQL privacy fixtures
model pre-existing workspaces only; they must not be used to verify provisioning.
