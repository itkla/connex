# Connex Special-Care Data Policy

This policy covers special-care-required personal information and other
sensitive personal information stored in Connex customer workspaces. It is a
product and operating policy for APPI Art. 20 guardrails. It is not legal
advice; customers remain the handling operator for CRM content they load, and
their counsel must confirm lawful basis and consent.

## Classification Model

Custom-field definitions carry `dataClassification` with these values:

| Value | Use |
| --- | --- |
| `standard` | Ordinary CRM data. Default for new custom fields. |
| `sensitive` | Personal or confidential data needing heightened handling but not necessarily special-care data. |
| `special_care` | Special-care-required personal information. Use only when the customer has confirmed a lawful basis and, where required, prior consent from the data subject. |

The settings UI requires explicit confirmation before a custom field is created
or changed to `special_care`. That confirmation is an operator guardrail, not a
legal determination by Connex.

## Customer Rules

Customers must not load special-care data into Connex unless all of these are
true:

1. The customer has identified a lawful basis for acquisition and handling.
2. The customer has obtained prior consent where APPI requires it, or confirmed
   that a narrow exception applies.
3. The data is necessary for the declared purpose of use.
4. The data is placed in a field classified as `special_care` whenever a
   structured field is available.
5. The customer understands that Connex processes customer CRM content in
   plaintext while providing the service; storage encryption and RBAC are not
   consent substitutes.

If any point is uncertain, do not load the data. Record it outside Connex until
counsel confirms the handling path.

## Free-Text Guardrails

Free-text surfaces are uncontrolled channels and can accidentally collect
special-care data. Today these include at least:

- Notes.
- Activity notes.
- Task descriptions.
- Deal, company, and person free-text fields.
- Untyped or text custom-field values.
- Imported CSV columns mapped into any of the above.

Policy:

- Prefer classified custom fields over free text for any sensitive or
  special-care data that must be retained.
- Do not paste medical history, criminal record, social status, disability,
  labor-union, religion, race/ethnicity, or similar special-care details into
  notes or activity notes unless the customer has confirmed lawful basis and
  consent.
- If special-care data is discovered in free text, classify the incident or
  cleanup task, notify the customer owner, and either move it into an
  explicitly classified field or delete it if it is not needed.
- CSV import review must treat unknown columns as `standard` only when their
  contents are visibly ordinary CRM data. Ambiguous columns should be mapped to
  a classified custom field or excluded.
- Support staff must not ask customers to paste special-care details into
  tickets, chat, email, or screenshots. If needed for an incident, store the
  artifact under the encrypted support-artifact process in
  `SAAS_STORAGE_ENCRYPTION_RUNBOOK.md`.

## Exports, Disclosure, And Data-Subject Requests

Exports are plaintext at generation. Any export containing `special_care`
fields or suspected special-care free text must be handled as a high-sensitivity
artifact:

- Limit export scope to the minimum subject, workspace, or incident window.
- Use encrypted staging storage and short retention where Connex stages the
  artifact.
- Tell the customer that downloaded exports are their responsibility to
  protect.
- For data-subject requests, include custom-field classification metadata and
  flag suspected special-care free-text locations for customer review.

## At-Rest Encryption Scope

Special-care classification changes governance, export handling, incident
assessment, and AI eligibility. It does not move searchable CRM fields into the
Connex app-level secret store. Searchable CRM data, including `special_care`
custom fields and free-text content, follows the deployment-specific storage
encryption posture in `ENCRYPTION_GUARANTEE_MATRIX.md`; future at-rest
encryption work under [#92] must preserve that boundary unless a separate
searchable-encryption design is approved.

## Incidents And Breach Assessment

The breach-response runbook treats special-care data as a reportable-situation
trigger. During scoping, responders must assume special-care data may be
present in both classified fields and free-text surfaces until proven otherwise.

Incident records should capture:

- Whether `special_care` custom fields were in scope.
- Whether free-text surfaces were sampled or searched for special-care content.
- Whether the customer confirmed lawful basis/consent for the affected data.
- Whether customer notification needs to warn that special-care data may be in
  scope.

## AI And Automation

Special-care data must not be sent to an AI provider unless the relevant AI
workstream has implemented all of these controls:

1. Customer BYOP/provider configuration and region controls.
2. Endpoint allowlisting and SSRF defenses.
3. Prompt assembly that respects `dataClassification`.
4. Masking or exclusion rules for `special_care` fields and suspected
   special-care free-text content.
5. Outbound LLM audit records that identify the data classification sent
   without logging the sensitive payload.
6. Customer-facing disclosure that the customer, not Connex, controls the AI
   provider relationship under the BYOP model.

Until those controls land under the AI roadmap ([#82], [#94]), special-care
fields and suspected special-care free text are excluded from AI payloads by
policy.

## Future Detection

Automated detection belongs with the anonymization and AI-masking workstreams
([#82], [#94]). The detector should be advisory and conservative: warn on
likely special-care terms, do not silently rewrite customer records, and do not
store the matched text in logs, audit changes, or telemetry. Detection results
should be scoped to the workspace and visible only to users authorized to manage
the affected records or custom-field definitions.

## References

- Security posture: [SECURITY.md](SECURITY.md)
- DPA template: [APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md)
- Breach response: [APPI_BREACH_RESPONSE_RUNBOOK.md](APPI_BREACH_RESPONSE_RUNBOOK.md)
- Data-subject request procedure: [APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md](APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md)
- SaaS storage encryption runbook: [SAAS_STORAGE_ENCRYPTION_RUNBOOK.md](SAAS_STORAGE_ENCRYPTION_RUNBOOK.md)
- Encryption guarantee matrix: [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md)

[#92]: https://github.com/itkla/connex/issues/92
[#82]: https://github.com/itkla/connex/issues/82
[#94]: https://github.com/itkla/connex/issues/94
[#222]: https://github.com/itkla/connex/issues/222
