# Duplicate Review and Import Contract

This document is authoritative for person/company/deal duplicate preflight, review proofs, CSV commit revalidation, and inert interaction-history imports. Lock ordering lives in `docs/backend/LOCKING.md`.

## Canonical matching boundary

`DuplicatePreflightService` is the single review boundary. It normalizes identities/names through `MatchingService`, obtains the same-organization workspace visibility allowlist through the established control access, and evaluates candidates with workspace/visibility-scoped mapper reads.

Evidence classes:

- Strong: exact canonical email, phone, domain, or external id.
- Weak: exact normalized name after a bounded broad candidate read and Java-side recheck.

Never auto-attach an unreviewed weak, shared, ambiguous, truncated, or aggregate-overflow result.

Response limits remain bounded. Direct and CSV response ceilings are product/security contracts; changing them requires explicit memory/UX review, not an arbitrary increase.

## Review context and proofs

A preview reserves a random, expiring, one-use rendered-review proof. Persist only non-PII proof material such as workflow/raw-payload review-context/result fingerprints; do not retain normalized PII merely to implement acknowledgement.

The server-derived review context binds the exact workspace, ordered rows, field mapping, duplicate action, manual links, and other candidate-affecting inputs before matching begins.

- A failed preview cancels its reservation.
- Every commit claims the exact unexpired proof returned by its preview, even when the candidate result was empty.
- A proof is invalid if workspace, rows/order, mapping, actions, links, or other bound input changed.
- A proof/acknowledgement cannot be reused across requests/workspaces or replaced after the user acknowledged a specific result.

## Manual creation and client preflight

Manual person/company/deal creation, staged contacts, and OCR-reviewed contact/company creation submit their complete current identity fields through the canonical boundary.

Client debounce is an early warning only. Submission performs an immediate final review. Deal acknowledgement is bound to the exact request workspace and complete candidate-response token; truncated results cannot be acknowledged. The mutation consumes that same proof only after its locked final candidate recheck.

The frontend uses the shared duplicate-preflight hook/request scope rather than implementing feature-specific matching.

## Locking and mutation recheck

Candidate-affecting create/update/restriction/delete/share/import/OCR/backfill operations acquire the duplicate-decision hierarchy documented in `docs/backend/LOCKING.md` before record/identity locks.

While holding the locks:

- requery the current candidate/identity state;
- automatic targets must remain the unique owner of supplied identities;
- creates must still have no owned match;
- acknowledgement must still describe the complete current candidate result;
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
