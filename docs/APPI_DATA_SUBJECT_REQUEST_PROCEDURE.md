# Connex — Data-Subject Request Handling Procedure (開示等の請求)

> **Status:** Phase-0 **process** deliverable for the APPI pathway ([#224]) — issue [#221]. Request **tracking** and the **subject-scoped disclosure export** are productized (increment 1 of [#221]): org administrators log and track requests via `POST/GET/PUT /api/orgs/{orgId}/data-subject-requests` and assemble a disclosure via `GET /api/orgs/{orgId}/data-subject-requests/{id}/disclosure` (all endpoints are org-admin gated; create/update and the disclosure assembly additionally require recent authentication and write audit-log records — the disclosure fails closed if its audit record cannot be persisted). Cease-of-use and cease-of-provision are also productized (increment 2 of [#221]): `PUT /api/persons/{id}/restrictions` on the owning workspace suspends processing and/or ceases third-party provision.
> **Not legal advice.** Confirm response methods, fees, and identity-verification standards with counsel and each customer's DPA ([APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md)).
> **Owner:** Hunter Nakagawa, Founder · **Contact point:** privacy@connexcrm.jp · **Last reviewed:** 2026-08-13

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
- **Disclosure:** assemble everything held about the subject via the subject-scoped export (`GET /api/orgs/{orgId}/data-subject-requests/{id}/disclosure`) — person record plus tags, custom-field values with classification metadata, activities, notes, tasks, attachment metadata, employment history, relationship edges, deal associations, introductions, third-party-provision/share records, and the person-scoped audit trail (capped at 1,000 entries; the uncapped total is included so truncation is visible). The export is **operator-facing raw material**: it can contain third-party personal data and confidential business information, so apply the Art. 33(2) exceptions and redact before releasing anything to the subject. Flag `special_care` custom fields and suspected special-care free-text locations under [SPECIAL_CARE_DATA_POLICY.md](SPECIAL_CARE_DATA_POLICY.md). Attachment binaries are not embedded — retrieve flagged files through the normal attachment endpoints. Do **not** substitute the workspace-wide CSV export, which over-discloses.
- **Correction:** update via the standard record edit; log the change (captured in `audit_log`).
- **Cease of use:** set `suspended: true` via `PUT /api/persons/{id}/restrictions` — the contact stops being processed (warmth/decay scoring, automation rules, intro suggestions, network reports, AI features, notification nudges, CSV exports) while staying visible for management and disclosure. For **erasure**, note that deletion is hard-delete per record — confirm with the customer/operator before irreversible deletion.
- **Cease third-party provision:** set `provisionCeased: true` on the same endpoint — every standing cross-workspace share is revoked immediately (audited with the revoked count), new shares are refused, and the contact is excluded from AI outbound. Record the instruction on the request.

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
