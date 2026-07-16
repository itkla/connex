#!/usr/bin/env bash
set -euo pipefail

IMAGE="${1:?OCR image name is required}"
NAME="connex-ocr-smoke-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
READY=0

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach \
    --name "$NAME" \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m \
    --network none \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --pids-limit 128 \
    --cpus 2 \
    --memory 2g \
    --env "CONNEX_OCR_SERVICE_TOKEN=$TOKEN" \
    --mount "type=bind,src=$PWD/ocr/ci/runtime_smoke.py,dst=/opt/connex-ocr/app/runtime_smoke.py,readonly" \
    "$IMAGE" >/dev/null

for _ in $(seq 1 60); do
    if docker exec "$NAME" python -c "import json,urllib.request; data=json.load(urllib.request.urlopen('http://127.0.0.1:8090/health',timeout=2)); raise SystemExit(0 if data.get('ready') is True else 1)"; then
        READY=1
        break
    fi
    if [ "$(docker inspect --format '{{.State.Running}}' "$NAME")" != "true" ]; then
        break
    fi
    sleep 2
done

if [ "$READY" != 1 ]; then
    docker logs "$NAME"
    exit 1
fi

docker exec "$NAME" python /opt/connex-ocr/app/runtime_smoke.py
