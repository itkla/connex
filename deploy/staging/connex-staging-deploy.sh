#!/bin/bash
#
# Transactional, health-gated auto-deploy for the Connex staging box.
#
# Each release is sealed under .staging/releases/<git-sha> as one verified pair:
# a target-stamped backend JAR and a complete Next standalone runtime. The frontend
# is stopped before backend activation, so incompatible component versions are never
# served together. An atomic transaction record plus the EXIT/signal handler covers
# ordinary failures; the next timer run recovers SIGKILL, reboot, and power-loss windows.

set -euo pipefail

STAGING_DIR="${CONNEX_STAGING_DIR:-/opt/connex-staging}"
NODE_BIN=/home/dev/.nvm/versions/node/v24.18.0/bin
PNPM="$NODE_BIN/pnpm"
LOG_TAG="connex-staging-deploy"
LOCK_FILE=/tmp/connex-staging-deploy.lock

STATE_DIR="$STAGING_DIR/.staging"
RELEASES_DIR="$STATE_DIR/releases"
MARKER="$STATE_DIR/deployed-sha"
ROLLBACK_MARKER="$STATE_DIR/rollback-sha"
FRONTEND_RELEASE_MARKER="$STATE_DIR/frontend-release"
FRONTEND_RUNNING_MARKER="$STATE_DIR/frontend-running"
TRANSACTION_FILE="$STATE_DIR/deploy-transaction"
LIVE_JAR="$STAGING_DIR/backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
FRONTEND_ENV=/etc/connex-staging/frontend.env
SMOKE_LOGIN_FILE=/etc/connex-staging/smoke-login.json
SMOKE_LOGIN_REQUIRED_UID=0
SMOKE_LOGIN_REQUIRED_GID="$(id -g)"
SMOKE_LOGIN_REQUIRED_MODE=640

BACKEND_URL=http://127.0.0.1:8081
FRONTEND_URL=http://127.0.0.1:3001
BACKEND_HEALTH_TIMEOUT=900
ROLLBACK_HEALTH_TIMEOUT=600
FRONTEND_HEALTH_TIMEOUT=90
STABILITY_INTERVAL=15
POLL_INTERVAL=5

SMOKE_COOKIE_JAR=
ROLLBACK_ARMED=0
ROLLBACK_IN_PROGRESS=0
ROLLBACK_STATE=not_attempted
RELEASE_COMMITTED=0
FAILURE_ACTIVE=0
FAILURE_GATE=none
FAILURE_COMPONENT=release
FAILURE_MESSAGE=
DEPLOY_TARGET=unknown
DEPLOY_PREVIOUS=unknown

export PATH="$NODE_BIN:$PATH"
export NODE_OPTIONS="--max-old-space-size=2048"
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx900m -Dorg.gradle.daemon=false"

log() {
    echo "[$LOG_TAG] $*"
}

is_git_sha() {
    [[ "$1" =~ ^[0-9a-f]{40}$ ]]
}

safe_sha_state() {
    if is_git_sha "$1"; then
        printf '%s\n' "$1"
    else
        case "$1" in
            none|unknown|unavailable|legacy_unversioned) printf '%s\n' "$1" ;;
            *) printf 'invalid\n' ;;
        esac
    fi
}

safe_alert_label() {
    case "$1" in
        bootstrap|preflight|build|bundle|frontend_quiesce|backend_restart|backend_health|frontend_restart|frontend_health|smoke_frontend|smoke_readiness|smoke_version|smoke_capabilities|smoke_auth_entry|smoke_login|smoke_authenticated_route|marker|recovery)
            printf '%s\n' "$1"
            ;;
        backend|frontend|release|authentication)
            printf '%s\n' "$1"
            ;;
        *)
            printf 'invalid\n'
            ;;
    esac
}

safe_rollback_state() {
    case "$ROLLBACK_STATE" in
        not_attempted|in_progress|complete|failed) printf '%s\n' "$ROLLBACK_STATE" ;;
        *) printf 'invalid\n' ;;
    esac
}

read_sha_file() {
    local path="$1" value
    value="$(sed -n '1p' "$path" 2>/dev/null || true)"
    if is_git_sha "$value"; then
        printf '%s\n' "$value"
        return 0
    fi
    return 1
}

backend_unit_state() {
    systemctl show -p ActiveState --value connex-staging-backend
}

backend_pid() {
    systemctl show -p MainPID --value connex-staging-backend
}

frontend_pid() {
    systemctl show -p MainPID --value connex-staging-frontend
}

served_git_sha() {
    curl -fsS --max-time 5 "$BACKEND_URL/api/version" 2>/dev/null \
        | sed -n 's/.*"gitSha"[[:space:]]*:[[:space:]]*"\([0-9a-f]\{40\}\)".*/\1/p'
}

live_frontend_sha() {
    read_sha_file "$FRONTEND_RELEASE_MARKER"
}

frontend_marker_absent() {
    [ ! -e "$FRONTEND_RELEASE_MARKER" ] && [ ! -L "$FRONTEND_RELEASE_MARKER" ]
}

