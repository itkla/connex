# Duplicate Review and Import Contract

This document is authoritative for person/company/deal duplicate preflight, review proofs, CSV commit revalidation, and inert interaction-history imports. Lock ordering lives in `docs/backend/LOCKING.md`.

## Canonical matching boundary

`DuplicatePreflightService` is the single review boundary. It normalizes identities/names through `MatchingService`, obtains the same-organization workspace visibility allowlist through the established control access, and evaluates candidates with workspace/visibility-scoped mapper reads.

Evidence classes:

- Strong: exact canonical email, phone, domain, or external id.
- Weak: exact normalized name after a bounded broad candidate read and Java-side recheck.

Company weak-name review additionally compares boundary legal-form variants through
`MatchingService.normalizeCompanyNameForReview`: 株式会社, 有限会社, 合同会社, their
parenthesized abbreviations, and the suffixes Inc./Incorporated, Co., Ltd., Ltd./Limited,
K.K., LLC/L.L.C. and GmbH. Unicode width, case and kana folding precedes this comparison. This review
key never replaces canonical names or identity keys. A variant never authorizes automatic
attachment or merging; CSV company dependencies retain canonical-exact name validation.

Never auto-attach an unreviewed weak, shared, ambiguous, truncated, or aggregate-overflow result.

Response limits remain bounded. Direct and CSV response ceilings are product/security contracts; changing them requires explicit memory/UX review, not an arbitrary increase.

## Review context and proofs

A preview reserves a random, expiring, one-use rendered-review proof. Persist only non-PII proof material such as workflow/raw-payload review-context/result fingerprints; do not retain normalized PII merely to implement acknowledgement.

The server-derived review context binds the exact workspace, ordered rows, field mapping, duplicate action, manual links, and other candidate-affecting inputs before matching begins.

- A failed preview cancels its reservation.
- Every commit claims the exact unexpired proof returned by its preview, even when the candidate result was empty.
- A proof is invalid if workspace, rows/order, mapping, actions, links, or other bound input changed.
- A proof/acknowledgement cannot be reused across requests/workspaces or replaced after the user acknowledged a specific result.

## Strong-identity review queue

`/api/duplicate-reviews` is the duplicate-family-specific review surface over the live
`identity_collision` artifact. It is not the shared Data Quality Center envelope for provider,
import, workflow, or other review families. `/api/identity-collisions` remains the lower-level
group diagnostic. The connected-capture queue at `/account/connections/reviews` also remains a
separate shipped surface in this increment: capture candidate matching still reads through its
allowlisted `IdentityMapper` path, while guarded creation runs duplicate preflight.

Queue reads require `REPORT_READ`. Dismiss and reopen additionally require the record-native update
permission for the pair (`PERSON_UPDATE` or `COMPANY_UPDATE`); both permissions are locked and
revalidated before the organization duplicate-decision mutex. The extra read gate is the
conservative posture for this mixed-record queue until a dedicated data-steward permission is
introduced.

The queue uses lowercase `IdentityKind` database values for query filters: `email`, `phone`,
`domain`, and `external_id`. Response evidence maps those four values to the existing uppercase
`DuplicateMatchKind` enum but never returns the canonical value. The top-level confidence remains
`DuplicateMatchStrength.STRONG`. `NAME` and `DEAL_KEY` are not valid collision-queue filters.

Each ordinary item is keyed by workspace, record type, identity kind, evidence fingerprint, and an
ordered pair of workspace-owned record IDs. This means two records that share both an email and a
phone have two independently dismissible items. Collision groups with at most twenty visible
members expand to pairs. A larger group produces one `oversized_group` item with no inline members
and cannot be dismissed through the pair endpoint; callers can use the existing collision-member
query to inspect its bounded member pages. This prevents a shared switchboard or general inbox from
causing quadratic queue growth.

Pair members expose `ownedByActiveWorkspace`; current materialization is intra-workspace, so it is
true for the shipped queue shape. Person summaries still resolve an employer shared into the active
workspace through the ordinary same-organization share ceiling.

The response fingerprint is SHA-256 over a length-prefixed tuple of record type, lowercase identity
kind, and the current canonical identity value. The decision row stores only that hash, record and
identity IDs, state, actor/timestamps, and an optional 500-character note. Neither the row nor the
HTTP response copies or exposes the normalized value. This deliberately follows the review-proof
material rule above instead of the older `provider_participant_decision` key, which includes
`normalized_email`: new suppression state does not need normalized PII to recover its matching key.

Identity intake refreshes review rows inside the same transaction and canonical identity-group lock
that rebuilds `identity_collision`. It marks the old evidence inactive, then reactivates or creates
the current pair or oversized item. An exact dismissed fingerprint stays dismissed. Materially
changed canonical evidence receives a different fingerprint and therefore appears open, while the
old decision row remains retained and inactive. The startup identity repair sweep keyset-pages
collision groups and materializes each bounded page in one mapper statement rather than issuing
decision statements per group.

Archive and processing-restriction mutations reconcile each affected identity group while holding
the organization duplicate-decision mutex. They remove the target's prior collision membership,
re-add it only when the live record is unarchived and unrestricted, remove any resulting singleton,
then rematerialize the pair-or-oversized review shape from those live members. They inspect every
current identity kind, including `external_id`, and lock at most twenty-one group members. Existing
pair decisions retain their state through upsert, pair expansion remains bounded to 190 rows, and
repeating an already-applied visibility event produces the same cardinality and shape.

