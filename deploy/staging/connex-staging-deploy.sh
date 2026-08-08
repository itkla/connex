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
#   - Restarts the backend, then polls unit + HTTP health with a bounded timeout,
#     verifies the served gitSha equals the target commit, and requires
#     GET /api/health/ready to answer 200 (DB reachable, migrations applied, startup
#     runners finished; falls back to /api/version for pre-readiness JARs); rechecks
#     the same PID after a stability interval. Only then swaps and restarts the frontend.
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
FRONTEND_SWAP_STATE=unchanged
FRONTEND_TSCONFIG_CLEANUP_ARMED=0

BACKEND_URL=http://127.0.0.1:8081
FRONTEND_URL=http://127.0.0.1:3001
# /api/health/ready only turns green after every ApplicationRunner has finished (identity
# backfill, legacy workflow backfill, secret rewrap), which is minutes on a large dataset and
# runs on the rollback JAR too. These budgets must stay well clear of that or a healthy deploy
# gets auto-rolled back — and the rollback then reports "manual intervention required".
BACKEND_HEALTH_TIMEOUT=900
ROLLBACK_HEALTH_TIMEOUT=600
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

# Readiness-gated health: /api/health/ready must answer 200 (DB reachable, migrations
# applied, startup runners finished). A 404/405 means the running JAR predates the
# endpoint (e.g. a rollback artifact), so fall back to the old /api/version liveness
# probe; any other status (notably 503 = not ready) fails the check.
backend_http_healthy() {
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "$BACKEND_URL/api/health/ready" 2>/dev/null)" || return 1
    case "$code" in
        200) return 0 ;;
        404|405) curl -fsS --max-time 5 -o /dev/null "$BACKEND_URL/api/version" 2>/dev/null ;;
        *) return 1 ;;
    esac
}