jar_git_sha() {
    local jar="$1"
    unzip -p "$jar" BOOT-INF/classes/META-INF/build-info.properties 2>/dev/null \
        | sed -n 's/^build\.gitSha=\([0-9a-f]\{40\}\)$/\1/p'
}

set_failure_context() {
    FAILURE_ACTIVE=1
    FAILURE_GATE="$1"
    FAILURE_COMPONENT="$2"
    FAILURE_MESSAGE="$3"
}

deploy_failure_alert() {
    local marker backend frontend rollback
    marker="$(read_sha_file "$MARKER" 2>/dev/null || printf 'unavailable')"
    backend="$(served_git_sha 2>/dev/null || true)"
    frontend="$(live_frontend_sha 2>/dev/null || true)"
    rollback="$(read_sha_file "$ROLLBACK_MARKER" 2>/dev/null || printf 'unavailable')"
    [ -n "$backend" ] || backend=unavailable
    [ -n "$frontend" ] || frontend=legacy_unversioned
    printf '[%s] ALERT status=failure gate=%s component=%s target_sha=%s marker_sha=%s backend_sha=%s frontend_sha=%s rollback_sha=%s rollback_state=%s\n' \
        "$LOG_TAG" \
        "$(safe_alert_label "$FAILURE_GATE")" \
        "$(safe_alert_label "$FAILURE_COMPONENT")" \
        "$(safe_sha_state "$DEPLOY_TARGET")" \
        "$(safe_sha_state "$marker")" \
        "$(safe_sha_state "$backend")" \
        "$(safe_sha_state "$frontend")" \
        "$(safe_sha_state "$rollback")" \
        "$(safe_rollback_state)" >&2
}

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
    local target="$1" timeout="$2" deadline sha
    deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        sha="$(served_git_sha || true)"
        if [ "$sha" = "$target" ] && backend_http_healthy; then
            return 0
        fi
        if [ "$sha" = "$target" ]; then
            log "Backend serving sha ${target:0:8} but readiness is not green yet; waiting..."
        elif [ -n "$sha" ]; then
            log "Backend answering with sha ${sha:0:8}, want ${target:0:8}; waiting..."
        fi
        sleep "$POLL_INTERVAL"
    done
    log "Backend health gate FAILED for sha ${target:0:8} after ${timeout}s (unit state: $(backend_unit_state))"
    return 1
}

