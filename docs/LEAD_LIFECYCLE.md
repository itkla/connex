# Lead lifecycle — model decision and delivery plan

Tracking issue: [#559](https://github.com/itkla/connex/issues/559). This document satisfies the epic's first
acceptance criterion — *the separate-Lead versus contact-lifecycle decision is documented before schema work
begins* — and is the reference for every increment that follows.

## Decision

**Connex models the lead lifecycle as state on the existing contact record. It does not introduce a separate
`lead` entity.**

Concretely:

- A contact carries a nullable **lifecycle stage** (`NEW`, `WORKING`, `NURTURING`, `QUALIFIED`,
  `DISQUALIFIED`, `CONVERTED`, `RECYCLED`). `NULL` means the contact is not currently in a lead lifecycle —
  it is a relationship, an alumnus, or a colleague, and Connex must not invent a sales stage for it.
- Qualification and disqualification carry an explicit reason plus free-text notes, and every transition is
  appended to a per-contact history table.
- "Convert to deal" is a controlled action on a qualified contact that reuses the existing deal-creation and
  duplicate-preflight paths. It does not copy a record from one table to another.
- The lead **queue** is a filtered view of contacts (stage, owner, first-response SLA), not a second record
  type with its own list, detail page, permissions, and export.

## Why not a separate Lead entity

The durable behaviours the epic asks for — intake, provenance, qualification, assignment, response SLA,
conversion, duplicate handling, preserved history — are required under either model. The question is only
whether they are cheaper and safer on one personal-data table or two. In this codebase the answer is one, for
five specific reasons.

### 1. The person record already carries the hard parts

Every capability a `lead` table would need is already built, tested, and load-bearing on `person`:

| Capability | Where it already lives |
|---|---|
| Multi-valued identifiers with acquisition provenance | `person_identity` ([`V127__canonical_identity.sql`](../backend/src/main/resources/db/migration/tenant/V127__canonical_identity.sql)), written by `IdentityIntakeService` |
| Duplicate detection before create, with reviewed-decision tokens | `DuplicatePreflightService`, `DuplicateDecisionLockService` |
| Channel consent and append-only consent history | `contact_channel_consent` ([`V93`](../backend/src/main/resources/db/migration/tenant/V93__contact_channel_consent.sql)), `ConsentService` |
| Ownership and member scoping | `person.owner_id` + `MemberScope` (#40) |
| Reversible archive instead of delete | `PersonService.archive` / `restore` |
| Custom fields, tags, saved views, segments, bulk actions | `CustomFieldValueService`, `TagService`, saved views, `SegmentCatalog` |
| Campaign audience membership and attribution | `campaign_audience` ([`V91`](../backend/src/main/resources/db/migration/tenant/V91__campaign_records_and_audience.sql)) |
| APPI data-subject requests, disclosure, restriction, erasure | `DataSubjectRequestService` |
| Tenant scoping and RBAC enforcement | `TenantScopeInterceptor`, `@RequirePermission`, arch tests |

A `lead` table means a second implementation — and a second set of tenancy and RBAC guarantees — for all of
it. That is not a one-time cost: every future change to identity, consent, retention, or sharing would have to
be made twice and kept in agreement.

### 2. A second personal-data table doubles the APPI surface

Connex's compliance posture (see [`APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md`](APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md)
and [`GOVERNANCE_DELETION_AND_RETENTION.md`](GOVERNANCE_DELETION_AND_RETENTION.md)) requires that disclosure,
correction, suspension of use, and erasure reach *every* copy of a data subject's personal information. Leads
are personal information from the moment they arrive. Splitting them across two tables means every DSR sweep,
every retention job, every teardown, and every export has a second place to miss — and a missed place is a
compliance failure, not a bug. The first client engagement is scheduled for July 2026; adding that surface now
buys queue ergonomics at the price of the guarantee the product is being sold on.

### 3. Connex's differentiation is relationship continuity, not queue throughput

The product is positioned as *a modern CRM built around trustworthy relationship intelligence* (#848). Warmth,
decay, employment history, and warm-intro paths are computed over contacts. A prospect held in a separate lead
table is invisible to all of it until it converts — exactly backwards for a relationship-led product, where the
question "have we met anyone here before?" is most valuable *before* qualification. Keeping prospects as
contacts means the warmth and intro-path engines light up on day one of the lifecycle.

### 4. Conversion is where lead/contact splits leak history

In the two-table model, conversion is a copy: activities, notes, files, consent, and audit rows have to be
re-parented, and every retry risks duplicating them. The epic's own acceptance criteria demand that conversion
"preserves history and cannot create duplicate records through retries". Under the contact-lifecycle model
conversion is a **state transition plus an optional deal creation** — nothing is copied, so nothing can be lost
or doubled. Idempotency reduces to the deal-creation path that already has duplicate preflight (#1141).

### 5. The target workflow is referral-led, not form-flood

The customer workflows Connex is built for — Japanese B2B, relationship- and referral-led, with introductions
and business-card capture as primary intake — produce tens to low hundreds of inbound records per month per
workspace, arriving with a name and a real human attached. The queue-of-anonymous-inquiries problem that
justifies a `lead` table in high-volume inbound-marketing CRMs is not this shape of work. Business-card scanning
(#557) and warm introductions (#43/#614) already land people directly as contacts, and users expect them there.

> **Validation status — open.** This decision is argued from the product's positioning, the shipped
> architecture, and the intake paths Connex actually has. It has **not** yet been confirmed against a live
> customer's stated workflow; the first client engagement is the checkpoint. The reversal criteria below exist
> because of that.

## What we build instead of a Lead entity

| Epic workstream | Contact-lifecycle realisation |
|---|---|
| 1 — Intake and provenance | Existing intake paths (manual, CSV import, business card) record provenance through `person_identity`. Untrusted intake (public form, webhook) lands in a **quarantine** staging table, not in `person` — see the escape hatch below. |
| 2 — Lead inbox and lifecycle | `person.lifecycle_stage` + qualification/disqualification reasons + `person_lifecycle_history`, surfaced as filtered contact views. |
| 3 — Routing and assignment | The existing rule/workflow engine assigning `person.owner_id`, plus first-response SLA fields on the contact. No second condition language. |
| 4 — Qualification and scoring | Deterministic qualification fields on the contact; scoring kept separate from stage; AI recommendations remain advisory and evidence-bearing (`AiRelationshipContext` pattern). |
| 5 — Duplicate prevention and conversion | `DuplicatePreflightService` at intake and at conversion; conversion is a transition plus a preflighted deal create. |
| 6 — Reporting and automation | Lifecycle transitions in `person_lifecycle_history` feed volume, first-response, qualification, and conversion reporting; new rule triggers on lifecycle events. |

### The one place a separate table is correct

**Untrusted, unreviewed inbound is not a contact yet.** A public web form, a partner webhook, or an API
submission can be hostile: bot traffic, injected content, deliberate poisoning of the contact database. Those
submissions must land in a dedicated **intake quarantine** table with their raw payload, source, consent
evidence, and received timestamp preserved, and become a contact only after passing abuse screening and an
explicit or rule-based admission decision.

That table is a *staging buffer*, not a Lead entity: it holds unadmitted submissions, has no owner, no
lifecycle stage, no timeline, and no detail page, and rows leave it by being admitted to a contact or being
discarded. Building it does not reopen this decision.

## Reversal criteria

Revisit the decision — and only then — if a real customer workflow demonstrates one of:

1. Sustained inbound volume where unqualified inquiries would outnumber genuine contacts in the contact
   database by more than roughly an order of magnitude, making the contact list unusable even with filters.
2. A regulatory or contractual requirement to hold unqualified inquiries under a retention or access regime
   that is materially different from contacts, and that cannot be expressed as a policy on the contact record.
3. A requirement to expose leads to actors who must not see the contact database at all, that cannot be met by
   ownership scoping and sharing.

Ergonomic preference for a separate list, or "the incumbent CRM has one", is explicitly not a reversal
criterion — that is what filtered views and saved views are for.

## Lifecycle model

### Stages

| Stage | Meaning |
|---|---|
| *(null)* | Not in a lead lifecycle. The default for every contact that predates this feature and for contacts created as relationships rather than prospects. |
| `NEW` | Entered the lifecycle and not yet worked. |
| `WORKING` | Actively being contacted or qualified. |
| `NURTURING` | Real but not ready; parked deliberately rather than dropped. |
| `QUALIFIED` | Meets the workspace's qualification criteria; eligible for conversion. |
| `DISQUALIFIED` | Rejected, with a required reason. |
| `CONVERTED` | A deal was created from this contact. |
| `RECYCLED` | Previously disqualified or converted-and-lost, returned to the top of the lifecycle. |

### Permitted transitions

```
(null) ──────────► NEW
NEW ────────────► WORKING, NURTURING, QUALIFIED, DISQUALIFIED
WORKING ────────► NURTURING, QUALIFIED, DISQUALIFIED
NURTURING ──────► WORKING, QUALIFIED, DISQUALIFIED
QUALIFIED ──────► WORKING, NURTURING, CONVERTED, DISQUALIFIED
DISQUALIFIED ───► RECYCLED
CONVERTED ──────► RECYCLED
RECYCLED ───────► NEW, WORKING, NURTURING, QUALIFIED, DISQUALIFIED
any ────────────► (null)   -- withdraw from the lifecycle entirely
```

Rules the implementation enforces:

- A transition into `DISQUALIFIED` requires a reason from the workspace's configured vocabulary.
- `CONVERTED` is reachable only from `QUALIFIED`, and requires a deal already linked to the contact, so the
  stage can never claim a deal that does not exist. The conversion action of increment 5 automates that pairing;
  until it lands, a user who has created the deal by hand can still record the outcome truthfully.
- Re-selecting the current stage is a no-op, not a history row.
- Withdrawing to *(null)* is always allowed and is recorded like any other transition.

### History

Every accepted transition appends a row to `person_lifecycle_history` — from-stage, to-stage, reason, note,
actor, and timestamp. The table is append-only; it is the source for first-response, qualification-rate, and
time-to-convert reporting, and it survives archive/restore of the contact.

## Delivery increments

| Increment | Scope | Status |
|---|---|---|
| 0 | This decision document | ✅ |
| 1 | Lifecycle stage, qualification/disqualification reasons, transition history, RBAC, audit, rule triggers, contact-list filtering and facets | see #559 children |
| 2 | Lifecycle UI: stage control, disqualify dialog, history timeline, browser filter, EN/JA | see #559 children |
| 3 | Record-level source provenance and the untrusted-intake quarantine table | deferred |
| 4 | Routing, assignment, first-response SLA and escalation on the existing rule engine | deferred |
| 5 | Qualification criteria configuration and deterministic scoring | deferred |
| 6 | Lifecycle reporting and attribution | deferred |

Increments 3–6 are tracked as separate issues under #559 and are not blocked on further model decisions.