wait_for_backend_sha() {
    local target="$1" deadline sha
    deadline=$(( $(date +%s) + BACKEND_HEALTH_TIMEOUT ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        sha="$(served_git_sha || true)"
        if [ "$sha" = "$target" ] && backend_http_healthy; then
            return 0
        fi
        if [ "$sha" = "$target" ]; then
            log "Backend serving sha ${target:0:8} but /api/health/ready not green yet; waiting..."
        elif [ -n "$sha" ]; then
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
    # One transient readiness blip (GC pause, momentary DB hiccup) must not roll back a
    # healthy deploy: retry the readiness probe once before declaring the recheck failed.
    if ! backend_http_healthy; then
        log "Backend readiness blip on stability recheck; retrying once in ${POLL_INTERVAL}s..."
        sleep "$POLL_INTERVAL"
        if ! backend_http_healthy; then
            log "Backend stability check FAILED: unhealthy on recheck (unit state: $(backend_unit_state))"
            return 1
        fi
    fi
    return 0
}

rollback_backend() {
    if [ ! -f "$ROLLBACK_JAR" ]; then
        log "No rollback artifact at $ROLLBACK_JAR — backend left as-is; manual intervention required"
        return 0
    fi
    log "Rolling back backend to previous JAR..."
    if ! cp -f "$ROLLBACK_JAR" "$LIVE_JAR"; then
        log "Backend rollback FAILED while restoring $LIVE_JAR; manual intervention required"
        return 1
    fi
    if ! sudo systemctl restart connex-staging-backend; then
        log "Rollback restart command failed (unit state: $(backend_unit_state)); manual intervention required"
        return 1
    fi
    if wait_for_backend_http "$ROLLBACK_HEALTH_TIMEOUT"; then
        log "Rollback complete: previous backend restored and answering"
        return 0
    else
        log "Rollback restart did not become healthy within ${ROLLBACK_HEALTH_TIMEOUT}s (unit state: $(backend_unit_state)); manual intervention required"
        return 1
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
    if ! cd "$STAGING_DIR/backend"; then
        log "Backend build FAILED: cannot enter $STAGING_DIR/backend"
        return 1
    fi
    if [ -f "$LIVE_JAR" ]; then
        if ! cp -f "$LIVE_JAR" "$ROLLBACK_JAR"; then
            log "Backend build FAILED: could not snapshot the previous JAR"
            return 1
        fi
    fi
    log "Building backend (clean bootJar, sha ${target:0:8})..."
    if ! bash ./gradlew clean bootJar -q -PgitSha="$target"; then
        if [ -f "$ROLLBACK_JAR" ] && [ ! -f "$LIVE_JAR" ]; then
            if cp -f "$ROLLBACK_JAR" "$LIVE_JAR"; then
                log "Backend build FAILED; restored previous JAR on disk (running service untouched)"
            else
                log "Backend build FAILED and the previous JAR could not be restored on disk; running service untouched"
            fi
        else
            log "Backend build FAILED"
        fi
        return 1
    fi
    if ! cp -f "$LIVE_JAR" "$ART_DIR/backend-$target.jar"; then
        log "Backend build FAILED: could not archive the target-stamped JAR"
        return 1
    fi
}

restore_frontend_tsconfig() {
    if [ "$FRONTEND_TSCONFIG_CLEANUP_ARMED" != "1" ]; then
        return 0
    fi
    if ! git checkout -- tsconfig.json; then
        log "Frontend tsconfig restore FAILED: git checkout did not succeed"
        return 1
    fi
    if ! git diff --quiet -- tsconfig.json; then
        log "Frontend tsconfig restore FAILED: tsconfig.json still differs from HEAD"
        return 1
    fi
    FRONTEND_TSCONFIG_CLEANUP_ARMED=0
}

frontend_tsconfig_exit_cleanup() {
    local exit_status="$1" cleanup_status=0
    restore_frontend_tsconfig || cleanup_status=$?
    if [ "$exit_status" -ne 0 ]; then
        exit "$exit_status"
    fi
    exit "$cleanup_status"
}

frontend_tsconfig_signal_cleanup() {
    local signal_name="$1" signal_status="$2" cleanup_status=0
    trap - INT TERM
    log "Frontend build interrupted by $signal_name; restoring tsconfig.json"
    restore_frontend_tsconfig || cleanup_status=$?
    if [ "$cleanup_status" -eq 0 ]; then
        trap - EXIT
    fi
    exit "$signal_status"
}

build_frontend() {
    local build_status=0 restore_status=0
    cd "$STAGING_DIR/frontend"
    log "Building frontend (into .next-new)..."
    set -a; source "$FRONTEND_ENV"; set +a
    "$PNPM" install --frozen-lockfile --silent
    # Only generated route types are safe to remove from the live .next tree before the
    # swap. The running frontend still reads .next/server and .next/static, so never clean
    # .next wholesale here. .next-new is disposable until it becomes the live build.
    if ! rm -rf .next-new .next/types .next/dev/types; then
        log "Frontend build FAILED while removing disposable generated output"
        return 1
    fi
    # Next rewrites tsconfig.json during a build. The EXIT trap covers shell termination and
    # the signal traps preserve conventional signal exit statuses; all remain armed until a
    # checkout plus a clean diff proves that the tracked file is back at HEAD.
    FRONTEND_TSCONFIG_CLEANUP_ARMED=1
    trap 'frontend_tsconfig_exit_cleanup "$?"' EXIT
    trap 'frontend_tsconfig_signal_cleanup INT 130' INT
    trap 'frontend_tsconfig_signal_cleanup TERM 143' TERM
    NEXT_DIST_DIR=.next-new "$PNPM" build || build_status=$?
    restore_frontend_tsconfig || restore_status=$?
    if [ "$restore_status" -eq 0 ]; then
        trap - EXIT INT TERM
    fi
    if [ "$build_status" -ne 0 ]; then
        return "$build_status"
    fi
    return "$restore_status"
}

swap_frontend_build() {
    if ! cd "$STAGING_DIR/frontend"; then
        log "Frontend swap FAILED: cannot enter $STAGING_DIR/frontend"
        return 1
    fi
    FRONTEND_SWAP_STATE=unchanged
    if ! rm -rf .next-old; then
        log "Frontend swap FAILED while removing the previous rollback tree"
        return 1
    fi
    if [ ! -d .next-new ]; then
        log "Frontend swap FAILED: .next-new is missing"
        return 1
    fi
    if [ -d .next ]; then
        if ! mv .next .next-old; then
            log "Frontend swap FAILED while saving the live .next tree; new build left in .next-new"
            return 1
        fi
        FRONTEND_SWAP_STATE=previous_saved
    else
        FRONTEND_SWAP_STATE=no_previous
    fi
    if ! mv .next-new .next; then
        log "Frontend swap FAILED while moving .next-new into place"
        return 1
    fi
    if [ "$FRONTEND_SWAP_STATE" = "previous_saved" ]; then
        FRONTEND_SWAP_STATE=new_live_with_previous
    else
        FRONTEND_SWAP_STATE=new_live_without_previous
    fi
    # next.config.ts sets output: standalone, so the build bakes its distDir into
    # standalone/server.js as .next-new — a directory this swap has just renamed away. Staging
    # serves with `next start` and never reads it, but leaving an unusable server.js behind is a
    # trap for anyone who later switches to the standalone runtime, so drop it.
    if [ -d .next/standalone ]; then
        if ! rm -rf .next/standalone; then
            log "Frontend swap FAILED while removing unusable standalone output"
            return 1
        fi
        log "Removed standalone output (its baked distDir does not survive the .next-new swap)"
    fi
}

restore_frontend_build() {
    if ! cd "$STAGING_DIR/frontend"; then
        log "Frontend restore FAILED: cannot enter $STAGING_DIR/frontend"
        return 1
    fi
    case "$FRONTEND_SWAP_STATE" in
        previous_saved)
            if [ -e .next ] || [ -L .next ]; then
                log "Frontend restore FAILED: refusing to move .next-old into an occupied .next path"
                return 1
            fi
            if ! mv .next-old .next; then
                log "Frontend restore FAILED while moving .next-old back into place"
                return 1
            fi
            FRONTEND_SWAP_STATE=previous_restored
            ;;
        new_live_with_previous)
            if [ -e .next-new ] || [ -L .next-new ]; then
                log "Frontend restore FAILED: .next-new is occupied, so the active tree cannot be parked safely"
                return 1
            fi
            if ! mv .next .next-new; then
                log "Frontend restore FAILED while parking the active build; previous build remains in .next-old"
                return 1
            fi
            FRONTEND_SWAP_STATE=new_build_parked
            if ! mv .next-old .next; then
                log "Frontend restore FAILED while moving .next-old back into place"
                if mv .next-new .next; then
                    FRONTEND_SWAP_STATE=new_live_with_previous
                    log "Returned the failed new build to .next after the restore failure"
                else
                    FRONTEND_SWAP_STATE=restore_incomplete
                    log "Could not return the failed new build to .next; manual intervention required"
                fi
                return 1
            fi
            FRONTEND_SWAP_STATE=previous_restored
            ;;
        previous_restored)
            ;;
        new_live_without_previous)
            log "Frontend restore FAILED: there is no previous .next tree to restore"
            return 1
            ;;
        *)
            log "Frontend restore FAILED: swap state '$FRONTEND_SWAP_STATE' is not restorable"
            return 1
            ;;
    esac
    if ! sudo systemctl restart connex-staging-frontend; then
        log "Frontend restore restart command failed; restored tree retained for manual recovery"
        return 1
    fi
    # The failed tree is renamed, rather than deleted, while the old process may still be
    # serving it. Only a successful restart makes it safe to remove that parked tree.
    if [ -e .next-new ] || [ -L .next-new ]; then
        if ! rm -rf .next-new; then
            log "Frontend restore FAILED while removing the parked failed build"
            return 1
        fi
    fi
    FRONTEND_SWAP_STATE=restored
    log "Frontend restored to previous build"
}

