# Governance: deletion policy and report-snapshot retention

Reference for the Wave 4 governance rules (issue [#856](https://github.com/itkla/connex/issues/856)). These are
product-visible behaviours, not implementation details — read this before changing deletion,
approval, or report-snapshot code.

## Approvals cannot be self-decided

By default a pending document approval may not be decided by the person who **requested** it *or* by
the person who **authored** the document. Both bindings are required: without the author check, an
author could have any colleague with `DEAL_UPDATE` submit the document for approval and then approve
it themselves.

This is the `strict` setting of an approval policy's **separation of duties**, and it is the default
for every policy and for voluntary requests with no matching policy. A workspace may relax it per
policy to `requester` (only the requester is blocked) or `off` (no restriction); the rule in force is
frozen onto the request when approval is asked for, so relaxing a policy later never unblocks an
approval that is already in flight.

Under `strict` and `requester` the check fails closed — a null actor or a null requester is never
decidable, and `strict` additionally refuses a document with no recorded author. Two escape hatches
remain for such legacy rows: the requester can always cancel their own request, and a workspace
admin/owner can cancel a request whose requester is unknown. Superseding a document still cancels its
in-flight approval, including when the superseder is a different member; that path is not a decision
and is intentionally unaffected.

## Approver chains

An approval policy may declare an ordered chain of steps instead of a single approval. Each step has
a quorum (`requiredCount`) and a set of approvers — either named workspace members or *anyone holding
`DOCUMENT_APPROVE`*. A `sequential` chain opens one step at a time; a `parallel` chain opens all of
them at once. A step passes when it holds that many **distinct** approvals, and the document only
becomes approvable once every step has passed.

A single rejection at any step terminates the whole request, cancels the remaining steps, and returns
the document to `draft`. Deciding still requires `DOCUMENT_APPROVE` — being named on a step never
grants it — and the permission is re-read from locked authorization rows rather than trusted from the
entry-point snapshot (see the lock order below).

The whole chain is **snapshotted onto the request**: editing or deleting the policy afterwards cannot
add, remove, or re-target the steps of an approval that is already pending. A policy with no steps,
and a voluntary request with no matching policy, both behave exactly like the original single-approver
flow: one approval from anyone who can approve documents.

Two guards keep a chain from becoming a trap:

- Saving a policy is refused when a step could never reach its quorum — an unknown approver, an
  approver who cannot hold `DOCUMENT_APPROVE`, a step mixing "anyone" with named members, a repeated
  approver, or a quorum larger than the approvers who could satisfy it.
- Requesting approval is refused when a step's named approvers are exactly the people separation of
  duties excludes for that request. A step open to *anyone* is deliberately not refused: its pool
  legitimately grows as people join the workspace, and the requester can always cancel.

`document_approval` also carries a database trigger that refuses to mark a request approved while any
of its steps is unapproved. That fences the rolling-deployment window, in which a node running a
binary from before chains existed would otherwise approve a chained request by writing the request row
alone. Rejection and cancellation are terminal outcomes and stay unfenced. A request frozen by such an
older binary carries no steps at all; the chained runtime freezes the one implicit step it always
meant rather than refusing to decide it.

Deciding re-reads the actor's permissions from **exclusively locked** membership and role rows, taken
before the document row lock so the order stays membership → record. A concurrent removal or role
change therefore serializes against the decision instead of racing it.

## Policy changes and approvals already in flight

Policy edits are classified against the persisted policy, including its steps and approver sets.
When an edit contains more than one kind of change, the precedence is **tighten → loosen →
retarget → none**:

- **Tighten** adds a step, raises a step quorum, narrows its approver set, replaces "any approver"
  with named approvers, or strengthens separation of duties along `off` → `requester` → `strict`.
- **Loosen** only removes a step, lowers a quorum, adds approvers, replaces named approvers with
  "any approver", or relaxes separation of duties.
- **Retarget** changes the policy name, active state, document type, currency, total or discount
  threshold, or chain mode without tightening or loosening the chain.
- **None** is a semantically identical save.

Only tightening invalidates pending requests frozen from that policy. Loosening, retargeting,
identical saves, and policy deletion leave them in flight because each request was legitimately
raised under the rule in force at the time. Invalidation terminates the request, cancels its open
steps, returns the document to `draft`, and requires a fresh request to freeze the new chain; it
never replaces the request's frozen steps or approver assignments.

An administrator must explicitly confirm a tightening edit when pending requests would be
invalidated. The pending count, confirmation check, policy write, and invalidations are evaluated in
one transaction while the policy root is locked. The write therefore cannot proceed against a count
that changed between disclosure and save.

## A step that can no longer be satisfied

Every approval read projects whether each open frozen step can still reach quorum from current
workspace membership and `DOCUMENT_APPROVE` grants. Named steps count only their still-active,
still-permitted named approvers; "any approver" steps count every active permission holder. Both
remove the people excluded by the request's frozen separation-of-duties rule and people whose
approval is already recorded, then compare the remaining people with the remaining approvals needed.

This is a computed projection and never edits the snapshot. A bounded reconciliation sweep revisits
pending requests and terminates a request whose step is unsatisfiable: the blocking step becomes
`unsatisfiable`, other open steps are cancelled, and the document returns to `draft`. Decisions
already recorded and every frozen step and approver assignment remain as historical evidence.

## Why a request ended

Every terminal request records an `outcome_reason` in addition to its coarse status:

| Reason | Meaning |
| --- | --- |
| `quorum` | Every frozen step reached quorum. |
| `rejected` | An eligible approver rejected a step. |
| `superseded` | The immutable document version was superseded. |
| `cancelled_by_requester` | The attributed requester withdrew it. |
| `cancelled_by_admin` | An administrator withdrew an unattributed legacy request. |
| `policy_invalidated` | A confirmed tightening policy edit invalidated it. |
| `unsatisfiable` | Current membership, permissions, decisions, and separation of duties left a step unable to reach quorum. |
| `cancelled_legacy` | Backfill-only marker for cancellations written before reasons existed. |

New application code never writes `cancelled_legacy`; old rows cannot reliably distinguish a
supersede from a manual cancellation, so the migration preserves that uncertainty instead of
inventing history. `outcome_detail` carries bounded context for policy invalidation and
unsatisfiability without storing document body content.

## Creator-or-admin deletion

`DeletionPolicy.requireDeletable(creatorUserId)` gates deletion of **report definitions, report
snapshots, and draft deal documents**: the creator may delete their own, otherwise the caller must be
a workspace admin or owner. **Content with no recorded creator is admin/owner-only** — fail closed.

This runs *after* the existing `@RequirePermission` gate; it narrows, never widens.

> **Known narrowing.** Admin-equivalence is resolved from the built-in `workspace_member.role`
> column, while permissions may come from a *custom* role. A custom-role member holding
> `REPORT_DELETE` therefore keeps only "delete your own reports". Tracked in
> [#935](https://github.com/itkla/connex/issues/935).

### Deleting a report definition cascades

`report_snapshot` cascades from `report_definition`, so deleting a definition destroys its snapshots.
Deletion therefore additionally requires **admin/owner** when the definition holds any snapshot
generated by another user, or any scheduled-origin snapshot. The audit entry for `report.delete`
carries `destroyedSnapshotCount`.

### Deleting a deal with non-draft documents

Finalized and approved documents are immutable, but deleting the parent deal removes them. A deal
carrying **any** non-draft document (`status != 'draft'`) is therefore deletable by **admin/owner
only**, in both the single and bulk delete paths. Deals carrying only drafts, or no documents, are
unchanged.

## Report-snapshot retention

`report_snapshot.origin` is `'manual'` (created by hand) or `'scheduled'` (frozen by scheduled
delivery). Scheduled delivery persists a snapshot **before** sending and mails figures from it, so
recipients and the linked page can never disagree.

| Bound | Value | Notes |
| --- | --- | --- |
| Manual snapshots per report | 100 | Hard cap; creation fails when reached. |
| Scheduled snapshots per schedule | 26 | Pruned oldest-first on each delivery. |
| Snapshots listed per report | 126 | Covers every retained row (one schedule per report). |
| Snapshots per workspace | 1000 | See eviction rule below. |
| Snapshot bytes per workspace | 256 MB | See eviction rule below. |

### Scheduled delivery must never wedge

The workspace quotas are shared by both origins, and a delivery occurrence is **already claimed** by
the time the snapshot is written. A workspace that reached its quota would otherwise fail every
future scheduled delivery permanently and silently.

So in the **scheduled path only**, when the workspace quota is exhausted, the oldest
*scheduled-origin* snapshots are evicted workspace-wide in small batches until the new snapshot fits.
This deliberately trades the oldest scheduled evidence tail for delivery continuity. Eviction stops
as soon as the snapshot fits, so a workspace marginally over quota loses only a few rows.

**Manual snapshots are never evicted.** If the snapshot still cannot fit, delivery fails closed: the
failure is audited and **zero mail is sent** — recipients never receive unfrozen figures. The manual
snapshot path keeps its plain hard-fail behaviour.

### Emailed links outlive snapshots

Retention prunes scheduled snapshots, so a delivery email can outlive the snapshot it points at. The
snapshot route renders a recoverable "no longer available — view live report" state, never a hard
404, and distinguishes that from a permissions failure.

### Orphans are reclaimed

Deleting a schedule sets `report_snapshot.report_schedule_id` to `NULL` (the foreign key is
deliberately single-column: MySQL rejects `ON DELETE SET NULL` on a composite key whose
`workspace_id` leg is `NOT NULL`; tenant equality is enforced by locking the workspace-scoped
schedule row instead). Those rows would otherwise be reachable by neither the per-schedule prune nor
the manual cap, so they are reclaimed twice over: the retention prune also matches orphaned
scheduled rows for the same definition, and schedule deletion purges that definition's orphans
outright.
