# Conditional record edits

Deal full-record edits use optional optimistic concurrency (#2216). This is the pattern for
contact edits and other whole-record forms. Tenant, visibility and permission checks still apply.

## HTTP contract

- GET/list/board deal DTOs return a database-owned integer `version`.
- Keep the read-only DTO carrier boxed (`Long`) and populate it from the non-null bean counter.
  Jackson 3 can select Lombok's property constructor; an ignored read-only primitive constructor
  parameter would otherwise fail missing/null-primitive deserialization on incoming writes. Do not
  relax the mapper's strict deserialization settings to work around this.
- A full-record PUT may supply `expectedVersion` from the immutable snapshot that seeded its form.
  Omission retains legacy behavior. A submitted response `version` is read-only and is never the
  expected token. Negative expected versions fail boundary validation.
- The actual broad UPDATE keeps `workspace_id`, record `id`, and, when supplied, `version =
  expectedVersion` in the same WHERE clause. A read/compare alone is never sufficient.
- The already locked current record may reject a known mismatch before domain validation, so an
  outdated currency or invisible relation cannot mask the conflict prompt with an unrelated error.
  This early refusal never replaces the atomic UPDATE predicate; that predicate remains the final
  write fence and is independently raced by the SQL concurrency test.
- Zero affected rows are a refusal, not success. A missing row remains 404; a version mismatch
  returns 409 `{code: "STALE_RECORD", currentVersion: <number>, message: <fixed text>}`.
  This code is distinct from the existing line-item value/currency conflicts.
- Run the conditional broad write before dependent value/outcome writes. Its transaction covers
  every dependent write, history, audit and event. A refusal rolls everything back. Return a
  fresh record after all writes so its version includes every mutation in that transaction. Close and reopen
  use the same final re-read; targeted name/value/date/owner/evaluation and board writes already
  return freshly selected records. Creation inserts at version zero and its augmentation changes
  junction/custom-field rows only.

## Version ownership and writer inventory

Tenant V241 adds `deal.version` and `trg_deal_edit_version`, a BEFORE UPDATE counter using
`OLD.version + 1`. Application writers cannot preserve or assign the counter. Every UPDATE,
including a no-op, advances it. This deliberately favors a conservative conflict over a lost edit.
No timestamp conversion, client clock, precision assumption or read-then-write claim is involved.
The column addition explicitly requires `ALGORITHM=INSTANT, LOCK=DEFAULT`; metadata-lock and
partial-DDL recovery requirements are in [Upgrading](../UPGRADING.md#conditional-deal-edits-tenant-v241).

`updated_at` remains the record freshness timestamp. It is unsuitable as this token: identifier
backfill deliberately assigns `updated_at = updated_at`, and even fractional timestamps can collide
within a clock tick. The integer trigger also covers writes from a rolled-back serving binary.
Schema rollback is not needed for an older binary; omitted expected tokens retain legacy behavior.

The trigger covers all DealMapper detail/name/outcome/value/source/actual-value/owner/risk/date/
position methods, member/account ownership cleanup, AssignmentSweepMapper owner cleanup,
AiAssistantIdentifierMapper backfill, and migration callbacks. Imports, bulk stage actions,
workflow/rule/assistant actions and line-item reconciliation reach these writers. Inserts start at
zero; deleted rows remain missing. Junction-only tags, people and collaborators do not rewrite
full-record fields and need no deal token increment.

MySQL foreign-key cascades do not activate UPDATE triggers. The existing company FK can detach a
surviving deal on physical deletion, so V241 detaches those exact `company_id` references in a
BEFORE DELETE company trigger first. It uses the existing indexed parent key and the same parent
company → child deal lock order as that FK action. The remaining deal parent FKs restrict deletion;
owner cleanup is already explicit after the cross-plane owner FK was removed in V65.
See [MySQL foreign-key behavior](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html).
Any new cascading parent action must account for this exception too.

## Form behavior

Capture a base record and token together when opening the form, including from an already stale
page/list. Do not update the base token when props or list data refresh while a draft exists.
Both deal sheets carry the token in their drafts. Inline value edits replace the complete row with
its successful response before a subsequent sheet captures it. Detail close/reopen, owner and full-edit
responses stay available as edit bases until refreshed props reach that version. Board moves adopt
the returned deal. Board Quick edit fetches a current record before opening because a move can
advance neighboring rows' position versions while the board reloads. Other bulk/calendar actions
refresh their records rather than seed an edit from a partial response. Submission and snapshot helpers live in `dealEditConflict.ts`, outside component
modules. A STALE_RECORD refusal fetches the latest record
using the write's captured workspace and compares it with the base, not with the edited draft.
`recordEditConflict.ts` and `useRecordEditConflict` provide the reusable discriminator, comparison
and latest-record loading pattern. Shared error copy maps the stable code before generic 409 text.

The conflict dialog names changed fields and preserves the draft. Keep editing never adopts the
new token and never retries the write. Reload latest requires explicit discard confirmation before
replacing that record's draft/base/token. A failed latest-record fetch preserves the draft and
explains how to try the check again. Another intervening save is caught by the next conditional
write. Custom-field edits run only after the core update succeeds.
Dismissal stays blocked while a save or conflict fetch is pending. Clearing a form's conflict also
invalidates late fetch answers, so an older form cannot reopen its prompt over a new draft.

Multi-record quick edits save sequentially, retain each successful response/base/token, and stop
on the first refusal. Remaining drafts stay open. A retry never resends already saved records.
Partial successes are intentionally separate transactions, as they were before this contract.
Both the helper's batch loop and the browser's record-save loop intentionally await sequentially:
stop on the first refusal and retain each successful response before attempting the next record.
These ordering requirements are part of the partial-save/error contract.
The batch helper captures a workspace pin before its first write when its caller supplies no
origin. Callers that already captured an origin forward those same options, including cancellation
signals. Conflict reads reuse the refused write's origin; a refusal never falls back to the newly
selected workspace. Board Quick edit pins its snapshot read and discards its response if the
rendered workspace changes before it completes. The workspace write-chain guard's exception
ledger remains limited to the dashboard queue; deal edits require no exception.

## Verification

`DealEditVersionTest` checks generated SQL, legacy compatibility, DTO transport and the error
contract without a database. `DealEditVersionMapperTest` checks targeted/legacy/maintenance/FK
writers and tenant isolation. `DealUpdateConcurrencyIntegrationTest` checks stale service refusal
and races two SQL updates carrying the same expected token; exactly one must win. The frontend
unit tests cover token submission, field comparison and localized error mapping; the two-tab
Playwright spec covers both sheet entry points and pages loaded before a competing write.