deploy_backend() {
    local target="$1"
    if [ "$(served_git_sha || true)" = "$target" ]; then
        log "Backend already serving sha ${target:0:8}; skipping backend build/restart"
        return 0
    fi
    if ! build_backend "$target"; then
        log "Backend build step FAILED; backend not restarted"
        return 1
    fi
    log "Restarting backend..."
    if ! sudo systemctl restart connex-staging-backend \
        || ! wait_for_backend_sha "$target" \
        || ! verify_backend_stability "$target"; then
        if ! rollback_backend; then
            log "Backend rollback did not complete; manual intervention required"
        fi
        return 1
    fi
    log "Backend healthy and serving sha ${target:0:8}"
}

deploy_frontend() {
    if ! swap_frontend_build; then
        case "$FRONTEND_SWAP_STATE" in
            previous_saved|new_live_with_previous|new_live_without_previous)
                if ! restore_frontend_build; then
                    log "Frontend restore after the failed swap did not complete; manual intervention may be required"
                fi
                ;;
        esac
        return 1
    fi
    log "Restarting frontend..."
    if ! sudo systemctl restart connex-staging-frontend; then
        log "Frontend restart command FAILED; restoring previous build"
        if ! restore_frontend_build; then
            log "Frontend restore after the failed restart did not complete; manual intervention required"
        fi
        return 1
    fi
    if ! wait_for_frontend; then
        log "Frontend did not answer on $FRONTEND_URL within ${FRONTEND_HEALTH_TIMEOUT}s after restart; restoring previous build"
        if ! restore_frontend_build; then
            log "Frontend restore after the failed health gate did not complete; manual intervention required"
        fi
        return 1
    fi
    return 0
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
        log "Deploy of ${target:0:8} FAILED at the backend build or health gate; frontend untouched, will retry next cycle"
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
