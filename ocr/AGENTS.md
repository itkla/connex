# Connex OCR — Agent Guide

The root `../AGENTS.md` applies here. `ocr/` is a private, CPU-only PaddleOCR sidecar used by the backend for English/Japanese business-card recognition.

## Runtime invariants

- Production and local inference run through `ocr/Dockerfile`; a host Python environment is not a supported runtime.
- Direct Python dependencies are pinned in `requirements.txt`; the complete hashed graph lives in `requirements.lock`. Production installs only the hash-checked lock. New dependencies require justification, lock regeneration, audit, and Dependabot coverage.
- The service uses the pinned CPU PaddlePaddle/PaddleOCR stack and pre-fetched pinned models. Runtime model downloads are forbidden. Missing models or unsupported CPU capability fail startup.
- Preserve the supported resource envelope unless a recorded benchmark justifies a change: two CPUs, 2 GiB memory, one concurrent inference.
- The container filesystem is read-only and the service has no public deployment route. Backend-to-sidecar authentication uses a unique secret bearer token of at least 32 characters.
- Production networking remains private between backend and OCR. Local Compose may expose `127.0.0.1:8090` for development without granting the sidecar outbound network access.

## HTTP and data boundary

Keep the HTTP surface narrow:

- `GET /health`
- authenticated `GET /ready`
- authenticated raw-image `POST /v1/ocr`

Do not add filesystem paths, remote URL fetching, arbitrary Paddle options, or an alternate public upload surface.

- Validate content length, media type, full image decode, dimensions, pixel/frame limits, OCR response shape, text/line limits, confidence, and coordinates at the boundary.
- Never log image bytes, recognized text, names, email addresses, phone numbers, request headers, tokens, configuration secrets, or exception messages containing them.
- Startup/runtime diagnostics use the established allowlisted component/reason-code format and generic error responses.
- Preserve bounded request handlers, absolute request deadlines, and non-blocking single-worker backpressure. Overlapping inference returns `429`; do not replace it with an unbounded queue.
- The persistent supervisor must preserve readiness/failure-generation semantics, hard deadlines for native hangs, bounded restart behavior, and fail-closed startup. Changes here are high-risk.

## Code conventions

- Follow the root documentation-comment policy. Prefer clear names/structure; use docstrings for legitimate public contracts and concise inline comments only for non-obvious safety, native-runtime, protocol, or resource reasoning.
- Keep validation and protocol behavior deterministic and covered by tests.
- Do not broaden the sidecar's responsibilities; business logic, tenant/RBAC decisions, provider fallback, and import persistence belong in the backend.

## Verification

Lightweight tests do not load Paddle models:

```bash
cd ocr
PYTHONPATH=. python3 -m unittest discover -s tests -v
```

After dependency changes, regenerate the lock with the repository-pinned tooling and audit it:

```bash
pip-compile --generate-hashes --strip-extras --allow-unsafe --output-file=requirements.lock requirements.txt
python3 -m pip install pip-audit
python3 -m pip_audit --requirement requirements.lock
```

For runtime/model/dependency changes, build the real image:

```bash
docker build -t connex-ocr:verify .
```

The deterministic release benchmark, fixtures, thresholds, and invocation procedure live in `benchmark/README.md`. Do not duplicate them here. Run the benchmark when its documented release/runtime criteria require it, not as routine PR fan-out.

## Review and deployment

Routine parser/validation/protocol changes normally need targeted tests and one material-change review. Changes to worker supervision, deadlines, backpressure, networking, model acquisition, dependency pins, image decoding, or resource limits are high-risk and require distinct security/isolation and correctness/resource-failure review.

When OCR runtime structure changes, inspect the backend local Compose profile, production deployment Compose/build overlay, environment examples, CI/release/SBOM/security configuration, and Dependabot coverage together. Detailed deployment sequencing belongs in `../docs/DEPLOYMENT.md`.
