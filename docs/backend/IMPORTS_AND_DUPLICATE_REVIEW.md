# Imports and Duplicate-Review Contract

This document is authoritative for person/company/deal identity matching, duplicate preflight, reviewed creation, CSV preview/commit proofs, OCR-reviewed creation, and interaction-history backfill. Lock ordering lives in `docs/backend/LOCKING.md`.

## Canonical matching boundary

`DuplicatePreflightService` is the canonical duplicate-review boundary.

- Normalize identity/name evidence through `MatchingService`.
- Hydrate the same-organization workspace visibility allowlist through the established control-access service, then use visibility-scoped `IdentityMapper` reads.
- Strong evidence is exact canonical identity (email, phone, domain, external id as applicable).
- Weak evidence is exact normalized name after a bounded broad candidate read and Java recheck.
- Company name review also recognizes the boundary legal forms documented in
  [Duplicate Review](DUPLICATE_REVIEW.md), after Unicode width/case/kana folding. These are weak
  review keys only; canonical identity/name keys are unchanged. Variants never authorize
  automatic attachment or merging.
- Never auto-attach an unreviewed weak, shared, ambiguous, truncated, or aggregate-overflow result.
- Every mapper statement remains parameterized and tenant/visibility scoped.

Direct responses remain bounded to the established candidate ceiling; CSV responses retain their per-row and whole-request bounds. A truncated result is not acknowledgeable proof of completeness.

