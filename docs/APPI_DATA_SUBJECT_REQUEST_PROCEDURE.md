# Connex — Data-Subject Request Handling Procedure (開示等の請求)

> **Status:** Phase-0 **process** deliverable for the APPI pathway ([#224]) — issue [#221]. Request **tracking** and the **subject-scoped disclosure export** are productized (increment 1 of [#221]): org administrators log and track requests via `POST/GET/PUT /api/orgs/{orgId}/data-subject-requests` and assemble a disclosure via `GET /api/orgs/{orgId}/data-subject-requests/{id}/disclosure` (all endpoints are org-admin gated; create/update and the disclosure assembly additionally require recent authentication and write audit-log records — the disclosure fails closed if its audit record cannot be persisted). Cease-of-use and cease-of-provision are also productized (increment 2 of [#221]): `PUT /api/persons/{id}/restrictions` on the owning workspace suspends processing and/or ceases third-party provision.
> **Not legal advice.** Confirm response methods, fees, and identity-verification standards with counsel and each customer's DPA ([APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md)).
> **Owner:** Hunter Nakagawa, Founder · **Contact point:** privacy@connexcrm.jp · **Last reviewed:** 2026-08-13 · **Next review:** 2027-02-13
> Review at least every 6 months; ownership, contact, and off-cycle review triggers follow [SECURITY.md](SECURITY.md) §6.

---

## 1. Rights in scope (APPI Arts. 33–35)
A data subject (or authorized representative) may request, regarding their **retained personal data**:
- **Disclosure (開示, Art. 33)** — including disclosure in electromagnetic-record format, and **disclosure of third-party-provision records**.
- **Correction / addition / deletion (訂正等, Art. 34)** — where the data is factually incorrect.
- **Cease of use / erasure (利用停止・消去, Art. 35)** and **cease of third-party provision** — on the prescribed grounds.

## 2. First question: are we operator or entrustee?
- **Connex is the operator** for its own account/service data (its direct users). Handle the request end-to-end here.
- **Connex is only the entrustee (委託先)** for a customer's CRM content. **Refer the request to that customer** (the handling operator) without undue delay, and support them (e.g. provide a subject-scoped export). Do not disclose/alter/erase customer content on a subject's direct request without the customer's instruction. See [APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md) §3.

## 3. Intake
1. Log the request as a data-subject-request record (`POST /api/orgs/{orgId}/data-subject-requests`): requester, claimed data subject, right(s) requested, date received, channel. Link the subject person record (`subjectWorkspaceId` + `subjectPersonId`) once identified.
2. Acknowledge receipt to the requester.
3. Start the response clock (see §7).

## 4. Identity verification
- Verify the requester is the data subject or an authorized agent, proportionate to the sensitivity of the data. Do not over-collect verification data; delete it after the request closes.
- For agents, obtain proof of authority.
- Record the verification on the request (`identityVerifiedAt`) — the disclosure export refuses to assemble until it is recorded.

## 5. Fulfil the request
- **Disclosure:** assemble the retained subject evidence in the inventory below via the subject-scoped export (`GET /api/orgs/{orgId}/data-subject-requests/{id}/disclosure`), including current consent, consent history, and outbound audience-export evidence. The export is **operator-facing raw material**: it can contain third-party personal data and confidential business information, so apply the Art. 33(2) exceptions and redact before releasing anything to the subject. Flag `special_care` custom fields and suspected special-care free-text locations under [SPECIAL_CARE_DATA_POLICY.md](SPECIAL_CARE_DATA_POLICY.md). Attachment binaries are not embedded — retrieve flagged files through the normal attachment endpoints. Do **not** substitute the workspace-wide CSV export, which over-discloses.
- **Correction:** update via the standard record edit; log the change (captured in `audit_log`).
- **Cease of use:** set `suspended: true` via `PUT /api/persons/{id}/restrictions` — the contact stops being processed (warmth/decay scoring, automation rules, intro suggestions, network reports, AI features, notification nudges, CSV exports) while staying visible for management and disclosure. For **erasure**, note that deletion is hard-delete per record — confirm with the customer/operator before irreversible deletion.
- **Cease third-party provision:** set `provisionCeased: true` on the same endpoint — every standing cross-workspace share is revoked immediately (audited with the revoked count), new shares are refused, and the contact is excluded from AI outbound. Record the instruction on the request.

### Disclosure inventory

The subject is identified by its owning workspace and person ID, within the request's organization.
Sections include retained evidence even when processing or third-party provision has ceased.
`DataSubjectDisclosureMapperXmlTest` pins the mapper statements and checks this section inventory
against the response DTO; changes to disclosure sections must update both.

| Response section | Retained evidence |
|---|---|
| `person` | Current person record and restrictions |
| `identities` | Current and superseded identifiers with acquisition provenance |
| `tags` | Subject tags |
| `customFieldValues` | Values and field classification metadata |
| `activities` | Subject activities |
| `providerCaptureEvidence` | Matched provider interactions, subject participant and admission/exclusion evidence |
| `notes` | Subject notes |
| `recordCommentThreads` | Subject comment threads and retained comments |
| `tasks` | Subject tasks |
| `attachments` | Subject attachment metadata |
| `employmentHistory` | Employment records |
| `lifecycleHistory` | Lifecycle transitions and reasons |
| `qualificationAnswers` | Qualification answers with the questions answered |
| `lifecyclePasses` | Retained lifecycle passes and response timestamps |
| `relationshipEdges` | Subject relationships |
| `dealAssociations` | Linked deals and roles |
| `introductions` | Subject introductions |
| `thirdPartyProvisions` | Current standing workspace shares |
| `consentState` | Current channel/purpose status, source, evidence reference and capture/update times |
| `consentHistory` | Retained grant/revoke/unknown events, source, evidence reference, actor ID and event time |
| `audienceExportEvidence` | Export destination (connector/list ID), snapshot membership, subject-only frozen/staged membership and export outcomes |
| `auditTrail` | Person-scoped action/outcome audit metadata, capped at 1,000 entries; `auditTrailTotal` gives the uncapped total |

The `notes` section is complete and is never filtered by note visibility class: private notes are
disclosed to the handling operator as raw assembly material and must be reviewed for statutory
redaction (Art. 33(2)) like every other section. It carries no row cap and holds the largest
free-text material in the response. Note bodies are read in pages, but the assembled response is
held in memory in full, so a subject with a very large note corpus produces a correspondingly large
response and a matching peak heap on the serving instance. Run such an export off-peak and expect
a slow response; there is no operator-facing continuation or page parameter, and the export is
never silently truncated.

Audience-export evidence includes exports by other workspaces in the request's organization that
held a shared subject, even after the standing share is revoked. Audience-export member lists are
projected to the subject's membership flags; other members' IDs
are not included. `stagedForPush` records membership in the intended provider request, which is
persisted before the call and does not itself prove provision. Export counts and
`outcomeClassification` describe the whole batch. `subjectProvisionOutcome` is `confirmed` only
when the provider or operator confirmed delivery of every staged member and no late conflicting
outcome is recorded; partial, pending, ambiguous and conflicting outcomes are `unconfirmed`.
A recorded definite failure for a staged subject is `not_delivered`; a subject absent from the
staged request is `not_staged`. Legacy exports without retained member lists remain visible through
their snapshot membership, with unknown frozen/staged flags omitted from JSON and an `unknown`
subject outcome.
The response retains failure, reconciliation and late-outcome evidence for operator review.
The destination is the retained connector/list identifier; historical endpoint configuration is
not stored on the export and cannot be reconstructed from its current connector configuration.

| Declared exclusion | Handling / limitation |
|---|---|
| `ai_output_cache` | Persisted AI outputs remain excluded; tracked in #579 and subject to the restriction purge described below. |
| `audit_log.changes` | Change payloads are excluded; the person-scoped audit trail includes actions/outcomes only. Consent/export evidence comes directly from its retained tables. |
| `attachment_binaries` | Retrieve files separately through authorized attachment endpoints and review before release. |
| `unlinked_free_text` | Text in other records that merely names the subject has no reliable subject link; operator review is required. |

## 6. Respond
- Respond in the manner prescribed by the APPI (the subject may specify electromagnetic-record format for disclosure).
- If a **fee** applies to disclosure, inform the requester of the amount before proceeding.
- If the request is refused in whole or part (e.g. a statutory exception), state the reason.
- Record the outcome and close the request (`PUT` with `status`, `respondedAt`/`closedAt`, and `resolution`).

## 7. Timeliness
- Respond **without undue delay**. Target: acknowledge within [3] business days; substantive response within [2] weeks, or explain the delay. Confirm concrete SLAs with counsel and align with customer DPAs.

## 8. Complaints
- If the requester is dissatisfied, direct them to the contact point above; they may also contact the Personal Information Protection Commission (個人情報保護委員会). See the public [Data Disclosure page](../frontend/app/disclosure) §6.

---

**Known conscious exclusions** (tracked in [#579](https://github.com/itkla/connex/issues/579)): the disclosure export omits persisted AI outputs (`ai_output_cache`) and audit `changes` payloads (actions/outcomes only). Restricting a contact now purges its person-keyed AI cache — intro rationales naming the subject and the briefs/risk rationales of the subject's deals — so those demasked outputs do not persist at rest; report narratives (keyed by report, not person) are not purged and regenerate without the subject. Free text in *other* records naming the subject is outside the restriction's reach. Keep this procedure as the human process around the tooling.

[#221]: https://github.com/itkla/connex/issues/221
[#224]: https://github.com/itkla/connex/issues/224
