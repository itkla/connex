# Business Card OCR Backend Contract

This document covers the backend admission, OCR routing, provider fallback, and idempotent import boundary for business-card scanning. The sidecar runtime itself is governed by `ocr/AGENTS.md`.

## Upload admission

Business-card endpoints accept only bounded, fully decoded JPEG, PNG, or WebP image bytes through the shared image-admission service.

Preserve:

- entitlement, workspace permission, and principal/global admission checks before multipart parsing;
- bounded request/content size and complete image decode;
- pre-decode sample-depth/channel checks and bounded dimensions/pixel/frame limits;
- global decode-slot and estimated working-memory admission;
- metadata-free canonical re-encoding through the capped seek-capable output so oversized encodings are refused before unbounded allocation;
- generic errors/logs that never include card bytes or recognized contact text.

Do not add remote-image fetching, filesystem-path input, or a second decoder that bypasses `ImageDecodeAdmissionService`.

## Local sidecar

The local PaddleOCR sidecar remains the preferred scan path.

- It is private and bearer-authenticated.
- Runtime model downloads and sidecar remote fetches remain impossible.
- Outside `dev`, use HTTPS or the explicitly configured single-label private plaintext host on an isolated private network.
- Scan-time fallback decisions join/start the bounded local-first readiness wait before declaring Paddle unavailable. Availability polling remains non-blocking.
- Never log sidecar tokens, input bytes, or recognized text.

Read `ocr/AGENTS.md` before changing the sidecar protocol, networking, models, resource limits, or supervisor behavior.

## AI-provider fallback

Provider fallback is allowed only when local OCR is unavailable under the established readiness policy and all AI gates pass.

- The actor must have `Permission.AI_USE`.
- The organization provider configuration must be enabled, complete, no-training-attested, and verified image-capable.
- The metadata-free canonical JPEG crosses the shared `AiInputImage` boundary and its conservative byte/dimension envelope.
- Readiness accepts only reviewed image-capable model families and explicitly supported Vertex model/location combinations.
- Recheck the exact resolved provider snapshot immediately before egress.
- Provider adapters embed bytes directly; they never supply a fetchable URL.
- Provider output is review input, not an unreviewed authoritative contact mutation.

The broader masking/egress contract lives in `docs/backend/AI_SECURITY.md`.

## Security filter and idempotency

Confirmed imports require a caller-retained UUID `Idempotency-Key`.

Before multipart parsing, the security filter validates the key, checks scan entitlement/workspace permissions, and applies principal/global admission/throttling. `BusinessCardService` retains tenant/reservation validation at the service boundary.

Import claims, request fingerprints, and result ids remain workspace-scoped in the existing request table so retries cannot create duplicate records or replay a different payload. `created_by_user_id` remains non-null and ownership checks fail closed.

Reserve/status admission happens before mapper access; status uses the established non-locking read. Do not move expensive body parsing or OCR ahead of admission/ownership checks.

## Duplicate review and provenance

OCR-populated contact/company review uses the same canonical duplicate-review boundary as manual creation/imports. Preserve:

- complete current identity fields at final review;
- workspace-bound one-use proof semantics;
- locked final recheck before persistence;
- no automatic attachment of weak/shared/ambiguous/truncated candidates;
- `BUSINESS_CARD` identity/import provenance;
- the dedicated business-card import API rather than converting the flow into generic manual create calls.

Read `docs/backend/DUPLICATE_REVIEW.md` and `docs/backend/LOCKING.md` before changing persistence or matching behavior.

## Review checklist

- Security/admission executes before multipart parsing.
- Decode and canonical-encode memory/output are bounded.
- No bytes/text/PII/token enters logs.
- Local sidecar remains private, authenticated, and local-first.
- Provider fallback requires `AI_USE`, attested verified config, `AiInputImage`, and immediate pre-egress revalidation.
- Idempotency key cannot be replayed across workspaces or payloads.
- Ownership/status checks fail closed.
- Duplicate final review/recheck and OCR provenance remain intact.
- Security and resource-failure behavior receive focused review.
