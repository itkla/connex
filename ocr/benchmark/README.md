# Business-card OCR benchmark

This deterministic synthetic suite covers exactly 40 English, Japanese, and mixed-language cards
across clean, glare, rotation, perspective, and low-light conditions. All names and contact details
are synthetic fixtures using reserved `example.test` addresses. The runner accepts only the reviewed
canonical manifest and matching generated fixture metadata. The exact 40-image set is pinned as
`bfff98a022ded013b42d2313f75c2ec6e5fc7632c1926adea6274ca0172899e5` using the case-id/byte
aggregate defined by the runner.

Use the pinned Noto CJK font from revision
`f8d157532fbfaeda587e826d4cd5b21a49186f7c`; its required SHA-256 is
`68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5`. Generate the fixtures inside
the exact OCR image being qualified so Pillow, libjpeg, and the generator runtime match that image:

```bash
OCR_IMAGE='ghcr.io/itkla/connex-ocr@sha256:...'
FONT=/tmp/NotoSansCJKjp-Regular.otf
curl --fail --location --proto '=https' --tlsv1.2 \
  --output "$FONT" \
  https://raw.githubusercontent.com/notofonts/noto-cjk/f8d157532fbfaeda587e826d4cd5b21a49186f7c/Sans/OTF/Japanese/NotoSansCJKjp-Regular.otf
echo '68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5  /tmp/NotoSansCJKjp-Regular.otf' \
  | sha256sum --check --strict
install -d -m 0777 /tmp/connex-benchmark-fixtures
docker run --rm --network none --entrypoint python \
  -e CONNEX_BENCHMARK_FONT=/font/NotoSansCJKjp-Regular.otf \
  -v "$PWD:/benchmark:ro" \
  -v "$FONT:/font/NotoSansCJKjp-Regular.otf:ro" \
  -v /tmp/connex-benchmark-fixtures:/fixtures \
  "$OCR_IMAGE" \
  /benchmark/generate_cards.py --output /fixtures
```

Run the exact backend, frontend, and OCR digests through `deploy/docker-compose.yml`. The OCR
container must be healthy, private, read-only, limited to two CPUs, 2 GiB memory without swap, and
128 PIDs. Sign in to a disposable workspace with person-create and attachment-create permissions,
then export the session inputs and actual Compose container IDs without committing them:

```bash
export CONNEX_BENCHMARK_BASE_URL=http://127.0.0.1:8088
export CONNEX_BENCHMARK_SESSION_COOKIE='JSESSIONID=...; connex_workspace=...'
export CONNEX_BENCHMARK_CSRF_TOKEN='...'
export CONNEX_BENCHMARK_CSRF_HEADER='X-CSRF-TOKEN'
export CONNEX_BENCHMARK_WORKSPACE_ID='...'
export CONNEX_BENCHMARK_BACKEND_CONTAINER="$(docker compose -f ../../deploy/docker-compose.yml ps -q backend)"
export CONNEX_BENCHMARK_FRONTEND_CONTAINER="$(docker compose -f ../../deploy/docker-compose.yml ps -q frontend)"
export CONNEX_BENCHMARK_OCR_CONTAINER="$(docker compose -f ../../deploy/docker-compose.yml ps -q ocr)"
python3 run_benchmark.py \
  --images /tmp/connex-benchmark-fixtures \
  --report /tmp/connex-business-card-benchmark.json
python3 verify_report.py \
  /tmp/connex-business-card-benchmark.json "$(git rev-parse HEAD)" \
  --base-url "$CONNEX_BENCHMARK_BASE_URL" \
  --requests-per-minute 3 \
  --backend-image-reference 'ghcr.io/itkla/connex-backend@sha256:...' \
  --frontend-image-reference 'ghcr.io/itkla/connex-frontend@sha256:...' \
  --ocr-image-reference "$OCR_IMAGE"
```

The runner ignores environment proxy settings and never follows redirects, so session, CSRF, and
workspace headers cannot leave the direct origin. It derives the source revision from the clean
checked-out benchmark sources and derives all image identities and OCR resource limits from Docker
inspection; caller-supplied source or image labels are not accepted. The report binds the canonical
manifest, exact fixture images and metadata, pinned font, generator, dependency lock, running image
references and IDs, host/runtime metadata, and request rate.
Independent verification requires the expected origin, request rate, and three immutable release
image references so a report cannot self-assert its qualification inputs.

Requests are spaced at three per minute by default to respect production throttles. Increase the
rate only for a disposable stack whose per-principal and global scan limits were raised to match.
The command fails unless all 40 responses succeed, email and phone accuracy are at least 95%, name
accuracy is at least 85%, title and company accuracy are each at least 80%, and end-to-end P95
latency is at most eight seconds. Generated images and reports are transient and must not be
committed. The release workflow performs this gate automatically against the exact candidate set
and binds its report and deterministic fixture archive into the signed release manifest.
