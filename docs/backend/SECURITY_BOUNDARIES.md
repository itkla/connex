# Backend Security Boundaries

This document is the entry point for backend work that crosses authentication, authorization, sensitive data, uploads, provider egress, or other fail-closed boundaries. It is intentionally a router plus the rules shared across those subsystems; detailed protocols live in the linked contracts and owning tests.

## Shared rules

- Treat request data, provider responses, uploaded bytes, external identifiers, and connector metadata as hostile at their boundary.
- Never log passwords, tokens, session identifiers, WebAuthn material, provider credentials, request/response content, uploaded bytes, recognized text, personal contact data, or raw third-party payloads.
- Never weaken CSRF, session rotation, tenant routing, RBAC, step-up authentication, capability flags, admission limits, idempotency, or audit behavior to simplify a feature.
- Provider/network I/O uses the subsystem's bounded, redirect-free, address-validated transport and normally occurs outside database transactions.
- Security decisions come from server-resolved identity, workspace/organization context, and current locked state—not caller-supplied ids or stale client/pre-lock snapshots.
- Security-sensitive changes require security-focused review; concurrency/locking changes additionally follow `docs/backend/LOCKING.md`.

## Authentication and session identity

All authentication methods continue through `AuthService.establishAuthenticatedSession` so the servlet session is rotated and the client receives the non-authorizing opaque request identity exposed by `/api/auth/csrf`.

