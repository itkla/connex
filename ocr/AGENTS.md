# Connex OCR — Agent Guide

This guide applies to `ocr/` in addition to the root `AGENTS.md`. The package is a private, single-worker PaddleOCR service used only by the Spring backend for business-card recognition.

## Runtime and dependencies

- Production and local inference run through `ocr/Dockerfile`; do not treat a host Python environment as a supported runtime.
- Every direct Python runtime dependency is pinned exactly in `requirements.txt`. New packages require a clear justification, an updated dependency audit, and Dependabot coverage.
- The image uses the current CPU-only PaddlePaddle wheel, which requires an x86-64 host with AVX support, and the `PP-OCRv6_small_det` / `PP-OCRv6_small_rec` models with document and text-line orientation classifiers. Keep the two-CPU, 2 GiB memory, and one-concurrent-inference limits unless a recorded benchmark justifies a change.
- Models are downloaded from pinned Paddle BOS artifacts with exact byte counts and SHA-256 digests during the image build. Runtime receives explicit cache directories, and the container filesystem is read-only. Missing model files or an unsupported CPU must fail startup; never add a runtime download fallback.
- The service has no public route or host binding in deployment. The backend-to-sidecar bearer token must be unique, secret, newline-free, and at least 32 characters.
- Local Compose uses a DNS-disabled, non-masquerading bridge so host-bound development traffic reaches `127.0.0.1:8090` without giving the sidecar outbound network access. Production Compose uses an internal network shared only with the backend.

## Code rules

- Python source follows the root no-inline-comments rule. Use clear names and structure; reserve docstrings for legitimate public documentation.
- Keep the HTTP surface at `GET /health` and authenticated raw-image `POST /v1/ocr`. Do not add file paths, remote URL fetching, multipart parsing, or arbitrary Paddle pipeline options.
- Never log image bytes, recognized text, names, email addresses, phone numbers, request headers, or tokens. Error responses and logs stay generic.
- Validate content length, media type, full decode, dimensions, pixel count, frame count, OCR response shape, text length, confidence, coordinates, and line count at the boundary.
- Preserve non-blocking single-worker backpressure: overlapping inference returns `429`; it must not create an unbounded queue. Inference exceeding the configured deadline marks health unavailable and terminates the process so the container restart policy replaces a wedged native worker.

## Verification

Run lightweight protocol, parser, and benchmark-manifest tests without loading Paddle models:

```bash
cd ocr
PYTHONPATH=. python3 -m unittest discover -s tests -v
```

Run a dependency audit after changing `requirements.txt`:

```bash
python3 -m pip install pip-audit
python3 -m pip_audit --requirement requirements.txt
```

Build the actual image to prove all pinned wheels resolve and all required models pre-fetch into the expected cache:

```bash
docker build -t connex-ocr:verify .
```

The full deterministic benchmark is intentionally outside the lightweight CI loop. It requires a running authenticated stack, a disposable benchmark workspace, and a Japanese-capable font. Follow `benchmark/README.md`; generated images and reports are transient and must not be committed. The release gate is at least 95% email accuracy, 95% phone accuracy, 85% name accuracy, 80% title accuracy, 80% company accuracy, and at most eight seconds end-to-end P95 latency across the 40-case English/Japanese/mixed manifest.

## Deployment checks

When runtime behavior or structure changes, update all of the following together:

- `backend/docker-compose.yml` for the optional local profile;
- `deploy/docker-compose.yml` and `deploy/docker-compose.build.yml` for the private production service and resource restrictions;
- deployment environment examples;
- CI, security audit, release image/SBOM, deploy-smoke, and Dependabot configuration;
- root and package agent guides when build or run conventions change.
