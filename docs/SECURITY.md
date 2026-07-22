# Connex — Security Posture & APPI 安全管理措置 Disclosure

> **Purpose.** One document serving two audiences: the **enterprise security-posture artifact** buyers ask for during procurement, and the **APPI 安全管理措置 (security control measures) disclosure** referenced from our public [Data Disclosure page](../frontend/app/disclosure) (APPI Art. 32). Issue [#104].
> **Status: living document.** It describes the architecture as it actually stands today and marks in-progress items with their tracking issue. It is deliberately honest about what has **not** landed yet — do not read an in-progress item as a shipped control.
> **Not legal advice.** Confirm the APPI framing and any customer commitments with counsel and the signed DPA ([APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md), [#93]).
> **Owner:** {{SECURITY_OWNER}} · **Last reviewed:** 2026-07-09

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
- **Authentication:** WebAuthn / passkeys; password fallback hashed with BCrypt. **CSRF** enabled (session-token model); **session rotation** on login. The session cookie is `HttpOnly`, `Secure` by default, and `SameSite=Lax` by default; deployments can set stricter or SAML-compatible cookie flags via env. The frontend-readable workspace selector cookie contains only the active workspace id, is `Secure` by default, and is always revalidated against server-side membership.
- **Authorization / RBAC:** custom per-workspace roles (`owner`/`admin`/`member` + custom `workspace_role`, `V13`) over a catalog of fine-grained permissions, enforced on a single path via `@RequirePermission` → `AuthorizationManager`. Destructive/structural ops are permission-gated and CI-backstopped (`RbacEnforcementArchTest`).
- **Transport:** HSTS (1y, `includeSubDomains`) + a restrictive Content-Security-Policy (`default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`) in `SecurityConfig`.
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

## 6. How to reach us / report a vulnerability

Security & personal-information contact: {{PRIVACY_CONTACT_EMAIL}}. Please report suspected vulnerabilities or personal-data incidents here; we triage per the breach-response runbook.

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
