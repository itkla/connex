# Conditional record edits

Deal and contact full-record edits use optional optimistic concurrency (#2216). This is the pattern
for other whole-record forms. Tenant, visibility and permission checks still apply.

## HTTP contract

- GET/list contact DTOs and GET/list/board deal DTOs return a database-owned integer `version`.
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
  every dependent write, history, audit and event. A preceding value/source write would advance
  the counter and conflict with the request's own token. A refusal rolls everything back. Return a
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
workflow/rule/assistant actions and line-item reconciliation reach these writers.
DealValueMapper fallback capture and clear also update the parent row and advance its counter.
Line preview is read-only; confirmation carries the reviewed manual amount and currency, without
a parent expected-version token. Line writes retain the parent lock and do not replay a broad edit. Inserts start at
zero; deleted rows remain missing. Junction-only tags, people and collaborators do not rewrite
full-record fields and need no deal token increment.

MySQL foreign-key cascades do not activate UPDATE triggers. The existing company FK can detach a
surviving deal on physical deletion, so V241 detaches those exact `company_id` references in a
BEFORE DELETE company trigger first. It uses the existing indexed parent key and the same parent
company → child deal lock order as that FK action. The remaining deal parent FKs restrict deletion;
owner cleanup is already explicit after the cross-plane owner FK was removed in V65.
See [MySQL foreign-key behavior](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html).
Any new cascading parent action must account for this exception too.

## Contact versions (tenant V242)

V242 adds `person.version` with `ALGORITHM=INSTANT, LOCK=DEFAULT` and the database-owned
`trg_person_edit_version` BEFORE UPDATE counter. Person DTOs use the same boxed read-only `version`
and optional write-only, non-negative `expectedVersion`. `PersonService.update` checks an already
locked owned contact before requested-company validation and retains the version predicate in the
actual workspace/id-scoped broad UPDATE. A zero-row write refuses before employment history,
identity intake, audit or rule publication; a missing/archived contact remains 404. The transaction
retains the existing organization duplicate mutex → person lock order and READ_COMMITTED isolation.

Person writers covered by the counter are:

- `PersonMapper.update`, `updateOwner`, `updateImageUrlIfCurrent`, `updateEvaluationExclusions`,
  `updateProcessingRestrictions`, `updateProvenance`, `updateLifecycle`, `startFirstResponseClock`,
  `clearFirstResponseClock`, `recordFirstResponse`, `recordFirstResponseBreach`,
  `clearMemberOwnership`, `clearOwnershipAnywhere`, `archive` and `restore`.
- `AssignmentSweepMapper.clearOwner` for person and lifecycle-pass units;
  `AiAssistantIdentifierMapper.updatePersonKeysIfNameCurrent` (which preserves `updated_at`);
  `LegacyTenantUploadMigrationMapper.updatePersonImage`; direct maintenance/migration UPDATEs.
- CSV import updates, bulk owner/lifecycle actions, assistant/rule/workflow actions,
  activity first responses, SLA sweeps and member/account cleanup use those same statements.

Person tags, employment, identity, qualification answers, lifecycle passes, consent, edges and
custom-field junction/value writes do not overwrite the person row and do not require its token.
Deletes remove the row; inserts start at zero. Creation augmentation affects only related rows.
Full updates and targeted owner/picture/evaluation/restrictions/provenance/archive/restore writes
already reselect the contact after their writes. Lifecycle transitions reselect after both stage and
clock writes, so a returned Person always carries the final version. The lifecycle state DTO also carries that
post-write version.

## Marketing consent and privacy patches (tenant V245)

Contact-channel consent has an independent database-owned `version`, initialized to one by V245.
`trg_contact_consent_version` advances it on every UPDATE, including no-op updates and trusted
unsubscribe/provider revocations. Its second-precision `updated_at` remains a freshness timestamp;
it cannot distinguish competing changes within one second. No client clock or timestamp conversion
participates in the fence.

HTTP consent PUT requires a non-negative `expectedVersion`. Zero explicitly means the member
reviewed an absent row; unique subject-key insertion admits one winner. Existing-row updates match
workspace, person, channel, purpose, and version in the UPDATE itself. Refusals return the existing
`STALE_RECORD` 409 shape without appending history or audit. The UI rereads, closes the outdated
confirmation, and requires a new explicit action; a fresh revoked state shows the opt-out-clearing
warning. Internal unsubscribe and provider revocations retain their unconditional trusted service
path and advance the same counter. They never depend on a browser snapshot.

Privacy restriction requests are patches: only explicitly supplied Boolean flags change. Omitted
or null flags retain their values from the current owned contact under the existing organization
duplicate mutex and person lock. The effective merged flags drive sharing revocation, captured-data
withdrawal, identity visibility and AI invalidation. A stale sharing-only update therefore cannot
clear a concurrently added processing hold. The frontend submits only flags changed by the member.

The historical `fk_person_company` is ON DELETE SET NULL, whose automatic action bypasses UPDATE
triggers. `trg_company_delete_person_edit_version` explicitly detaches those exact indexed company
references before the existing FK action. The V241 company→deal trigger remains intact and runs
first; V242 then detaches contacts, preserving parent→child ordering. `person.owner_id`, introduced
in V90, has never had a user FK; member/account removal uses explicit UPDATEs and the counter.
`workspace_id`'s control-plane FK was removed in V65, and V179 deliberately introduced no
`referrer_person_id` FK. There is no other surviving parent cascade that mutates person fields.

`PersonEditVersionTest` checks generated SQL, DTO transport/validation and shared error mapping;
`PersonFrozenSqlTest` keeps the frozen visibility oracle aligned with the version projection.
`PersonEditVersionMapperTest` checks targeted/legacy/maintenance writers, company detachment and
workspace isolation. `PersonUpdateConcurrencyIntegrationTest` checks a committed second title edit
against a stale phone draft, atomic-refusal side effects, missing rows, legacy writes, returned
versions and the same-token two-writer SQL race. Database execution is CI-owned for this migration.

## Form behavior

Capture a base record and token together when opening the form, including from an already stale
page/list. Do not update the base token when props or list data refresh while a draft exists.
Both deal sheets carry the token in their drafts. Inline value edits replace the complete row with
its successful response before a subsequent sheet captures it. Detail close/reopen, owner and full-edit
responses stay available as edit bases until refreshed props reach that version. Board moves adopt
the returned deal. Detail Edit fetches a pinned current record before opening, so line-item
reconciliation cannot seed another draft with an old token while route refresh is pending.
The sheet opens only if the rendered workspace still matches the captured origin. Board Quick edit
fetches a current record before opening because a move can
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
Core deal fields remain editable while a save is pending. Each response always advances the base
snapshot and token, but replaces the draft only if it is still the immutable draft sent by that
request. Newer input keeps all its fields and receives only the acknowledged version. Edits made
during a save keep the sheet open, including after a successful batch; the next explicit Save
submits remaining differences against the acknowledged snapshots. This also applies to detail edits.
Both the helper's batch loop and the browser's record-save loop intentionally await sequentially:
stop on the first refusal and retain each successful response before attempting the next record.
These ordering requirements are part of the partial-save/error contract.
The batch helper captures a workspace pin before its first write when its caller supplies no
origin. Callers that already captured an origin forward those same options, including cancellation
signals. Conflict reads reuse the refused write's origin; a refusal never falls back to the newly
selected workspace. Board Quick edit pins its snapshot read and discards its response if the
rendered workspace changes before it completes. The workspace write-chain guard's exception
ledger remains limited to the dashboard queue; deal edits require no exception.

### Contact edit surfaces

Contact detail Edit, list selection/row quick edit, and the internal contact-card editor on company
profiles fetch current records with the rendered workspace pin before opening. The internal card
reuses `EditContactSheet`. Forms freeze their base/token and the opening workspace; background list
or route refreshes never rebase an existing draft. Email/phone/title inline saves condition on the
row token and replace the complete row through `useServerRecords.replaceItem`, including removal
of nullable fields omitted from JSON. Draft submissions retain explicit empty strings for cleared
email, phone and title values. Omission/null means no change for those mapper columns; an empty
string means clear, for both inline edits and sheets. A stale inline refusal restores the row's last server snapshot
and keeps the refused value only in the quick-edit draft. The entire inline request and conflict
read block another editor from replacing it.

`contactEditConflict.ts` owns contact submission, reconciliation and field comparison; the sheets
reuse `useRecordEditConflict` and the same explicit discard-confirmation dialog pattern as deals.
Selected-contact batches save sequentially and stop on the first refusal. Each success replaces its
base/token; later input keeps its text and advances only to the acknowledged version. Reloading one
conflicted contact replaces only that draft; it resets the sheet's discard baseline only when no
other changed drafts remain. Retrying skips unchanged acknowledged rows. Custom fields and photos save only after the conditional core write.
Photo responses advance the draft token only when they are exactly one database update after that
core write. Any intervening writer leaves the core token in place and opens a conflict check, so a
photo cannot bless stale text with someone else's newer token.

Company association and both detail/bulk removal also send broad PUTs, so `ChangeCompanyDialog`
loads pinned bases and uses the same conditional submission/conflict flow. Partial successes retain
returned snapshots; retries compare each acknowledged company with the current target, so changing
the target cannot silently skip an earlier row. Target selection and dismissal stay blocked during
writes and current-record reads. Other profile panels (owner, lifecycle, provenance, restrictions,
evaluation, archive and restore) use targeted endpoints and act as competing versioned writers.

## Verification

`DealEditVersionTest` checks generated SQL, legacy compatibility, DTO transport and the error
contract without a database. `DealEditVersionMapperTest` checks targeted/legacy/maintenance/FK
writers and tenant isolation. `DealUpdateConcurrencyIntegrationTest` checks stale service refusal
and races two SQL updates carrying the same expected token; exactly one must win. The frontend
unit tests cover token submission, field comparison and localized error mapping; the two-tab
Playwright spec covers both sheet entry points and pages loaded before a competing write.

Contact frontend regressions cover token submission, changed-field copy, delayed draft reconciliation,
photo interleavings, company target changes after partial success, full response replacement, and
workspace-pinned reads/writes. `workspaceBoundRecordChains.test.tsx` exercises contact batch responses
and retries through the real API transport. `contact-stale-edit.spec.ts` provisions an isolated workspace
per case and covers English/Japanese two-tab detail, list and company-profile edits, pages loaded
before the competing write, and a delayed inline refusal. Browser execution remains CI-owned.