Dismiss and reopen echo the exact record type, kind, ordered-or-unordered pair IDs, and fingerprint
returned by the queue. The service normalizes the pair order. A fingerprint that is no longer
current returns HTTP 409. The lock posture is the terminal decision-write rule in
`docs/backend/LOCKING.md`: locked `REPORT_READ` and type-specific update permissions, organization
duplicate-decision mutex, then exact decision row, followed by a current-evidence and visibility
recheck.

## Manual creation and client preflight

Manual person/company/deal creation, staged contacts, and OCR-reviewed contact/company creation submit their complete current identity fields through the canonical boundary.

Client debounce is an early warning only. Submission performs an immediate final review. Deal acknowledgement is bound to the exact request workspace and complete candidate-response token; truncated results cannot be acknowledged. The mutation consumes that same proof only after its locked final candidate recheck.

Person name lookup is bounded independently of exact email/phone lookup. When its broad
prefilter or exact-name set exceeds the existing response bound, `truncated` remains true:
filtering a bounded prefilter cannot prove that unseen exact names do not exist. Direct
person review may return `allowSeparateCreation=true` only when every supplied canonical
identity lookup is complete and empty. The user can then explicitly acknowledge the displayed
bounded name review and create a separate contact, even if no exact candidate survived the
prefilter. This exceptional path requires a server-issued, expiring, one-use proof. The issuer
uses the existing configured audit-integrity HMAC secret with a separate bounded-person domain,
the actor/workspace, normalized request/result fingerprints and a cryptographic random nonce.
Only the token hash and non-PII binding fingerprints are stored in the existing
`deal_duplicate_review_proof` table through `DealDuplicateReviewProofService`; its historical
name does not imply a deal foreign key. Existing expiry, actor and workspace cleanup applies.

The result binds both the displayed candidates/permission and the complete visible broad-name
prefilter's count and highest record ID. That aggregate is conservative broad evidence, not
an exact normalized-name total, and uses the display query's visibility/restriction predicates.
Issuance and the final recheck acquire the existing organization duplicate mutex. Creation
rechecks identities and the current result, then atomically consumes the exact unexpired proof
for the same actor/workspace/normalized request in its transaction. Successful creation commits
the proof deletion; rollback preserves it. Deleting a created record cannot revive the proof,
even if the count and highest ID return to their earlier values. New strong evidence or a
changed displayed/aggregate result requires fresh review. Client final preflight echoes the
acknowledged proof and preserves it only while the entire binding still matches.

This is permission to create,
never proof of completeness for reuse, imports, automatic linking or merging. Those paths
retain their existing truncation guards. Company and deal overflow remain blocking.

The frontend uses the shared duplicate-preflight hook/request scope rather than implementing feature-specific matching.

Assistant person preparation uses `personCandidatesExist`, the read-only advisory matcher;
it neither takes the duplicate mutex nor issues a proof. Truncation still refuses automatic
assistant creation. Approval retains the canonical locked creation check.

## Locking and mutation recheck

Candidate-affecting create/update/restriction/delete/share/import/OCR/backfill operations acquire the duplicate-decision hierarchy documented in `docs/backend/LOCKING.md` before record/identity locks.

While holding the locks:

- requery the current candidate/identity state;
- automatic targets must remain the unique owner of supplied identities;
- creates must still have no owned match;
- acknowledgement must still describe the current candidate result, or the explicitly bounded
  name-only separate-person review described above;
- target loss or concurrent candidate change fails/revalidates according to the owning batch contract.

Do not rely on the preview snapshot as commit authorization.

## CSV dependencies and commit behavior

Person/deal previews bind every exact referenced-company name; deal proofs also bind every rendered create-vs-match decision.

During commit:

- resolve company dependencies under the duplicate-decision hierarchy;
- reuse only a unique visible dependency;
- ambiguous/truncated dependencies fail closed for rows that consume them;
- lock writable matched targets ascending, then canonical identity groups deterministically;
- create custom fields, tags, or reviewed referenced companies only after target/identity revalidation;
- if one target vanished, invalidate its rows without corrupting unrelated valid rows;
- if every target vanished, produce no dependency or audit side effect;
- stable canonical-key order controls dependency creation.

Do not introduce dependency-first lock acquisition; it conflicts with interactive target-first ordering and can create a deadlock cycle.

## Interaction-history imports

Historical activity/note/task imports use the same one-use proof and duplicate-decision hierarchy, then lock resolved people in ascending order and write bounded direct mapper batches with replay provenance.

They are intentionally inert backfill:

- one historical person participant per imported item;
- no per-row service invocation;
- no automation/rule evaluation;
- no mention processing;
- no ordinary audit/notification publisher side effects;
- no automatic linking of shared/ambiguous candidates.

Source-less rows receive a stable semantic-occurrence ordinal before participant resolution so progressive manual linking cannot renumber legitimate identical interactions. Replaying the same ordered file remains idempotent.

Historical notification baselines are computed independently of recipient delivery preferences and suppress only causally scoped expectation keys. Compare a fixed pre-import snapshot with a post-write counterfactual excluding the exact imported entities; concurrent changes to relevant non-import inputs fail the transaction. Baselines bind to complete persisted source evidence rather than clock-only/presentation fields, and participate in tenant/member/account lifecycle cleanup.

## Review checklist

- One canonical matching/normalization implementation is used.
- Weak/shared/ambiguous/truncated candidates never auto-link.
- Review proof is random, expiring, one-use, and bound to the complete exact request snapshot.
- No normalized PII is persisted merely for review proofing.
- Commit claims the proof and performs a locked final recheck.
- Dependency and target lock order matches `docs/backend/LOCKING.md`.
- Partial target disappearance cannot create unrelated side effects.
- Interaction-history imports remain idempotent, bounded, and inert.
- Relevant matching/proof/concurrency/import tests pass.
