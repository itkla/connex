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

- Never expose a principal id or servlet session id for client request correlation.
- Preserve CSRF and secure-cookie posture outside the documented local-development profile.
- Editing organization security/provider configuration remains organization-admin and recent-authentication/step-up gated where the existing domain requires it.
- WebAuthn/auth changes are high-risk and must exercise login/session rotation, CSRF, logout/revocation, and failure behavior rather than only the happy path.

## Tenant isolation and RBAC

`docs/MULTITENANCY_PLAN.md` is authoritative for tenant resolution, catalog/control planes, lifecycle routing, support-journal attribution, export/teardown, and residual verification.

Every tenant-owned query uses server-resolved tenant/workspace scope; every endpoint follows the owning domain's RBAC pattern. Sharing/permission changes require explicit other-tenant and unauthorized verification.

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

## Review checklist

- Server-resolved identity/tenant/organization is authoritative.
- Admission and authorization run before expensive parsing/provider work where the established filter contract requires it.
- Secrets/PII/content never enter logs, audit rows, URLs, or third-party search/debug tools.
- Provider destination is revalidated immediately before egress and redirects remain disabled.
- Network I/O does not occur while metadata/aggregate locks are held unless the owning contract explicitly permits it.
- Idempotency/proof/lease/generation state is scoped to the correct workspace/organization and fails closed.
- Feature/capability/operator/customer gates remain independent and default to the documented posture.
- Relevant security, tenancy, failure, and cross-workspace tests pass.
