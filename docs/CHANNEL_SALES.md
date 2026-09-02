# Channel sales — model decision and delivery contract

Tracking issue: [#566](https://github.com/itkla/connex/issues/566). Roadmap parent:
[#568](https://github.com/itkla/connex/issues/568).

This document is Increment 0 for partner, referral, and channel-sales workflows. It fixes the
model, safety boundaries, sequencing, and vocabulary that later increments must implement. It
introduces no runtime contract and authorizes no schema work by itself.

## Status and demand gate

The model decisions below are binding so later work does not reopen one-way schema and permission
choices. Runtime Increments 1–7 are **parked until a named customer validates channel-sales demand**.
This is the conservative response to #568 placing #566 in Wave 3 while the meaning and priority of
channel demand remain unvalidated. The external experience in Increment 8 has its own stricter gate.

When implementation begins, each migration uses the next globally available Flyway version at that
time. The current floor is **V196**, because
[`V195__record_creation_templates.sql`](../backend/src/main/resources/db/migration/tenant/V195__record_creation_templates.sql)
is the present maximum. V196–V200 are not reserved. The global tenant/control-plane sequence and
rebase rule remain authoritative in [`backend/MIGRATIONS.md`](backend/MIGRATIONS.md).

## Terminology

In this document, a **channel partner** is a company in a workspace that refers, resells,
distributes, co-sells, or fulfills commercial work. Product surfaces may use the short label
**Partner** when the channel-sales context is unambiguous.

This is not a **controlled partner**. That existing term means a named, contracted early Connex
customer admitted before general availability, as defined by
[`CONTROLLED_PARTNER_ADMISSION.md`](CONTROLLED_PARTNER_ADMISSION.md). The two concepts must remain
distinct in code and operator documentation:

- Channel-sales schema uses the `channel_partner_` prefix, or `company_channel_partner` when the
  company is the subject.
- Channel-sales permissions use `CHANNEL_PARTNER_VIEW`, `CHANNEL_PARTNER_MANAGE`, and
  `CHANNEL_PARTNER_APPROVE`.
- A future external-access capability uses a `CHANNEL_PARTNER_` prefix.
- The unqualified `partner_*` schema and `PARTNER_*` permission prefixes are rejected because they
  collide with the established controlled-partner meaning.

## Current Connex baseline

These are descriptions of shipped behavior, not proposed channel-sales behavior.

| Current behavior | Evidence on `main` |
| --- | --- |
| Contact provenance supports `PARTNER` and `REFERRAL`; a referrer is allowed only for those sources. | [`PersonLeadSource.java:11-32`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/beans/PersonLeadSource.java#L11-L32), [`V179__person_lead_provenance.sql:12-27`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/db/migration/tenant/V179__person_lead_provenance.sql#L12-L27), and [`PersonService.java:579-599`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/PersonService.java#L579-L599) |
| Guided record creation repeats those lead-source values but does not add a channel-sales domain. | [`RecordCreationTemplateResolver.java:87-89`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/RecordCreationTemplateResolver.java#L87-L89) and [`RecordCreationTemplateValidator.java:65-69`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/RecordCreationTemplateValidator.java#L65-L69) |
| The frontend exposes partner/referral only as contact lead-source provenance. | [`contactProvenance.ts:3-22`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/frontend/app/lib/contactProvenance.ts#L3-L22), [`messages/en/contacts.json:450-453`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/frontend/messages/en/contacts.json#L450-L453), and [`messages/ja/contacts.json:450-453`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/frontend/messages/ja/contacts.json#L450-L453) |
| Introduction lineage and warm-path computation already exist. | [`V23__introduction.sql:2-30`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/db/migration/V23__introduction.sql#L2-L30), [`IntroductionService.java:54-66`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/IntroductionService.java#L54-L66), and [`WarmPathService.java:39-54`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/WarmPathService.java#L39-L54) |
| `deal_person.role` is free text, while `deal_collaborator` is restricted by a foreign key to an internal `workspace_member`. | [`V1__baseline.sql:264-283`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/db/migration/V1__baseline.sql#L264-L283) |
| Duplicate candidate review and serialized duplicate decisions already exist for records and deals. | [`DuplicatePreflightService.java:87-129`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/DuplicatePreflightService.java#L87-L129), [`DuplicateDecisionLockService.java:16-33`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/DuplicateDecisionLockService.java#L16-L33), and [`DealDuplicateReviewProofService.java:16-50`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/DealDuplicateReviewProofService.java#L16-L50) |
| Approval policy and decision machinery is bound to document subjects and `DOCUMENT_APPROVE`. | [`ApprovalPolicyService.java:47-57`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/ApprovalPolicyService.java#L47-L57), [`DocumentApprovalService.java:62-79`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/DocumentApprovalService.java#L62-L79), and [`Permission.java:49-54`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/tenant/Permission.java#L49-L54) |
| My Work currently projects tasks, notifications, and document approvals only. | [`WorkItemSource.java:3-8`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/dto/WorkItemSource.java#L3-L8) and [`DocumentApprovalWorkItemProvider.java:35-45`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/work/DocumentApprovalWorkItemProvider.java#L35-L45) |
| Radar has an existing relationship-signal model and detector to extend. | [`RelationshipSignal.java:8-34`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/beans/RelationshipSignal.java#L8-L34) and [`RelationshipSignalDetectorService.java:41-69`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/RelationshipSignalDetectorService.java#L41-L69) |
| Company and person records are workspace-owned but may be shared; private-to-shareable references use a plain foreign key plus a service-level visibility check. | [`MULTITENANCY_PLAN.md:41-56`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/docs/MULTITENANCY_PLAN.md#L41-L56) |
| `MemberScope` is a presentational owner filter, not an authorization boundary. | [`DEAL_VALUE_CONTRACT.md:185-192`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/docs/DEAL_VALUE_CONTRACT.md#L185-L192) and [`MemberScope.java:9-24`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/dto/MemberScope.java#L9-L24) |

The decisions below deliberately reuse the adjacent behavior in this table rather than treating it
as a partial implementation of #566.

## Decision 1 — designate an existing company

**Decision.** A channel partner is a reversible, workspace-specific designation on an existing
`company`, accompanied by a workspace program catalog. It is not a second company-like record.

The schema contract for the first implementation is:

### `channel_partner_program`

- `id`, `workspace_id`, `name`, `type`, `status`
- `protected_period_days`
- `attribution_model`
- `eligibility_notes`
- optional commission/rebate metadata that is display-only
- `created_at`, `updated_at`
- unique `(workspace_id, id)` for same-workspace child references

### `company_channel_partner`

- `id`, `workspace_id`, `company_id`
- `program_id`
- `partner_type`, `tier`, `status`
- `regions` and `products` as validated JSON arrays of unique, nonblank strings
- `agreement_starts_on`, `agreement_ends_on`
- `partner_manager_user_id`
- `created_at`, `updated_at`
- unique `(workspace_id, company_id)`, making the designation one-to-one per workspace and company

`program_id` uses a composite same-workspace foreign key to `channel_partner_program`. The partner
manager must be an active member of the same workspace, validated in the service; the tenant table
must not create a foreign key to the control-plane `workspace_member` table. `company_id` points to
the shareable company by plain id; the service must prove the company is visible in the workspace
before create, read, update, or removal. The designation is a per-workspace overlay and does not
modify a company merely shared into that workspace. This follows the private-to-shareable rule in
[`MULTITENANCY_PLAN.md`](MULTITENANCY_PLAN.md#01-sharing-model--companies-contacts-pipelines-answers-2-6)
and avoids both a cross-workspace composite foreign key that would reject a valid shared company
and a tenant-to-control-plane foreign key.

The Partners directory is a filtered Companies view and the designation appears on Company detail.
It does not create a record type, route family, or top-level sidebar section. A future navigation
change requires separate evidence that Partners is a primary destination.

This choice follows Company's own implementation on the `origin/main` baseline, not the Person
capability comparison: Company reads use the owned-or-shared workspace visibility predicate and
exclude archived rows ([`CompanyMapper.xml:16`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/mappers/CompanyMapper.xml#L16-L25)); the service owns permission-checked,
audited archive/restore while preserving tags, shares, identities, and custom-field values
([`CompanyService.java:584`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/CompanyService.java#L584-L639)), as well as Company tag and custom-field operations
([`CompanyService.java:653`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/CompanyService.java#L653-L727),
[`CompanyService.java:748`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/services/CompanyService.java#L748-L784)). Privacy disclosure includes the Contact's associated Company identity
([`DataSubjectDisclosureMapper.xml:21`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/mappers/DataSubjectDisclosureMapper.xml#L21-L35)), and tenant lifecycle ordering explicitly enrolls custom-field data, Company shares, and Company
rows ([`TenantLifecycleRegistry.java:281`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/java/ooo/klae/connex/backend/tenant/TenantLifecycleRegistry.java#L281-L319)). A designation adds channel-specific state without duplicating those
load-bearing behaviors.

**Rejected alternative — a separate partner record type.** It would duplicate Company identity,
archive, duplicate handling, sharing, custom fields, retention, export, teardown, tenant scoping,
and authorization. It would also require an irreversible reconciliation rule for when a partner
record and a company record refer to the same legal entity. The designation is smaller and
reversible.

## Decision 2 — referrals and registrations are first-class history

**Decision.** A referral or deal registration is a first-class append-preserving
`channel_partner_referral` row. Contact provenance remains on `person`; the referral row records the
commercial workflow and links to that provenance.

The row contract is:

| Field group | Required contract |
| --- | --- |
| Identity and scope | `id`, `workspace_id`, immutable creation identity, `created_at`, `created_by_user_id` |
| Partner | `partner_company_id`, linking the designated company visible to the workspace |
| Program snapshot | nullable `program_id` as the live catalog link plus required frozen `program_name_snapshot`, `tier_snapshot`, and `protected_period_days_snapshot` captured at submission |
| Direction and source | required `direction` (`inbound` or `outbound`) and required `source` text bounded to 255 characters; source is historical input, not recomputed |
| People and records | nullable `introducer_person_id`, nullable `referred_company_id`, nullable `referred_person_id`; at least one referred record is required before approval |
| Evidence | nullable `introduction_id`; required `consent_disposition` (`not_required`, `confirmed`, or `missing`) plus nullable `consent_evidence_ref`; `confirmed` requires a reference and `missing` cannot be approved; no raw evidence document is copied into the row |
| Workflow | `owner_user_id`, `status`, `submitted_at`, nullable `decided_at`, nullable `expires_at`, and a note bounded to 2,000 characters |
| Conversion | nullable `converted_company_id`, `converted_person_id`, and `converted_deal_id`, written once by the existing creation paths |

The status vocabulary is `draft`, `submitted`, `approved`, `rejected`, `expired`, and `converted`.
Only a draft may change referral inputs. Submission freezes the partner, program snapshot,
introducer, referred records, source, consent disposition/evidence reference, owner, and expiry.
Later decisions and conversion append decision history or fill one-time conversion links; they do
not rewrite the submitted facts. A catalog edit can change `program_id`'s current projection but can
never change the three snapshot values.

Company and Contact references follow the plain-foreign-key plus workspace-visibility rule for
shareable records. Same-workspace private references use composite workspace keys. User references
are validated against active membership by the service and do not create tenant-to-control-plane
foreign keys. Every accepted id is resolved from server-side workspace context, never from a
client-supplied workspace id.

The existing contact fields introduced by
[`V179__person_lead_provenance.sql`](../backend/src/main/resources/db/migration/tenant/V179__person_lead_provenance.sql)
remain authoritative for **how a contact entered Connex**. On conversion, existing creation paths set
`person.lead_source = PARTNER` and set `referrer_person_id` when the introducer is a valid owned
workspace contact. `channel_partner_referral.converted_person_id` links the commercial history to
that contact. The referral row must not add duplicate lead-source or referrer snapshot columns, and
read projections must not claim conflicting provenance if the linked contact was later corrected.

An `introduction_id` is evidence by reference. Introduction people, status, and history are read
from the existing introduction record through
[`IntroductionService.java`](../backend/src/main/java/ooo/klae/connex/backend/services/IntroductionService.java);
they are never copied into referral columns.

**Rejected alternative — provenance columns on Person or Deal only.** Those columns cannot preserve
a submission, decision, expiry, program/tier snapshot, conflict evidence, or retry-safe conversion
history. They also cannot represent a rejected registration that creates no record. A first-class
row preserves the workflow without superseding contact provenance.

## Decision 3 — ownership precedence is a pure recommendation

**Decision.** Registration approval computes an explainable ownership recommendation. It never
reassigns an existing Company, Contact, or Deal, and `owner_id` changes remain separate explicit,
permission-checked, audited actions.

Increment 3 implements this pure contract:

```text
resolveChannelOwnership(evaluatedAt, facts) -> OwnershipDecision

OwnershipDecision = ASSIGN(ownerUserId, rule, evidenceId)
                  | UNASSIGNED(noEligibleRule)
```

`facts` is an immutable snapshot of six ordered claim sets. Every claim contains `ownerUserId`,
`evidenceId`, `effectiveAt`, and `sourceId`. A claim is eligible only when its evidence belongs to
the active workspace at `evaluatedAt`, its owner is a current active `workspace_member`, and its
source-specific conditions below are true. Ineligible claims are retained as explanation evidence
but cannot win.

`evaluatedAt` is one server-generated UTC instant captured at the start of the approval decision,
before its fact snapshot is read. It is neither supplied by the client nor refreshed between
rungs, and the same value is persisted with the decision. For `referral_registration`, a row is
active only when its status is `approved` or `converted` and (`expires_at IS NULL` or
`expires_at > evaluatedAt`). A `NULL expires_at` means **no expiry**. An `expires_at` equal to
`evaluatedAt` is expired and cannot win.

The resolver evaluates these rungs in order and stops at the first rung with an eligible claim:

| Rank | Rule key | Eligible claim | Tie-break within the rung |
| --- | --- | --- | --- |
| 1 | `existing_customer` | A claim emitted by the canonical customer-status model for the referred company. Until that model exists, this set is always empty; a won deal is not silently treated as customer status. | Earliest `effectiveAt`, then lowest `sourceId`, then lowest `ownerUserId`. |
| 2 | `active_opportunity` | An open Deal for the referred company with a non-null active-member owner. | Earliest Deal `created_at`, then lowest Deal id, then lowest owner user id. The incumbent open opportunity wins. |
| 3 | `named_account` | A claim emitted by the canonical named-account model. Until #564 provides it, this set is empty. | Earliest `effectiveAt`, then lowest `sourceId`, then lowest `ownerUserId`. |
| 4 | `territory` | The single claim returned by the canonical territory resolver. Until #564 provides that resolver, this set is empty. If that resolver reports ambiguity, it emits no eligible claim and approval records the ambiguity in evidence. | The canonical resolver must return zero or one claim; this engine does not invent territory specificity. |
| 5 | `referral_registration` | An active registration, as defined above, for the referred company or Deal with a non-null active-member owner. | Earliest `submitted_at`, then numerically lowest `channel_partner_referral.id`, then numerically lowest owner user id. |
| 6 | `manual_exception` | A currently effective, approved manual-exception decision with an active-member owner. | Latest `effectiveAt`, then highest decision id, then lowest owner user id. |

All timestamps compare as UTC instants. `sourceId` and `ownerUserId` compare numerically. In
particular, two active registrations are filtered by the exact status/expiry rule first and then
compared by `submitted_at`, referral id, and owner user id in that order. The complete ordered
comparison is part of each named table-driven unit-test case; SQL row order may not affect the
result. With no eligible claim, the result is `UNASSIGNED`.

The `existing_customer`, `named_account`, and `territory` rungs are deliberately inert until their
canonical models exist. Channel sales must not infer a parallel customer, account-assignment, or
territory model. Adding one of those providers later supplies facts to the fixed function without
changing precedence.

Registration approval stores the chosen rule, winning evidence id, considered conflicting ids,
actor, reason, and evaluated timestamp in append-only decision history. Approval may copy the
recommendation into the referral's own `owner_user_id` if that field is still unset; it may not
write `company.owner_id`, `person.owner_id`, or `deal.owner_id`.

`MemberScope` continues to filter lists and figures by the current record owner. It does not grant
access, constrain which claim may win, or substitute for `@RequirePermission`. This preserves the
existing contract in
[`DEAL_VALUE_CONTRACT.md`](DEAL_VALUE_CONTRACT.md#owner-scope-is-not-an-authorization-boundary).
Approval and any separate ownership transfer must revalidate the acting member and exact
permission after acquiring locks, as required by [`backend/LOCKING.md`](backend/LOCKING.md).

**Rejected alternative — first matching SQL row or approval-time reassignment.** Database row order
is not a tie-break and would make retries disagree. Reassigning a Deal merely because a partner
submitted it would turn an approval into a hidden ownership transfer and could bypass a revoked
permission. A pure recommendation plus a separate transfer keeps both actions explainable.

## Decision 4 — explicit attribution with evidence

**Decision.** Channel attribution uses the closed participation vocabulary `sourced`, `influenced`,
`introduced`, `co_sold`, and `fulfilled_by`. User-facing labels are Sourced, Influenced,
Introduced, Co-sold, and Fulfilled by.

### Glossary entries required in `PRODUCT.md` before Increment 1 ships

The English terms below are the intended binding labels. Each Japanese term is **PROPOSED** pending
product-glossary ratification; no user-facing implementation ships until the EN/JA pair and
definition are added to [`PRODUCT.md`](PRODUCT.md#4-vocabulary). The proposals follow the existing
Japanese use of パートナー, 紹介, and 担当 in `frontend/messages/ja/*.json`.

| EN term | Proposed JA term | Definition |
| --- | --- | --- |
| Partner | **パートナー** — PROPOSED | A Company designated in the workspace as a channel partner for referrals, resale, distribution, co-selling, or fulfillment. |
| Sourced | **紹介元** — PROPOSED | The partner originated the referral or Deal. |
| Influenced | **影響** — PROPOSED | The partner materially helped a Deal progress without originating it. |
| Introduced | **紹介** — PROPOSED | The partner made the evidence-backed introduction that connected the relevant people or companies. |
| Co-sold | **共同販売** — PROPOSED | The partner and the workspace team jointly worked the sales process. |
| Fulfilled by | **履行担当** — PROPOSED | The partner was responsible for delivering the contracted product, service, or outcome. |

Every `deal_channel_partner_attribution` row contains the workspace, Deal, channel-partner company,
attribution-model key, participation type, split percentage, creator, timestamp, and exactly one
evidence reference: either `channel_partner_referral_id` or `introduction_id`. It also contains
required `status` (`active` or `removed`), nullable `removed_at`, and nullable `removed_by_id`. A row
with neither or both evidence references is invalid. The evidence must be visible in the same
workspace and must support the attributed partner and Deal; a label or free-text note is not
sufficient evidence.

Rows are created with `status = active` and both removal fields null. Removal is the one-way,
audited transition to `status = removed`; it sets `removed_at` to the server UTC decision time and
`removed_by_id` to the active member who performed the removal. Both removal fields are required
when removed and forbidden when active. A removed row is never reactivated, and its partner,
participation type, percentage, and evidence are never rewritten.

For every `(workspace_id, deal_id, attribution_model)` group:

- each split is greater than 0 and at most 100, with scale 2;
- the sum of rows whose `status = active` is at most 100; `removed` rows do not count;
- retries and concurrent writes cannot temporarily or finally exceed 100; and
- removing attribution preserves audit history.

A row-level `CHECK` cannot enforce an aggregate sum. Increment 6 must use a database-backed
per-Deal/per-model allocation root or an equivalent serialization constraint, plus the service
guard, and prove concurrent over-allocation is refused. Inserts and removals lock that same
allocation root before recomputing the active sum. It must not describe a percentage-column `CHECK`
as protection of the group total.

The `deal_channel_partner_attribution` table itself is the source of both current state and removal
history; current projections select only `status = active`, while audit views may include both
statuses. A separate `deal_channel_partner_attribution_history` table is rejected because copying
or moving a row during removal would split the authoritative state and audit trail across tables.

Attributed monetary figures are derived server-side from the Deal's canonical projected or actual
value. They are never stored as an independent currency amount. Every aggregate is keyed by the
Deal currency; a request spanning currencies returns separate currency buckets or refuses the
aggregate. It never adds unlike currencies. The money source, scale, realized/projected distinction,
and reconciliation declaration follow [`DEAL_VALUE_CONTRACT.md`](DEAL_VALUE_CONTRACT.md).

Participation and attribution remain separate: a company can participate without receiving a
split, and an evidence-backed attribution can survive removal of a current participant. A channel
partner contact remains a normal Contact and never becomes a `workspace_member`. Neither a
participation nor attribution row grants record access.

**Rejected alternatives — one lead-source field, one winning partner, or a separate history
table.** A lead-source field or single winner cannot represent multiple evidence-backed roles or
partial credit and invites client-side revenue arithmetic. A separate history table creates the
dual-write failure described above. Status-bearing rows in one authoritative table preserve
evidence, permit multi-partner deals, keep removals auditable, and make the 100-percent invariant
enforceable.

## Decision 5 — reuse existing engines at their real boundaries

**Decision.** Later increments compose the following shipped services rather than cloning their
behavior:

| Channel-sales need | Reuse contract |
| --- | --- |
| Contact, Company, and Deal conflict candidates | Call [`DuplicatePreflightService.java`](../backend/src/main/java/ooo/klae/connex/backend/services/DuplicatePreflightService.java); do not create partner-only matching SQL. |
| Serialized duplicate-sensitive conversion | Enter the existing [`DuplicateDecisionLockService.java`](../backend/src/main/java/ooo/klae/connex/backend/services/DuplicateDecisionLockService.java) hierarchy and use [`DealDuplicateReviewProofService.java`](../backend/src/main/java/ooo/klae/connex/backend/services/DealDuplicateReviewProofService.java) where a Deal proof is required. |
| Warm-introduction evidence | Link and validate with [`IntroductionService.java`](../backend/src/main/java/ooo/klae/connex/backend/services/IntroductionService.java); rank or explain through [`WarmPathService.java`](../backend/src/main/java/ooo/klae/connex/backend/services/WarmPathService.java). Do not copy introduction lineage. |
| Partner relationship signals | Extend [`RelationshipSignalDetectorService.java`](../backend/src/main/java/ooo/klae/connex/backend/services/RelationshipSignalDetectorService.java) and the existing Radar reconciliation/dismissal paths. Do not create a second signal or task engine. |
| Registration decisions | Use a separate append-only `channel_partner_registration_decision` aggregate, permission, and provider. Reuse locking and work-item patterns, not document-subject tables. |
| My Work | Add a source-owned projection through the existing [`WorkItemProvider.java`](../backend/src/main/java/ooo/klae/connex/backend/work/WorkItemProvider.java) SPI; never copy registration state into a second store. |

Document approval remains a reference implementation for step authorization, immutable decisions,
post-lock permission checks, and work-item projection. It is **not** generalized in #566. Any future
cross-subject approval platform is a separately reviewed Tier 3 change with migration and
compatibility plans.

**Rejected alternative — generalize `ApprovalPolicy` and `DocumentApproval` during registration
delivery.** Those tables, permissions, finalization gates, DTOs, and work-item behavior are shipped
for document subjects. Generalizing them while adding a new domain expands the blast radius of a
Tier 3 gate and couples channel-sales validation to a broad migration. The parallel append-only
decision aggregate is narrower; the intentional duplication is decision state, not authorization
or locking shortcuts.

## Decision 6 — every mapper and table pays the complete safety tax

**Decision.** The enrollment checklist is derived from architecture tests present on `main`, not
from memory. Every later increment must assess every row below. “Not applicable” is acceptable only
when the named test agrees; silent omission is not.

| Enrollment or contract | Required action | Fail-closed test |
| --- | --- | --- |
| [`TenantScopeInterceptor.SCOPED_NAMESPACES` / `CONTROL_PLANE_NAMESPACES`](../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantScopeInterceptor.java) | Classify every mapper namespace exactly once. Channel-sales org-data mappers are scoped, not control-plane. Every statement also carries explicit workspace predicates. | [`TenantRegistryCompletenessArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/TenantRegistryCompletenessArchTest.java), [`TenantScopeArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/TenantScopeArchTest.java) |
| [`TablePlaneRegistry`](../backend/src/main/java/ooo/klae/connex/backend/tenant/TablePlaneRegistry.java) | Classify every new channel-sales table as org data in the tenant lineage. No foreign key crosses the plane wall. | [`TablePlaneArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/TablePlaneArchTest.java), [`MapperPlaneArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/MapperPlaneArchTest.java) |
| [`TenantLifecycleRegistry`](../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantLifecycleRegistry.java) | Declare export, teardown order, and residual verification for every org-data table, including immutable history. | [`TenantTeardownArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/TenantTeardownArchTest.java), [`AppiComplianceArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/AppiComplianceArchTest.java) |
| [`ProcessingRestrictionRegistry`](../backend/src/main/java/ooo/klae/connex/backend/tenant/ProcessingRestrictionRegistry.java) | Every mapper that reads `person` has exactly one reviewed disposition: a strategy enrollment or the explicit reader allowlist, never both. SQL must carry the declared evidence. | [`AppiComplianceArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/AppiComplianceArchTest.java) |
| [`ArchiveVisibilityRegistry`](../backend/src/main/java/ooo/klae/connex/backend/tenant/ArchiveVisibilityRegistry.java) | Every mapper reading `person` or `company` gets a namespace disposition. Every statement excludes archived rows with `archived_at IS NULL` unless it earns an exact named statement exemption. | [`ArchiveVisibilityArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/ArchiveVisibilityArchTest.java) |
| [`FigureReconciliationRegistry`](../backend/src/main/java/ooo/klae/connex/backend/tenant/FigureReconciliationRegistry.java) | Declare every new partner pipeline/revenue figure, its exact mapper evidence, value source, archive/restriction/sharing posture, period basis, and owner basis. Required for Increment 6. | [`FigureReconciliationArchTest.java`](../backend/src/test/java/ooo/klae/connex/backend/architecture/FigureReconciliationArchTest.java) |

The migration mechanics and globally monotonic version rule come from
[`backend/MIGRATIONS.md`](backend/MIGRATIONS.md). Endpoint writes additionally require the new
`CHANNEL_PARTNER_*` permission, controller-to-service-to-mapper layering, and post-lock permission
revalidation wherever a lock is taken. The lock requirement comes from
[`backend/LOCKING.md`](backend/LOCKING.md), not from a preliminary annotation snapshot.

The full checklist applies to the proposed domain in practice: designation/referral/participant
mappers read Company or Contact rows, and partner reporting reads Deal values. Increment acceptance
criteria must name the exact registries and tests implicated by its statements rather than say only
“all registries” or “architecture tests.”

**Rejected alternative — a three-registry checklist.** Tenant scope, plane, and lifecycle enrollment
alone do not prove processing-restriction behavior, archive visibility, or revenue reconciliation.
The omitted tests fail closed on precisely those gaps, so documenting only three would encode an
incomplete privacy and reporting contract.

## Decision 7 — constrained scope and no dormant external access

**Decision.** #566 does not ship an external channel-partner portal, commission ledger, payout or
payment state, externally usable sharing permission, or channel-specific territory model.
Internal-only is the default and no external principal receives access. The baseline currently
scopes activities and tasks to a workspace
([`ActivityMapper.xml:177`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/mappers/ActivityMapper.xml#L177-L203),
[`TaskMapper.xml:207`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/mappers/TaskMapper.xml#L207-L218)), limits notes to workspace visibility or their author
([`NoteMapper.xml:230`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/mappers/NoteMapper.xml#L230-L250)), and scopes files to the workspace while inheriting a linked note's visibility
([`AttachmentMapper.xml:38`](https://github.com/itkla/connex/blob/894b08efcbf8bf44eb0fc13fc9c31691a45d34e6/backend/src/main/resources/mappers/AttachmentMapper.xml#L38-L70)). These citations describe current internal visibility; they do not claim that the proposed
cross-surface sharing classification already exists.

- **Product decision — commercial scope.** Commission and rebate fields on a program are optional
  display-only metadata. They cannot accrue, become payable, record settlement, or drive accounting
  entries; accounting and payments remain integrations.
- **PROPOSED — product-owner ratification required.** #566 delivers the sharing-classification tag
  workstream item for tasks, notes, activities, files, and milestones as gated Increment 8. Its
  `sharing_classification` is `internal_only` or
  `approved_for_future_external_sharing`. `internal_only` is the default. The proposed
  `approved_for_future_external_sharing` value is eligibility metadata only: it never grants access
  or causes disclosure by itself. Its schema, UI, and behavior remain parked behind the
  external-access demand gate and require product-owner glossary/scope ratification plus the
  Increment 8 threat model before implementation.
- **Product decision — assignment scope.** Named-account and territory inputs are inert until #564
  supplies canonical models; channel sales does not create substitutes.
- **Product decision — information architecture.** The Partners directory remains a Companies
  filter; no sidebar section is added.
- **Product decision — delivery gate.** Increments 1–7 remain parked until named demand is recorded.

Increment 8 begins with a demand-validation spec and threat model, not code, and then delivers the
classification after its gates pass. The threat model must define external identity without granting
`app_user` or `workspace_member`, field-level least privilege, non-enumerability across
search/deep links/notifications/exports/errors, revocation, tenant scope, audit, and adversarial
tests. Only a separately reviewed external-access implementation may introduce that capability and
its production configuration.

No dormant external-access capability ships with the classification. No read, search, export,
notification, deep-link, or authorization path may interpret
`approved_for_future_external_sharing` as access; no external principal or production configuration
is introduced. The classification is inert eligibility metadata until a separately reviewed
external-access implementation consumes it.

**Rejected alternative — build portal seams or a disabled capability now.** Demand is unvalidated,
and external identity and sharing would enlarge the tenancy and privacy boundary before its access
contract exists. Deferring all external-access code is the only option that is both fail-closed and
reversible.

## Delivery increments and epic coverage

The model-decision criterion is tracked as AC 10 and is closed by Increment 0. Runtime acceptance
criteria AC 1–9 retain their original order.

| Increment | Delivery | #566 coverage | Risk |
| --- | --- | --- | --- |
| 0 | This model decision, terminology, safety checklist, and demand gate | AC 10 | Tier 1; docs only |
| 1 | Channel-partner program catalog, Company designation, permissions, filtered Companies directory | Foundation for AC 3 and AC 9 | Tier 2; next available migration, floor V196 |
| 2 | Referral rows, frozen snapshots, duplicate preflight, introduction link, retry-safe conversion | AC 1, AC 2, AC 9 | Tier 2; next available migration, floor V196 |
| 3 | Pure ownership precedence, append-only registration decisions, approval, protection expiry | AC 1, AC 2, AC 4, AC 9 | **Tier 3**: ownership, RBAC, locking, concurrency; next available migration, floor V196 |
| 4 | Deal channel-partner participation and external-contact roles, granting zero access | AC 3, AC 9 | Tier 2; next available migration, floor V196 |
| 5 | Registration projection in My Work plus internal partner-manager alerts | AC 4, AC 9 | Tier 2; no copied authority |
| 6 | Evidence-backed split attribution and currency-partitioned partner reporting | AC 5, AC 6, AC 9 | Tier 2; **Tier 3 if exports change**; next available migration, floor V196 |
| 7 | Evidence-grounded partner relationship signals through Radar | AC 7, AC 9 | Tier 2 |
| 8 | After named external-access demand, product-owner ratification and threat modeling, ship the inert sharing-classification tag for tasks, notes, activities, files, and milestones; do not ship external access | AC 8, AC 9 plan | **Tier 3**: cross-surface privacy classification; external access remains out of scope |

Tier 3 work receives security review plus a second non-overlapping review focused on
correctness/concurrency/migration behavior. Increment 0 also receives independent adversarial review
because it constrains those later changes.

Increment 8 implementation may begin only after the external-access demand gate is met, the product
owner ratifies the two-value glossary and five-surface scope, and the threat model is approved. Its
acceptance criteria are:

1. tasks, notes, activities, files, and milestones persist and expose exactly `internal_only` and
   `approved_for_future_external_sharing`, with `internal_only` as the default for existing and new
   records;
2. authorized internal users can view and update the tag consistently across the five surfaces,
   with EN/JA copy and audit coverage;
3. workspace isolation, existing visibility rules, archive/privacy behavior, exports, search,
   notifications, and deep links remain fail-closed and have negative tests proving the tag grants
   no access; and
4. no external identity, principal, route, token, permission, feature flag, deployment setting, or
   portal seam ships with the classification.

## Increment acceptance checklist

Every runtime increment must:

1. cite this document and state which decision it implements;
2. choose the next available migration version at implementation time, never a pre-reserved number;
3. enumerate the applicable Decision 6 registry entries and their exact architecture tests;
4. include workspace-isolation and permission-negative tests for every new read/write boundary;
5. preserve EN/JA parity for user-facing work;
6. prove unavailable states honestly rather than presenting them as empty data;
7. update audit, export, retention/deletion, duplicate handling, and integration coverage where its
   data participates; and
8. stop rather than weakening tenant scope, RBAC, locks, archive/privacy behavior, or figure
   reconciliation to make a gate pass.
