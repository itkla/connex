# Connex — Data-Subject Request Handling Procedure (開示等の請求)

> **Status:** Phase-0 **process** deliverable for the APPI pathway ([#224]) — issue [#221]. This documents how requests are handled **today, as a manual process**; the productized workflow (request tracking, subject-scoped export, suspension-of-use flag, cease-provision) is the engineering slice of [#221].
> **Not legal advice.** Confirm response methods, fees, and identity-verification standards with counsel and each customer's DPA ([APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md)).
> **Owner:** {{PRIVACY_OWNER}} · **Contact point:** {{PRIVACY_CONTACT_EMAIL}} · **Last reviewed:** 2026-07-01

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
1. Log the request in {{REQUEST_TRACKER}}: requester, claimed data subject, right(s) requested, date received, channel.
2. Acknowledge receipt to the requester.
3. Start the response clock (see §7).

## 4. Identity verification
- Verify the requester is the data subject or an authorized agent, proportionate to the sensitivity of the data. Do not over-collect verification data; delete it after the request closes.
- For agents, obtain proof of authority.

## 5. Fulfil the request
- **Disclosure:** assemble everything held about the subject — person record plus related activities, notes, employment history, relationship edges, custom fields, custom-field classification metadata, and any third-party-provision/share records. Flag `special_care` custom fields and suspected special-care free-text locations under [SPECIAL_CARE_DATA_POLICY.md](SPECIAL_CARE_DATA_POLICY.md). Until the subject-scoped export in [#221] ships, assemble manually (audit_log + record lookups); do **not** substitute the workspace-wide CSV export, which over-discloses.
- **Correction:** update via the standard record edit; log the change (captured in `audit_log`).
- **Cease of use / erasure:** until the suspension-of-use flag in [#221] ships, cease processing manually and record the action; note that current deletion is hard-delete per record — confirm with the customer/operator before irreversible deletion.
- **Cease third-party provision:** exclude the record from sharing and any outbound provision; record the instruction.

## 6. Respond
- Respond in the manner prescribed by the APPI (the subject may specify electromagnetic-record format for disclosure).
- If a **fee** applies to disclosure, inform the requester of the amount before proceeding.
- If the request is refused in whole or part (e.g. a statutory exception), state the reason.
- Record the outcome and close the request in the tracker.

## 7. Timeliness
- Respond **without undue delay**. Target: acknowledge within [3] business days; substantive response within [2] weeks, or explain the delay. Confirm concrete SLAs with counsel and align with customer DPAs.

## 8. Complaints
- If the requester is dissatisfied, direct them to the contact point above; they may also contact the Personal Information Protection Commission (個人情報保護委員会). See the public [Data Disclosure page](frontend/app/disclosure) §6.

---

**When the productized workflow lands ([#221]):** replace the manual assembly in §5 with the subject-scoped export, the suspension-of-use (`suspended_at`) state, and the cease-provision flag; keep this procedure as the human process around the tooling.

[#221]: https://github.com/itkla/connex/issues/221
[#224]: https://github.com/itkla/connex/issues/224
