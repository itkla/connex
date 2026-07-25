#!/bin/bash
#
# Health-gated, versioned auto-deploy for the Connex staging box (preview.connexcrm.jp).
#
# Invoked every ~5 minutes by the root-installed thin wrapper
# (see connex-staging-deploy-wrapper.sh) or manually as the `dev` user. Runs entirely
# unprivileged except for the two NOPASSWD-sudo service restarts.
#
# Deploy contract (fixes #829 and #596):
#   - Gates on a deployed-sha marker, not on HEAD, so a failed build is retried on the
#     next cycle instead of being silently skipped.
#   - Builds the backend with `clean bootJar` and stamps the git sha into build info,
#     so the running JAR is verifiable via GET /api/version. Skips the backend step
#     entirely when the running backend already serves the target sha.
#   - Builds both artifacts before restarting anything; the frontend builds into
#     .next-new (NEXT_DIST_DIR) and is swapped into .next only after the backend
#     passes its health gate, so the live frontend keeps a consistent build dir.
#   - Restarts the backend, then polls unit + HTTP health with a bounded timeout and
#     verifies the served gitSha equals the target commit; rechecks the same PID after
#     a stability interval. Only then swaps and restarts the frontend.
#   - On backend health failure: restores the previous JAR, restarts, and exits
#     nonzero without touching the frontend or the marker.
#
# The whole body lives in functions with a single trailing `main` call so that the
# `git reset --hard` mid-run cannot corrupt the interpreter's view of this file.

set -euo pipefail

STAGING_DIR="${CONNEX_STAGING_DIR:-/opt/connex-staging}"
NODE_BIN=/home/dev/.nvm/versions/node/v24.18.0/bin
PNPM="$NODE_BIN/pnpm"
LOG_TAG="connex-staging-deploy"
LOCK_FILE=/tmp/connex-staging-deploy.lock

STATE_DIR="$STAGING_DIR/.staging"
ART_DIR="$STATE_DIR/artifacts"
MARKER="$STATE_DIR/deployed-sha"
LIVE_JAR="$STAGING_DIR/backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
ROLLBACK_JAR="$ART_DIR/rollback.jar"
FRONTEND_ENV=/etc/connex-staging/frontend.env

BACKEND_URL=http://127.0.0.1:8081
FRONTEND_URL=http://127.0.0.1:3001
BACKEND_HEALTH_TIMEOUT=300
ROLLBACK_HEALTH_TIMEOUT=180
FRONTEND_HEALTH_TIMEOUT=90
STABILITY_INTERVAL=15
POLL_INTERVAL=5

export PATH="$NODE_BIN:$PATH"
export NODE_OPTIONS="--max-old-space-size=2048"
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx900m -Dorg.gradle.daemon=false"

log() {
    echo "[$LOG_TAG] $*"
}

backend_unit_state() {
    systemctl show -p ActiveState --value connex-staging-backend
}

backend_pid() {
    systemctl show -p MainPID --value connex-staging-backend
}

served_git_sha() {
    curl -fsS --max-time 5 "$BACKEND_URL/api/version" 2>/dev/null \
        | sed -n 's/.*"gitSha"[[:space:]]*:[[:space:]]*"\([0-9a-f]\{40\}\)".*/\1/p'
}

backend_http_healthy() {
    curl -fsS --max-time 5 -o /dev/null "$BACKEND_URL/api/version" 2>/dev/null
}