verify_backend_stability() {
    local target="$1" pid_before pid_after
    pid_before="$(backend_pid)"
    sleep "$STABILITY_INTERVAL"
    pid_after="$(backend_pid)"
    if [ "$pid_before" != "$pid_after" ] || [ "$pid_after" = "0" ]; then
        log "Backend stability check FAILED: PID changed ($pid_before -> $pid_after)"
        return 1
    fi
    if ! systemctl is-active --quiet connex-staging-backend || [ "$(served_git_sha || true)" != "$target" ]; then
        log "Backend stability check FAILED on unit or sha recheck"
        return 1
    fi
    if ! backend_http_healthy; then
        log "Backend readiness blip on stability recheck; retrying once..."
        sleep "$POLL_INTERVAL"
        if ! backend_http_healthy; then
            log "Backend stability check FAILED on readiness recheck"
            return 1
        fi
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

write_sha_marker() {
    local path="$1" sha="$2" temporary
    is_git_sha "$sha" || return 1
    temporary="$(mktemp "$STATE_DIR/.marker.XXXXXX")" || return 1
    if ! printf '%s\n' "$sha" > "$temporary" || ! mv -f "$temporary" "$path"; then
        rm -f "$temporary"
        return 1
    fi
}

release_manifest_value() {
    local manifest="$1" key="$2"
    awk -F '\t' -v wanted="$key" '
        $1 == wanted { count += 1; value = $2 }
        END { if (count == 1) print value; else exit 1 }
    ' "$manifest"
}

frontend_tree_sha256() {
    local runtime="$1" path
    (
        cd "$runtime"
        while IFS= read -r -d '' path; do
            if [ -L "$path" ]; then
                printf 'symlink\t%s\t%s\0' "$path" "$(readlink "$path")"
            else
                printf 'file\t%s\t%s\0' "$path" "$(sha256sum "$path" | awk '{print $1}')"
            fi
        done < <(find . \( -type f -o -type l \) -print0 | LC_ALL=C sort -z)
    ) | sha256sum | awk '{print $1}'
}

verify_release_directory() {
    local sha="$1" release_dir="$2" manifest backend_hash frontend_hash provenance
    local actual_backend_hash actual_frontend_hash embedded_sha frontend_sha
    is_git_sha "$sha" || return 1
    manifest="$release_dir/manifest.tsv"
    [ -f "$manifest" ] && [ ! -L "$manifest" ] || return 1
    [ -f "$release_dir/backend.jar" ] && [ ! -L "$release_dir/backend.jar" ] || return 1
    [ -d "$release_dir/frontend" ] && [ ! -L "$release_dir/frontend" ] || return 1
    [ -f "$release_dir/frontend/server.js" ] || return 1
    [ -d "$release_dir/frontend/public" ] || return 1
    [ -f "$release_dir/frontend/release-sha" ] || return 1
    if [ -d "$release_dir/frontend/.next-new/server" ]; then
        [ -d "$release_dir/frontend/.next-new/static" ] || return 1
    elif [ -d "$release_dir/frontend/.next/server" ]; then
        [ -d "$release_dir/frontend/.next/static" ] || return 1
    else
        return 1
    fi

    [ "$(release_manifest_value "$manifest" schema_version)" = "1" ] || return 1
    [ "$(release_manifest_value "$manifest" release_sha)" = "$sha" ] || return 1
    backend_hash="$(release_manifest_value "$manifest" backend_sha256)" || return 1
    frontend_hash="$(release_manifest_value "$manifest" frontend_sha256)" || return 1
    provenance="$(release_manifest_value "$manifest" frontend_provenance)" || return 1
    [[ "$backend_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
    [[ "$frontend_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
    case "$provenance" in
        built-from-target|rebuilt-from-marker-commit) ;;
        *) return 1 ;;
    esac

    actual_backend_hash="$(sha256sum "$release_dir/backend.jar" | awk '{print $1}')"
    actual_frontend_hash="$(frontend_tree_sha256 "$release_dir/frontend")"
    [ "$actual_backend_hash" = "$backend_hash" ] || return 1
    [ "$actual_frontend_hash" = "$frontend_hash" ] || return 1
    embedded_sha="$(jar_git_sha "$release_dir/backend.jar" || true)"
    frontend_sha="$(read_sha_file "$release_dir/frontend/release-sha" || true)"
    [ "$embedded_sha" = "$sha" ] && [ "$frontend_sha" = "$sha" ]
}

verify_release_bundle() {
    local sha="$1"
    verify_release_directory "$sha" "$RELEASES_DIR/$sha"
}

assemble_frontend_runtime() {
    local source_root="$1" dist_name="$2" runtime="$3" sha="$4"
    local dist_dir="$source_root/$dist_name"
    [ -f "$dist_dir/standalone/server.js" ] || return 1
    [ -d "$dist_dir/static" ] || return 1
    if ! mkdir -p "$runtime" \
        || ! cp -a "$dist_dir/standalone/." "$runtime/" \
        || ! mkdir -p "$runtime/$dist_name/static" \
        || ! cp -a "$dist_dir/static/." "$runtime/$dist_name/static/" \
        || ! mkdir -p "$runtime/public" \
        || ! cp -a "$source_root/public/." "$runtime/public/" \
        || ! printf '%s\n' "$sha" > "$runtime/release-sha"; then
        return 1
    fi
}

seal_release_bundle() {
    local sha="$1" backend_jar="$2" runtime="$3" provenance="$4"
    local pending final backend_hash frontend_hash
    is_git_sha "$sha" || return 1
    final="$RELEASES_DIR/$sha"
    if [ -e "$final" ]; then
        if verify_release_directory "$sha" "$final"; then
            log "Reusing verified release bundle ${sha:0:8}"
            return 0
        fi
        log "Release bundle $final exists but is invalid; refusing to overwrite it"
        return 1
    fi
    pending="$(mktemp -d "$RELEASES_DIR/.release-${sha}.XXXXXX")" || return 1
    if ! cp -f "$backend_jar" "$pending/backend.jar" \
        || ! cp -a "$runtime" "$pending/frontend"; then
        rm -rf "$pending"
        return 1
    fi
    backend_hash="$(sha256sum "$pending/backend.jar" | awk '{print $1}')"
    frontend_hash="$(frontend_tree_sha256 "$pending/frontend")"
    {
        printf 'schema_version\t1\n'
        printf 'release_sha\t%s\n' "$sha"
        printf 'backend_sha256\t%s\n' "$backend_hash"
        printf 'frontend_sha256\t%s\n' "$frontend_hash"
        printf 'frontend_provenance\t%s\n' "$provenance"
    } > "$pending/manifest.tsv"
    if ! verify_release_directory "$sha" "$pending" || ! mv "$pending" "$final"; then
        rm -rf "$pending"
        return 1
    fi
}

select_previous_backend_artifact() {
    local sha="$1" candidate
    for candidate in \
        "$LIVE_JAR" \
        "$STATE_DIR/artifacts/backend-$sha.jar" \
        "$STATE_DIR/artifacts/rollback.jar"; do
        if [ -f "$candidate" ] && [ "$(jar_git_sha "$candidate" || true)" = "$sha" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

load_frontend_environment() {
    if [ ! -r "$FRONTEND_ENV" ]; then
        return 1
    fi
    set -a
    # shellcheck disable=SC1090
    if ! source "$FRONTEND_ENV"; then
        set +a
        return 1
    fi
    set +a
}

verify_frontend_build_assets() {
    local frontend_root="$1" dist_dir="$2"
    "$NODE_BIN/node" "$frontend_root/ci/verify_build_chunks.mjs" "$dist_dir"
}

build_frontend_runtime_from_source() (
    local sha="$1" source_root="$2" dist_name="$3" output="$4"
    local frontend_root="$source_root/frontend" original_tsconfig build_status=0 restore_status=0
    original_tsconfig="$source_root/tsconfig.original"
    if ! cp -f "$frontend_root/tsconfig.json" "$original_tsconfig" \
        || ! cd "$frontend_root" \
        || ! load_frontend_environment \
        || ! "$PNPM" install --frozen-lockfile --silent; then
        return 1
    fi
    NEXT_DIST_DIR="$dist_name" "$PNPM" build || build_status=$?
    if ! cp -f "$original_tsconfig" "$frontend_root/tsconfig.json" \
        || ! cmp -s "$original_tsconfig" "$frontend_root/tsconfig.json"; then
        restore_status=1
    fi
    if [ "$build_status" -ne 0 ] || [ "$restore_status" -ne 0 ] \
        || ! verify_frontend_build_assets "$frontend_root" "$frontend_root/$dist_name" \
        || ! assemble_frontend_runtime "$frontend_root" "$dist_name" "$output" "$sha"; then
        return 1
    fi
)

rebuild_previous_frontend_runtime() {
    local sha="$1" output="$2" scratch source_root status=0
    scratch="$(mktemp -d "$STATE_DIR/.previous-frontend-${sha}.XXXXXX")" || return 1
    source_root="$scratch/source"
    mkdir -p "$source_root"
    log "Rebuilding previous frontend ${sha:0:8} into a complete rollback runtime..."
    if ! git -C "$STAGING_DIR" archive "$sha" frontend | tar -x -C "$source_root" \
        || ! build_frontend_runtime_from_source "$sha" "$source_root" .next "$output"; then
        status=1
    fi
    rm -rf "$scratch"
    return "$status"
}

ensure_previous_release() {
    local sha="$1" backend_artifact scratch runtime
    if verify_release_bundle "$sha"; then
        return 0
    fi
    backend_artifact="$(select_previous_backend_artifact "$sha" || true)"
    if [ -z "$backend_artifact" ]; then
        log "Cannot prove a previous backend artifact for ${sha:0:8}"
        return 1
    fi
    scratch="$(mktemp -d "$STATE_DIR/.previous-release-${sha}.XXXXXX")" || return 1
    runtime="$scratch/frontend"
    if ! rebuild_previous_frontend_runtime "$sha" "$runtime" \
        || ! seal_release_bundle "$sha" "$backend_artifact" "$runtime" rebuilt-from-marker-commit; then
        rm -rf "$scratch"
        return 1
    fi
    rm -rf "$scratch"
}

build_target_release() {
    local target="$1" scratch source_root runtime backend_jar status=0
    scratch="$(mktemp -d "$STATE_DIR/.target-release-${target}.XXXXXX")" || return 1
    source_root="$scratch/source"
    runtime="$scratch/frontend"
    mkdir -p "$source_root"
    log "Building isolated frontend and backend release ${target:0:8}..."
    if ! git -C "$STAGING_DIR" archive "$target" | tar -x -C "$source_root" \
        || ! build_frontend_runtime_from_source "$target" "$source_root" .next-new "$runtime" \
        || ! (
            cd "$source_root/backend"
            bash ./gradlew clean bootJar -q -PgitSha="$target"
        ); then
        status=1
    else
        backend_jar="$source_root/backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
        if [ "$(jar_git_sha "$backend_jar" || true)" != "$target" ] \
            || ! seal_release_bundle "$target" "$backend_jar" "$runtime" built-from-target; then
            status=1
        fi
    fi
    rm -rf "$scratch"
    return "$status"
}

write_transaction() {
    local phase="$1" temporary
    case "$phase" in
        prepared|frontend_stopped|backend_live|frontend_live|committed) ;;
        *) return 1 ;;
    esac
    is_git_sha "$DEPLOY_PREVIOUS" && is_git_sha "$DEPLOY_TARGET" || return 1
    temporary="$(mktemp "$STATE_DIR/.transaction.XXXXXX")" || return 1
    {
        printf 'schema_version\t1\n'
        printf 'prior_sha\t%s\n' "$DEPLOY_PREVIOUS"
        printf 'target_sha\t%s\n' "$DEPLOY_TARGET"
        printf 'phase\t%s\n' "$phase"
    } > "$temporary"
    if ! mv -f "$temporary" "$TRANSACTION_FILE"; then
        rm -f "$temporary"
        return 1
    fi
}

read_transaction() {
    local manifest="$TRANSACTION_FILE" schema prior target phase
    [ -f "$manifest" ] && [ ! -L "$manifest" ] || return 1
    schema="$(release_manifest_value "$manifest" schema_version)" || return 1
    prior="$(release_manifest_value "$manifest" prior_sha)" || return 1
    target="$(release_manifest_value "$manifest" target_sha)" || return 1
    phase="$(release_manifest_value "$manifest" phase)" || return 1
    [ "$schema" = "1" ] && is_git_sha "$prior" && is_git_sha "$target" || return 1
    case "$phase" in
        prepared|frontend_stopped|backend_live|frontend_live|committed) ;;
        *) return 1 ;;
    esac
    printf '%s\t%s\t%s\n' "$prior" "$target" "$phase"
}

activate_backend() {
    local sha="$1"
    verify_release_bundle "$sha" || return 1
    if ! cp -f "$RELEASES_DIR/$sha/backend.jar" "$LIVE_JAR" \
        || ! sudo systemctl restart connex-staging-backend \
        || ! wait_for_backend_sha "$sha" "$BACKEND_HEALTH_TIMEOUT" \
        || ! verify_backend_stability "$sha"; then
        return 1
    fi
}

ensure_frontend_launcher() {
    local target="$1" start_command
    is_git_sha "$target" || return 1
    start_command="$(git -C "$STAGING_DIR" show "$target:frontend/package.json" 2>/dev/null \
        | jq -r '.scripts.start // empty')" || return 1
    [ "$start_command" = "bash ../deploy/staging/connex-frontend-start.sh" ] || return 1
    git -C "$STAGING_DIR" cat-file -e "$target:deploy/staging/connex-frontend-start.sh" || return 1
    git -C "$STAGING_DIR" reset --hard "$target" --quiet || return 1
    [ -f "$STAGING_DIR/deploy/staging/connex-frontend-start.sh" ] \
        && git -C "$STAGING_DIR" diff --quiet "$target" -- \
            frontend/package.json deploy/staging/connex-frontend-start.sh
}

frontend_runtime_matches() {
    local sha="$1" running_sha pid extra expected actual
    [ -f "$FRONTEND_RUNNING_MARKER" ] && [ ! -L "$FRONTEND_RUNNING_MARKER" ] || return 1
    IFS=$'\t' read -r running_sha pid extra < "$FRONTEND_RUNNING_MARKER" || return 1
    [ "$running_sha" = "$sha" ] && [[ "$pid" =~ ^[1-9][0-9]*$ ]] && [ -z "$extra" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    expected="$(readlink -f "$RELEASES_DIR/$sha/frontend")" || return 1
    actual="$(readlink -f "/proc/$pid/cwd")" || return 1
    [ "$actual" = "$expected" ]
}

activate_frontend() {
    local sha="$1"
    verify_release_bundle "$sha" || return 1
    if ! write_sha_marker "$FRONTEND_RELEASE_MARKER" "$sha" \
        || ! sudo systemctl restart connex-staging-frontend \
        || ! wait_for_frontend; then
        return 1
    fi
    [ "$(live_frontend_sha || true)" = "$sha" ] || return 1
    systemctl is-active --quiet connex-staging-frontend \
        && [ "$(frontend_pid)" != "0" ] \
        && frontend_runtime_matches "$sha"
}

quiesce_frontend_and_switch_checkout() {
    local target="$1"
    if ! sudo systemctl stop connex-staging-frontend; then
        ROLLBACK_ARMED=0
        return 1
    fi
    ROLLBACK_ARMED=1
    write_transaction frontend_stopped || return 1
    ensure_frontend_launcher "$target"
}

rollback_release() {
    local sha="$1"
    ROLLBACK_IN_PROGRESS=1
    ROLLBACK_STATE=in_progress
    if ! verify_release_bundle "$sha"; then
        log "Rollback FAILED: release bundle ${sha:0:8} is invalid"
        ROLLBACK_STATE=failed
        return 1
    fi
    log "Rolling back the complete application to ${sha:0:8}..."
    if ! sudo systemctl stop connex-staging-frontend \
        || ! ensure_frontend_launcher "$DEPLOY_TARGET" \
        || ! cp -f "$RELEASES_DIR/$sha/backend.jar" "$LIVE_JAR" \
        || ! sudo systemctl restart connex-staging-backend \
        || ! wait_for_backend_sha "$sha" "$ROLLBACK_HEALTH_TIMEOUT" \
        || ! verify_backend_stability "$sha" \
        || ! activate_frontend "$sha" \
        || [ "$(served_git_sha || true)" != "$sha" ] \
        || [ "$(live_frontend_sha || true)" != "$sha" ] \
        || ! frontend_runtime_matches "$sha"; then
        log "Rollback FAILED; frontend remains stopped or component state is uncertain"
        ROLLBACK_STATE=failed
        return 1
    fi
    ROLLBACK_STATE=complete
    ROLLBACK_ARMED=0
    rm -f "$TRANSACTION_FILE"
    log "Rollback complete: frontend and backend ${sha:0:8} restored"
}

validate_smoke_login_file() {
    local owner group mode
    command -v jq >/dev/null 2>&1 || return 1
    [ -f "$SMOKE_LOGIN_FILE" ] && [ ! -L "$SMOKE_LOGIN_FILE" ] && [ -r "$SMOKE_LOGIN_FILE" ] || return 1
    owner="$(stat -c '%u' "$SMOKE_LOGIN_FILE")" || return 1
    group="$(stat -c '%g' "$SMOKE_LOGIN_FILE")" || return 1
    mode="$(stat -c '%a' "$SMOKE_LOGIN_FILE")" || return 1
    [ "$owner" = "$SMOKE_LOGIN_REQUIRED_UID" ] \
        && [ "$group" = "$SMOKE_LOGIN_REQUIRED_GID" ] \
        && [ "$mode" = "$SMOKE_LOGIN_REQUIRED_MODE" ] || return 1
    jq -e '
        type == "object"
        and (keys | sort) == ["password", "username"]
        and (.username | type == "string" and length > 0 and length <= 255)
        and (.password | type == "string" and length > 0 and length <= 255)
    ' "$SMOKE_LOGIN_FILE" >/dev/null 2>&1
}

validate_live_components() {
    local expected="$1" allow_legacy_frontend="$2" backend frontend
    backend="$(served_git_sha || true)"
    frontend="$(live_frontend_sha || true)"
    [ "$backend" = "$expected" ] || return 1
    [ "$(jar_git_sha "$LIVE_JAR" || true)" = "$expected" ] || return 1
    if [ "$frontend" = "$expected" ]; then
        frontend_runtime_matches "$expected" || return 1
    else
        [ "$allow_legacy_frontend" = "1" ] && frontend_marker_absent || return 1
    fi
    systemctl is-active --quiet connex-staging-backend \
        && systemctl is-active --quiet connex-staging-frontend
}

post_deploy_smoke() {
    local target="$1" work frontend_body readiness_body version_body capabilities_body login_body dashboard_body
    work="$(mktemp -d "$STATE_DIR/.smoke.XXXXXX")" || return 1
    umask 077
    SMOKE_COOKIE_JAR="$work/cookies"
    frontend_body="$work/frontend.html"
    readiness_body="$work/readiness.json"
    version_body="$work/version.json"
    capabilities_body="$work/capabilities.json"
    login_body="$work/login.html"
    dashboard_body="$work/dashboard.html"

    set_failure_context smoke_frontend frontend "Post-deploy frontend smoke FAILED"
    curl -fsS --max-time 15 -o "$frontend_body" "$FRONTEND_URL/" || return 1
    grep -q '<main' "$frontend_body" || return 1

    set_failure_context smoke_readiness backend "Post-deploy readiness smoke FAILED"
    curl -fsS --max-time 15 -o "$readiness_body" "$BACKEND_URL/api/health/ready" || return 1
    jq -e '.status == "UP"' "$readiness_body" >/dev/null || return 1

    set_failure_context smoke_version release "Post-deploy version smoke FAILED"
    curl -fsS --max-time 15 -o "$version_body" "$FRONTEND_URL/api/version" || return 1
    jq -e --arg sha "$target" '.gitSha == $sha' "$version_body" >/dev/null || return 1

    set_failure_context smoke_capabilities backend "Post-deploy capabilities smoke FAILED"
    curl -fsS --max-time 15 -o "$capabilities_body" "$FRONTEND_URL/api/capabilities" || return 1
    jq -e '
        type == "object"
        and (.sso | type == "boolean")
        and (.socialLogin | type == "object"
            and (.google | type == "boolean")
            and (.microsoft | type == "boolean"))
        and (.connectedAccounts | type == "object"
            and (.google | type == "boolean")
            and (.microsoft | type == "boolean"))
        and (.connectedCapture | type == "object"
            and (.google | type == "boolean")
            and (.microsoft | type == "boolean"))
        and (.mailManaged | type == "boolean")
        and (.businessCardScanning | type == "boolean")
        and (.businessCardImport | type == "boolean")
        and (.campaignDelivery | type == "boolean")
    ' "$capabilities_body" >/dev/null || return 1

    set_failure_context smoke_auth_entry authentication "Post-deploy authentication entry smoke FAILED"
    curl -fsS --max-time 15 -o "$login_body" "$FRONTEND_URL/auth/login" || return 1
    grep -q 'id="login-username"' "$login_body" || return 1

    set_failure_context smoke_login authentication "Post-deploy smoke login FAILED"
    curl -fsS --max-time 15 \
        --cookie-jar "$SMOKE_COOKIE_JAR" \
        -H 'Content-Type: application/json' \
        --data-binary "@$SMOKE_LOGIN_FILE" \
        -o /dev/null \
        "$FRONTEND_URL/api/auth/login" || return 1

    set_failure_context smoke_authenticated_route frontend "Post-deploy authenticated core route smoke FAILED"
    curl -fsS --max-time 30 \
        --cookie "$SMOKE_COOKIE_JAR" \
        -o "$dashboard_body" \
        "$FRONTEND_URL/dashboard" || return 1
    grep -q 'data-app-main' "$dashboard_body" || return 1

    curl -sS --max-time 5 --cookie "$SMOKE_COOKIE_JAR" -X POST -o /dev/null \
        "$FRONTEND_URL/api/auth/logout" 2>/dev/null || true
    rm -rf "$work"
    SMOKE_COOKIE_JAR=
}

prune_releases() {
    local deployed rollback path sha kept=0
    deployed="$(read_sha_file "$MARKER" 2>/dev/null || true)"
    rollback="$(read_sha_file "$ROLLBACK_MARKER" 2>/dev/null || true)"
    while IFS= read -r path; do
        sha="$(basename "$path")"
        if ! is_git_sha "$sha"; then
            continue
        fi
        if [ "$sha" = "$deployed" ] || [ "$sha" = "$rollback" ]; then
            continue
        fi
        kept=$((kept + 1))
        if [ "$kept" -gt 3 ]; then
            rm -rf "$path"
        fi
    done < <(find "$RELEASES_DIR" -mindepth 1 -maxdepth 1 -type d -name '[0-9a-f]*' -printf '%T@ %p\n' \
        | sort -rn | cut -d' ' -f2-)
}

deployment_exit() {
    local status="$1" cleanup_status=0
    trap - EXIT INT TERM
    if [ -n "$SMOKE_COOKIE_JAR" ]; then
        rm -rf "$(dirname "$SMOKE_COOKIE_JAR")"
        SMOKE_COOKIE_JAR=
    fi
    if [ "$status" -ne 0 ] && [ "$ROLLBACK_ARMED" = "1" ] && [ "$ROLLBACK_IN_PROGRESS" != "1" ] \
        && [ "$RELEASE_COMMITTED" != "1" ]; then
        rollback_release "$DEPLOY_PREVIOUS" || cleanup_status=1
    fi
    if [ "$status" -ne 0 ] && [ "$FAILURE_ACTIVE" = "1" ]; then
        [ -z "$FAILURE_MESSAGE" ] || log "$FAILURE_MESSAGE"
        deploy_failure_alert
    fi
    if [ "$cleanup_status" -ne 0 ]; then
        status=1
    fi
    exit "$status"
}

arm_global_cleanup() {
    trap 'deployment_exit "$?"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
}

guard_wrapper_contract() {
    local pinned_target="$1" previous
    if [ "${CONNEX_DEPLOY_LOCK_HELD:-0}" != "1" ] || [ -n "$pinned_target" ]; then
        return 0
    fi
    previous="$(read_sha_file "$MARKER" 2>/dev/null || true)"
    if is_git_sha "$previous"; then
        git reset --hard "$previous" --quiet || return 1
        DEPLOY_PREVIOUS="$previous"
    fi
    set_failure_context preflight release "Deploy refused: installed wrapper is stale; prior checkout restored and operator update required"
    return 1
}

recover_transaction() {
    local record prior target phase backend frontend marker
    record="$(read_transaction || true)"
    if [ -z "$record" ]; then
        sudo systemctl stop connex-staging-frontend || true
        set_failure_context recovery release "Deployment recovery FAILED: transaction record is invalid"
        return 1
    fi
    IFS=$'\t' read -r prior target phase <<< "$record"
    DEPLOY_PREVIOUS="$prior"
    DEPLOY_TARGET="$target"
    if ! verify_release_bundle "$prior" || ! verify_release_bundle "$target"; then
        sudo systemctl stop connex-staging-frontend || true
        set_failure_context recovery release "Deployment recovery FAILED: a recorded release bundle is invalid"
        return 1
    fi
    backend="$(served_git_sha || true)"
    frontend="$(live_frontend_sha || true)"
    marker="$(read_sha_file "$MARKER" 2>/dev/null || true)"
    log "Recovering deploy transaction phase=$phase prior=${prior:0:8} target=${target:0:8}"

    if [ "$phase" = "committed" ] && [ "$marker" = "$target" ] \
        && [ "$backend" = "$target" ] && [ "$frontend" = "$target" ] \
        && [ "$(jar_git_sha "$LIVE_JAR" || true)" = "$target" ] \
        && frontend_runtime_matches "$target"; then
        rm -f "$TRANSACTION_FILE"
        FAILURE_ACTIVE=0
        return 0
    fi
    if [ "$phase" = "prepared" ] && [ "$backend" = "$prior" ] \
        && { [ "$frontend" = "$prior" ] || frontend_marker_absent; } \
        && systemctl is-active --quiet connex-staging-backend \
        && systemctl is-active --quiet connex-staging-frontend; then
        rm -f "$TRANSACTION_FILE"
        set_failure_context recovery release "Interrupted deploy had not activated anything; prior release stayed live and target will retry next cycle"
        return 1
    fi
    if { [ "$phase" = "backend_live" ] || [ "$phase" = "frontend_live" ]; } \
        && [ "$backend" = "$target" ]; then
        if ! validate_smoke_login_file; then
            set_failure_context preflight authentication "Recovery refused: smoke credential file is missing or unsafe"
            ROLLBACK_ARMED=1
            return 1
        fi
        ROLLBACK_ARMED=1
        set_failure_context backend_restart backend "Recovery could not reinstall and health-gate the target backend"
        sudo systemctl stop connex-staging-frontend || return 1
        ensure_frontend_launcher "$target" || return 1
        activate_backend "$target" || return 1
        set_failure_context frontend_restart frontend "Recovery could not activate the target frontend"
        activate_frontend "$target" || return 1
        set_failure_context smoke_frontend release "Recovery smoke FAILED"
        post_deploy_smoke "$target" || return 1
        if ! write_sha_marker "$ROLLBACK_MARKER" "$prior"; then
            set_failure_context marker release "Recovery could not record rollback sha"
            return 1
        fi
        RELEASE_COMMITTED=1
        ROLLBACK_ARMED=0
        if ! write_sha_marker "$MARKER" "$target" || ! write_transaction committed; then
            set_failure_context marker release "Recovery completed components but could not commit release markers"
            return 1
        fi
        rm -f "$TRANSACTION_FILE"
        FAILURE_ACTIVE=0
        log "Recovered and committed release ${target:0:8}"
        return 0
    fi

    ROLLBACK_ARMED=1
    set_failure_context recovery release "Interrupted deployment restored the previous complete release; target will retry next cycle"
    rollback_release "$prior" || return 1
    return 1
}

main() {
    local pinned_target="${CONNEX_DEPLOY_TARGET:-}"
    arm_global_cleanup
    set_failure_context bootstrap release "Deploy FAILED during bootstrap"
    if [ "${CONNEX_DEPLOY_LOCK_HELD:-0}" != "1" ]; then
        exec 9>"$LOCK_FILE"
        flock -n 9 || { log "Deploy already in progress, skipping"; FAILURE_ACTIVE=0; return 0; }
    fi
    if ! cd "$STAGING_DIR"; then
        return 1
    fi
    guard_wrapper_contract "$pinned_target" || return 1
    if [ -n "$pinned_target" ]; then
        if ! is_git_sha "$pinned_target" || ! git cat-file -e "$pinned_target^{commit}"; then
            set_failure_context preflight release "Deploy refused: wrapper supplied an invalid target commit"
            return 1
        fi
    elif ! git fetch origin main --quiet; then
        return 1
    else
        pinned_target="$(git rev-parse origin/main)"
    fi
    mkdir -p "$STATE_DIR" "$RELEASES_DIR"

    if [ -e "$TRANSACTION_FILE" ]; then
        recover_transaction
        return $?
    fi

    DEPLOY_TARGET="$pinned_target"
    DEPLOY_PREVIOUS="$(read_sha_file "$MARKER" 2>/dev/null || true)"
    if ! is_git_sha "$DEPLOY_TARGET" || ! is_git_sha "$DEPLOY_PREVIOUS"; then
        set_failure_context preflight release "Deploy refused: target or deployed marker is not a full git sha"
        return 1
    fi

    if [ "$DEPLOY_TARGET" = "$DEPLOY_PREVIOUS" ]; then
        if ! validate_live_components "$DEPLOY_PREVIOUS" 0 \
            || ! verify_release_bundle "$DEPLOY_PREVIOUS"; then
            set_failure_context preflight release "Deploy refused: live components do not match the committed release"
            return 1
        fi
        FAILURE_ACTIVE=0
        return 0
    fi

    if ! validate_live_components "$DEPLOY_PREVIOUS" 1; then
        set_failure_context preflight release "Deploy refused: live component identities or unit state do not match the deployed marker"
        return 1
    fi
    if ! validate_smoke_login_file; then
        set_failure_context preflight authentication "Deploy refused: smoke login file must be root-owned mode 0640 with exact username/password JSON"
        return 1
    fi
    if ! command -v unzip >/dev/null 2>&1; then
        set_failure_context preflight backend "Deploy refused: unzip is required to verify JAR release identity"
        return 1
    fi

    log "Preparing release ${DEPLOY_TARGET:0:8} (currently ${DEPLOY_PREVIOUS:0:8})..."
    set_failure_context bundle release "Deploy FAILED while sealing the previous complete release"
    ensure_previous_release "$DEPLOY_PREVIOUS" || return 1

    set_failure_context build release "Deploy FAILED while building the isolated target release; live release untouched"
    build_target_release "$DEPLOY_TARGET" || return 1

    set_failure_context bundle release "Deploy FAILED while verifying the target release pair"
    verify_release_bundle "$DEPLOY_PREVIOUS" && verify_release_bundle "$DEPLOY_TARGET" || return 1
    write_transaction prepared || return 1

    set_failure_context frontend_quiesce frontend "Deploy FAILED while quiescing or switching the checkout; prior release remains committed"
    quiesce_frontend_and_switch_checkout "$DEPLOY_TARGET" || return 1

    set_failure_context backend_restart backend "Deploy FAILED at backend activation or health gate; restoring previous release"
    activate_backend "$DEPLOY_TARGET" || return 1
    write_transaction backend_live || return 1

    set_failure_context frontend_restart frontend "Deploy FAILED at frontend activation or health gate; restoring previous release"
    activate_frontend "$DEPLOY_TARGET" || return 1
    write_transaction frontend_live || return 1

    set_failure_context smoke_frontend release "Deploy FAILED the post-deploy smoke; restoring previous release"
    post_deploy_smoke "$DEPLOY_TARGET" || return 1

    set_failure_context marker release "Deploy components passed smoke but release markers could not be committed"
    write_sha_marker "$ROLLBACK_MARKER" "$DEPLOY_PREVIOUS" || return 1
    RELEASE_COMMITTED=1
    ROLLBACK_ARMED=0
    write_sha_marker "$MARKER" "$DEPLOY_TARGET" || return 1
    write_transaction committed || return 1
    rm -f "$TRANSACTION_FILE"
    prune_releases
    FAILURE_ACTIVE=0
    log "Done — release ${DEPLOY_TARGET:0:8} live as one verified frontend/backend unit"
}

main "$@"
