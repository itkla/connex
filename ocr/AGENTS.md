# Connex OCR — Agent Guide

This guide applies to `ocr/` in addition to the root `AGENTS.md`. The package is a private, single-worker PaddleOCR service used only by the Spring backend for business-card recognition.

## Runtime and dependencies

- Production and local inference run through `ocr/Dockerfile`; do not treat a host Python environment as a supported runtime.
- Every direct Python runtime dependency is pinned exactly in `requirements.txt`, and the complete hashed transitive graph is generated into `requirements.lock` with Python 3.12 and `pip-tools==7.5.3`. Production installs only the hash-checked lock. Regenerate it with `pip-compile --generate-hashes --strip-extras --allow-unsafe --output-file=requirements.lock requirements.txt`. New packages require a clear justification, an updated dependency audit, and Dependabot coverage.
- The image uses the current CPU-only PaddlePaddle wheel, which requires an x86-64 host with AVX support, and the `PP-OCRv6_small_det` / `PP-OCRv6_small_rec` models with document and text-line orientation classifiers. Keep the two-CPU, 2 GiB memory, and one-concurrent-inference limits unless a recorded benchmark justifies a change.
- Models are downloaded from pinned Paddle BOS artifacts with exact byte counts and SHA-256 digests during the image build. Runtime receives explicit cache directories, and the container filesystem is read-only. Missing model files or an unsupported CPU must fail startup; never add a runtime download fallback.
- The service has no public route or host binding in deployment. The backend-to-sidecar bearer token must be unique, secret, newline-free, and at least 32 characters.
- Local Compose uses a DNS-disabled, non-masquerading bridge so host-bound development traffic reaches `127.0.0.1:8090` without giving the sidecar outbound network access. Production Compose uses an internal network shared only with the backend.

## Code rules

- Python source follows the root no-inline-comments rule. Use clear names and structure; reserve docstrings for legitimate public documentation.
- Keep the HTTP surface at container-only `GET /health`, authenticated `GET /ready`, and authenticated raw-image `POST /v1/ocr`. Do not add file paths, remote URL fetching, multipart parsing, or arbitrary Paddle pipeline options.
- Never log image bytes, recognized text, names, email addresses, phone numbers, request headers, or tokens. Error responses and logs stay generic.
- Startup diagnostics use allowlisted reason codes and exception type names only; never include exception messages or configuration values.
- Validate content length, media type, full decode, dimensions, pixel count, frame count, OCR response shape, text length, confidence, coordinates, and line count at the boundary. A valid engine result that exceeds the recognized-line ceiling is an image rejection with HTTP 422; it is not a native worker failure and must not trigger the fatal restart path.
- Preserve non-blocking single-worker backpressure: overlapping inference returns `429`; it must not create an unbounded queue. HTTP handler threads are bounded by `CONNEX_OCR_MAX_REQUEST_HANDLERS`, slow request bodies must not occupy the inference slot, and every connection must remain under the absolute request deadline. The persistent supervisor must continuously probe the worker, distinguish adjacent requests by the health generation, immediately hard-kill native startup or inference that exceeds its deadline, and fail the container when configuration or a worker fails before readiness. Only a worker that previously reached readiness may be restarted, with bounded exponential backoff that resets after stable uptime. Compose uses `unless-stopped` so the supervisor also returns after a Docker daemon or host restart.

## Verification

Run lightweight protocol, parser, and benchmark-manifest tests without loading Paddle models:

```bash
cd ocr
PYTHONPATH=. python3 -m unittest discover -s tests -v
```

Run a dependency audit after changing `requirements.txt`:

```bash
python3 -m pip install pip-audit
python3 -m pip_audit --requirement requirements.lock
```

Build the actual image to prove all pinned wheels resolve and all required models pre-fetch into the expected cache:

```bash
docker build -t connex-ocr:verify .
```

The full deterministic benchmark is outside the lightweight PR loop. It requires a running authenticated stack and disposable workspace; fixture generation uses the pinned Noto CJK font inside the exact OCR image. Follow `benchmark/README.md`; generated images and reports are transient and must not be committed. The release workflow runs the canonical 40-case suite against the exact candidate backend/frontend/OCR set under the production two-CPU, 2 GiB OCR limits, and binds the passing report and fixture archive into the signed release transaction. Promotion requires at least 95% email accuracy, 95% phone accuracy, 85% name accuracy, 80% title accuracy, 80% company accuracy, all HTTP responses successful, and at most eight seconds end-to-end P95 latency.

## Deployment checks

When runtime behavior or structure changes, update all of the following together:

- `backend/docker-compose.yml` for the optional local profile;
- `deploy/docker-compose.yml` and `deploy/docker-compose.build.yml` for the private production service and resource restrictions;
- deployment environment examples;
- CI, security audit, release image/SBOM, deploy-smoke, and Dependabot configuration;
- root and package agent guides when build or run conventions change.
