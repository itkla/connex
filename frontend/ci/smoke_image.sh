#!/usr/bin/env bash
set -euo pipefail

IMAGE="${1:?Frontend image name is required}"
NAME="connex-frontend-smoke-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHUNK_VERIFIER="$SCRIPT_DIR/verify_build_chunks.mjs"
READY=0
SMOKE_IMAGE="$(mktemp)"
HEADERS="$(mktemp)"
BODY="$(mktemp)"

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    rm -f "$SMOKE_IMAGE" "$HEADERS" "$BODY"
}

report_failure() {
    docker inspect --format 'running={{.State.Running}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} error={{.State.Error}}' "$NAME" || true
    docker logs "$NAME" || true
    if [ -s "$HEADERS" ]; then
        sed -n '1,20p' "$HEADERS"
    fi
    if [ -s "$BODY" ]; then
        od -An -tx1 -N12 "$BODY"
    fi
}
trap cleanup EXIT

printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEklEQVQImWNQTX79HxkzkC4AABofJyEbiQTfAAAAAElFTkSuQmCC' \
    | base64 --decode > "$SMOKE_IMAGE"
chmod 0644 "$SMOKE_IMAGE"

docker run --detach \
    --name "$NAME" \
    --publish 127.0.0.1::3000 \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --pids-limit 256 \
    --cpus 2 \
    --memory 2g \
    --mount "type=bind,src=$SMOKE_IMAGE,dst=/app/public/sharp-smoke.png,readonly" \
    --mount "type=bind,src=$CHUNK_VERIFIER,dst=/app/verify_build_chunks.mjs,readonly" \
    "$IMAGE" >/dev/null

if ! docker exec "$NAME" node /app/verify_build_chunks.mjs /app/.next; then
    report_failure
    exit 1
fi

if ! docker exec "$NAME" sh -ec '
    addon="$(find /app/node_modules -type f -name "sharp-linuxmusl-*.node" -print -quit)"
    libvips="$(find /app/node_modules -type f -name "libvips-cpp.so.*" -print -quit)"
    test -n "$addon" && test -s "$addon"
    test -n "$libvips" && test -s "$libvips"
'; then
    report_failure
    exit 1
fi

ADDRESS="$(docker port "$NAME" 3000/tcp | head -n 1)"
PORT="${ADDRESS##*:}"

for _ in $(seq 1 60); do
    if curl --connect-timeout 2 --max-time 5 --fail --silent "http://127.0.0.1:$PORT/auth/login" >/dev/null; then
        READY=1
        break
    fi
    if [ "$(docker inspect --format '{{.State.Running}}' "$NAME")" != "true" ]; then
        break
    fi
    sleep 1
done

if [ "$READY" != 1 ]; then
    report_failure
    exit 1
fi

if ! curl --connect-timeout 5 --max-time 30 --fail --silent --show-error \
    --header 'Accept: image/webp' \
    --dump-header "$HEADERS" \
    --output "$BODY" \
    "http://127.0.0.1:$PORT/_next/image?url=%2Fsharp-smoke.png&w=64&q=75"; then
    report_failure
    exit 1
fi

if ! grep -Eiq '^content-type:[[:space:]]*image/webp([;[:space:]]|$)' "$HEADERS"; then
    report_failure
    exit 1
fi

if [ ! -s "$BODY" ]; then
    report_failure
    exit 1
fi

SIGNATURE="$(od -An -tx1 -N12 "$BODY" | tr -d '[:space:]')"
if [[ ! "$SIGNATURE" =~ ^52494646[0-9a-f]{8}57454250$ ]]; then
    report_failure
    exit 1
fi
