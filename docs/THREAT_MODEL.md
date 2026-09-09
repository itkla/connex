# Connex architecture and data-flow threat model

> CHK-004 / SEC-7 · Initial source analysis: **2026-09-08** · Baseline: `c4a7a276f`
> **Status:** source-reviewed threat analysis; owner review and operational validation pending.
> Accountable Security Owner: **Hunter Nakagawa, Founder**, as already designated in
> [SECURITY.md](SECURITY.md). This document records an analysis, not his approval of residual risk.

## Scope, assets and architecture assumptions

The protected assets are CRM content (including sensitive free text and card pixels), tenant and
record authorization state, account/session/passkey material, integration credentials, emailed
bearers, object bytes, audit evidence, backups and operational key custody. Attackers include
anonymous internet clients, a malicious authenticated member of another workspace/organization,
a compromised member/browser, hostile uploads/provider responses, and a compromised privileged
operator or external provider. Confidentiality, integrity, availability and attributable decisions
matter at each boundary; an org-wide disclosure has greater blast radius than one record.

The organization is the customer/breach boundary; workspace membership and record ownership/sharing
remain authorization boundaries even within one organization. Global identity and deliberately
cross-workspace user experiences do not grant cross-org CRM access. Hosted application processes
see plaintext; storage encryption cannot constrain a compromised running backend or its operator.

Read with [multitenancy architecture](MULTITENANCY_PLAN.md),
[deployment editions](DEPLOYMENT_EDITIONS.md), [encryption guarantee matrix](ENCRYPTION_GUARANTEE_MATRIX.md),
[security boundaries](backend/SECURITY_BOUNDARIES.md) and [deployment topology](DEPLOYMENT.md).
The multitenancy plan contains historical proposals as well as shipped updates: code evidence below
wins for implementation, while the encryption matrix governs commercial availability. Edition
configuration exists; dedicated placement/silo code does not prove a sellable, deployed CMK tier.

The deployed Compose path is browser → configured Cloudflare edge → Caddy → Next.js for HTML,
while **Caddy routes `/api/*` directly to Spring** [M01]. The alternative Next-served path is
browser → Next.js `/api` rewrite → Spring [M03]. Do not draw Next's HTML proxy as an API security
filter: its matcher excludes `/api` [M02]. Cloudflare activation, TLS termination and origin firewall
state require operator evidence under [EDGE_DEFENCE.md](EDGE_DEFENCE.md); none was tested here.

Each flow below has an explicit source review date. The M identifiers resolve to the verified-file
register. They credit only the stated behavior, not an exhaustive endpoint audit or a runtime pass.
Residual priority is a triage proposal: **High** means potential cross-tenant disclosure, credential
compromise, destructive loss or unavailable incident response; **Medium** means a bounded or
configuration-dependent exposure. No listed residual is newly accepted by this document.

## F01 — Browser, public edge, frontend and API

**Boundary/data:** hostile browser and network → edge/proxy → HTML runtime or backend; cookies,
request bodies, CRM responses and security headers cross different hops. **Source reviewed: 2026-09-08.**

**Attack paths:** forge forwarding headers to evade source-based controls; bypass Cloudflare via an
exposed origin; send oversized/slow requests; exploit XSS or cross-site requests; treat a forged
JSESSIONID as proof of login; reach APIs without passing the frontend.

**Existing mitigations:** Caddy declares request/header/time bounds and rewrites upstream client-IP
headers [M01]. Next's proxy redirects protected HTML paths based on cookie presence and wires a
per-request nonce/CSP [M02]; it does not validate a session. Spring's authenticated fallback,
explicit public routes, CSRF configuration and HSTS apply at the API boundary [M04].

**Residual / treatment — High:** WAF adoption is not activation evidence; Caddy listens on port 80
and its HSTS is operator-enabled, so neither TLS nor universal HSTS follows from this file alone.
Wrong trusted-proxy ranges, a publicly reachable origin/backend or CSP rollback can undermine these
layers. Owner must retain deployed edge/header and origin-bypass tests from
[EDGE_DEFENCE.md](EDGE_DEFENCE.md) and [CSP](CONTENT_SECURITY_POLICY.md) before claiming protection.
An XSS running in an authorized origin can exercise the user's authority despite HttpOnly cookies.

## F02 — Authentication, sessions, passkeys, SSO and workspace selection

**Boundary/data:** unauthenticated client/IdP → authenticated account → selected workspace; assertions,
passkey responses, cookies and workspace selector. **Source reviewed: 2026-09-08.**

