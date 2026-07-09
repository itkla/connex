# Connex — Security Posture & APPI 安全管理措置 Disclosure

> **Purpose.** One document serving two audiences: the **enterprise security-posture artifact** buyers ask for during procurement, and the **APPI 安全管理措置 (security control measures) disclosure** referenced from our public [Data Disclosure page](frontend/app/disclosure) (APPI Art. 32). Issue [#104].
> **Status: living document.** It describes the architecture as it actually stands today and marks in-progress items with their tracking issue. It is deliberately honest about what has **not** landed yet — do not read an in-progress item as a shipped control.
> **Not legal advice.** Confirm the APPI framing and any customer commitments with counsel and the signed DPA ([APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md), [#93]).
> **Owner:** {{SECURITY_OWNER}} · **Last reviewed:** 2026-07-01

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
- *In progress:* audit tamper-evidence (hash-chain / external sink) — [#91]; organization-scope re-verification — [#97].

### 2.2 Human (人的)
- Staff access on least-privilege; confidentiality obligations. Supervision of employees per APPI Art. 24.
- *To formalize during onboarding:* documented access-grant/revoke process and periodic access review ({{SECURITY_OWNER}}).

### 2.3 Physical (物理的)
- No first-party data centers. Personal data is stored on managed cloud infrastructure (target: AWS `ap-northeast-1`, Japan) inheriting the provider's physical-security controls. Subprocessor list in the DPA.

### 2.4 Technical (技術的)
- **Authentication:** WebAuthn / passkeys; password fallback hashed with BCrypt. **CSRF** enabled (session-token model); **session rotation** on login; cookies `HttpOnly` + `SameSite=Lax` + `Secure` (env-gated).
- **Authorization / RBAC:** custom per-workspace roles (`owner`/`admin`/`member` + custom `workspace_role`, `V13`) over a catalog of fine-grained permissions, enforced on a single path via `@RequirePermission` → `AuthorizationManager`. Destructive/structural ops are permission-gated and CI-backstopped (`RbacEnforcementArchTest`).
- **Transport:** HSTS (1y, `includeSubDomains`) + a restrictive Content-Security-Policy (`default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`) in `SecurityConfig`.
- **Secrets:** externalized to environment (`CONNEX_DB_*`, bootstrap credentials); local Docker passwords are kept in untracked `backend/.env`.
- **Database transport:** non-dev startup requires `CONNEX_DB_URL` to use MySQL Connector/J verified TLS (`sslMode=VERIFY_CA` or `sslMode=VERIFY_IDENTITY`). Local `dev` and `test` profiles may explicitly use plaintext Docker MySQL.
- *In progress:* at-rest encryption + customer-managed key (CMK) — [#92]; operational rotation of any database credentials that reused old committed local defaults — [#88]; session idle/absolute timeouts + WebAuthn step-up for sensitive ops — [#98]; brute-force/rate-limit protection — [#80].

### 2.5 External-environment grasp (外的環境の把握)
- SaaS personal data is stored in **Japan**. Where any handling involves a party outside Japan (e.g. a future AI provider under BYOP, [#94]), we identify the country and take account of its data-protection framework, and handle it under APPI Art. 28. No third-party AI/enrichment/analytics/email integrations send personal data externally today.

## 3. Data lifecycle

- **Categories handled:** account/profile (name, email, passkeys, role), usage/technical (IP, user-agent, session, audit events), and — under entrustment — customer CRM content (contacts, employment history, activities, notes, relationships). Free-text notes and untyped custom fields are an uncontrolled channel for 要配慮個人情報; governance is tracked in [#222].
- **Retention & deletion:** personal data kept only as long as necessary; per-tenant retention policy, export, and teardown are tracked in [#105]. Deletion is currently hard-delete per record.
- **Data-subject requests (開示等):** handled per [APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md](APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md), [#221]; where Connex is only the entrustee, requests are referred to the customer.

## 4. Subprocessors

Cloud infrastructure (AWS, `ap-northeast-1`) today; a customer-designated AI provider under BYOP in future ([#94]), where the customer holds the provider contract and is the data exporter. Current list is maintained in the DPA and available to customers on request.

## 5. Country of processing

Japan (`ap-northeast-1`). Any change is treated as a cross-border transfer and reflected here and in the Data Disclosure page before it takes effect.

## 6. How to reach us / report a vulnerability

Security & personal-information contact: {{PRIVACY_CONTACT_EMAIL}}. Please report suspected vulnerabilities or personal-data incidents here; we triage per the breach-response runbook.

---

### Roadmap references
Tracked under the security roadmap [#87] and the APPI pathway [#224]. Key open items: [#97] org boundary close-out · [#89] read-scoping backstop · [#92] CMK · [#91] audit tamper-evidence · [#98] step-up · [#80] rate-limit · [#99] CI scanning · [#106] third-party pentest.

[#87]: https://github.com/itkla/connex/issues/87
[#89]: https://github.com/itkla/connex/issues/89
[#91]: https://github.com/itkla/connex/issues/91
[#92]: https://github.com/itkla/connex/issues/92
[#93]: https://github.com/itkla/connex/issues/93
[#94]: https://github.com/itkla/connex/issues/94
[#97]: https://github.com/itkla/connex/issues/97
[#98]: https://github.com/itkla/connex/issues/98
[#80]: https://github.com/itkla/connex/issues/80
[#99]: https://github.com/itkla/connex/issues/99
[#104]: https://github.com/itkla/connex/issues/104
[#105]: https://github.com/itkla/connex/issues/105
[#106]: https://github.com/itkla/connex/issues/106
[#221]: https://github.com/itkla/connex/issues/221
[#222]: https://github.com/itkla/connex/issues/222
[#223]: https://github.com/itkla/connex/issues/223
[#224]: https://github.com/itkla/connex/issues/224
