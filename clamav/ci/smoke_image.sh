#!/usr/bin/env bash
set -euo pipefail

IMAGE="${1:?ClamAV image name is required}"
NAME="connex-clamav-smoke-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
READY=0

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
}

report_failure() {
    docker inspect --format 'running={{.State.Running}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} error={{.State.Error}}' "$NAME"
    docker exec "$NAME" sh -c 'if [ -r /sys/fs/cgroup/memory.events ]; then cat /sys/fs/cgroup/memory.events; fi' || true
    docker logs "$NAME"
}
trap cleanup EXIT

# --network none proves the baked signature set is sufficient to reach readiness with no egress
# whatsoever, which is the whole basis of the air-gapped on-prem story.
#
# The tmpfs is sized from the sidecar's own arithmetic: max_concurrent_scans (2) multiplied by
# clamd's StreamMaxLength (32 MiB) plus MaxScanSize (192 MiB) is 448 MiB. clamd spools every
# INSTREAM body to a temporary file and unpacks archive members beside it, so this is a hard
# requirement of read_only:true, not a nicety -- clamav_service.config refuses to start when the
# mount is too small. Do NOT copy the ocr sidecar's 64 MiB here.
#
# The memory limit must cover clamd's resident signature database (~2 GiB and growing) AND the
# tmpfs, because tmpfs pages are charged to the container's memory cgroup.
docker run --detach \
    --name "$NAME" \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=512m,mode=1777 \
    --network none \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --pids-limit 128 \
    --cpus 2 \
    --memory 3g \
    --env "CONNEX_CLAMAV_SERVICE_TOKEN=$TOKEN" \
    --env "CONNEX_CLAMAV_MAX_CONCURRENT_SCANS=2" \
    --env "CONNEX_CLAMAV_STARTUP_TIMEOUT_SECONDS=300" \
    --mount "type=bind,src=$PWD/clamav/ci/runtime_smoke.py,dst=/opt/connex-clamav/app/runtime_smoke.py,readonly" \
    "$IMAGE" >/dev/null

test "$(docker inspect "$NAME" --format '{{.HostConfig.NetworkMode}}')" = "none"

# clamd's own SCAN/MULTISCAN commands take filesystem paths, so a published 3310 would be both an
# unauthenticated scanning surface and an arbitrary-file-read primitive. The image must expose the
# authenticated HTTP front and nothing else.
EXPOSED="$(docker image inspect "$IMAGE" --format '{{json .Config.ExposedPorts}}' \
    | jq -r 'if . == null then "" else (keys | sort | join(",")) end')"
if [ "$EXPOSED" != "8091/tcp" ]; then
    echo "::error::the clamav image exposes an unexpected port set: '${EXPOSED}'"
    echo "raw image config for diagnosis:"
    docker image inspect "$IMAGE" --format '{{json .Config}}' | jq .
    exit 1
fi

for _ in $(seq 1 150); do
    if docker exec "$NAME" python -c "import json,urllib.request; data=json.load(urllib.request.urlopen('http://127.0.0.1:8091/health',timeout=3)); raise SystemExit(0 if data.get('ready') is True else 1)" 2>/dev/null; then
        READY=1
        break
    fi
    if [ "$(docker inspect --format '{{.State.Running}}' "$NAME")" != "true" ]; then
        break
    fi
    sleep 2
done

if [ "$READY" != 1 ]; then
    report_failure
    exit 1
fi

if ! docker exec --env "CONNEX_CLAMAV_SERVICE_TOKEN=$TOKEN" "$NAME" \
        python /opt/connex-clamav/app/runtime_smoke.py; then
    report_failure
    exit 1
fi

# An undersized scan mount must fail startup rather than degrade to ENOSPC under concurrency.
docker rm -f "$NAME" >/dev/null 2>&1 || true
UNDERSIZED_LOG="$(docker run --rm \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777 \
    --network none \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --memory 3g \
    --env "CONNEX_CLAMAV_SERVICE_TOKEN=$TOKEN" \
    "$IMAGE" 2>&1 || true)"
if ! printf '%s' "$UNDERSIZED_LOG" | grep -q "reason=scan_scratch_undersized"; then
    echo "::error::an undersized scan mount did not fail startup"
    printf '%s\n' "$UNDERSIZED_LOG" | tail -20
    exit 1
fi

# A short token must fail startup: a fat-fingered secret has to be a deployment error, not a
# sidecar that quietly rejects every backend call.
SHORT_TOKEN_LOG="$(docker run --rm \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=512m,mode=1777 \
    --network none \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --memory 3g \
    --env "CONNEX_CLAMAV_SERVICE_TOKEN=tooshort" \
    "$IMAGE" 2>&1 || true)"
if ! printf '%s' "$SHORT_TOKEN_LOG" | grep -q "reason=invalid_configuration"; then
    echo "::error::a short service token did not fail startup"
    printf '%s\n' "$SHORT_TOKEN_LOG" | tail -20
    exit 1
fi