**Attack paths:** fix or steal a session; replay an SSO assertion into a victim browser; use another
account's passkey for step-up; forge a workspace selector; retain access after membership changes.

**Existing mitigations:** common session establishment replaces a differently bound session or
rotates an existing same-account session, then selects a server-side default workspace [M05].
Session cookie configuration is HttpOnly, Secure by default and SameSite=Lax by default [M06].
WebAuthnService delegates assertion verification to the configured relying-party operations and
checks that step-up resolves the current account [M07]. SSO success uses common session establishment
[M08]; the SAML validator rejects a missing InResponseTo [M04]. Workspace resolution checks live
membership, derives org/catalog on the server and clears request scope [M09].

**Residual / treatment — High:** cookie presence is not session validity. Passkey verification
still depends on correctly configured relying-party origins and the deployed IdP; this review does
not certify either. Password fallback/recovery, compromised IdP/mailbox, stolen authenticated
browsers and privileged recovery remain separate attack paths. CSRF has explicit pre-session,
webhook/native and enabled-SAML exceptions [M04], not universal coverage. Preserve the operational
recovery limitation and exercise [SECURITY.md](SECURITY.md#operator-verification-procedure);
re-review any authentication/recovery or cookie configuration change.

## F03 — Tenant isolation, owner scope, RBAC and sharing

**Boundary/data:** account and selected workspace → service permissions → mapper/tenant catalog →
CRM records; record owner workspace → share recipient workspace. **Source reviewed: 2026-09-08.**

**Attack paths:** submit another workspace or record ID; omit a SQL workspace predicate; misclassify
a new mapper; confuse a share's read visibility with ownership; leak context on a pooled thread.

**Existing mitigations:** membership-derived context and finally cleanup [M09]; classified statement
context checking [M10]; annotated permission calls delegate to WorkspaceService [M11]. Sharing
requires SHARE_MANAGE and an owned record, with target membership/org checks [M12]; share inserts
have an ownership and same-org SQL ceiling, and revocation is owner-anchored [M13].

**Residual / treatment — High:** TenantScopeInterceptor **does not rewrite SQL or add row filters**.
It checks classified statements, has exemptions, and in single-database mode off-request work does
not receive the same unresolved-context refusal. With routing enabled, off-request scoped statements
need resolved context or a catalog override [M10]. Correct mapper predicates and service checks are
still load-bearing; the sharing SQL is verified here, not every CRM query. Business assignee/owner
selection is not automatically an authorization boundary. Changes need mapper classification,
negative cross-tenant/RBAC checks and independent review under
[MULTITENANCY_PLAN.md](MULTITENANCY_PLAN.md), including owned-versus-shared writes.

## F04 — Upload, content inspection, malware scan, object storage and download

**Boundary/data:** member-selected hostile file → parser → scanner → managed object store → authorized
download/browser. **Source reviewed: 2026-09-08.**

**Attack paths:** MIME/extension spoofing, parser exploits, decompression/resource exhaustion,
malware, forged managed URLs, other-workspace object lookup and compromised storage/scanner.

**Existing mitigations:** UploadContentInspector validates server-selected purpose and metadata,
reads/inspects content and refuses timed-out inspection [M14]. The attachment path calls inspection,
then malware scan, then write; content lookup resolves metadata with the active workspace before
opening the managed object [M16]. Enabled scanning grants ScannedUpload only for CLEAN; infected,
unscannable and unavailable paths refuse it [M15]. See the detailed
[content-inspection](UPLOAD_CONTENT_INSPECTION.md), [malware](MALWARE_SCANNING.md) and
[object-storage](backend/OBJECT_STORAGE.md) contracts for format and lifecycle policy.

**Residual / treatment — High:** a CLEAN verdict is not proof of harmlessness. The baseline scanner
accepts a degraded CLEAN report while auditing stale signatures [M15]; this model does not credit
another lane's prospective lifecycle changes. Existing stored files are not thereby continuously
rescanned. Parser vulnerabilities and storage-admin access remain. Non-deployment scanning-disabled
mode returns a synthetic CLEAN proof [M15], so dev posture must never be deployment evidence.
Verify deployed scanner availability/signature freshness and storage access separately; re-review
new ingress purposes and download routes, not just the generic attachment path.

## F05 — AI masking and provider egress

**Boundary/data:** authorized CRM reads → prompt/token map in backend memory → external provider →
untrusted model output; integration secrets authorize external calls. **Source reviewed: 2026-09-08.**

**Attack paths:** prompt injection requesting unauthorized records/actions; unseeded identifiers in
free text; serialized-input leakage; hostile configured endpoints/SSRF; excessive calls; poisoned
provider responses; provider retention or compromised request memory.

**Existing mitigations:** AI feature/membership/AI_USE/workspace governance gate [M17]; enabled,
complete organization provider with no-training attestation and credentials readiness [M18]. The
invocation path resolves the org provider, scans serialized model input, reserves budget and uses
the selected adapter [M19]. Free text is normalized, injected token delimiters removed and screened
before identifier substitution [M20]. The reversible token map is in memory and its toString is
redacted [M21]. The leak scanner checks normalized raw and JSON-decoded text against the seeded
identifier dictionary [M22]. OpenAI-compatible, Azure, Bedrock and Vertex adapters construct their
respective protocol requests through the provider-attempt executor [M23] [M24] [M25] [M26].
OpenAI-compatible transport validates/pins the resolved address and disables redirects [M27]; the
fixed-provider transport bounds DNS/HTTP with a deadline, pins addresses and disables redirects [M28].

**Residual / treatment — High:** the invocation scan covers its serialized model input before
adapter-specific JSON construction; do not call it a byte-for-byte scan of every final HTTP body.
The dictionary scan ignores normalized identifiers shorter than four characters and has trusted
static-text collision exemptions [M22]. Unknown identifiers, semantic clues and model inference
can still disclose identity; the map is reversible and not anonymization or secure memory erasure.
UNMASKED mode is explicitly possible when deployment and current attestation permit it [M18].
Image pixels are a separate unmaskable disclosure path, detailed in
[AI_SECURITY.md](backend/AI_SECURITY.md) and [business-card scanning](backend/BUSINESS_CARD_SCANNING.md).
Attestation records customer assertions, not technical proof of provider deletion/no training.
Prompt injection is not solved by masking: keep tool authorization and response screening in the
linked AI contract under review for every new tool/provider. Re-review serialized fields, native
tools, streaming, destination/privacy changes and image use before rollout.

## F06 — Outbound mail and emailed acceptance/unsubscribe links

**Boundary/data:** backend → configured SMTP service → recipient mailbox/browser → public link
exchange → scoped domain mutation. CRM content and bearer credentials leave the tenant runtime.
**Source reviewed: 2026-09-08.**

**Attack paths:** SSRF through workspace SMTP, recipient compromise or forwarded links, bearer in
access/referrer logs, email-scanner GET causing acceptance, stolen grant, cross-tab confirmation
of a different flow, or token routing to the wrong organization.

**Existing mitigations:** MailService invokes the SMTP destination guard before constructing the
sender [M30]. Workspace-supplied hosts use port/address checks [M29]; the factory supports pinned
sockets and requires/checks TLS when STARTTLS/SSL is selected [M31]. OneTimeLinkFlowService resolves
purpose/browser-lineage-bound grants and checks the confirmation's flow identity [M32]; SQL matches
grant digest, exchange owner, purpose and unexpired time [M33]. Unsubscribe separates exchange,
preview and bound POST confirmation [M34], derives workspace from the delivery digest and applies
idempotent suppression/consent effects [M35]. Acceptance exchange throttles and locks/checks an
actionable recipient; later grant access is routed and purpose-checked [M36]. Cookie-authorized
acceptance/unsubscribe mutations are not in the CSRF exemptions [M04].

**Residual / treatment — High:** possession of an emailed bearer proves access to the link, not
legal identity. Forwarding, compromised mailboxes and recipient/browser extensions remain threats;
preview flow binding does not revoke a leaked original bearer. Trusted instance SMTP and explicitly
internal transports bypass public-address validation [M29]; the factory's TLS properties are
conditional, not proof of every deployment's encrypted mail hop. Default-catalog unsubscribe token
lookup does not resolve dedicated-placement links [M35]. Use the
[signature contract](ESIGNATURE.md) and [security boundaries](backend/SECURITY_BOUNDARIES.md), verify
recipient/transport policy, and keep dedicated-placement enablement gated. Mail submission is not
proof of receipt; no operational delivery claim is made here.

## F07 — Private OCR sidecar

**Boundary/data:** backend → private CPU-only OCR process → recognized text returned for review;
card bytes contain direct identifiers. **Source reviewed: 2026-09-08.**

**Attack paths:** invoke the sidecar directly, steal its shared service token, exhaust decoding or
inference resources, exploit image/model parsers, or escape through an incorrectly exposed network.

**Existing mitigations:** `/ready` and `/v1/ocr` check the bearer using constant-time comparison;
OCR POST checks declared byte length, supported media type and readiness and takes a nonblocking
inference permit [M37]. Compose puts OCR only on the internal isolated OCR network, publishes no
host port, and configures read-only filesystem, dropped capabilities, no-new-privileges and CPU,
memory/PID limits [M38]. These are configuration/code controls, not a reachability test.

**Residual / treatment — High:** `/health` exposes readiness/activity/generation without bearer
inside that network [M37]. The sidecar has no user/workspace RBAC: backend admission and private
network/token custody are its boundary. Host/container compromise can read pixels and tokens.
Actual egress isolation and availability require the runtime smoke in [DEPLOYMENT.md](DEPLOYMENT.md);
no such smoke ran in this documentation lane. Changes to OCR models, networks, limits or external
fallback trigger review with [business-card scanning](backend/BUSINESS_CARD_SCANNING.md).

## F08 — Background jobs, rules and scheduled work

**Boundary/data:** scheduler/outbox/database state → non-request execution → tenant services/provider
side effects. Stored rule definitions and run-as identities can outlive their author's access.
**Source reviewed: 2026-09-08.**

**Attack paths:** stale member authority, cross-catalog job enumeration, leaked thread context,
recursive rule triggers, duplicate scheduled side effects and noisy-tenant starvation.

**Existing mitigations:** AutomationExecutor installs an explicit principal/workspace placement,
marks automation against recursive triggers and restores prior contexts in finally [M39].
RuleScheduler enumerates within each catalog then runs within each workspace [M40]; legacy rule
principal resolution checks the run-as member's current role/account before execution [M41].
WorkflowRuntimeScheduler bounds sweeps and workspace quantum and dispatches claimed work [M42].

**Residual / treatment — High:** these are verified execution paths, not proof that every scheduler
uses them. The interceptor's off-thread exception makes bypassing explicit scope particularly
hazardous [M10]. System-actor execution is intentional elevated service behavior, not a customer's
session. Duplicate external effects after timeout and lease/claim races need subsystem-level tests;
a claim call alone does not prove exactly-once delivery. Review new jobs, principal changes and
retry semantics against [automation](backend/AUTOMATION.md),
[connected capture](CONNECTED_CAPTURE.md) and [locking](backend/LOCKING.md).

## F09 — Backup, restore and support bundles

**Boundary/data:** database/object media → operator backup files → restore target; organization
metadata → administrator export → support recipient. **Source reviewed: 2026-09-08.**

**Attack paths:** steal plaintext dumps, omit object volume or tenant schemas, alter backup and
checksum together, restore into production/wrong tenant, or over-disclose through support export.

**Existing mitigations:** full-backup scripts validate gzip and produce/check SHA-256 manifests
[M43]. The common library restricts permissions, validates checksums and guards protected/nonempty
restore targets unless deliberately overridden [M44]; full restore validates integrity before
restoring and reports row counts [M45]. Support export uses POST and attachment/no-store/security
headers, with same-org active workspace plus AUDIT_READ for entity slices [M46]. Its service checks
org admin and recent authentication before assembly, with admission and size/time bounds [M47].

**Residual / treatment — High:** these database scripts create **plaintext compressed dumps**, not
encrypted backups; object storage is separately backed up by the operator. Hashes detect corruption,
not authenticity against an attacker who can replace the manifest too. Force-overwrite is a real
operator override. Restore success is not a proven RPO/RTO, coverage, key recovery or tenant-safe
traffic cutover. Support metadata can still be sensitive; a zero recent-auth window weakens step-up
as disclosed in [SUPPORT_BUNDLE.md](SUPPORT_BUNDLE.md). Run deployment-specific drills and custody
checks under [BACKUP_RESTORE.md](BACKUP_RESTORE.md), [storage-encryption evidence](SAAS_STORAGE_ENCRYPTION_RUNBOOK.md)
and the [encryption matrix](ENCRYPTION_GUARANTEE_MATRIX.md); none was executed here.

## F10 — Deployment editions, silo model and operator plane

**Boundary/data:** operator/cloud/CI credentials and configuration → running fleet, control-plane
identity/placement registry, tenant catalogs, keys and support access. **Source reviewed: 2026-09-08.**

**Attack paths:** privileged insider or CI takeover, enable dev/internal escape hatches, misroute
placement/catalog, reuse a dirty pooled connection, promise customer-only keys while serving plaintext,
or lose the sole operator and recovery custody during an incident.

**Existing mitigations:** DeploymentProfileValidator requires a known edition outside dev/test/seeder
and refuses profile-specific forbidden settings (including SaaS internal-access opt-ins and silo
public API enablement) [M48]. TenantCatalogResolver refuses unsupported/unservable placement [M49];
TenantRoutingDataSource switches/reset catalogs and evicts on failures [M50]. These mechanisms reduce
routing errors; they do not grant permission to activate a new deployment tier.

**Residual / treatment — High:** the same artifact supports profile variants, but customer-managed
keys, dedicated databases and Connex-operated silos retain the availability/evidence limits in the
[encryption matrix](ENCRYPTION_GUARANTEE_MATRIX.md). A silo still has a Connex operator and backend
plaintext access; on-prem moves operator custody to the customer. Shared-fleet compromise can cross
application tenant boundaries. CI, cloud IAM, storage encryption, key recovery and real network
isolation were not operationally audited. The single owner/no-deputy risk acceptance dated 2026-08-13
and expiring 2027-02-13 remains valid as recorded in [SECURITY.md](SECURITY.md); mailbox receipt,
administrative recovery and emergency succession remain unverified. Follow its verification
procedure and [internal operations](INTERNAL_OPERATIONS_RUNBOOK.md), without inventing a custodian.

## Review ownership, cadence and records

Hunter Nakagawa owns review of this model as part of his existing security/runbook review duty.
A separate security-focused reviewer checks source claims and negative paths; the reviewer must be
identified in each completed review record. **No independent human approval is implied.** Review at
least every six months, aligned to the existing **2027-02-13** security review, then schedule the
next date explicitly. Changes to trust boundaries, tenant routing/sharing/RBAC, auth/recovery,
provider adapters/disclosure, upload parsers/scanners, OCR networking, emailed-link protocols,
job authority/retries, backup/restore/key custody or deployment profiles trigger review before
rollout. Incidents, penetration-test findings, critical vulnerabilities and owner/provider changes
trigger an immediate out-of-cycle review.

For each review record: date, reviewer and role, source commit/environment, flows examined, attack
scenarios, evidence commands/results, findings, risk owner, treatment/expiry decision and next review.
Link the PR/control issue and protected operational evidence location; do not copy secrets/PII.
A source review is distinct from a runtime exercise and owner risk acceptance. Never backfill a
periodic review that did not occur.

| Date | Reviewer / scope | Record and outcome | Next action |
|---|---|---|---|
| 2026-09-08 | Codex governance lane; source analysis of F01–F10 at c4a7a276f | Initial threat analysis and mitigation file register below; documentation-only checks in the lane report/PR; no runtime or historical periodic review claimed | Independent security review of this PR, then Hunter Nakagawa owner review and operational evidence/treatment decisions; scheduled review 2027-02-13 |
| 2026-09-08 | Independent Codex security reviewer (`governance_review`), separate agent context | No blocking findings in F01–F10 and selected source checks: edge/auth, tenant interception, upload/scanning, AI scan/dispatch, grants, automation, editions/routing, backup and support gates. Not an exhaustive second verification of all 50 entries; no runtime exercise, owner approval or risk acceptance | Hunter Nakagawa owner review and operational evidence remain pending; scheduled review 2027-02-13 |

CHK-004 now has a concrete per-flow source analysis. Its operating review cycle is newly documented;
owner approval, past periodic-review history and deployment verification remain unconfirmed. Treat
this as **ready for independent re-test**, not an automatic control pass.

## Verified mitigation file register

Every entry was checked against implementation source on 2026-09-08. Links are relative to this
file inside `docs/`. The claims above deliberately distinguish implemented behavior from deployment
settings, external evidence and residual risks. Documentation links provide contracts rather than
substituting for implementation evidence.

| ID | Verified mitigation / boundary | Implementation file |
|---|---|---|
| M01 | Edge request/header bounds, sanitized upstream client-IP headers and API routing | [Caddyfile](../deploy/Caddyfile) |
| M02 | HTML route cookie-presence redirect and nonce/CSP wiring; acceptance credentials stripped | [proxy.ts](../frontend/proxy.ts) |
| M03 | Same-origin API and SAML backend rewrite | [next.config.ts](../frontend/next.config.ts) |
| M04 | Authenticated fallback, explicit public exceptions, CSRF exceptions, HSTS, SSO chain and unsolicited-SAML rejection | [SecurityConfig.java](../backend/src/main/java/ooo/klae/connex/backend/config/SecurityConfig.java) |
| M05 | Session replacement/rotation and server-selected default workspace | [AuthService.java](../backend/src/main/java/ooo/klae/connex/backend/services/AuthService.java) |
| M06 | HttpOnly session cookie; Secure and SameSite defaults | [application.yml](../backend/src/main/resources/application.yml) |
| M07 | Passkey assertion verification delegation and same-account step-up check | [WebAuthnService.java](../backend/src/main/java/ooo/klae/connex/backend/webauthn/WebAuthnService.java) |
| M08 | SSO completion enters common authenticated-session path | [SsoAuthenticationSuccessHandler.java](../backend/src/main/java/ooo/klae/connex/backend/sso/SsoAuthenticationSuccessHandler.java) |
| M09 | Membership-derived workspace/org/catalog and request-context cleanup | [TenantResolutionInterceptor.java](../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantResolutionInterceptor.java) |
| M10 | Classified statement context backstop; explicit request/off-thread distinction | [TenantScopeInterceptor.java](../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantScopeInterceptor.java) |
| M11 | RequirePermission delegates to workspace permission enforcement | [RequirePermissionAuthorizationManager.java](../backend/src/main/java/ooo/klae/connex/backend/tenant/RequirePermissionAuthorizationManager.java) |
| M12 | Sharing requires permission, owned record and target membership/org checks | [ShareService.java](../backend/src/main/java/ooo/klae/connex/backend/services/ShareService.java) |
| M13 | Sharing SQL owner and same-org ceiling; owner-anchored revoke | [ShareMapper.xml](../backend/src/main/resources/mappers/ShareMapper.xml) |
| M14 | Purpose-selected content validation and inspection deadline | [UploadContentInspector.java](../backend/src/main/java/ooo/klae/connex/backend/storage/UploadContentInspector.java) |
| M15 | Only CLEAN yields scanned proof; infected/unscannable/unavailable refuse storage in enabled path | [UploadMalwareScanner.java](../backend/src/main/java/ooo/klae/connex/backend/storage/UploadMalwareScanner.java) |
| M16 | Attachment inspect→scan→write order; workspace-scoped content lookup | [AttachmentService.java](../backend/src/main/java/ooo/klae/connex/backend/services/AttachmentService.java) |
| M17 | AI feature, membership, AI_USE and workspace-governance gate | [AiFeatureGate.java](../backend/src/main/java/ooo/klae/connex/backend/ai/AiFeatureGate.java) |
| M18 | Organization provider enablement/no-training readiness and conditional unmasked mode | [AiProviderConfigService.java](../backend/src/main/java/ooo/klae/connex/backend/services/AiProviderConfigService.java) |
| M19 | Invocation gate, serialized-input leak scan, budget reservation and adapter dispatch | [AiInvocationService.java](../backend/src/main/java/ooo/klae/connex/backend/ai/AiInvocationService.java) |
| M20 | Free-text normalization, token-delimiter removal, special-care screen and masking | [MaskingEngine.java](../backend/src/main/java/ooo/klae/connex/backend/ai/masking/MaskingEngine.java) |
| M21 | In-memory token map and redacted toString | [MaskingContext.java](../backend/src/main/java/ooo/klae/connex/backend/ai/masking/MaskingContext.java) |
| M22 | Normalized known-identifier scan with minimum length and trusted-text exceptions | [OutboundLeakScan.java](../backend/src/main/java/ooo/klae/connex/backend/ai/masking/OutboundLeakScan.java) |
| M23 | OpenAI-compatible request construction through provider-attempt executor | [OpenAiCompatibleAdapter.java](../backend/src/main/java/ooo/klae/connex/backend/ai/provider/openai/OpenAiCompatibleAdapter.java) |
| M24 | Azure endpoint construction and request through provider-attempt executor | [AzureOpenAiAdapter.java](../backend/src/main/java/ooo/klae/connex/backend/ai/provider/azure/AzureOpenAiAdapter.java) |
| M25 | Closed Bedrock region resolution and provider-attempt executor | [BedrockAnthropicAdapter.java](../backend/src/main/java/ooo/klae/connex/backend/ai/provider/bedrock/BedrockAnthropicAdapter.java) |
| M26 | Vertex target component validation and provider-attempt executor | [VertexAdapter.java](../backend/src/main/java/ooo/klae/connex/backend/ai/provider/vertex/VertexAdapter.java) |
| M27 | OpenAI-compatible validated/pinned resolution and redirect-free client | [OpenAiCompatibleClient.java](../backend/src/main/java/ooo/klae/connex/backend/ai/provider/openai/OpenAiCompatibleClient.java) |
| M28 | Fixed-provider bounded DNS, pinned transport, cancellation and redirects disabled | [FixedAiProviderClient.java](../backend/src/main/java/ooo/klae/connex/backend/ai/egress/FixedAiProviderClient.java) |
| M29 | Workspace SMTP destination/port/address checks; explicit trusted/internal exceptions | [SmtpDestinationGuard.java](../backend/src/main/java/ooo/klae/connex/backend/mail/SmtpDestinationGuard.java) |
| M30 | Mail send invokes destination guard before constructing sender | [MailService.java](../backend/src/main/java/ooo/klae/connex/backend/mail/MailService.java) |
| M31 | SMTP pinned socket and TLS-required/identity-check properties when TLS selected | [JavaMailSenderFactory.java](../backend/src/main/java/ooo/klae/connex/backend/mail/JavaMailSenderFactory.java) |
| M32 | Purpose/browser-lineage grant resolution and preview flow binding | [OneTimeLinkFlowService.java](../backend/src/main/java/ooo/klae/connex/backend/services/OneTimeLinkFlowService.java) |
| M33 | Grant digest, exchange-owner, purpose and expiry SQL predicates | [OneTimeLinkFlowMapper.xml](../backend/src/main/resources/mappers/OneTimeLinkFlowMapper.xml) |
| M34 | Unsubscribe exchange, grant preview and bound POST confirmation | [DeliveryUnsubscribeController.java](../backend/src/main/java/ooo/klae/connex/backend/controllers/DeliveryUnsubscribeController.java) |
| M35 | Unsubscribe digest lookup, delivery-derived workspace and idempotent effect | [DeliveryUnsubscribeService.java](../backend/src/main/java/ooo/klae/connex/backend/services/DeliveryUnsubscribeService.java) |
| M36 | Acceptance rate-limited exchange, actionable locked recipient and routed grant checks | [DocumentAcceptanceService.java](../backend/src/main/java/ooo/klae/connex/backend/services/DocumentAcceptanceService.java) |
| M37 | OCR bearer comparison, byte limits, readiness and nonblocking inference admission | [server.py](../ocr/ocr_service/server.py) |
| M38 | OCR private network membership and container resource/privilege limits | [docker-compose.yml](../deploy/docker-compose.yml) |
| M39 | Explicit automation principal/placement, recursion marker and finally restoration | [AutomationExecutor.java](../backend/src/main/java/ooo/klae/connex/backend/services/AutomationExecutor.java) |
| M40 | Catalog/workspace-scoped schedule enumeration | [RuleScheduler.java](../backend/src/main/java/ooo/klae/connex/backend/services/RuleScheduler.java) |
| M41 | Rule run-as member re-resolution and execution under AutomationExecutor | [RuleEngineService.java](../backend/src/main/java/ooo/klae/connex/backend/services/RuleEngineService.java) |
| M42 | Bounded workspace/catalog sweep and claim dispatch | [WorkflowRuntimeScheduler.java](../backend/src/main/java/ooo/klae/connex/backend/services/WorkflowRuntimeScheduler.java) |
| M43 | Backup gzip/checksum integrity generation | [connex-backup-full.sh](../deploy/backup/connex-backup-full.sh) |
| M44 | Backup file permissions, checksum validation and target overwrite guards | [connex-backup-lib.sh](../deploy/backup/connex-backup-lib.sh) |
| M45 | Restore integrity validation before restore/row summary | [connex-restore-full.sh](../deploy/backup/connex-restore-full.sh) |
| M46 | Support export POST, workspace/org plus AUDIT_READ gate and response headers | [SupportBundleController.java](../backend/src/main/java/ooo/klae/connex/backend/controllers/SupportBundleController.java) |
| M47 | Support org-admin/recent-auth gate, admission and assembly caps | [SupportBundleService.java](../backend/src/main/java/ooo/klae/connex/backend/services/SupportBundleService.java) |
| M48 | Mandatory edition selection and forbidden posture settings | [DeploymentProfileValidator.java](../backend/src/main/java/ooo/klae/connex/backend/config/DeploymentProfileValidator.java) |
| M49 | Fail-closed organization placement-to-catalog resolution | [TenantCatalogResolver.java](../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantCatalogResolver.java) |
| M50 | Connection catalog switch/reset and eviction on failure | [TenantRoutingDataSource.java](../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantRoutingDataSource.java) |

[M01]: ../deploy/Caddyfile
[M02]: ../frontend/proxy.ts
[M03]: ../frontend/next.config.ts
[M04]: ../backend/src/main/java/ooo/klae/connex/backend/config/SecurityConfig.java
[M05]: ../backend/src/main/java/ooo/klae/connex/backend/services/AuthService.java
[M06]: ../backend/src/main/resources/application.yml
[M07]: ../backend/src/main/java/ooo/klae/connex/backend/webauthn/WebAuthnService.java
[M08]: ../backend/src/main/java/ooo/klae/connex/backend/sso/SsoAuthenticationSuccessHandler.java
[M09]: ../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantResolutionInterceptor.java
[M10]: ../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantScopeInterceptor.java
[M11]: ../backend/src/main/java/ooo/klae/connex/backend/tenant/RequirePermissionAuthorizationManager.java
[M12]: ../backend/src/main/java/ooo/klae/connex/backend/services/ShareService.java
[M13]: ../backend/src/main/resources/mappers/ShareMapper.xml
[M14]: ../backend/src/main/java/ooo/klae/connex/backend/storage/UploadContentInspector.java
[M15]: ../backend/src/main/java/ooo/klae/connex/backend/storage/UploadMalwareScanner.java
[M16]: ../backend/src/main/java/ooo/klae/connex/backend/services/AttachmentService.java
[M17]: ../backend/src/main/java/ooo/klae/connex/backend/ai/AiFeatureGate.java
[M18]: ../backend/src/main/java/ooo/klae/connex/backend/services/AiProviderConfigService.java
[M19]: ../backend/src/main/java/ooo/klae/connex/backend/ai/AiInvocationService.java
[M20]: ../backend/src/main/java/ooo/klae/connex/backend/ai/masking/MaskingEngine.java
[M21]: ../backend/src/main/java/ooo/klae/connex/backend/ai/masking/MaskingContext.java
[M22]: ../backend/src/main/java/ooo/klae/connex/backend/ai/masking/OutboundLeakScan.java
[M23]: ../backend/src/main/java/ooo/klae/connex/backend/ai/provider/openai/OpenAiCompatibleAdapter.java
[M24]: ../backend/src/main/java/ooo/klae/connex/backend/ai/provider/azure/AzureOpenAiAdapter.java
[M25]: ../backend/src/main/java/ooo/klae/connex/backend/ai/provider/bedrock/BedrockAnthropicAdapter.java
[M26]: ../backend/src/main/java/ooo/klae/connex/backend/ai/provider/vertex/VertexAdapter.java
[M27]: ../backend/src/main/java/ooo/klae/connex/backend/ai/provider/openai/OpenAiCompatibleClient.java
[M28]: ../backend/src/main/java/ooo/klae/connex/backend/ai/egress/FixedAiProviderClient.java
[M29]: ../backend/src/main/java/ooo/klae/connex/backend/mail/SmtpDestinationGuard.java
[M30]: ../backend/src/main/java/ooo/klae/connex/backend/mail/MailService.java
[M31]: ../backend/src/main/java/ooo/klae/connex/backend/mail/JavaMailSenderFactory.java
[M32]: ../backend/src/main/java/ooo/klae/connex/backend/services/OneTimeLinkFlowService.java
[M33]: ../backend/src/main/resources/mappers/OneTimeLinkFlowMapper.xml
[M34]: ../backend/src/main/java/ooo/klae/connex/backend/controllers/DeliveryUnsubscribeController.java
[M35]: ../backend/src/main/java/ooo/klae/connex/backend/services/DeliveryUnsubscribeService.java
[M36]: ../backend/src/main/java/ooo/klae/connex/backend/services/DocumentAcceptanceService.java
[M37]: ../ocr/ocr_service/server.py
[M38]: ../deploy/docker-compose.yml
[M39]: ../backend/src/main/java/ooo/klae/connex/backend/services/AutomationExecutor.java
[M40]: ../backend/src/main/java/ooo/klae/connex/backend/services/RuleScheduler.java
[M41]: ../backend/src/main/java/ooo/klae/connex/backend/services/RuleEngineService.java
[M42]: ../backend/src/main/java/ooo/klae/connex/backend/services/WorkflowRuntimeScheduler.java
[M43]: ../deploy/backup/connex-backup-full.sh
[M44]: ../deploy/backup/connex-backup-lib.sh
[M45]: ../deploy/backup/connex-restore-full.sh
[M46]: ../backend/src/main/java/ooo/klae/connex/backend/controllers/SupportBundleController.java
[M47]: ../backend/src/main/java/ooo/klae/connex/backend/services/SupportBundleService.java
[M48]: ../backend/src/main/java/ooo/klae/connex/backend/config/DeploymentProfileValidator.java
[M49]: ../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantCatalogResolver.java
[M50]: ../backend/src/main/java/ooo/klae/connex/backend/tenant/TenantRoutingDataSource.java
