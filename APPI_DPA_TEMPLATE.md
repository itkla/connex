# Data Processing Agreement (委託契約) — Template

> **Template for counsel — NOT a binding agreement and NOT legal advice.** This is a starting clause set for the entrustment (委託) of personal-data handling from a Connex customer (the **Entruster / 委託元**, the APPI handling operator) to Connex (the **Entrustee / 委託先**). Bracketed items and the responsible legal entity **must** be completed and the whole reviewed by qualified Japanese counsel before execution. Issue [#93].
>
> Governs alongside the [Security Posture](SECURITY.md) and [Breach Response Runbook](APPI_BREACH_RESPONSE_RUNBOOK.md). Where this template and a signed DPA differ, the signed DPA governs.

## 1. Parties & roles
- **Entruster (委託元):** [customer legal name] — the operator handling personal information under the APPI.
- **Entrustee (委託先):** [Connex legal entity] — handles personal data solely on the Entruster's instructions and within the scope below.
- This entrustment is **not** a provision to a third party under APPI Art. 27 (no opt-in/opt-out required for the entrustment itself). Each party retains its own APPI duties.

## 2. Scope & purpose of entrustment
- **Purpose:** to provide the Connex service (CRM storage, relationship intelligence, scoring, and features the Entruster enables).
- **Categories of data subjects:** the Entruster's contacts, employees, and business relationships.
- **Categories of personal data:** names, contact details, employment history, activities, notes, relationship data, and any custom fields the Entruster populates.
- **要配慮個人情報:** the Entruster must not load special-care-required personal information without a lawful basis; classification/guardrails per [#222].
- Connex handles data **only** within this scope and the Entruster's documented instructions.

## 3. Entrustee obligations (APPI Art. 25 supervision-ready)
- Handle personal data only on documented instructions; do not use it for Connex's own purposes.
- Implement the security control measures described in [SECURITY.md](SECURITY.md) (organizational, human, physical, technical, external-environment — APPI Art. 23).
- Impose confidentiality on personnel; supervise them (Art. 24).
- Assist the Entruster with data-subject requests (開示等) it receives, per [APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md](APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md).
- Maintain records sufficient for the Entruster's audits; permit reasonable audit/inspection.

## 4. Subprocessors (再委託)
- The Entruster authorizes the subprocessors listed in **Annex A**; Connex remains responsible for their handling.
- Connex gives [30] days' notice of any new subprocessor; the Entruster may object on reasonable data-protection grounds.
- **Annex A (current):** AWS (`ap-northeast-1`, infrastructure hosting). AI providers are **not** subprocessed by Connex — see §7.

## 5. Location of processing & cross-border (APPI Art. 28)
- Personal data is stored and processed in **Japan** (`ap-northeast-1`).
- Any processing outside Japan is a cross-border transfer; Connex will not introduce one without the Entruster's prior agreement and the required Art. 28 information/consent or equivalent safeguards, reflected in [SECURITY.md](SECURITY.md).

## 6. Personal-data breach (APPI Art. 26 co-notification)
- On becoming aware of a reportable breach of the Entruster's data, Connex will **promptly notify the Entruster** (target ≤ 24h) with the information needed for the Entruster's PPC report (速報 ~3–5 days; 確報 30/60 days) and individual notifications, and will support the investigation. Process: [APPI_BREACH_RESPONSE_RUNBOOK.md](APPI_BREACH_RESPONSE_RUNBOOK.md).
- The Entruster, as operator, is responsible for reporting to the PPC and notifying individuals; Connex may report on the Entruster's behalf only if expressly agreed.

## 7. AI features (if enabled) — customer is the data exporter
- Where the Entruster enables AI, it operates under **BYOP** ([#94]): the Entruster brings a provider it has contracted with (Vertex / Bedrock / Azure AI), holds that provider's contract/DPA, and configures the endpoint.
- For the transfer to that provider, **the Entruster is the data exporter to its own designated provider**; the AI provider is the Entruster's subprocessor, not Connex's. Connex pins region and applies SSRF controls, and masks payloads where feasible (caveat: masking does not change handler status — 容易照合性).

## 8. Return & deletion (APPI Art. 22)
- On termination, Connex provides an **export** of the Entruster's data, then **deletes** it within **[30] days**, including from routine backups on their normal cycle. Deletion interacts with the append-only audit log per [#105]/[#91]; retained audit metadata is described there.

## 9. Term, liability, governing law
- [Term, liability caps, indemnities — counsel to complete.]
- Governing law: [Japan]; disputes: [venue].

---

**Completion checklist before execution:** parties/entity ✔ · Annex A subprocessors ✔ · deletion SLA ✔ · breach-notification timing ✔ · AI/BYOP construction (if applicable) ✔ · counsel review ✔.

[#93]: https://github.com/itkla/connex/issues/93
[#94]: https://github.com/itkla/connex/issues/94
[#105]: https://github.com/itkla/connex/issues/105
[#91]: https://github.com/itkla/connex/issues/91
[#222]: https://github.com/itkla/connex/issues/222