wait_for_backend_sha() {
    local target="$1" deadline sha
    deadline=$(( $(date +%s) + BACKEND_HEALTH_TIMEOUT ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        sha="$(served_git_sha || true)"
        if [ "$sha" = "$target" ]; then
            return 0
        fi
        if [ -n "$sha" ]; then
            log "Backend answering but serving sha ${sha:0:8}, want ${target:0:8}; waiting..."
        fi
        sleep "$POLL_INTERVAL"
    done
    log "Backend health gate FAILED: no healthy response with sha ${target:0:8} within ${BACKEND_HEALTH_TIMEOUT}s (unit state: $(backend_unit_state))"
    return 1
}

wait_for_backend_http() {
    local timeout="$1" deadline
    deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if backend_http_healthy; then
            return 0
        fi
        sleep "$POLL_INTERVAL"
    done
    return 1
}

verify_backend_stability() {
    local target="$1" pid_before pid_after
    pid_before="$(backend_pid)"
    sleep "$STABILITY_INTERVAL"
    pid_after="$(backend_pid)"
    if [ "$pid_before" != "$pid_after" ] || [ "$pid_after" = "0" ]; then
        log "Backend stability check FAILED: PID changed ($pid_before -> $pid_after) within ${STABILITY_INTERVAL}s of passing health"
        return 1
    fi
    if ! systemctl is-active --quiet connex-staging-backend || [ "$(served_git_sha || true)" != "$target" ]; then
        log "Backend stability check FAILED: unhealthy on recheck (unit state: $(backend_unit_state))"
        return 1
    fi
    return 0
}

rollback_backend() {
    if [ ! -f "$ROLLBACK_JAR" ]; then
        log "No rollback artifact at $ROLLBACK_JAR — backend left as-is; manual intervention required"
        return 0
    fi
    log "Rolling back backend to previous JAR..."
    cp -f "$ROLLBACK_JAR" "$LIVE_JAR"
    sudo systemctl restart connex-staging-backend \
        || log "Rollback restart command failed (unit state: $(backend_unit_state))"
    if wait_for_backend_http "$ROLLBACK_HEALTH_TIMEOUT"; then
        log "Rollback complete: previous backend restored and answering"
    else
        log "Rollback restart did not become healthy within ${ROLLBACK_HEALTH_TIMEOUT}s (unit state: $(backend_unit_state)); manual intervention required"
    fi
}

wait_for_frontend() {
    local deadline
    deadline=$(( $(date +%s) + FRONTEND_HEALTH_TIMEOUT ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -fsS --max-time 5 -o /dev/null "$FRONTEND_URL/" 2>/dev/null; then
            return 0
        fi
        sleep "$POLL_INTERVAL"
    done
    return 1
}

prune_artifacts() {
    find "$ART_DIR" -maxdepth 1 -name 'backend-*.jar' -printf '%T@ %p\n' \
        | sort -rn | tail -n +6 | cut -d' ' -f2- | xargs -r rm -f
}

build_backend() {
    local target="$1"
    cd "$STAGING_DIR/backend"
    if [ -f "$LIVE_JAR" ]; then
        cp -f "$LIVE_JAR" "$ROLLBACK_JAR"
    fi
    log "Building backend (clean bootJar, sha ${target:0:8})..."
    if ! bash ./gradlew clean bootJar -q -PgitSha="$target"; then
        if [ -f "$ROLLBACK_JAR" ] && [ ! -f "$LIVE_JAR" ]; then
            cp -f "$ROLLBACK_JAR" "$LIVE_JAR"
            log "Backend build FAILED; restored previous JAR on disk (running service untouched)"
        else
            log "Backend build FAILED"
        fi
        return 1
    fi
    cp -f "$LIVE_JAR" "$ART_DIR/backend-$target.jar"
}

build_frontend() {
    cd "$STAGING_DIR/frontend"
    log "Building frontend (into .next-new)..."
    set -a; source "$FRONTEND_ENV"; set +a
    "$PNPM" install --frozen-lockfile --silent
    rm -rf .next-new
    NEXT_DIST_DIR=.next-new "$PNPM" build
}

swap_frontend_build() {
    cd "$STAGING_DIR/frontend"
    rm -rf .next-old
    if [ -d .next ]; then
        mv .next .next-old
    fi
    mv .next-new .next
}

restore_frontend_build() {
    cd "$STAGING_DIR/frontend"
    if [ -d .next-old ]; then
        rm -rf .next
        mv .next-old .next
        sudo systemctl restart connex-staging-frontend \
            || log "Frontend restore restart command failed"
        log "Frontend restored to previous build"
    fi
}

deploy_backend() {
    local target="$1"
    if [ "$(served_git_sha || true)" = "$target" ]; then
        log "Backend already serving sha ${target:0:8}; skipping backend build/restart"
        return 0
    fi
    build_backend "$target"
    log "Restarting backend..."
    if ! sudo systemctl restart connex-staging-backend \
        || ! wait_for_backend_sha "$target" \
        || ! verify_backend_stability "$target"; then
        rollback_backend
        return 1
    fi
    log "Backend healthy and serving sha ${target:0:8}"
}

deploy_frontend() {
    swap_frontend_build
    log "Restarting frontend..."
    if ! sudo systemctl restart connex-staging-frontend || ! wait_for_frontend; then
        log "Frontend did not answer on $FRONTEND_URL within ${FRONTEND_HEALTH_TIMEOUT}s after restart; restoring previous build"
        restore_frontend_build
        return 1
    fi
}

main() {
    if [ "${CONNEX_DEPLOY_LOCK_HELD:-0}" != "1" ]; then
        exec 9>"$LOCK_FILE"
        flock -n 9 || { log "Deploy already in progress, skipping"; exit 0; }
    fi

    cd "$STAGING_DIR"
    git fetch origin main --quiet
    local target deployed
    target="$(git rev-parse origin/main)"
    deployed="$(cat "$MARKER" 2>/dev/null || echo none)"
    if [ "$target" = "$deployed" ]; then
        exit 0
    fi

    if [ ! -r "$FRONTEND_ENV" ]; then
        log "Deploy FAILED: $FRONTEND_ENV is missing or unreadable"
        exit 1
    fi

    mkdir -p "$ART_DIR"
    log "Deploying ${target:0:8} (previously deployed: ${deployed:0:8})..."
    git reset --hard "$target" --quiet

    build_frontend
    if ! deploy_backend "$target"; then
        log "Deploy of ${target:0:8} FAILED at the backend health gate; frontend untouched, will retry next cycle"
        exit 1
    fi
    if ! deploy_frontend; then
        log "Deploy of ${target:0:8} FAILED at the frontend; marker not updated, will retry next cycle"
        exit 1
    fi

    printf '%s\n' "$target" > "$MARKER"
    prune_artifacts
    log "Done — ${target:0:8} live and health-verified"
}

main "$@"
