# Durable read-only missions

Phase 2 slice 2 retains `record_review_v1` definition 1 and introduces runtime contract 2
for new requests. V235 adds continuation columns and replaces V232's single-segment
constraint with bounded immutable attempt-number triggers. Existing runtime-1 requests
replay unchanged and execute one logical segment with at most three attempts. Runtime 2
allows logical segments 1–8, each with attempts 1–3. Neither version permits domain writes,
questions, proposals, action slots, or larger root limits.

## Execution and scope

Root limits remain eight model decisions, 131,072 tokens and 900 seconds, including repairs
and fallback. Requested lower limits and both original root deadlines never refresh.
Each attempt is bounded by 180 seconds and the earlier root deadline. The session mutex
prevents overlap with an ordinary reactive turn; ordinary turns may occupy it between segments.
The existing dedicated single worker discovers bounded workspace pages and queued continuations.
Unavailable readiness backs off for 30 seconds without spending an attempt.

Create, claim, plan, checkpoint, accounting and controls use READ COMMITTED and the
[global mission lock order](LOCKING.md#durable-record-review-missions-v232v235).
Claims install a fresh turn, segment, generation and AGENT_RUN lease. Every callback binds
those identities. A stale callback refuses before any lease operation, including an old
checkpoint arriving after its successor claims. Lease heartbeat subject reads remain nonlocking;
AGENT_RUN tombstones remain non-reapable.

The immutable scope is one person, company or deal. Current scoped slice-1 reads retain
RBAC, restriction predicates and closed linked expansion. Company history stays empty when
its retrieval would require related-record expansion. There is no unrestricted retrieval,
post-filtered search or unbounded refill. Runtime 1 retains its exact read-tool ceiling.
Runtime 2 adds only mission-specific `set_todos` and `checkpoint_segment` bookkeeping.

## Durable continuation and model projection

`ai_agent_run` holds compact canonical UTF-8 TEXT plan, checkpoint and memory envelopes,
all schema 1. Creation initializes their revisions to 1 and the committed cursor to 0.
Missing payloads are malformed state. The compatibility catalog accepts only
`record_review_v1 / 1 / 1` with empty continuation or `/ 1 / 2` with valid bound envelopes.
Unknown definitions, runtimes, schemas or continuation discriminators expose metadata and
safe stop controls only. They cannot reconstruct, claim, resume, publish or export content.
Privacy cleanup and expiry never decode payloads.

The strict codec rejects unknown/duplicate properties, coercion, missing fields, trailing
content, invalid references and malformed UTF-16. Complete serialized limits are 32,768
UTF-8 bytes each for plan/checkpoint and 16,384 for memory. There are at most 20 lifetime
plan keys, 64 distinct evidence references and 20 member-provenance entries per text.
All bounds apply on reading and writing; overflow refuses atomically without truncation.
Revisions and generations are positive signed longs and refuse overflow.
Every continuation writer, including insert and general state update, binds UTF-8 bytes
with explicit `CONVERT(... USING utf8mb4)`; later control, claim and settlement cannot
corrupt previously committed Japanese or supplementary text under Cp1252 connections.
All run, bookkeeping, chat and export readers select binary payloads and decode UTF-8
explicitly, including JSON columns whose JDBC string reader would use the connection charset.
Screened bookkeeping arguments/results use the same byte transport through a dedicated
mission insert. Replay compares strict JSON values because MySQL JSON normalizes object
formatting and property order.

Server-issued keys `p0001`–`p0020` are allocated monotonically. Replacement may rename,
reorder or hide items, but never deletes identities, resets the lifetime allocation or
demotes a completed item. One append-only immutable fact belongs to each done key.
Plan-only updates advance plan/checkpoint revisions while preserving the committed segment
cursor. Reserved action references are preserved verbatim by the pure merger; executable
runtime-2 state requires an empty action inventory.

All labels, fact summaries, memory and original member labels pass generated-content,
special-care, exposure-validated demasking and control/placeholder/handle refusal before
any durable copy. Bookkeeping bypasses the ordinary cache, proposal path and forgiving
todo parser. Result/replay size admission precedes the transaction. Screened canonical
arguments, result, plan and facts commit together. An exact attempt-local call identity
returns its original result after fresh authorization; changed payloads conflict.
Bookkeeping is not new evidence and does not reset the loop's no-progress guard.

Canonical member IDs, original-label provenance, fingerprints and evidence bindings stay
server-side. Provider-facing plan/results carry only plan keys, revisions, statuses and
freshly masked prose. Reconstructed context uses that same explicit projection, never
serializing the canonical plan or completed-fact ledger directly into a request. Stored bookkeeping results
retain their original durable text provenance; every replay uses the same explicit provider
projection with fresh member authorization and field-scoped containment masking. Server-only
identifier pointers survive evidence nesting, tool replay and closing-context projection until
the prompt assembler's single masking and leak-scan pass. Parser
provenance stays tied to each argument pointer, including individual native batch calls, so
Japanese honorific suffixes cannot discard a demasked member's provenance.

## Reconstruction and evidence

Every claimed segment starts fresh masking/resource registries. It reads only the stored
goal/scope, decoded continuation, current member directory and required bounded authorized
evidence. Goal-message lookup selects identity/sequence/privacy ancestry without old prose.
Session history, summaries, attachments, previous answers, tool transcripts, token maps,
provider thought signatures and native call IDs are excluded.

Evidence versions hash a deterministic versioned projection of the exact privacy-shaped
read plus identifier provenance before masking and handle serialization. `get_record` and
single-primary `get_records` normalize to `record/0`; activities/tasks bind their actual
bounded limit. Revalidation repeats that exact closed read. Lost authority pauses with
`access_lost`; changed evidence pauses with `source_changed` before prompt assembly/egress.
Stored facts remain intact. Every plan and checkpoint mutation binds carried prose to all
validated sources observed in that segment, including the primary record when citations are
empty. A conflicting reread in the same segment refuses `source_changed` before it can replace
the original source binding. Nonblank carried prose without a primary-record binding also
refuses before reconstruction. Original member-label provenance must still authorize its
member before seeding fresh masking. Complete context is assembled through PromptAssembly;
overflow refuses `mission_context_overflow` without truncation or history fallback.

## Reservation, checkpoint and recovery

New requests accept `maxTokens` from 81,920 through 131,072; omission still defaults to
131,072. Creation measures the goal with the application JSON serializer, including string
quoting/escaping, in UTF-8 bytes. Goals are bounded by both 16,000 UTF-16 code units and
48,002 serialized bytes. The byte limit admits 16,000 Japanese characters but rejects
escape-heavy strings that fit the character limit and would exceed the default allowance.
Creation feasibility is not re-evaluated for same-key replays that pass current request
validation; stored limits remain unchanged.

The runtime-2 fixed envelope measured through `AiInvocationService.serializedPromptBytes`
is 8,616 bytes for native tools (8,856 with tagged reasoning); the larger JSON fallback
envelope with tagged reasoning is 11,749 bytes. A final-only native request retains the same
8,856-byte tagged envelope; JSON closing measures 8,152 bytes. Admission uses the larger
11,749-byte envelope for both dispatches plus 4,096 bytes for serialized context, scope directives and message framing, plus the
256-token minimum output, and retains the existing 65,536 closing-report allowance:
`required = serializedGoalBytes + 11749 + 4096 + 256 + 65536`.
Both dispatches must fit independently. At the new minimum, a continuation-capable request
has 16,384 tokens for a checkpoint segment and retains 65,536 for closing. Requests with
fewer than three decisions remain terminal-only. Creation refuses an infeasible combination
with HTTP 400 / `mission_budget_infeasible` before persistence or provider admission.
The maximum accepted goal requires 129,639 at creation, below the unchanged 131,072 default. Its
closing dispatch requires at most 64,103 under this admission estimate, within the 65,536
reserve. Tests pin the fixed envelopes to the dispatch serializer so prompt/schema growth
requires recalculating admission. These are byte-based capacity checks, with no tokenizer
or bytes-to-token discount; the historical `maxTokens` API name remains unchanged.
Provider-derived history capacity, live reconstructed evidence, masking expansion, repairs
and further decisions still pass their own context and remaining-allowance checks at execution;
admission does not promise
that every possible trajectory fits.

Under run X, with no active reservation, continuation candidates reserve
`min(4, remainingSteps - 1)` decisions and `min(65536, remainingTokens - 65536)` tokens.
They require at least two decisions, 8,192 tokens and another logical segment. Otherwise
runtime 2 reserves terminal-only `min(4, remainingSteps)` and all remaining tokens. Runtime 1
retains its full remaining reservation. Every serialized provider request must fit its
active token allowance. Each mission invocation clamps its requested output to the unused
segment tokens minus the serialized request's UTF-8 byte estimate, retaining the provider's
lower ceiling. The 65,536 root closing tokens are already excluded from continuation
reservations; they are not subtracted again. Output below 256 tokens refuses with
`mission_budget_exhausted`, leaving room for a complete final-answer or checkpoint envelope
with concise prose. Repairs recompute the clamp after prior usage and their own serialized
prompt; definite protocol fallback retains the clamped request and rechecks the dispatch
reservation after settling the rejected attempt's zero-token usage. Ordinary invocations
retain their existing output limits. The final decision exposes only final report/checkpoint in a
continuation-capable segment, or only final report in a terminal-only segment.

Dispatch reservation increments a durable ordinal and sets uncertainty before egress.
Usage callbacks bind that exact ordinal/generation and clear it once. Duplicates cannot
charge twice or clear a successor. An explicit 400/422 protocol rejection settles that exact
mission dispatch as one decision and zero tokens before fallback or repair can reserve again.
Transport failures, timeouts and server errors retain uncertainty. Organization reservations
remain independently conservative and charge their dispatched ceiling for rejected requests.
Observed overrun stops with `usage_overrun` and retains
truthful totals. Certain settlement charges known decisions/tokens; uncertain settlement
charges at least the full attempt allowance and any observed overrun. Proven-unused
allowance alone is refunded. Organization accounting and invocation audits remain independent.

`checkpoint_segment` must be the only call in its step. The model cannot supply canonical
IDs, cursor, coverage, completed ledgers or action references. The server screens memory,
resolves current observed citations, derives coverage and commits checkpoint/memory, cursor
+1, tool result, accounting, terminal turn/segment, queued state and lease release atomically.
The loop exits without successor dispatch or recursive execution. A later scheduler claim
rebuilds context. Only the existing final report transaction creates a report.

Orphan settlement preserves continuation/cursor and original caps/deadlines, settles the
attempt and changes the run to `paused/interrupted`, with lease takeover/release last.
A separate freshly authorized transaction may queue only supported, unexpired runtime-2
interrupted READ work with allowance and fewer than three attempts for the next logical
segment. Discovery excludes exhausted next-segment attempts before applying its page limit,
so stopped exhausted runs cannot starve later eligible work. It spends no attempt and touches no lease. Member/content/schema/access/source/
budget/provider/deadline stops never automatically retry. The runner preserves actual
failure classification. Explicit pause over `paused/interrupted` records `reason=member`
and increments state version under session/run locks; only an existing member pause is
an idempotent no-op. A recovery-winning race invalidates the older control version, and
an acknowledged current-version pause prevents automatic claim.

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

Organization authority is unconditional after V237, including for never-tainted chats. The
workspace-generation and taint rules below govern the additional mission-v1 namespace; they do not
exempt any chat from organization invalidation. See [the all-chat contract](#organization-wide-assistant-privacy-v236v237).

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
the taint boundary. Earlier ordinary user input remains unchanged under v1 work. V2 work erases the whole affected session.

Every execution retains its original generation and captured taint sequence. Current
privacy requires an unchanged taint identity, the current unblocked barrier generation,
current access, and an unwithheld mission source. Reader predicates apply before physical
erasure, including ordinary transcript, retained content, notification sources, and tenant
export. Transcript detail reads use READ COMMITTED, lock the actor/workspace/organization audit parents
shared before organization S and workspace barrier S, append strict read audits afterward, and retain both through projection. They flush the
mapper cache at that lock, and reload session metadata afterward. Ordinary turn polling likewise holds
barrier S through partial, progress, and scope DTO construction, with fresh provenance validation
after acquiring the barrier. Admission retains audit parents early and appends its audit only after
the restriction and privacy fences. Browser invalidation discards
rendered and buffered text, including missing-turn reconnects and late responses. Canonical terminal
partials replace local text exactly. Withdrawal invalidates mission output snapshots before reload.
Export residual counting remains unconditional: privacy filtering cannot hide
remaining rows from teardown verification.

New person restrictions advance organization authority and create durable all-chat operations and
workspace intents in the person mutation transaction. Authorization and person roots precede local
restriction stripes, the publication write fence and organization X. Invalidation acquires no chat
roots and does not advance or block workspace barriers. Organization authority covers omitted
workspaces immediately; routed reconciliation installs v2 work afterward. Explicit v1 operations
retain ascending workspace-barrier X before their work rows and preserve their original cutoffs.
Placement is installed before routed transactions; control locks use the same transaction-bound
connection. Clearing a restriction never revives retired content.

## Bounded erasure

`AiAgentPrivacyService` separates invalidation from physical cleanup.
`AiAgentPrivacyWorker` runs even when AI/provider features are disabled or an owner has
left. Its pass journal is instance-scoped outside tenant routing so cleanup cannot recreate
workspace journal rows during teardown. Each work item freezes cutoff generation and maximum committed IDs once. Its
revision and integer keyset cursors advance in the same transaction as deletion.

The run phase permanently withholds content, invalidates execution authority, settles
reserved allowance conservatively, severs report/active pointers, detaches terminal
attempts, detaches terminal-turn segment references before deleting segments, and retains a minimal consumed-key tombstone. All nine continuation fields clear to null/zero.
A shared run-residual predicate enrolls continuation JSON, schema versions, revisions and
cursor both in bounded discovery and unconditional final verification. Purged rows with
residue are repaired without decoding their content; runtime-2 inventories up to 24
attempts remain erasable. Existing table-based lifecycle/export/DSR/account-erasure enrollment
covers these columns; tenant teardown counts remain unconditional. Before serializing an
unpurged mission export row, validate its versioned continuation; malformed/unsupported
content aborts the artifact. Tombstones must match the erased shape.
The session phase settles active turns, drains tool children and proposal parents before messages
and terminal turns, and removes notification/brief projections. V1 preserves reusable sessions and
pre-taint input while resetting generated titles and preview timing. V2 also drains chat files with
durable object disposition, then deletes affected sessions and participants. Deletion batches
contain at most 100 rows; a settlement transaction handles one leased subject.

Mutation order follows [the global locking rule](LOCKING.md#global-privacy-restriction-and-audit-order):
session/run/proposal-parent/tool/turn/segment roots, domain/storage roots, local restriction and
publication fences, organization authority, ascending workspace barriers, operation/placement/work
rows, audit heads, then leases last. Privacy authority never follows an audit head or lease.
Initialization and completion acquire no content/domain roots after privacy locks.
Discovery occurs outside the transaction. Changed work revisions abort a stale batch.
Failure preserves blocking and schedules metadata-only retries from one second up to
five minutes; exception payloads are not persisted.

V1 completion retains organization S, then workspace barrier X and exact work X and performs uncapped residual queries.
Unblocking requires all v1 work complete and no v1 residual below the current workspace generation.
V2 retains organization authority before workspace S and its exact operation/work rows; completion
requires uncapped all-chat/object/lease verification and reconciled placement receipts. It never
unblocks the v1 barrier. Older completion cannot complete or clear a newer organization operation.

## Authorized DSR execution

A workflow status change never initiates deletion. Organization administrators explicitly
POST to `/api/orgs/{orgId}/data-subject-requests/{requestId}/mission-holdings-purge`.
Both versions retain recent authentication, identity verification, linked-subject proof, the current
full-state `dsr1` snapshot and UUID key requirements. New browser submissions use v2, described in
[the all-chat contract](#organization-wide-assistant-privacy-v236v237). The original v1 pair remains:

- `organization_mission_holdings_v1`
- `contents_at_each_workspace_first_invalidation_v1`

The snapshot hashes the approved fixed-order representation with explicit nulls, exact
stored strings, and UTC database-precision dates. It is transient and never stored or
audited. Same-key replay resolves the existing accepted operation before considering the
submitted snapshot.

C1 reuses the linked-person proof and ordered control roots, serializes on the request,
and strictly appends the immutable acceptance envelope to the organization audit ledger.
The v1 envelope contains only operation/key/request IDs, fixed policy identifiers, frozen workspace
IDs, schema version and acceptance time. V2 adds decimal organization cutoff/invalidated generations
and creates its organization operation/intents in C1 before the strict acceptance audit. Stored JSON must equal the accepted
metadata after audit sanitization or the transaction aborts.

T1 runs after C1 commits. V1 uses only its frozen workspace list; each workspace installs
its cutoff exactly once under `(workspace_id,operation_key)` and operation fingerprint.
Initiation attempts T1 synchronously before projecting progress. C1's reserved linked-person
session owns an independent routed JDBC connection, so a surrounding nontransactional Spring
scope cannot lend it a retained control connection. It rolls back and restores connection state
before release. T1 and tenant progress reads install a restorable system tenant identity from
accepted authority; they do not require workspace membership. Progress reads open fresh
READ COMMITTED transactions after placement installation, including scheduled recovery after
control-ledger enumeration, and lifecycle progress continues to use lifecycle placement. Initiation and
scheduled recovery failures log the recovery job name and exception class without exception payloads.
T2 is the bounded privacy worker. C2 strictly records completion after residual-verified work
for the v1 accepted list or reconciled v2 placement coverage, including managed-object evidence. Recovery scans the acceptance ledger
in bounded keyset pages. It does not reauthorize against mutable subject linkage or the
initiating administrator's later membership. API replays and progress reads still require
current administrator access.

For v1, the cutoff is established at each workspace's first T1 invalidation, and later workspaces
are excluded. V2 fixes the organization cutoff at C1; initial workspace IDs are discovery only and
later-discovered old-generation holdings remain covered. New current-generation chats are excluded. Domain records and effects are
not reversed. Missing lifecycle completion evidence is a refusal, never invented success. The current
teardown flow verifies residuals but deletes its final cleanup marker without retaining a
durable success receipt. An accepted DSR operation whose workspace disappears therefore
remains incomplete and refuses projection/recovery instead of reporting erased holdings.
#2101 must track a reviewed durable supersession contract and verified operator recovery;
missing roots or work rows cannot be used as a completion signal.
V1 acceptance/completion records and operation tombstones must remain available until
workspace teardown; audit retention must preserve these records if introduced later.

## Initialization and lifecycle

Startup and periodic reconciliation enumerate control-plane workspace IDs, resolve each
placement, and initialize only an empty workspace. Existing barriers are preserved.
Missing barriers with run/segment/v1-work/taint evidence fail readiness. An incomplete v1 work
record under an unblocked barrier also fails readiness. V2 work does not block that barrier.
Pre-V232 active ordinary turns
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
JVM, initialized workspace and organization authority, completed active-attempt reconciliation,
and the full compatible reader/writer rollout. Drain incompatible serving binaries, scheduled
workers and in-flight writers before V236/V237. Upgrades crossing V232 must also retain that
version's stopped-writer sequence-backfill procedure.
V237 is the rollback floor, including with AI and missions disabled. Multi-instance activation requires the separately reviewed
#941 distributed fencing contract.

Focused continuation selectors are `AiAgentContinuationCodecTest`, `AiAgentPlanServiceTest`,
`AiAgentBudgetServiceTest`, `AiAgentContextBuilderTest`, `AiAgentContinuationIntegrationTest`,
`AiAgentContinuationV235MigrationIntegrationTest`, and the extended HTTP, concurrency,
privacy-lifecycle and scripted-trajectory classes. Scripted goldens prove server behavior,
not live-model quality; this slice authorizes no live runner or egress.

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

## All-chat privacy cutover (control V236, tenant V237)

Option A invalidates every retained pre-cutover chat, including archived, private, shared, invited,
never mission-tainted chats and departed authors. Existing content stays organization generation 0;
control authority starts at 1. Session provenance is immutable. Every dependent writer preserves its
original execution generation, and every read/input/decision/publication requires current authority.
New chats remain usable while historical cleanup is pending. Clearing a contact hold never revives
a retired chat. Saved CRM changes and ordinary domain audit history remain.

The existing privacy initializer and worker reconcile stable organization operations and placement
receipts. V2 work uses `organization_assistant_holdings_v2`; it neither advances nor blocks the
workspace mission barrier. Legacy `organization_mission_holdings_v1` work retains its exact
fingerprint, cutoff and reusable-session semantics. V2 retires and physically removes whole affected
sessions, explicitly drains proposal parents/children and attachments in bounded phases, and clears
mission continuation payloads while retaining minimal consumed-key tombstones.

Completion uses uncapped holdings/lease/object residuals and placement coverage. Bounded repair
finds restored rows above initial watermarks or behind cursors without changing the semantic cutoff.
Startup revalidates historical bootstrap completion; unavailable placements and missing workspace
evidence keep progress pending. Pending object obligations require positive final queue receipts;
independent CRM file references retain bytes but never preserve the old chat link.

The existing DSR endpoint additionally accepts schema version 2 with exact scope
`organization_assistant_holdings_v2` and policy `organization_generation_at_acceptance_v2`.
C1 fixes decimal-string organization cutoff/invalidated generations once; T1 enrolls routed work,
T2 cleans, and C2 requires reconciled placement/object/residual evidence. Same-key replay precedes
mutable snapshots and cannot change scope. Existing v1 eight-field receipts retain their authority.
Status/resolution edits never execute erasure, and ordinary departure never triggers blanket erasure.

Workspace teardown first drains accepted organization operations and preserves their placement
evidence. Its existing v1 offboarding work retains the original workspace-generation cutoff.
The lifecycle fence additionally authorizes bounded full-workspace chat/run/object cleanup through
the same engine, with a separate uncapped lifecycle residual check. That local full scan neither
creates a synthetic organization generation nor invalidates unrelated workspaces. Departure and
account-owner erasure retain their narrower existing behavior.
