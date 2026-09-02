# Customer lifecycle — model decision, validation gate, and delivery plan

Tracking issue: [#563](https://github.com/itkla/connex/issues/563). Program issue:
[#568](https://github.com/itkla/connex/issues/568). Related guided-playbook work:
[#401](https://github.com/itkla/connex/issues/401).

This document is an engineering decision record proposed by convention. Unlike
[`LEAD_LIFECYCLE.md`](LEAD_LIFECYCLE.md), it does not satisfy an acceptance criterion in its epic, and writing
it does not satisfy #563's demand-validation gate. The evidence baseline is `origin/main` at
`894b08efcbf8bf44eb0fc13fc9c31691a45d34e6`.

**Gate status: open. No migration, endpoint, UI, permission, capability, or implementation issue after
increment 0 may start until the product owner records the evidence in [Demand-validation gate](#demand-validation-gate)
as a comment on #563.** If the answer is "not yet", decompose and park the domain. Do not file the remaining
implementation issues or reserve schema.

## Decision labels

- **SETTLED** means the current architecture or an existing product invariant resolves the contract. It binds a
  future implementation only if the demand gate opens; it does not authorize that implementation.
- **PROPOSED** means a conservative, reversible default. The named owner must record sign-off on #563 before a
  later increment may treat it as binding.

| Contract | Label | Authority or required sign-off |
|---|---|---|
| One `Company` record; contact/customer lifecycle separation; nullable non-customer state | SETTLED | Existing Company and person-lifecycle architecture cited below |
| Account stages and transition matrix | PROPOSED | #563 product owner |
| Durable, idempotent, explicit handoff invariants | PROPOSED | #563 product owner and backend domain owner |
| Canonical success-owner placement | PROPOSED | #563 product owner and #40 `MemberScope` owner |
| Batched deterministic warmth scoring and evidence-free cold output | SETTLED | Existing `ScoringService` contract cited below |
| Health component schema, unknown-data comparability, and configuration version | PROPOSED | #563 product owner and backend domain owner |
| Health recomputation cadence and card/Radar split | PROPOSED | #563 product owner and backend operations owner |
| Contract-end derivation and fallback precedence | PROPOSED | #563 product owner and commercial domain owner |
| No-stale-pricing, rounding, currency, and snapshot rules | SETTLED | V103 line-item and deal-value contracts cited below |
| Retention measures | PROPOSED | Commercial reporting owner and #563 product owner |
| Radar as the alert store; My Work as a projection | SETTLED | Existing Radar and My Work contracts cited below |
| Exact My Work source routing | PROPOSED | #563 product owner and My Work contract owner |
| Product exclusions | SETTLED | Existing product and architectural boundaries cited below |
| Customer-success edition capability posture | PROPOSED | Deployment-edition owner and #563 product owner |

## Shipped baseline: partially started

Connex does not yet have a customer-success domain: the existing `Company` record has no lifecycle, health,
success-owner, or contract-date fields
([`Company.java:20-35`](../backend/src/main/java/ooo/klae/connex/backend/beans/Company.java#L20-L35)). There is no
durable customer handoff, onboarding plan, health composition, or renewal derivation.

Three shipped surfaces already deliver parts of #563 and must be extended rather than re-created:

1. The curated workflow catalog includes `person-job-change-follow-up`, `deal-won-handoff`, and
   `cooling-company-review`
   ([`WorkflowRecipeService.java:53-57`](../backend/src/main/java/ooo/klae/connex/backend/services/WorkflowRecipeService.java#L53-L57)).
   The handoff recipe reacts to `deal.won` and creates an ordinary task and activity
   ([`WorkflowRecipeService.java:265-280`](../backend/src/main/java/ooo/klae/connex/backend/services/WorkflowRecipeService.java#L265-L280),
   [`346-366`](../backend/src/main/java/ooo/klae/connex/backend/services/WorkflowRecipeService.java#L346-L366)).
2. Deal risk already detects `no_stakeholders` and `stakeholder_cold`, including champion, decision-maker,
   buyer, and sponsor roles
   ([`DealRiskService.java:90-104`](../backend/src/main/java/ooo/klae/connex/backend/services/DealRiskService.java#L90-L104)).
3. Relationship decay already evaluates company subjects and emits company cooling signals
   ([`RelationshipSignalDetectorService.java:67-85`](../backend/src/main/java/ooo/klae/connex/backend/services/RelationshipSignalDetectorService.java#L67-L85)).

## Account model

### SETTLED — one Company record

**A customer is lifecycle state on the existing `Company` record, with optional deal-specific handoff state;
Connex does not introduce a second customer or account entity.**

This follows the shape of the lead decision without pretending that the two domains are identical:

- `Company` already owns the links to contacts and deals. A second customer row would duplicate identity,
  ownership, sharing, archive, export, teardown, and retention paths, or require permanent synchronization
  between two account records.
- Personal information remains on the existing contact path. Entering a company lifecycle must never copy
  contacts, identities, consent, activities, or other personal data into a customer store. That preserves one
  APPI disclosure, restriction, retention, and erasure surface rather than creating another place a sweep can
  miss. The same risk is documented for the contact model in
  [`LEAD_LIFECYCLE.md`](LEAD_LIFECYCLE.md#why-not-a-separate-lead-entity).
- A lifecycle change is state plus append-only history, not copy-on-conversion. Lifecycle writes therefore use
  the established company mutation boundary: `COMPANY_UPDATE` against an active record owned by the current
  workspace
  ([`CompanyService.java:512-527`](../backend/src/main/java/ooo/klae/connex/backend/services/CompanyService.java#L512-L527),
  [`CompanyMapper.xml:900-903`](../backend/src/main/resources/mappers/CompanyMapper.xml#L900-L903)). Company
  visibility includes active records owned by the workspace and eligible same-organization shares
  ([`CompanyMapper.xml:16-26`](../backend/src/main/resources/mappers/CompanyMapper.xml#L16-L26)); it does not
  make a visible shared record writable. `MemberScope` is only a request-level presentation filter for
  member-scoped reads, defaults to unfiltered `ALL_TEAM`, and is not an authorization predicate
  ([`MemberScope.java:57-79`](../backend/src/main/java/ooo/klae/connex/backend/dto/MemberScope.java#L57-L79)).
- Handoff obligations differ per won deal, so a deal-specific handoff row is additive state, not a replacement
  company record.

### SETTLED — contact and company lifecycles are independent

`PersonLifecycleStage.CONVERTED` means only "a deal was created from this contact"
([`PersonLifecycleStage.java:19-33`](../backend/src/main/java/ooo/klae/connex/backend/beans/PersonLifecycleStage.java#L19-L33)).
It does not mean that the contact, the contact's employer, or any deal is a customer. Contact lifecycle answers
"where is this prospect in lead qualification?" Company lifecycle answers "what is this company's post-sale
relationship state?" Neither value implies or mutates the other.

As with the contact lifecycle, `NULL` is a first-class value meaning "not in this lifecycle." It is not an enum
sentinel, and existing rows must not be backfilled. The contact precedent explicitly preserves `NULL` rather
than inventing state for existing records
([`V178__person_lifecycle.sql:1-20`](../backend/src/main/resources/db/migration/tenant/V178__person_lifecycle.sql#L1-L20)).

### PROPOSED — closed account-lifecycle vocabulary

Required sign-off: the #563 product owner. The proposal deliberately omits `AT_RISK`: risk is a derived health
band, and storing it as lifecycle state would create two sources of truth.

| Stage | Meaning |
|---|---|
| *(null)* | Not classified as a customer. This is the default for every existing company and for companies that have no post-sale relationship. |
| `ONBOARDING` | A post-sale relationship has begun and required onboarding work is incomplete. |
| `ACTIVE` | The company is a current customer outside an active onboarding or renewal motion. |
| `RENEWING` | A person has opened an active renewal motion. A date entering a lead-time window does not silently change this stage. |
| `CHURNED` | The post-sale relationship ended and the outcome is retained for history and reporting. |

Proposed full transition matrix:

```text
(null)     -> ONBOARDING, ACTIVE
ONBOARDING -> ACTIVE, CHURNED
ACTIVE     -> RENEWING, CHURNED
RENEWING   -> ACTIVE, CHURNED
CHURNED    -> ONBOARDING, ACTIVE
any stage  -> (null)
same stage -> no-op
```

Entry at `ACTIVE` supports an existing customer that does not need a newly instantiated onboarding plan.
Reactivation from `CHURNED` enters `ONBOARDING` when new obligations exist and `ACTIVE` otherwise. Withdrawal
to `NULL` means the company was removed from customer classification; it is distinct from recording a genuine
churn. Every accepted transition appends exactly one row to a proposed `company_lifecycle_history`; history is
never updated or deleted while the company exists. Later implementation must mirror the nullable stages,
no-op guard, append-only history, and absence of cross-plane actor foreign keys in
[`V178__person_lifecycle.sql:30-71`](../backend/src/main/resources/db/migration/tenant/V178__person_lifecycle.sql#L30-L71).

## Handoff

### SETTLED — deal-write containment

`DealOutcomeWriter` is the only writer of deal rows and atomically reconciles close state and realized value
([`DealOutcomeWriter.java:18-29`](../backend/src/main/java/ooo/klae/connex/backend/services/DealOutcomeWriter.java#L18-L29)).
This containment contract does not establish a durable handoff row, initiation action, retry behavior, or
completion requirements.

### PROPOSED — durable handoff invariants

Required sign-off: the #563 product owner and backend domain owner. Before increment 2 begins, the approval
recorded on #563 must identify the durable row and uniqueness constraint, initiation and retry contract,
mandatory completion fields, lifecycle transition boundary, and relationship to the shipped workflow recipe.

- A durable handoff is optional per deal and has at most one row per `(workspace_id, deal_id)`, enforced by a
  unique key. Retrying initiation returns that row and does not duplicate checklist items, tasks, activities,
  or notifications.
- Initiation is an explicit, permissioned action on a won deal. It is not an automatic side effect of
  `DealOutcomeWriter`.
- Completion requires all mandatory objectives, commitments, stakeholders, products, dates, risks, and open
  actions to be resolved. It advances company lifecycle only through the lifecycle service.
- The shipped `deal-won-handoff` recipe cannot become a second unrelated handoff mechanism. Increment 2 must
  either target the durable initiation action or document a deliberately distinct lightweight workflow with
  distinct product language and dedupe behavior before the durable path ships.
- Deal owner, company owner, and collaborators are preserved. A success owner is additive.

### PROPOSED — success-owner placement

Required sign-off: the #563 product owner and the owner of the #40 member-scoped presentation contract.
The conservative proposal adds nullable `company.success_owner_id` as the one canonical current success owner.
It is independent of durable handoff rows: a company entered directly at `ACTIVE` may be explicitly assigned
there, and multiple won deals cannot create competing current owners. Assignment, reassignment, and clearing
go only through a permissioned company-level action; initiating or editing a handoff never implicitly
overwrites the field. A handoff may snapshot its deal-specific assignee and may suggest an initial owner when
the company field is null, but that value is historical or a proposal, never a fallback source of truth.
Therefore precedence is unconditional: `company.success_owner_id` is current when non-null, and null means the
customer is unowned even if a handoff has an assignee. Portfolio filters, offboarding, export, and the unowned
signal read this field. Its effect on presentation filters and reassignment remains gated by the required
sign-off. Reusing `company.owner_id` is rejected because it would overwrite the existing account owner rather
than preserve the sales relationship.

## Account health

### SETTLED — batched deterministic warmth scoring

`ScoringService` deterministically computes contact and company warmth from workspace-batched aggregates. Its
versioned warmth model uses timestamped, intent-weighted interaction evidence, and its output exposes the
resulting score, band, trend, and evidence timestamp. Contacts with no recorded evidence are included in the
warmth output as cold
([`ScoringService.java:63-76`](../backend/src/main/java/ooo/klae/connex/backend/services/ScoringService.java#L63-L76),
[`121-138`](../backend/src/main/java/ooo/klae/connex/backend/services/ScoringService.java#L121-L138)). That
evidence-free cold output does not by itself establish how a future cross-component account-health composite
must interpret missing evidence.

### PROPOSED — health composition contract

Required sign-off: the #563 product owner and backend domain owner. Health is proposed as a composition of
deterministic components, never a replacement signal or an opaque AI score. Every response and snapshot would
carry the composite and all components in this shape:

```text
{ component_key, value, weight, source, as_of, state: ok | stale | unknown }
```

An absent component would be `unknown` and include its last known freshness when one exists. The effective
component keys and weights are fixed by the configuration version; an unknown configured component makes the
composite itself `unknown` and non-comparable rather than removing that component and re-weighting the known
values upward. The last fully known composite may be displayed separately with its original `as_of`, but it
is not a current score. Two snapshots are comparable only when they use the same configuration version and
the same component set is known in both. Deterioration and recovery signals require that complete
previously-known set; losing a component suppresses comparison and cannot emit a recovery. In particular, the
health composition would not translate "there are no recorded interactions" into negative health merely
because the warmth service emits an evidence-free contact as cold. A composite with no known components would
itself be unknown, not zero. Workspace configuration would own weights and thresholds; a code-owned versioned
default would apply when no configuration exists. The composite would always expose the effective
configuration version.

| Component | Existing or planned source | Source contract |
|---|---|---|
| Relationship warmth and decay | `ScoringService` and `RelationshipWarmthModel` | Reuse the workspace-batched company warmth calculation and its evidence timestamp; do not recalculate warmth. |
| Stakeholder coverage | `DealStakeholder` plus current `PersonEmployment` | Roles are already bulk-loaded for deal risk ([`DealStakeholder.java:6-20`](../backend/src/main/java/ooo/klae/connex/backend/beans/DealStakeholder.java#L6-L20)); employment history identifies the current company ([`PersonEmployment.java:6-23`](../backend/src/main/java/ooo/klae/connex/backend/beans/PersonEmployment.java#L6-L23)). Restricted contacts must be handled under the APPI registry contract. |
| Meaningful-contact cadence | `ActivityMapper` | Use workspace-scoped activity timestamps and the canonical activity semantics; the mapper already exposes workspace-scoped activity reads ([`ActivityMapper.java:18-37`](../backend/src/main/java/ooo/klae/connex/backend/mappers/ActivityMapper.java#L18-L37)). |
| Onboarding obligations | `customer_onboarding_item` planned in increment 3 | Until that source exists or is available, this component is `unknown`, never empty or healthy. Blocked and overdue state is derived from source-owned plan items. |
| Commercial timing | Won deals plus recurring `deal_line_item.service_period_end` | Use the renewal source-of-truth contract below. A missing contract date is `unknown`. |
| Support and business signals | Integrations | Use only signals supplied through an available integration. When no integration supplies this component, report it as `unknown`, never healthy or complete. |
| Lifecycle context | Proposed nullable `company.lifecycle_stage` | Lifecycle may explain the score but must not manufacture health; `AT_RISK` remains a derived band. |

AI could phrase, translate, rank, or summarize these server-computed components. It would not replace a
component, hide an unknown or stale state, change a value or weight, or assert a cause absent from the
evidence.

### PROPOSED — recomputation and surfaces

Required sign-off: the #563 product owner and backend operations owner. The proposed cadence is scheduled,
append-only snapshots for durable history and Radar reconciliation, with an on-read refresh only when the
snapshot is beyond the documented freshness window. Every read reports `as_of` and availability. The simpler
alternative is entirely on-read composition, but it cannot provide stable history or alert-source hashes
without a second durable mechanism.

Required sign-off: the #563 product owner. The proposed UI is both a company-detail health card and Radar:
the card explains the current composition; Radar alone owns alert disposition, snooze, dismiss, follow, and
task-creation state. The card may link to a Radar signal but must not duplicate alert state.

## Renewals and expansion

### SETTLED — pricing integrity

Renewal and expansion generation creates a draft. Prior line items are only starting evidence. A
catalog-backed line is re-resolved against the current `Product` catalog, and SKU, availability, unit price,
tax-rate, frequency, or currency drift is shown for explicit confirmation. When `product_id` is null because
the line was ad hoc or its product was deleted, generation retains the full snapshot line as
unresolved from the catalog for explicit manual confirmation; it never drops the line, invents a catalog
match, or silently re-prices it. The existing service accepts ad-hoc lines without a product
([`DealLineItemService.java:130-168`](../backend/src/main/java/ooo/klae/connex/backend/services/DealLineItemService.java#L130-L168)),
and product deletion deliberately sets the reference to null while preserving the snapshot
([`V103__deal_line_item.sql:8-29`](../backend/src/main/resources/db/migration/tenant/V103__deal_line_item.sql#L8-L29)).
**Generated commercial records never silently copy stale pricing or apply live pricing without
confirmation.** All values stay server-computed `BigDecimal` with
`HALF_UP` rounding through `DealLineItemService` and `DealOutcomeWriter`, under
[`DEAL_VALUE_CONTRACT.md`](DEAL_VALUE_CONTRACT.md). The line-item schema snapshots catalog values and keeps one
currency per deal
([`V103__deal_line_item.sql:1-32`](../backend/src/main/resources/db/migration/tenant/V103__deal_line_item.sql#L1-L32)).
The product table is the current catalog while deal lines retain pricing snapshots rather than being mutated
by later catalog edits
([`V102__product.sql:1-19`](../backend/src/main/resources/db/migration/tenant/V102__product.sql#L1-L19)).
This money-writing increment is Tier 3 from the start and requires separate security and
commercial-correctness reviewers.

### PROPOSED — contract-end derivation and fallback precedence

Required sign-off: the #563 product owner and commercial domain owner. The proposed renewal source of truth is
the set of dated recurring line items on won deals for the company. Each expiring line produces a separately
keyed renewal candidate from its own `service_period_end`; an approved future contract grouping may combine
lines only when it preserves every distinct service-period end. Reminders, renewal motions, and delayed-renewal
signals reconcile per candidate, so a later expiration cannot hide an earlier one. A company-wide end may be
reported as the following summary:

```text
MAX(deal_line_item.service_period_end)
over won deals for the company
where deal_line_item.billing_frequency = 'recurring'
```

That maximum is never the sole input to renewal candidate generation or alert reconciliation.

The line-item schema makes this derivation possible by supplying `billing_frequency`, `service_period_start`,
and `service_period_end` and constraining frequency to `one_time | recurring`
([`V103__deal_line_item.sql:1-32`](../backend/src/main/resources/db/migration/tenant/V103__deal_line_item.sql#L1-L32)).
The proposed fallback is a workspace-designated company custom field containing a manually maintained contract
end. It yields a company-level candidate only when no dated recurring line candidate exists; it never overrides
or collapses recurring-line-item dates. Every candidate and summary would state `recurring_line_item`,
`company_custom_field`, or `unknown` as its source and carry `as_of`.

### PROPOSED — retention definitions

Required sign-off: the owner of Connex's commercial reporting story and the #563 product owner. Before
increment 8, each proposed measure must define its numerator, denominator, observation window, cohort rules,
mid-term expansion/contraction treatment, won/lost timing, and currency treatment. Logo retention, gross
revenue retention, and net revenue retention remain separate measures. Recurring and one-time revenue are not
combined unless the approved definition states the treatment. No retention measure is binding in this record
until those definitions are reviewed and the sign-off is linked from #563.

## Alerts and My Work

### SETTLED — extend Radar; do not duplicate existing signals

Alerts are `relationship_signal` rows. Radar already owns stable dedupe keys, evidence, source-state hashes,
family availability, dispositions, and task creation. Its code currently reconciles three families
([`RelationshipSignalReconciliationService.java:19-56`](../backend/src/main/java/ooo/klae/connex/backend/services/RelationshipSignalReconciliationService.java#L19-L56))
and exposes them through one family list
([`RadarService.java:51-64`](../backend/src/main/java/ooo/klae/connex/backend/services/RadarService.java#L51-L64)).

Only genuinely new account-health signals belong to a new `account_health` family: material health
deterioration or recovery, an unresolved onboarding blocker, an unowned customer, and, after renewal inputs
exist, a delayed renewal. Existing company cooling stays in `relationship_decay`; no-recent-contact behavior
extends that detector if necessary. Existing no-stakeholder and cold key-stakeholder behavior stays in
`deal_risk`; account-level projection must dedupe against the shipped deal signal. Job-change follow-up extends
the existing `person.job_changed` event and recipe rather than creating a parallel detector.

Adding `account_health` requires a later migration to use MySQL `DROP CHECK` followed by `ADD CONSTRAINT` for
all three frozen V155 constraints:

1. `chk_relationship_signal_family`
   ([`V155:26`](../backend/src/main/resources/db/migration/tenant/V155__relationship_signal_domain.sql#L26));
2. `chk_relationship_signal_family_subject`, adding `account_health` with `subject_type = 'company'`
   ([`V155:28-32`](../backend/src/main/resources/db/migration/tenant/V155__relationship_signal_domain.sql#L28-L32));
3. `chk_relationship_signal_family_state_family`
   ([`V155:67-68`](../backend/src/main/resources/db/migration/tenant/V155__relationship_signal_domain.sql#L67-L68)).

### SETTLED — no second task or queue store

Actionable customer-success work is projected into My Work from source-owned state. Completing, dismissing, or
resolving work delegates to that source; My Work does not copy authoritative state. Work whose authoritative
state is an ordinary task or notification uses that provider. A domain source that owns its own obligation
uses a source-specific provider rather than copying state into a task or notification.

`WorkItemSource` currently contains only `task`, `notification`, and `document_approval`
([`WorkItemSource.java:1-8`](../backend/src/main/java/ooo/klae/connex/backend/dto/WorkItemSource.java#L1-L8)). A new
source requires an availability-aware provider and must disappear when its underlying obligation resolves. An
unavailable source is reported as unavailable with a reason, never as an unexplained empty result.

### PROPOSED — My Work routing

Required sign-off: the #563 product owner and the My Work contract owner. Onboarding work is projected through
an availability-aware `onboarding` provider backed by authoritative `customer_onboarding_item` rows. The
provider's complete, dismiss, or resolve actions delegate to the onboarding service and disappear only when
the source item resolves. A nullable linked `task_id` may support coordination, but the task and notification
providers cannot stand in for the onboarding provider, and task completion does not implicitly resolve the
item. This preserves the health contract's source-owned blocked and overdue state without requiring every item
to create a task. Renewal may use an existing task or notification only when that row truthfully owns the
obligation; otherwise its source-owned state requires its own availability-aware provider. Exact handoff
routing remains unresolved until the durable handoff shape exists.

## Product boundary and reversal criteria

### SETTLED — excluded products and behaviors

This domain does not add:

- a customer portal or external customer identity;
- a support-ticket or case-management system;
- a separate customer database or duplicate account record;
- a second alert, task, or work-queue store;
- autonomous account actions; AI remains explanatory or advisory, and automation runs only through workflows
  a person explicitly reviewed and enabled; or
- a customer-success `Capability` flag without an edition/deployment decision. The present capability catalog
  is for concrete instance-level dependencies
  ([`Capability.java:3-41`](../backend/src/main/java/ooo/klae/connex/backend/capability/Capability.java#L3-L41));
  dormant flags are not an implementation substitute.

Revisit one boundary only after the named evidence and product approval are recorded on #563:

1. **Portal:** a customer demonstrates an external-user workflow, authentication population, data boundary,
   and support model that an internal CRM view cannot satisfy.
2. **Support tickets:** a customer demonstrates intake, entitlement, SLA, routing, and case-resolution needs
   that ordinary tasks and activities cannot represent.
3. **Separate customer entity:** a regulatory or contractual boundary requires customer records to have a
   lifecycle, access, or retention regime that cannot be expressed on `Company` and its existing links.
4. **Second work store:** an authoritative obligation cannot live in its domain or be projected through the
   provider contract without data loss or inconsistent actions.
5. **Autonomous actions:** product, security, and audit owners approve a separately scoped contract defining
   authority, confirmation, rollback, evidence, and failure recovery.
6. **Capability gate:** deployment-edition owners identify a real external dependency or edition boundary and
   approve the behavior of every profile. Required sign-off: the deployment-edition owner and #563 product
   owner; until then the conservative proposal is always-on after the demand gate opens.

Preference for a familiar customer-success suite is not reversal evidence.

## Demand-validation gate

Before **any increment after 0** starts, the #563 product owner must comment on #563 with all of:

1. At least one customer organization and the role of the participant who owns post-sale relationships in its
   CRM. The public comment may use the organization's name only with authorization; otherwise it uses a stable,
   product-owner-approved redacted alias, identifies a restricted evidence location outside the tracker, and
   names the internal evidence owner who attests that they reviewed the identity and supporting details. Do not
   place customer PII, confidential data, customer identity behind an alias, or interview transcripts in the
   issue.
2. The participant's current post-sale workflow and tools, the concrete failure or cost Connex would address,
   and the frequency or volume that makes the problem material.
3. Which #563 outcomes the customer asked for: handoff, onboarding, health explanation, renewals, retention,
   expansion, reporting, or another explicitly described outcome. A generic request for "customer success" is
   insufficient.
4. Evidence of adoption intent, such as willingness to pilot the named workflow, and how success will be
   measured.
5. The product decision: build breadth now or defer it until core sales adoption is stronger, answering #568's
   open question directly.
6. Sign-off or explicit deferral for every PROPOSED decision in this document: lifecycle vocabulary and
   transitions, durable handoff invariants, success-owner placement, health composition, health recomputation
   and surfaces, contract-end derivation and fallback precedence, My Work routing, retention definitions, and
   edition capability posture.

If this evidence is absent or says demand is not established, the outcome is increment 0 only and the domain
is parked. The increment-0 issue is created by the orchestrator when this document merges and is the only issue
linked as a child of #563. The eight build increments below remain in this document and are filed in order only
after the gate opens, consistent with #568's rule to create child issues when a domain enters discovery or
build.

## Later-increment implementation checklist

Every later increment must cite this document from migration header comments and service Javadoc, mirroring
the V178 and `PersonLifecycleService` references to `docs/LEAD_LIFECYCLE.md`
([`PersonLifecycleService.java:36-45`](../backend/src/main/java/ooo/klae/connex/backend/services/PersonLifecycleService.java#L36-L45)).

For every new table or mapper, the implementing increment must explicitly review and test:

- `TenantScopeInterceptor.SCOPED_NAMESPACES` for every workspace-scoped mapper namespace;
- `TablePlaneRegistry` for exactly one table plane;
- `TenantLifecycleRegistry` for export, teardown, and residual verification;
- `ArchiveVisibilityRegistry` for reads reachable through archived companies or contacts;
- `ProcessingRestrictionRegistry` for any namespace reading person rows, particularly handoff, onboarding,
  health, and alert increments 2-5; this registry is a separate APPI restriction-sweep obligation from tenant
  scope and plane placement
  ([`ProcessingRestrictionRegistry.java:6-17`](../backend/src/main/java/ooo/klae/connex/backend/tenant/ProcessingRestrictionRegistry.java#L6-L17));
- `Permission`, grantable catalogs, role presets, endpoint annotations, and post-lock permission revalidation
  only when the slice adds or reuses a permissioned action;
- `WorkItemSource` and its provider registry whenever source-owned state cannot honestly project through an
  existing task or notification; increment 7 requires the onboarding provider because
  `customer_onboarding_item.task_id` remains nullable;
- `RelationshipSignalDetectorService`, reconciliation, `RadarService` families, and all three V155 checks when
  adding `account_health`; and
- the tenant, RBAC, migration-lineage, deal-value, processing-restriction, archive-visibility, and other
  architecture tests implicated by the exact change.

The next schema migration is `V196` only if no lower-numbered migration has landed when increment 1 begins.
This document reserves no version. No migration belongs to increment 0.

## Ordered delivery plan after validation

All entries are blocked until the demand gate opens. Only increment 0 should exist as a GitHub sub-issue while
the gate is open.

| Increment | Scope | Risk | Required correction or dependency |
|---|---|---|---|
| 0 | This decision record and validation gate | Tier 1 | Documentation only; does not close the demand gate. |
| 1 | Nullable company lifecycle plus append-only history and company UI | Tier 2 | Product owner first approves the proposed vocabulary. The company facet DTO remains in `frontend/app/lib/types.ts`/`api.ts` and renders in `CompaniesBrowser.tsx`; there is no `CompanyFacets` component. |
| 2 | Idempotent, permissioned durable won-deal handoff | Tier 2 | Integrate or deliberately distinguish the shipped `deal-won-handoff` recipe; re-assert permission after locks. |
| 3 | Immutable onboarding templates and per-customer plan instances | Tier 2 | Mirror V195's set/template/version pattern. Persist the company/task association on `customer_onboarding_item`; do not use prose-derived `EntityReference` as a structural link. |
| 4 | Explainable deterministic account-health components and snapshots | Tier 2 | Unknown makes the composite non-comparable; use batched source reads and the approved freshness contract. |
| 5 | New account-health Radar family | Tier 2 | Add only genuinely new health signals; extend and dedupe against shipped `deal_risk`, `relationship_decay`, and job-change behavior. Relax all three V155 checks. |
| 6 | Renewal derivation, candidates, reminders, and confirmed draft generation | **Tier 3** | Money is load-bearing. Derive per-line candidates; re-resolve catalog pricing or retain unresolved snapshots for confirmation; preserve currency/rounding; require separate security and commercial-correctness reviews. |
| 7 | Customer-success projection in My Work and recovery playbooks | Tier 2 | Add the source-owned onboarding provider, preserve honest availability, and coordinate guided playbook/action-macro scope with #401. |
| 8 | Retention, renewal, health, onboarding, and expansion reporting | Tier 2 | Commercial owner approves exact measure definitions before implementation; extend Reports and figure reconciliation rather than adding a dashboard silo. |

**Increment 5 — Owner boundary:** Platform epic #847 owns Radar signal families and the shared detector engine.
This epic may add an `account_health` family through that contract; it must not re-implement the detector
engine.

**Increment 7 — Owner boundary:** Platform epic #846 owns My Work providers and routing, while #401 retains
the guided playbook/action-macro scope noted above. This epic may add a customer-success projection through the
provider contract; it must not re-implement the queue store.

**Increment 8 — Owner boundary:** Platform epic #505 owns Reports v2 and the shared report engine. This epic
may add a customer-success report definition through that contract; it must not re-implement the report
engine.

The onboarding-task association is structural: `Task` has no company field
([`Task.java:21-37`](../backend/src/main/java/ooo/klae/connex/backend/beans/Task.java#L21-L37)), while
`EntityReference` is derived from prose tokens and replaced when prose is saved
([`ReferenceService.java:42-50`](../backend/src/main/java/ooo/klae/connex/backend/services/ReferenceService.java#L42-L50)).
Therefore `customer_onboarding_item` owns `plan_id`, `company_id`, and nullable `task_id`; editing task text
cannot sever the account association.

Onboarding template/version design should reuse the workspace-scoped immutable-version precedent in
[`V195__record_creation_templates.sql:1-10`](../backend/src/main/resources/db/migration/tenant/V195__record_creation_templates.sql#L1-L10)
and [`75-147`](../backend/src/main/resources/db/migration/tenant/V195__record_creation_templates.sql#L75-L147),
not invent a mutable template definition.
