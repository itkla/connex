# Business-Card Scanning Contract

This document is authoritative for the backend business-card upload, local OCR, AI fallback, and confirmed-import boundary. The OCR sidecar's own runtime contract lives in `ocr/AGENTS.md`.

## Upload admission

Business-card endpoints accept only bounded, fully decoded JPEG, PNG, or WebP input through the shared managed-image admission path.

Preserve `ImageDecodeAdmissionService` behavior:

- content-length and media-type admission;
- pre-decode sample-depth/channel checks;
- global decode-slot and estimated working-memory admission;
- complete decode with dimension/pixel/frame limits;
- metadata-free canonical re-encoding through the capped seek-capable output;
- refusal before unbounded allocation when the canonical output would exceed limits.

Scan entitlement, workspace permission, and principal/global admission run in the security filter before multipart parsing. Do not move expensive parsing ahead of those gates.

Never log uploaded bytes, recognized text, names, email addresses, phone numbers, headers, tokens, or provider payloads.

## Local OCR first

The private PaddleOCR sidecar is the preferred scan path.

- It is bearer-authenticated and never publicly routed.
- Runtime model downloads and remote-image fetching remain impossible.
- Outside `dev`, use HTTPS or the explicitly approved private single-label plaintext service binding on an isolated network.
- A scan-time fallback decision joins or starts the bounded `CONNEX_OCR_LOCAL_FIRST_WAIT` readiness probe before treating local OCR as unavailable.
- Availability polling remains non-blocking and does not create an unbounded request queue.

Sidecar endpoints, worker supervision, backpressure, resource limits, models, and benchmark behavior are defined in `ocr/AGENTS.md` and `ocr/benchmark/README.md`.

## AI fallback

AI fallback is permitted only when local PaddleOCR is unavailable according to the bounded readiness contract and every AI/media gate passes.

- The actor must have `Permission.AI_USE` in addition to scan entitlement/workspace permission.
- Use the organization's exact enabled/no-training-attested provider snapshot and recheck it immediately before egress.
- Use `AiInputImage` and the conservative multimodal byte/dimension/estimated-memory envelope.
- Send only the metadata-free canonical image.
- Provider readiness accepts only verified image-capable model families and the exact supported Vertex model/location pairs.
- Embed bytes directly; never give the provider a fetchable URL.
- The result is review input, not an autonomous record mutation.

The general model-provider boundary lives in `docs/backend/AI_SECURITY.md`.

## Confirmed import and idempotency

Confirmed imports require a caller-retained UUID `Idempotency-Key`.

Before multipart parsing, the filter:

- validates the key;
- checks scan entitlement and workspace permissions;
- applies principal/global throttling/admission.

`BusinessCardService` then performs tenant/reservation validation and persistence orchestration.

Claims, request fingerprints, status, and result ids remain workspace-scoped in `business_card_import_request` so retries cannot:

- create duplicate records;
- replay the key against a different payload;
- read another workspace's result;
- claim work owned by another user.

`created_by_user_id` remains non-null and ownership checks fail closed. Reserve/status admission occurs before mapper access; status uses the established non-locking read path.

## Duplicate review and provenance

OCR-populated contact/company review uses the same canonical duplicate-review boundary as manual/staged creation. Read `docs/backend/IMPORTS_AND_DUPLICATE_REVIEW.md`.

Keep business-card submission on its dedicated import API so OCR provenance, source-object lifecycle, idempotency, and reviewed person/company creation remain bound together.

The locked final duplicate recheck and identity provenance use the post-lock snapshot. OCR-created identity provenance remains `BUSINESS_CARD`.

## Object storage and transaction order

Source images are backend-managed objects; never write them into frontend `public/` or return filesystem paths.

Read `docs/backend/OBJECT_STORAGE.md` and `docs/backend/LOCKING.md` before changing persistence order. Preserve binary/object storage admission and the documented deletion-queue → quota → audit hierarchy, including the established point at which business-card bytes are stored relative to company/person/audit writes.

## Failure behavior

Keep these outcomes distinct:

- invalid/oversized/unsupported image;
- local OCR unavailable;
- provider fallback unavailable or unauthorized;
- provider failure/rate limit/timeout;
- duplicate-review conflict;
- idempotency-key conflict/replay mismatch;
- confirmed success.

A valid OCR engine response exceeding the recognized-line ceiling is an image rejection, not a native worker failure and not a reason to restart the sidecar.

## Review checklist

- Authorization/admission occurs before multipart decode.
- Decode and canonical re-encode remain bounded and metadata-free.
- Local OCR is attempted through the bounded readiness contract before fallback.
- AI fallback requires `AI_USE`, exact provider revalidation, `AiInputImage`, and embedded bytes.
- No raw/recognized data enters logs, audit, URLs, or third-party debugging.
- Idempotency state is workspace/user/payload scoped and fail-closed.
- Duplicate review and OCR provenance remain canonical.
- Object lifecycle and transaction lock order remain intact.
- Upload-admission, local/fallback, idempotency, ownership, duplicate, and cross-workspace tests pass.
