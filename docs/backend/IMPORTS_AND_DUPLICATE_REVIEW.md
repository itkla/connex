# Imports and Duplicate-Review Contract

This document is authoritative for person/company/deal identity matching, duplicate preflight, reviewed creation, CSV preview/commit proofs, OCR-reviewed creation, and interaction-history backfill. Lock ordering lives in `docs/backend/LOCKING.md`.

## Canonical matching boundary

`DuplicatePreflightService` is the canonical duplicate-review boundary.

- Normalize identity/name evidence through `MatchingService`.
- Hydrate the same-organization workspace visibility allowlist through the established control-access service, then use visibility-scoped `IdentityMapper` reads.
- Strong evidence is exact canonical identity (email, phone, domain, external id as applicable).
- Weak evidence is exact normalized name after a bounded broad candidate read and Java recheck.
- Never auto-attach an unreviewed weak, shared, ambiguous, truncated, or aggregate-overflow result.
- Every mapper statement remains parameterized and tenant/visibility scoped.

Direct responses remain bounded to the established candidate ceiling; CSV responses retain their per-row and whole-request bounds. A truncated result is not acknowledgeable proof of completeness.

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

Do not create a separate simplified duplicate path for a new form/import surface.

## Locking and final recheck

Candidate-affecting creates, updates, restrictions, deletes, sharing, imports, OCR, and identity backfill pass through `DuplicateDecisionLockService` before record/identity locks.

Follow `docs/backend/LOCKING.md` for the exact organization/workspace/membership/mutex/target/identity order.

While locks are held:

- requery current canonical identities;
- automatic match targets must remain the unique owner of supplied identities;
- creates must still have no owned match;
- locked final state, not preview state, authorizes the write.

## CSV dependency resolution

Person/deal previews bind exact referenced-company names to the proof; deal proofs also bind every rendered create-vs-match decision.

At commit:

- a unique visible company dependency may be reused;
- ambiguous/truncated dependencies fail closed only for rows that consume them;
- do not create a dependency under the duplicate mutex until all required target/identity revalidation passes;
- lock writable matched targets individually in ascending record id and canonical identity groups in deterministic kind/value order;
- create custom-field definitions, tags, or reviewed referenced companies only after target/identity validation;
- a vanished target invalidates its rows without forcing unrelated valid rows to fail;
- an all-vanished batch produces no dependency/audit side effect;
- disappearing dependencies fail transactionally so the complete import rolls back.

Do not add a dependency-first lock pass; it reverses the interactive mutation order and creates a deadlock cycle.

## Interaction-history imports

The Settings → Data interaction-history wizard imports one historical person participant per activity, note, or task.

- Use the same one-use proof and duplicate-decision hierarchy.
- Lock resolved people in ascending order.
- Write bounded direct mapper batches with replay provenance.
- Never call per-row domain services or publish rules, mentions, audit, or notifications from the backfill path.
- Never auto-link shared or ambiguous candidates.
- Keep the backfill inert: imported history does not masquerade as live user activity.

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