[Privileged MFA](../PRIVILEGED_MFA.md#confinement-recheck-during-locked-workspace-authorization)
defines account-wide filter confinement, the workspace-local recheck at locked authorization,
shared exemptions, browser/PAT refusal contracts, deferred audit evidence and the precise
in-flight residuals. Preserve the same-connection, root-first order in
[LOCKING.md](LOCKING.md#servlet-filter-confinement-recheck-g1g5).

- Never expose a principal id or servlet session id for client request correlation.
- Preserve CSRF and secure-cookie posture outside the documented local-development profile.
- Editing organization security/provider configuration remains organization-admin and recent-authentication/step-up gated where the existing domain requires it.
- WebAuthn/auth changes are high-risk and must exercise login/session rotation, CSRF, logout/revocation, and failure behavior rather than only the happy path.

## Tenant isolation and RBAC

`docs/MULTITENANCY_PLAN.md` is authoritative for tenant resolution, catalog/control planes, lifecycle routing, support-journal attribution, export/teardown, and residual verification.

Every tenant-owned query uses server-resolved tenant/workspace scope; every endpoint follows the owning domain's RBAC pattern. Sharing/permission changes require explicit other-tenant and unauthorized verification.

Workspace teams are tenant-plane configuration. Reads require active workspace membership; every mutation requires the grantable `TEAM_MANAGE` permission and revalidates it from locked authorization rows before locking or changing a team. Team seats may name only active members of the same workspace. Path-addressed membership removal performs preliminary authorization against the path workspace before resolving its placement, then pins that workspace's catalog and tenant identity before opening the transaction, regardless of the active-header workspace. Active removal, pending-invitation decline, leave, fresh re-invitation, and account erasure delete team seats and clear manager references in the routed catalog without treating those configuration references as authored content.

### Export authority

`EXPORT_DATA` is additive to each domain's existing authority, visibility, privacy exclusions,
and recent-passkey requirements. Workspace built-in owner/admin/member roles use independent
export flags on their workspace; custom roles use their explicit permission rows. Organization
owner/admin exports use organization export flags, independently of any active workspace.
Only un-overlaid built-in workspace administrators/owners can change built-in export policy,
under existing member-management locks and recent authentication. Organization policy mutations
retain account → organization → membership lock order. Policy administration remains available
after self-revocation, while custom-role grants keep their existing permission ceiling. Enabling
owner exports must also leave an active, unreserved owner able to restore the proposed owner
bundle. The policy change refuses under owner/overlay locks instead of silently adding export
grants to custom roles; restore a built-in owner before retrying.

| Export operation | Additional retained authority |
| --- | --- |
| GET `/api/exports/persons`, `/companies`, `/products`, `/deals`; POST `/api/exports/deals/segment` | Active workspace membership, scoped filters/privacy exclusions; existing step-up |
| POST `/api/reports/{id}/export.csv`, `/snapshots/{snapshotId}/export.csv`, `/export-completion` | `REPORT_READ`, report visibility and underlying domain permissions, step-up |
| POST and PUT `/api/reports/{id}/schedule` | Workspace `EXPORT_DATA` plus `REPORT_UPDATE`, report/domain read permissions, active eligible recipients, existing privileged-account step-up; locked grants are rechecked before saving |
| Unattended report delivery | The saved run-as member's current workspace `EXPORT_DATA` and report/domain read permissions, checked before generation and again before email enqueueing; recipients keep their report/domain read requirements |
| GET `/api/audit/export` | `AUDIT_READ`, bounded authorized scope, step-up |
| GET `/api/orgs/{orgId}/audit/export` | Organization admin/owner, bounded organization-only audit scope, step-up |
| POST and GET `/api/orgs/{orgId}/workspaces/{workspaceId}/export` | Organization admin/owner, target-workspace lifecycle, recent authentication at grant issuance, session-bound one-use grant at redemption |
| POST `/api/campaigns/{id}/exports` | `CAMPAIGN_MANAGE`, `CONSENT_MANAGE`, consent/visibility/provider fences and step-up; status and reconciliation remain separately authorized |
| POST `/api/orgs/{orgId}/support-bundle` | Organization admin/owner and recent authentication; entity-filtered workspace events also require that workspace's `AUDIT_READ` and `EXPORT_DATA` |

Workspace ZIP issuance and redemption revalidate organization export policy under the existing
organization root lock before grant/lease writes. Locked workspace authorization reads current
policy with `FOR SHARE`; nonlocking snapshot authorization retains its consistent-read semantics.
Ordinary attachments/document artifacts and statutory data-subject disclosure retain their own
access/privacy contracts; they are not bulk-export endpoints. Existing export completion audit
semantics and passkey step-up are unchanged. The current release-wide rollback floor is control V248;
retain the [V247 invitation-expiry cutover](../UPGRADING.md#historical-pending-invitation-expiry-control-v247),
then follow the [V248 export-permission cutover](../UPGRADING.md#export-data-permission-control-v248).

Schedule reads and deletion retain their existing report permissions; withdrawing export access
does not prevent stopping a saved delivery channel. A withdrawn run-as export grant skips the
occurrence, advances its next run, records the reason in the audit log, and records the
`export_permission_withdrawn` job phase. The enqueue-time check also catches withdrawal during
snapshot generation. It does not cancel mail already queued after a successful final check.

### Workspace invitation disclosure

Both email invitation creation and the compatibility member-add route return the same token
invitation for every address. Creation does not resolve recipient profiles to choose its response. Before acceptance,
administrators see the addressed email, requested role, invitation status, expiry and revocation
control; account ids, names, usernames, avatars and verification/account state are withheld.
Member rosters, organization layout rosters, member search (including global user search) and
reference hydration expose only active memberships. Profile-returning role mutations also require an active target.
Authored reference labels for historical pending grants remain internal to mention persistence and
assistant masking. Reader hydration and write-response reference projections filter pending targets;
masking provenance never resolves or returns the recipient's account profile.

Control V247 gives historical pending-membership grants a fixed deadline thirty days after its
successful installation. NULL deadlines use that UTC cutover deadline until bounded runtime pages
persist it. Recipient pending lists, invitation inbox listing/counts/direct lookup and the final
activation claim require a future effective deadline; missing successful migration history fails
closed. See [the cutover and backfill contract](../UPGRADING.md#historical-pending-invitation-expiry-control-v247). New internal pending grants expire after fourteen days. Administrators manage
them as address-only invitations using opaque membership-generation references and see the actual
deadline, including after expiry so they can resend. Revocation rechecks pending status and the same generation under membership-removal locks,
uses a generic audit description and cannot remove a membership that has since accepted. A historical
owner-role grant retains the owner-only revocation check. Resending
supersedes the historical pending grant in the token-creation transaction. These historical grants
retain their historical format without an inviter label; the new contract prevents new account probes
but does not erase information disclosed before this change or conceal the historical invitation format.
Invite-link redemption and removed-member rejoin policy are unchanged.

Invitation creation also reports `emailDelivery`, uniformly for every addressed email: `ACCEPTED`
means the SMTP server accepted submission, not confirmed inbox delivery; `SKIPPED` means no usable
sender was available; `FAILED` means preparation or submission failed. None of these outcomes
resolves a recipient's Connex account. The command refuses an enclosing transaction before
invitation writes, then submits mail synchronously after its own transaction fully releases its
resources. There is no memory-only dispatch queue; every completed creation request reports the submission
outcome. The creator can share
the returned link or explicitly replace the invitation after a failed submission. The mail service
routes the target workspace, and SMTP starts after sender-resolution locks are released.

Authentication return destinations use the same strict relative-path contract on the frontend
and backend. Starts carrying `redirect` require exactly `Sec-Fetch-Site: same-origin`; absent,
`none`, same-site and cross-site metadata are refused before registration/configuration reads.
Only enabled OAuth registrations and enabled SAML ceremonies can admit a destination. A disabled
SAML start cannot mutate an OAuth attempt, including on a social-only deployment.
All attempt allocations also refuse cross-site subresource requests: `cross-site` metadata
requires both `Sec-Fetch-Mode: navigate` and `Sec-Fetch-Dest: document`. This guard covers every
supported start method, SAML's query-form start, and repository allocation from other paths.
Ordinary starts without destinations retain top-level cross-site and headerless-client support.

Full authorization requests and their destinations are saved together in one immutable session
map keyed by OAuth state or SAML RelayState. SAML records retain the AuthnRequest id for
framework response correlation. Matching also checks the callback's registration. Unknown,
duplicate or mismatched callback state does not remove another attempt or produce navigation.
Only matching repository removal publishes request-local navigation before session replacement;
success, linking and failure handlers never read a last-destination session slot.

Attempts expire after ten minutes. Each save prunes expired entries, refuses duplicate live
identities, evicts the earliest-expiring entry when full, and persists a fresh immutable map with
at most four entries. Capacity never refuses a fresh start. Each removal also copies, prunes and
replaces the map. No request can persist more than four entries; this bound does not rely on
locking per-request Spring Session wrappers. Concurrent detached snapshots may drop another
in-flight entry; its callback fails closed, and no destination or authorization is rebound.
Evicted attempts fail closed in the same way. Legacy per-attempt attributes and singleton return
state are ignored. Missing or invalid destinations retain the existing
dashboard/onboarding fallback, and no caller-supplied origin becomes the redirect origin.

Self-service registration accepts `skipInitialWorkspace` to suppress optional personal workspace
creation while returning to invitation acceptance. This preference grants no workspace membership
or invitation authority, changes no signup or verification policy, and is ignored by administrator
account creation. Existing purpose-bound invitation cookies retain their narrow API paths.

## AI and model-provider egress

Read `docs/backend/AI_SECURITY.md` before changing AI gates, masking, prompts, media admission, provider adapters, endpoint validation, budgets, streaming, assistant tools, or transcript collaboration.

No feature calls a provider directly or constructs an unmasked ad-hoc prompt outside the established invocation/masking boundary.

## Business-card scanning and OCR

Read `docs/backend/BUSINESS_CARD_SCANNING.md` before changing upload admission, local OCR readiness/fallback, multimodal provider egress, import idempotency, or recognized-data handling. The sidecar runtime contract additionally lives in `ocr/AGENTS.md`.

## Imports and duplicate review

Read `docs/backend/IMPORTS_AND_DUPLICATE_REVIEW.md` before changing identity matching, duplicate candidates, preview/acknowledgement proofs, CSV commits, OCR-reviewed creation, or interaction-history backfill.

Those proofs and final locked rechecks are integrity/security boundaries, not optional UX hints.

## Connected capture

`docs/CONNECTED_CAPTURE.md` is authoritative. Connection availability, global scheduling authorization, and provider ingestion authorization are separate fail-closed gates. Preserve fixed-host bounded transport, generation/lease-safe refresh, no provider I/O in database transactions, tenant-routed commits, inert backfill, immediate policy pause, disconnect-with-retention, and separately authenticated current-workspace erasure that remains available without an active connection or ingestion flag.

## Commercial-document signatures

`docs/ESIGNATURE.md` is authoritative. Preserve capability/feature gating, callback authentication before workspace routing, membership/role authorization before record locks, the documented record lock order, and the prohibition on logging tokens, recipient addresses, document content, or raw acceptance evidence.

## Uploads and object storage

- Upload/image endpoints retain bounded content-length and full-decode admission before expensive work.
- Managed images/business cards preserve metadata-free canonical re-encoding and global/estimated-memory admission.
- User objects are stored through backend `ObjectStorage`, never frontend-public paths.
- Read `docs/backend/OBJECT_STORAGE.md` before changing object writes, replacement, cleanup, provider identity, or legacy upload migration.

## Support and audit data

Audit/support events contain only their approved metadata envelope. Never add raw paths, query strings, headers, bodies, labels, record content, provider payloads, or caller-controlled correlation values where the owning contract excludes them.

Audit records prove that an operation occurred; they do not become a shadow copy of customer data.

[AUDIT.md](AUDIT.md) defines the execution-origin context keys, deferred capture, and disclosure
contract. Automation provenance is separate from the member or system principal acting on its behalf.

## Review checklist

- Server-resolved identity/tenant/organization is authoritative.
- Admission and authorization run before expensive parsing/provider work where the established filter contract requires it.
- Secrets/PII/content never enter logs, audit rows, URLs, or third-party search/debug tools.
- Provider destination is revalidated immediately before egress and redirects remain disabled.
- Network I/O does not occur while metadata/aggregate locks are held unless the owning contract explicitly permits it.
- Idempotency/proof/lease/generation state is scoped to the correct workspace/organization and fails closed.
- Feature/capability/operator/customer gates remain independent and default to the documented posture.
- Relevant security, tenancy, failure, and cross-workspace tests pass.

## Ownership consent (#1534 slice 4o)

Existing-scope owner promotions create offers; acceptance grants ownership. The HTTP, MFA, locking, control-plane, notice and cleanup contracts are in [Ownership offers](OWNERSHIP_OFFERS.md). Founding, admin changes and last-owner checks retain their existing behavior. The self-offer card is authoritative on every catalog layout; ordinary inbox delivery retains the existing cross-catalog limitation.

## Business refusal codes

Safety refusals use the existing exception `getCode()` and `GlobalExceptionHandler` envelope.
The frontend selects catalogued EN/JA descriptions through `errorMessages.ts`; unknown codes
retain status-based safe copy and never surface exception prose. Existing last-owner codes remain
unchanged. Invitation domain checks return `INVITE_EMAIL_DOMAIN_NOT_ALLOWED` without disclosing
allowlist contents. Passkey removal distinguishes `LAST_PASSKEY_REQUIRED` from
`LAST_SUFFICIENT_PASSKEY_REQUIRED`. Unverified disclosure returns
`DISCLOSURE_IDENTITY_VERIFICATION_REQUIRED` only after organization authorization and request lookup.
Organization grants use `ORG_MEMBER_ELIGIBILITY_REQUIRED` for every ineligible target, including
unknown addresses and outsiders; explanations describe prerequisites without asserting account
existence. Invitation creation retains its uniform email-bound flow and does not resolve recipient
accounts to provide more specific advice.