Direct person review can explicitly permit separate creation from a bounded name-only result
through `allowSeparateCreation`, while retaining `truncated=true`. This permission requires
complete, empty canonical email/phone results, explicit user acknowledgement, and the same
server-issued keyed proof at the locked final recheck, including when the displayed candidate list
is empty. The existing duplicate proof store binds actor/workspace, normalized request, displayed
result and the visible broad-name count/highest ID; expiry and transactional consumption prevent
forgery and replay. Counts are not a substitute for consumption: deletion could restore them.
The final preflight echoes the acknowledged proof instead of replacing a still-valid one.
See [Duplicate Review](DUPLICATE_REVIEW.md#manual-creation-and-client-preflight).
It never permits reuse or import resolution; company and deal overflow still blocks.

### Person CSV identity decisions

Person CSV imports use exact normalized email as their automatic matching and in-file combining
key. A phone number, even alongside an exact name, is review evidence only: office numbers can
belong to several people. Different emails or phone-only rows remain separate unless the importer
explicitly links them to an owned contact. A phone candidate never automatically merges source
rows, selects an update target, or prevents another person from being created.

Repeated rows with the same email combine under the selected duplicate action, retaining the
existing first-value/fill-empty or last-value/overwrite precedence and unioned references. Shared
or changed phones cannot connect different email groups. Conflicting explicit targets for one
email still fail closed; they are never resolved by row order.

Both email and phone groups retain deterministic locks and current-state rechecks. Only email
ownership authorizes or rejects an automatic person CSV decision; phone ownership is nonexclusive.
An automatic target that loses its email before the locked recheck fails even if its phone still
matches. Missing current phone evidence is checked per target, so another contact's phone identity
does not suppress the selected contact's provenance backfill. Candidate visibility, truncation
limits and one-use review proofs remain unchanged.

## CSV headers and person names

The CSV wizard suggests only exact known aliases for fields supported by the selected entity,
after Unicode NFKC, case and space/underscore/hyphen normalization. Japanese headers remain
Japanese. Company Name means the company link for contacts/deals and the record name for
companies. Unsupported lifecycle/source fields, phonetic-name headers and uncertain headers stay
unmapped; fragments never imply Tags. Competing columns for a single-valued target stay unmapped rather
than choosing a winner by file order. An unambiguous full-name column takes precedence over
initial split-name suggestions.

Contacts may map one full-name column unchanged, or select one given-name and/or one family-name
column. The Map step shows the composed sample and offers both orders. Japanese 姓/名 headers or
the Japanese interface default to family then given; otherwise the default is given then family.
Trim each selected part, omit empty parts and join with a single space. Keep single-column full
names intact; do not guess where to split them. A full-name mapping combined with name parts, or
multiple columns for a name part, blocks progression until the user resolves the conflict.

Composition happens before preview through the existing single `name` target, using a derived
column that cannot overwrite a source column. The original row values remain present. Preview
and commit use the exact same derived rows and mapping, so the existing review context and proof
bind the composed names. Changing name parts or their effective order requires a fresh review.
The server continues to validate required names and all other imported fields.

## Review proofs

Preview admission stores only the established hashes/fingerprints plus a random expiring one-use rendered-review proof. Do not persist normalized PII merely to recreate matching later.

The server-derived context binds the proof to the complete workflow snapshot, including as applicable:

- workspace;
- ordered rows and raw-payload review context;
- field mapping;
- duplicate action;
- manual links;
- referenced-company decisions;
- rendered candidate/create-vs-match decisions.

Every person/company/deal CSV commit atomically claims the exact unexpired proof returned by preview before acquiring database locks, including when the duplicate result was empty. Failed previews cancel their reserved proof.

A proof is never reusable after any bound input changes.

## Manual and staged creation

Manual person/company/deal creation, staged contacts, and OCR-reviewed person/company creation submit their complete current fields through the same duplicate boundary.

Client debounce is presentation only. Submit performs an immediate `reviewNow()`/equivalent current review with the same request/workspace scope as the mutation.

Deal acknowledgement:

- is bound to the exact proposed values, workspace, and complete candidate response;
- passes the existing proof back for validation without replacement;
- cannot acknowledge a truncated candidate set;
- is atomically consumed only after the mutation's locked final candidate recheck.

If the locked final deal candidate set is empty, creation needs no acknowledgement, but a submitted
one-use proof is invalidated for that workspace, actor, and deal workflow before creation proceeds.
A proof from an earlier non-empty result of the same workflow must not survive candidate
disappearance for later replay. Invalidation binds to the workflow fingerprint but not the result
fingerprint — the empty set legitimately differs from the reviewed result — so a still-valid proof
for a different deal workflow submitted concurrently under the same token is left untouched.

Do not create a separate simplified duplicate path for a new form/import surface.

## Locking and final recheck

Candidate-affecting creates, updates, restrictions, deletes, sharing, imports, OCR, and identity backfill pass through `DuplicateDecisionLockService` before record/identity locks.

Follow `docs/backend/LOCKING.md` for the exact organization/workspace/membership/mutex/target/identity order.

While locks are held:

- requery current canonical identities;
- automatic match targets must remain the unique owner of supplied automatic matching identities
  (email for person CSV imports);
- creates must still have no owned automatic match;
- locked final state, not preview state, authorizes the write.

## CSV dependency resolution

Person/deal previews bind exact referenced-company names to the proof; deal proofs also bind every rendered create-vs-match decision.

At commit:

- a unique visible company dependency may be reused;
- dependency reuse still requires canonical-exact names; a legal-form variant is shown for
  review and refuses unresolved rows until the file uses the intended company's exact name;
- ambiguous/truncated dependencies fail closed only for rows that consume them;
- do not create a dependency under the duplicate mutex until all required target/identity revalidation passes;
- lock writable matched targets individually in ascending record id and canonical identity groups in deterministic kind/value order;
- create custom-field definitions, tags, or reviewed referenced companies only after target/identity validation;
- a vanished target invalidates its rows without forcing unrelated valid rows to fail;
- an all-vanished batch produces no dependency/audit side effect;
- disappearing dependencies fail transactionally so the complete import rolls back.

An import that uses an auto-create custom-field mapping locks the affected record-creation template
set before writable record targets, as defined in `docs/backend/LOCKING.md`. This is a schema fence;
the definition itself remains uncreated until target and identity revalidation succeeds.

Auto-created custom fields use stable SHA-256-derived suffixes for non-ASCII labels, empty ASCII
projections and labels whose keys would otherwise be truncated. Mixed Japanese/ASCII labels retain
the complete label in the suffix input. An existing definition is reusable only in the active
workspace and entity type, with the exact trimmed label, requested field type and active status.
An unrelated, differently typed or archived definition at the preferred key instead selects a
stable label/type-derived alternate key, which is subject to the same reuse checks. Creation still
requires custom-field management permission. Existing legacy keys are not renamed or migrated.

Preview and commit use the same key resolution and reject multiple columns targeting the same
auto-created definition before a review is accepted. The template-set fence rechecks resolved keys
and their presence before target locks, preventing a concurrent catalog change from silently
redirecting imported values.

Do not add a dependency-first lock pass; it reverses the interactive mutation order and creates a deadlock cycle.

## Deal history dates

Deal CSV mapping supports `closedAt` (Closed at) and `createdAt` (Created at), separately from
`expectedCloseDate`. Both accept strict `yyyy-MM-dd`, `yyyy/MM/dd`, and ISO instants with an
explicit offset. Date-only values mean the start of that day in the active workspace analytics
reporting timezone (workspace override, then actor timezone, then UTC). They are converted to UTC
before storing in the existing DATETIME columns, at second precision. Thus Tokyo's March 1 belongs
to March's local revenue period even though its stored UTC timestamp begins on February 28.
Unparseable or out-of-range dates are row errors in preview and commit; they never become today.

The existing duplicate action governs both history targets: Fill empty retains stored dates,
Overwrite uses supplied dates, and Skip changes nothing. A missing Created at on a new deal keeps
the database's current-time default. Historical creation dates are written only by CSV import;
ordinary creation and edits retain their existing semantics.

Review exposes the effective dates and timezone for each writable row. Closed deals with no
effective close date carry a non-blocking warning; the client names the affected source-row count
and explains that import time will be used. Future close dates also carry a row warning. Open
deals retain the outcome writer's existing behavior of clearing close dates. Closed dates supplied
to that writer are preserved; missing dates still use current UTC time.

Raw rows and mapping targets remain bound to the one-use proof. The rendered decision fingerprint
also binds effective dates, warning flags, reporting timezone, effective stage, and outcome,
including coalesced rows and matched-record merge semantics. A timezone, stage, or stored-date
change that alters the reviewed result requires a fresh preview. Matched dates are rechecked under
the existing target locks before dependencies or writes. Period revenue and cycle calculations
continue to read the canonical `closed_at` and `created_at` columns. Realized revenue charts use
won deals' `actual_value` and reporting-timezone buckets of `closed_at`, including wins that also
have an expected close date. Projected series keep their expected-close date basis. Deal CSV
exports append `closedAt` and `createdAt` after the existing columns, including custom fields and
`tags`, preserving their positions. These timestamps are UTC ISO instants with an explicit `Z`, so
exporting and reimporting the history targets preserves the same instants. Deal close-date displays and
editors delegate to the shared frontend UTC parser and civil-input helpers, using the workspace
reporting timezone above so the displayed close day matches its realized-revenue bucket regardless
of the viewer's profile timezone. They do not use the browser/server host timezone. Other timestamp
displays retain the profile timezone. Editor conversion preserves an
unchanged repeated-hour instant and seconds, chooses the earlier occurrence for a new repeated-hour
value, and rejects missing civil times. Bare calendar-only close and expected-close dates never shift.
See [the frontend date/time contract](../frontend/DATES_AND_TIME_ZONES.md).

## Interaction-history imports

The Settings → Data interaction-history wizard imports one historical person participant per activity, note, or task.

- Use the same one-use proof and duplicate-decision hierarchy.
- Lock resolved people in ascending order.
- Write bounded direct mapper batches with replay provenance.
- Never call per-row domain services or publish rules, mentions, audit, or notifications from the backfill path.
- Never auto-link shared or ambiguous candidates.
- Keep the backfill inert: imported history does not masquerade as live user activity.
- For completed task history, the supplied interaction occurrence is the completion instant (`completed_at`); open imported tasks have no completion instant. Do not infer one for previously stored rows.

Source-less rows receive their stable semantic-occurrence ordinal before participant resolution. Identical legitimate interactions remain distinct, while replaying the same ordered file remains idempotent.

## Notification baselines for backfill

Historical notification baselines are computed independently of recipient delivery preferences and may suppress only expectations causally scoped to the imported people, changed deal-risk expectations, or exact imported task ids at one fixed evaluation instant.

Preserve the existing counterfactual/concurrency contract:

- compare the pre-import snapshot with a post-write counterfactual excluding the exact imported entity ids;
- fail the transaction when relevant non-import inputs changed concurrently;
- bind each baseline to a fingerprint over complete persisted source evidence with the same import exclusions;
- do not bind to clock-only decay or presentation fields;
- advance an existing baseline only when it still matches the pre-import stable source state;
- remove baselines with tenant lifecycle/member-detachment/account-erasure cleanup.

A disabled recipient preference retains its baseline. An import-caused deal-risk disappearance does not erase a pre-existing reminder until a live source-state change releases it.

## Frontend integration

Person/company/deal create forms, staged contacts, and OCR review use the shared duplicate-preflight hook/client contract with complete current identity fields and the same `RequestInit`/workspace scope as the mutation.

CSV clients retain only the proof for the exact preview snapshot and include it in the corresponding commit. Auth/workspace transitions discard stale proof state.

## Review checklist

- All surfaces use the canonical matching service/boundary.
- Strong/weak evidence and visibility scope are unchanged.
- Ambiguous/shared/truncated/overflow candidates never auto-link.
- Proofs are random, expiring, one-use, and bound to the complete request snapshot without persisting normalized PII.
- Submit/commit performs the locked final recheck.
- Lock order follows `docs/backend/LOCKING.md` and does not introduce dependency-first inversion.
- Interaction-history backfill remains direct-batch, replay-safe, and inert.
- Notification baseline/counterfactual concurrency semantics remain intact.
- Matching, proof replay, truncation, cross-workspace visibility, concurrent mutation, dependency, rollback, and backfill tests pass.
