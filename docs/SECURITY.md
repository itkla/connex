# Connex — Security Posture & APPI 安全管理措置 Disclosure

> **Purpose.** One document serving two audiences: the **enterprise security-posture artifact** buyers ask for during procurement, and the **APPI 安全管理措置 (security control measures) disclosure** referenced from our public [Data Disclosure page](../frontend/app/disclosure) (APPI Art. 32). Issue [#104].
> **Status: living document.** It describes the architecture as it actually stands today and marks in-progress items with their tracking issue. It is deliberately honest about what has **not** landed yet — do not read an in-progress item as a shipped control.
> **Not legal advice.** Confirm the APPI framing and any customer commitments with counsel and the signed DPA ([APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md), [#93]).
> **Owner:** Hunter Nakagawa, Founder · **Last reviewed:** 2026-08-13 · **Next review:** 2027-02-13

---

## 1. What Connex is, and our APPI role

Connex is a multi-tenant relationship-intelligence CRM delivered as SaaS. Under the APPI, for the personal data a customer loads about its own contacts, **the customer is the handling operator (個人情報取扱事業者) and Connex is an entrustee (委託先)** — we handle that data only on the customer's instructions and within the scope of our agreement. We do **not** rely on the "cloud exception": the backend reads and processes content, which is handling. See [#93].

The **organization (`org_id`)** is the customer / contract / breach boundary; **workspaces** are partitions inside an organization. A cross-organization leak would be a breach; cross-workspace access within an organization is an internal RBAC matter.

## 2. Security control measures (安全管理措置)

APPI groups these into four categories plus external-environment. This is our current posture.

### 2.1 Organizational (組織的)
- **Tenant isolation as an enforced invariant.** A fail-closed MyBatis interceptor (`TenantScopeInterceptor`) rejects workspace-scoped queries that run without a resolved tenant context; `TenantResolutionInterceptor` resolves and re-validates org/workspace membership per request. Backed by a CI architecture test (`TenantScopeArchTest`).
- **Append-only audit log.** `audit_log` (Flyway `V1`, workspace-scoped since `V10`) records `action`, `entity_type`/`entity_id`, `actor_id`, `ip_address`, `user_agent`, hashed `session_id`, `request_id`, field-level `changes`, and `created_at`. Row `UPDATE`/`DELETE` are blocked by DB triggers; writes are insert-only. Surfaced to admins in-app.
- **Incident response.** Documented breach-response runbook (APPI Art. 26): [APPI_BREACH_RESPONSE_RUNBOOK.md](APPI_BREACH_RESPONSE_RUNBOOK.md), [#223].
- **Vulnerability remediation.** Findings across application code, dependencies, images, infrastructure, GitHub Actions, and third-party services follow the severity-adjusted deadlines, emergency release path, ownership, and fixed-term exception rules in [VULNERABILITY_MANAGEMENT.md](VULNERABILITY_MANAGEMENT.md).
- **Static analysis.** Pull requests, `main`, merge groups, and weekly scans run fail-closed GitHub CodeQL analysis for backend Java and frontend TypeScript. Critical/High and error-severity findings fail the selected workflow job, but the check is not yet required by `main` branch protection and therefore does not itself prevent merge; current enforcement and self-modification limitations are documented in [STATIC_ANALYSIS.md](STATIC_ANALYSIS.md).
- *In progress:* audit tamper-evidence (hash-chain / external sink) — [#91]; organization-scope re-verification — [#97].

### 2.2 Human (人的)
- Staff access on least-privilege; confidentiality obligations. Supervision of employees per APPI Art. 24.
- *To formalize during onboarding:* documented access-grant/revoke process and periodic access review (Hunter Nakagawa, Founder).

### 2.3 Physical (物理的)
- No first-party data centers. Personal data is stored on managed cloud infrastructure (target: AWS `ap-northeast-1`, Japan) inheriting the provider's physical-security controls. Subprocessor list in the DPA.

### 2.4 Technical (技術的)
- **Authentication:** WebAuthn / passkeys; password fallback hashed with BCrypt. **CSRF** enabled (session-token model); **session rotation** on login. The session cookie is `HttpOnly`, `Secure` by default, and `SameSite=Lax` by default; deployments can set stricter or SAML-compatible cookie flags via env. The frontend-readable workspace selector cookie contains only the active workspace id, is `Secure` by default, and is always revalidated against server-side membership.
- **Authorization / RBAC:** custom per-workspace roles (`owner`/`admin`/`member` + custom `workspace_role`, `V13`) over a catalog of fine-grained permissions, enforced on a single path via `@RequirePermission` → `AuthorizationManager`. Destructive/structural ops are permission-gated and CI-backstopped (`RbacEnforcementArchTest`).
- **Transport:** HSTS (1y, `includeSubDomains`), `Referrer-Policy: strict-origin-when-cross-origin`, and a restrictive backend Content-Security-Policy (`default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`) in `SecurityConfig`. The single-origin edge and standalone Next.js runtime extend clickjacking, MIME-sniffing, and referrer protection to frontend HTML, static assets, downloads, and error responses; see [DEPLOYMENT.md](DEPLOYMENT.md#topology).
- **Secrets:** externalized to environment (`CONNEX_DB_*`, bootstrap credentials); local Docker passwords are kept in untracked `backend/.env`. Never-searched integration secrets are stored through the central envelope-encrypted secret store with key IDs, keyring rotation, disabled-key fail-closed behavior, metadata-only diagnostics, and audited use/rewrap operations ([runbook](SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md), [#372]).
- **Database transport:** non-dev startup requires `CONNEX_DB_URL` to use MySQL Connector/J verified TLS (`sslMode=VERIFY_CA` or `sslMode=VERIFY_IDENTITY`). Local `dev` and `test` profiles may explicitly use plaintext Docker MySQL. A systemd invocation from the local staging checkout at `/opt/connex-staging/backend` has a narrow local-only exception for explicit loopback MySQL URLs with `sslMode=DISABLED`; remote plaintext URLs still fail closed.
- **Business-card reading:** uploaded cards are globally admission-limited, size/signature/full-decode/dimension/frame bounded, and local PaddleOCR is preferred through a private bearer-authenticated CPU-only sidecar. The sidecar has no ingress route, outbound network access, remote-image fetch, runtime model download, content logging, or unbounded queue. Before external fallback, a scan waits up to the bounded local-first interval for an in-flight or fresh Paddle readiness decision. If Paddle is unavailable, a member with `AI_USE` may use the organization's enabled, no-training-attested provider; only the metadata-free canonical JPEG is embedded in the provider request, under a 3.5 MB/4096-pixel shared bound, and image URLs are never fetched. Image pixels can contain direct or special-care identifiers and cannot be masked like text, so this path is disclosed separately and returns bounded review-only fields. Image readiness accepts only verified multimodal model families and the exact resolved provider snapshot is rechecked before egress. Global, per-organization, and exact estimated-memory leases remain held through provider response parsing; OpenAI-compatible, Bedrock, Vertex, and Google OAuth production transports hard-cancel under one wall-clock deadline covering bounded final DNS resolution and the HTTP exchange, with the validated address pinned for the connection. Unknown models and provider failures fail closed to manual entry. Neither raw OCR output, provider output, nor card pixels are logged or persisted by the scan operation. Confirmed imports use owner-bound workspace-scoped UUID idempotency claims plus SHA-256 request fingerprints; fingerprints and request bodies are never logged, and key reuse with different card bytes or reviewed fields fails closed.
- **Encryption guarantees:** customer-facing encryption, key custody, revocation, backup/export, and plaintext-access claims are governed by the canonical [Encryption Guarantee Matrix](ENCRYPTION_GUARANTEE_MATRIX.md), [#369]. Hosted Connex is not E2EE or zero-knowledge: the backend processes customer CRM content in plaintext to provide the service. SaaS production storage-encryption launch/evidence requirements are in [SAAS_STORAGE_ENCRYPTION_RUNBOOK.md](SAAS_STORAGE_ENCRYPTION_RUNBOOK.md), [#371]. Customer-operated/on-prem encryption default-on guidance is in [ON_PREM_ENCRYPTION_RUNBOOK.md](ON_PREM_ENCRYPTION_RUNBOOK.md), [#373]. Dedicated SaaS CMK claims are limited by [DEDICATED_SAAS_CMK_FEASIBILITY.md](DEDICATED_SAAS_CMK_FEASIBILITY.md), [#376].
- *In progress:* broader at-rest encryption roadmap — [#92]; dedicated database provisioning/routing — [#313]; operational rotation of any database credentials that reused old committed local defaults — [#88]; brute-force/rate-limit protection — [#80].

### 2.5 External-environment grasp (外的環境の把握)
- SaaS personal data is stored in **Japan**. When a customer enables a BYOP AI provider, submitted masked text and explicitly disclosed metadata-free image data may be processed in the configured provider region; the customer must account for that country's data-protection framework and APPI Art. 28 requirements. Connex does not send CRM data to an AI provider unless the organization configures and enables it and the acting member has `AI_USE`.

## 3. Data lifecycle

- **Categories handled:** account/profile (name, email, passkeys, role), usage/technical (IP, user-agent, session, audit events), and — under entrustment — customer CRM content (contacts, business-card images, employment history, activities, notes, relationships). Special-care and sensitive-data handling is governed by [SPECIAL_CARE_DATA_POLICY.md](SPECIAL_CARE_DATA_POLICY.md), including the `standard` / `sensitive` / `special_care` custom-field classification model and free-text guardrails ([#222]).
- **Retention & deletion:** personal data kept only as long as necessary; per-tenant retention policy, export, and teardown are tracked in [#105]. Deletion is currently hard-delete per record.
- **Data-subject requests (開示等):** handled per [APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md](APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md), [#221]; where Connex is only the entrustee, requests are referred to the customer.

## 4. Subprocessors

Cloud infrastructure (AWS, `ap-northeast-1`) and, only when a customer enables it, the customer-designated AI provider under BYOP ([#94]), where the customer holds the provider contract and is the data exporter. Current list is maintained in the DPA and available to customers on request.

## 5. Country of processing

Japan (`ap-northeast-1`). Any change is treated as a cross-border transfer and reflected here and in the Data Disclosure page before it takes effect.

## 6. Security ownership, accountability, and escalation

**Named roles.** Hunter Nakagawa, Founder is the Security Owner, Privacy Owner, and Incident Lead. He
currently holds every accountability below; there is no deputy and no separation of duties for these
decisions today.

| Area | Accountable owner | Responsibility boundary |
|---|---|---|
| Security incidents | Hunter Nakagawa, Founder | Declares and leads incidents, prioritizes containment, owns the authoritative timeline, and approves reportability and notification decisions. Technical responders execute containment and forensics; counsel advises on legal duties. |
| Vulnerability intake and triage | Hunter Nakagawa, Founder | Owns intake at the designated channel, acknowledges reports, validates and assigns severity, and routes remediation to an owner. |
| Risk and exception approval | Hunter Nakagawa, Founder | Is the sole approver. Each decision must record its scope, rationale, expiry, compensating controls, and review date; there is no separate approval authority today. |
| Periodic review | Hunter Nakagawa, Founder | Reviews this ownership record and the linked security runbooks, verifies that the published contact and access-recovery paths work, and records the outcome and next review date. |
| Customer-facing security communication | Hunter Nakagawa, Founder | Is accountable for the timing, scope, accuracy, and authorization of security notices. Execution by a Customer Liaison remains unassigned and is tracked in [#249]. |

Operational incident execution follows the
[APPI breach-response runbook](APPI_BREACH_RESPONSE_RUNBOOK.md); access, deployment, and teardown
mechanics remain in the [internal operations runbook](INTERNAL_OPERATIONS_RUNBOOK.md).

### Single escalation destination

The single designated destination for every security or personal-information escalation is
**privacy@connexcrm.jp**. Do not use a personal inbox, ordinary support channel, or public issue
tracker. Hunter Nakagawa is the only operator and monitors the contact point on a best-effort basis;
there is no 24/7 coverage. The goals below are targets, not guarantees, and measure elapsed time from
receipt to first acknowledgement rather than resolution.

For vulnerability remediation, acknowledgement is the precise custody record defined in
[VULNERABILITY_MANAGEMENT.md](VULNERABILITY_MANAGEMENT.md#clock-start). The targets below bound the
pre-clock response target; remediation deadlines begin at that recorded acknowledgement, not at
mailbox delivery or automated detection.

| Escalation | Required subject prefix | Best-effort acknowledgement target |
|---|---|---|
| Suspected vulnerability | `[VULNERABILITY]` | Within 24 hours |
| Active or suspected security/personal-data incident | `[ACTIVE INCIDENT]` | Within 1 hour |
| Risk-exception request | `[RISK EXCEPTION]` | Within 48 hours |

Mailbox delivery, external receipt, monitoring/alerting, administrative recovery, and response-time
performance are not yet verified. A 24/7 or on-call claim must not be activated until a successful
external delivery and receipt test, tested alerts to responders, a named rota with coverage and
handoff rules, and measured acknowledgement times are recorded. [#249] tracks that operational
evidence; [#1286] tracks matching publication on the public legal pages. Until those checks pass, the
address is designated but not operationally verified.

A risk-exception request must identify the affected control and scope, business reason, proposed
expiry, accountable implementer, and compensating controls. Only the named Security Owner can approve
it. A credible active-incident report then follows the breach-response runbook, where the one-hour
internal escalation goal is also a best-effort target rather than a 24/7 commitment.

### Review and handover

Review this ownership record at least every 6 months. The next scheduled review is **2027-02-13**.
Review it sooner after any security or personal-data incident; change to the named owner, role, or
published address; actual or expected owner unavailability; or material change to company structure,
legal duties, critical providers, the domain registrar/DNS, credential recovery, or the customer
notification process.

For a planned departure or role change, name the successor and complete the handover before the
change takes effect. No independent party is currently documented to detect an unplanned loss of
Hunter Nakagawa's availability, appoint a successor, or access the account, key, provider, and
registrar recovery paths. Emergency succession is therefore not executable today, and no 24-hour
appointment or handover deadline is claimed. [#249] tracks naming and testing that appointment
authority and custody recipient. This document must name the actor, detection path, and achievable
deadline before emergency succession can be treated as operational.

A planned handover, and any future emergency handover once those prerequisites exist, must transfer
and verify:

- company account and credential custody, including recovery contacts and MFA/passkey recovery;
- encryption, signing, secret-store, audit-integrity, and backup key-material custody and rotation
  records through the approved secret-management path, never in the handover record itself;
- administrative access and recovery for hosting, cloud, source control, CI/CD, database, monitoring,
  email, other critical providers, and the domain registrar/DNS;
- the Security Owner, Privacy Owner, and Incident Lead designations in this document, the linked
  runbooks, and operational contact lists; and
- administration, monitoring, recovery, and a successful receipt test for the published
  **privacy@connexcrm.jp** address.

Record the non-secret transfer status, access-test results, outgoing-owner revocations, required
rotations, and unresolved gaps in the CHK-001 control issue [#1230]. Never record credentials or key
material in GitHub.

### Time-bounded risk acceptance: no deputy

No deputy is designated. This creates a single point of accountability and availability that may
delay triage, incident decisions, exception approval, or customer communication if Hunter Nakagawa
is unavailable. Hunter Nakagawa is both risk owner and approver; this absence of independent approval
is part of the accepted limitation.

Existing compensating measures are limited to the named owner, one designated contact route,
best-effort acknowledgement targets, the breach-response runbook, and scheduled and off-cycle review.
There is no human redundancy, independently verified mailbox operation, emergency appointment
authority, or verified recovery custodian today. The acceptance is dated **2026-08-13** and expires on
**2027-02-13** unless formally renewed or closed earlier.

[#249] tracks only the deferred deputy decision and Customer Liaison, Legal/Counsel, and Comms roles;
mailbox delivery, monitoring, administrative recovery, receipt-test, and response-time evidence; any
future 24/7 rota and alerts; and the missing emergency appointment authority and custody recipient.

---

### Roadmap references
Tracked under the security roadmap [#87] and the APPI pathway [#224]. Key open items: [#97] org boundary close-out · [#89] read-scoping backstop · [#92] CMK · [#313]/[#100] dedicated database/deployment architecture · [#80] rate-limit · [#99] CI scanning · [#106] third-party pentest.

[#87]: https://github.com/itkla/connex/issues/87
[#89]: https://github.com/itkla/connex/issues/89
[#91]: https://github.com/itkla/connex/issues/91
[#92]: https://github.com/itkla/connex/issues/92
[#93]: https://github.com/itkla/connex/issues/93
[#94]: https://github.com/itkla/connex/issues/94
[#97]: https://github.com/itkla/connex/issues/97
[#98]: https://github.com/itkla/connex/issues/98
[#100]: https://github.com/itkla/connex/issues/100
[#80]: https://github.com/itkla/connex/issues/80
[#99]: https://github.com/itkla/connex/issues/99
[#104]: https://github.com/itkla/connex/issues/104
[#105]: https://github.com/itkla/connex/issues/105
[#106]: https://github.com/itkla/connex/issues/106
[#313]: https://github.com/itkla/connex/issues/313
[#369]: https://github.com/itkla/connex/issues/369
[#371]: https://github.com/itkla/connex/issues/371
[#372]: https://github.com/itkla/connex/issues/372
[#373]: https://github.com/itkla/connex/issues/373
[#376]: https://github.com/itkla/connex/issues/376
[#221]: https://github.com/itkla/connex/issues/221
[#222]: https://github.com/itkla/connex/issues/222
[#223]: https://github.com/itkla/connex/issues/223
[#224]: https://github.com/itkla/connex/issues/224
[#249]: https://github.com/itkla/connex/issues/249
[#1230]: https://github.com/itkla/connex/issues/1230
[#1286]: https://github.com/itkla/connex/issues/1286
