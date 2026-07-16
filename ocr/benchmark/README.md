# Business-card OCR benchmark

This deterministic synthetic suite covers 40 English, Japanese, and mixed-language cards across clean, glare, rotation, perspective, and low-light conditions. All names and contact details are synthetic fixtures using reserved `example.test` addresses.

Install the pinned OCR requirements, provide a Japanese-capable font when the default Noto CJK path is unavailable, and generate the fixtures:

```bash
python -m pip install --require-hashes --requirement ../requirements.lock
CONNEX_BENCHMARK_JA_FONT=/path/to/NotoSansCJK-Regular.ttc python generate_cards.py
```

Run the backend and private OCR service, sign in to a disposable benchmark workspace with person-create and attachment-create permissions, then export the session inputs without committing them:

```bash
export CONNEX_BENCHMARK_BASE_URL=http://localhost:8080
export CONNEX_BENCHMARK_SESSION_COOKIE='JSESSIONID=...; XSRF-TOKEN=...'
export CONNEX_BENCHMARK_CSRF_TOKEN='...'
export CONNEX_BENCHMARK_WORKSPACE_ID='...'
python run_benchmark.py --report /tmp/connex-business-card-benchmark.json
```

The runner spaces requests at 12 per minute by default so it exercises the end-to-end API without
tripping the production scan throttle. Set `CONNEX_BENCHMARK_REQUESTS_PER_MINUTE` only when the
disposable backend is configured with the same or a higher
`CONNEX_BUSINESS_CARD_MAX_SCANS_PER_MINUTE` value.

The command fails unless email and phone accuracy are at least 95%, name accuracy is at least 85%, title and company accuracy are each at least 80%, and end-to-end P95 latency is at most eight seconds. Generated images and reports are transient and must not be committed.
