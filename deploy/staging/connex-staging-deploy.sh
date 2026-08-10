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
PROC_ROOT=/proc
CGROUP_ROOT=/sys/fs/cgroup

STATE_DIR="$STAGING_DIR/.staging"
RELEASES_DIR="$STATE_DIR/releases"
RELEASE_QUARANTINE_DIR="$STATE_DIR/release-quarantine"
MARKER="$STATE_DIR/deployed-sha"
ROLLBACK_MARKER="$STATE_DIR/rollback-sha"
FRONTEND_RELEASE_MARKER="$STATE_DIR/frontend-release"
FRONTEND_RUNNING_MARKER="$STATE_DIR/frontend-running"
TRANSACTION_FILE="$STATE_DIR/deploy-transaction"
PRUNE_NEEDED_MARKER="$STATE_DIR/prune-needed"
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
SMOKE_SESSION_ACTIVE=0
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
DEPLOY_RETAINED=unknown

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
        bootstrap|preflight|build|bundle|frontend_quiesce|backend_restart|backend_health|frontend_restart|frontend_health|smoke_frontend|smoke_readiness|smoke_version|smoke_capabilities|smoke_auth_entry|smoke_login|smoke_authenticated_route|smoke_logout|smoke_cleanup|marker|recovery)
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
    value="$(sed -n '1p' "$path" 2>/dev/null)" || return 1
    if is_git_sha "$value"; then
        printf '%s\n' "$value" || return 1
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

frontend_control_group() {
    systemctl show -p ControlGroup --value connex-staging-frontend
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
    if ! marker="$(read_sha_file "$MARKER" 2>/dev/null)"; then marker=unavailable; fi
    if ! backend="$(served_git_sha 2>/dev/null)"; then backend=; fi
    if ! frontend="$(live_frontend_sha 2>/dev/null)"; then frontend=; fi
    if ! rollback="$(read_sha_file "$ROLLBACK_MARKER" 2>/dev/null)"; then rollback=unavailable; fi
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
    local target="$1" timeout="$2" deadline now sha unit_state
    now="$(date +%s)" || return 1
    deadline=$((now + timeout))
    while true; do
        now="$(date +%s)" || return 1
        [ "$now" -lt "$deadline" ] || break
        if ! sha="$(served_git_sha)"; then
            sha=
        fi
        if [ "$sha" = "$target" ] && backend_http_healthy; then
            return 0
        fi
        if [ "$sha" = "$target" ]; then
            log "Backend serving sha ${target:0:8} but readiness is not green yet; waiting..."
        elif [ -n "$sha" ]; then
            log "Backend answering with sha ${sha:0:8}, want ${target:0:8}; waiting..."
        fi
        sleep "$POLL_INTERVAL" || return 1
    done
    if ! unit_state="$(backend_unit_state 2>/dev/null)"; then unit_state=unavailable; fi
    log "Backend health gate FAILED for sha ${target:0:8} after ${timeout}s (unit state: $unit_state)"
    return 1
}

verify_backend_stability() {
    local target="$1" pid_before pid_after served
    pid_before="$(backend_pid)" || return 1
    sleep "$STABILITY_INTERVAL" || return 1
    pid_after="$(backend_pid)" || return 1
    if [ "$pid_before" != "$pid_after" ] || [ "$pid_after" = "0" ]; then
        log "Backend stability check FAILED: PID changed ($pid_before -> $pid_after)"
        return 1
    fi
    if ! systemctl is-active --quiet connex-staging-backend; then
        log "Backend stability check FAILED on unit or sha recheck"
        return 1
    fi
    served="$(served_git_sha)" || return 1
    if [ "$served" != "$target" ]; then
        log "Backend stability check FAILED on unit or sha recheck"
        return 1
    fi
    if ! backend_http_healthy; then
        log "Backend readiness blip on stability recheck; retrying once..."
        sleep "$POLL_INTERVAL" || return 1
        if ! backend_http_healthy; then
            log "Backend stability check FAILED on readiness recheck"
            return 1
        fi
    fi
}

wait_for_frontend() {
    local deadline now
    now="$(date +%s)" || return 1
    deadline=$((now + FRONTEND_HEALTH_TIMEOUT))
    while true; do
        now="$(date +%s)" || return 1
        [ "$now" -lt "$deadline" ] || break
        if curl -fsS --max-time 5 -o /dev/null "$FRONTEND_URL/" 2>/dev/null; then
            return 0
        fi
        sleep "$POLL_INTERVAL" || return 1
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
    local runtime="$1"
    (
        local path value digest_line digest
        cd "$runtime" || exit 1
        find . \( -type f -o -type l \) -print0 \
            | LC_ALL=C sort -z \
            | while IFS= read -r -d '' path; do
                if [ -L "$path" ]; then
                    value="$(readlink "$path")" || exit 1
                    printf 'symlink\t%s\t%s\0' "$path" "$value" || exit 1
                else
                    digest_line="$(sha256sum "$path")" || exit 1
                    digest="${digest_line%% *}"
                    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || exit 1
                    printf 'file\t%s\t%s\0' "$path" "$digest" || exit 1
                fi
            done
    ) | sha256sum | awk '{print $1}'
}

verify_release_directory() {
    local sha="$1" release_dir="$2" manifest schema manifest_sha backend_hash frontend_hash provenance
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

    schema="$(release_manifest_value "$manifest" schema_version)" || return 1
    manifest_sha="$(release_manifest_value "$manifest" release_sha)" || return 1
    [ "$schema" = "1" ] && [ "$manifest_sha" = "$sha" ] || return 1
    backend_hash="$(release_manifest_value "$manifest" backend_sha256)" || return 1
    frontend_hash="$(release_manifest_value "$manifest" frontend_sha256)" || return 1
    provenance="$(release_manifest_value "$manifest" frontend_provenance)" || return 1
    [[ "$backend_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
    [[ "$frontend_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
    case "$provenance" in
        built-from-target|rebuilt-from-marker-commit) ;;
        *) return 1 ;;
    esac

    actual_backend_hash="$(sha256sum "$release_dir/backend.jar" | awk '{print $1}')" || return 1
    actual_frontend_hash="$(frontend_tree_sha256 "$release_dir/frontend")" || return 1
    [ "$actual_backend_hash" = "$backend_hash" ] || return 1
    [ "$actual_frontend_hash" = "$frontend_hash" ] || return 1
    embedded_sha="$(jar_git_sha "$release_dir/backend.jar")" || return 1
    frontend_sha="$(read_sha_file "$release_dir/frontend/release-sha")" || return 1
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
        rm -rf "$pending" || true
        return 1
    fi
    backend_hash="$(sha256sum "$pending/backend.jar" | awk '{print $1}')" || {
        rm -rf "$pending" || true
        return 1
    }
    frontend_hash="$(frontend_tree_sha256 "$pending/frontend")" || {
        rm -rf "$pending" || true
        return 1
    }
    if ! {
        printf 'schema_version\t1\n' \
            && printf 'release_sha\t%s\n' "$sha" \
            && printf 'backend_sha256\t%s\n' "$backend_hash" \
            && printf 'frontend_sha256\t%s\n' "$frontend_hash" \
            && printf 'frontend_provenance\t%s\n' "$provenance"
    } > "$pending/manifest.tsv"; then
        rm -rf "$pending" || true
        return 1
    fi
    if ! verify_release_directory "$sha" "$pending" || ! mv "$pending" "$final"; then
        rm -rf "$pending" || true
        return 1
    fi
}

select_previous_backend_artifact() {
    local sha="$1" candidate candidate_sha
    for candidate in \
        "$LIVE_JAR" \
        "$STATE_DIR/artifacts/backend-$sha.jar" \
        "$STATE_DIR/artifacts/rollback.jar"; do
        [ -f "$candidate" ] || continue
        if ! candidate_sha="$(jar_git_sha "$candidate")"; then
            continue
        fi
        if [ "$candidate_sha" = "$sha" ]; then
            printf '%s\n' "$candidate" || return 1
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
    if ! mkdir -p "$source_root"; then
        rm -rf "$scratch" || true
        return 1
    fi
    log "Rebuilding previous frontend ${sha:0:8} into a complete rollback runtime..."
    if ! git -C "$STAGING_DIR" archive "$sha" frontend | tar -x -C "$source_root" \
        || ! build_frontend_runtime_from_source "$sha" "$source_root" .next "$output"; then
        status=1
    fi
    if ! rm -rf "$scratch"; then
        status=1
    fi
    return "$status"
}

ensure_previous_release() {
    local sha="$1" backend_artifact scratch runtime
    if verify_release_bundle "$sha"; then
        return 0
    fi
    if ! backend_artifact="$(select_previous_backend_artifact "$sha")"; then
        log "Cannot prove a previous backend artifact for ${sha:0:8}"
        return 1
    fi
    scratch="$(mktemp -d "$STATE_DIR/.previous-release-${sha}.XXXXXX")" || return 1
    runtime="$scratch/frontend"
    if ! rebuild_previous_frontend_runtime "$sha" "$runtime" \
        || ! seal_release_bundle "$sha" "$backend_artifact" "$runtime" rebuilt-from-marker-commit; then
        rm -rf "$scratch" || true
        return 1
    fi
    rm -rf "$scratch" || return 1
}

build_target_release() {
    local target="$1" scratch source_root runtime backend_jar embedded_sha status=0
    scratch="$(mktemp -d "$STATE_DIR/.target-release-${target}.XXXXXX")" || return 1
    source_root="$scratch/source"
    runtime="$scratch/frontend"
    if ! mkdir -p "$source_root"; then
        rm -rf "$scratch" || true
        return 1
    fi
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
        if ! embedded_sha="$(jar_git_sha "$backend_jar")"; then
            status=1
        elif [ "$embedded_sha" != "$target" ] \
            || ! seal_release_bundle "$target" "$backend_jar" "$runtime" built-from-target; then
            status=1
        fi
    fi
    if ! rm -rf "$scratch"; then
        status=1
    fi
    return "$status"
}

write_transaction() {
    local phase="$1" temporary
    case "$phase" in
        prepared|frontend_stopped|backend_live|frontend_live|committed) ;;
        *) return 1 ;;
    esac
    is_git_sha "$DEPLOY_PREVIOUS" && is_git_sha "$DEPLOY_TARGET" \
        && is_git_sha "$DEPLOY_RETAINED" || return 1
    temporary="$(mktemp "$STATE_DIR/.transaction.XXXXXX")" || return 1
    if ! {
        printf 'schema_version\t2\n' \
            && printf 'prior_sha\t%s\n' "$DEPLOY_PREVIOUS" \
            && printf 'target_sha\t%s\n' "$DEPLOY_TARGET" \
            && printf 'retained_sha\t%s\n' "$DEPLOY_RETAINED" \
            && printf 'phase\t%s\n' "$phase"
    } > "$temporary"; then
        rm -f "$temporary" || true
        return 1
    fi
    if ! mv -f "$temporary" "$TRANSACTION_FILE"; then
        rm -f "$temporary" || true
        return 1
    fi
}

commit_release_markers() {
    local prior="$1" target="$2"
    # Recovery must remain armed until both release markers and the committed
    # transaction are written; any earlier disarm can strand a partial release live.
    write_sha_marker "$ROLLBACK_MARKER" "$prior" || return 1
    write_sha_marker "$MARKER" "$target" || return 1
    write_transaction committed || return 1
    RELEASE_COMMITTED=1
    ROLLBACK_ARMED=0
}

read_transaction() {
    local manifest="$TRANSACTION_FILE" schema prior target retained phase
    [ -f "$manifest" ] && [ ! -L "$manifest" ] || return 1
    schema="$(release_manifest_value "$manifest" schema_version)" || return 1
    prior="$(release_manifest_value "$manifest" prior_sha)" || return 1
    target="$(release_manifest_value "$manifest" target_sha)" || return 1
    retained="$(release_manifest_value "$manifest" retained_sha)" || return 1
    phase="$(release_manifest_value "$manifest" phase)" || return 1
    [ "$schema" = "2" ] && is_git_sha "$prior" && is_git_sha "$target" \
        && is_git_sha "$retained" || return 1
    case "$phase" in
        prepared|frontend_stopped|backend_live|frontend_live|committed) ;;
        *) return 1 ;;
    esac
    printf '%s\t%s\t%s\t%s\n' "$prior" "$target" "$retained" "$phase"
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

pid_in_control_group() {
    local pid="$1" wanted="$2" hierarchy controllers member
    [ -n "$wanted" ] && [ -r "$PROC_ROOT/$pid/cgroup" ] || return 1
    while IFS=: read -r hierarchy controllers member; do
        if [ "$member" != "$wanted" ] && [[ "$member" != "$wanted/"* ]]; then
            continue
        fi
        if { [ "$hierarchy" = "0" ] && [ -z "$controllers" ]; } \
            || [[ ",$controllers," = *,name=systemd,* ]]; then
            return 0
        fi
    done < "$PROC_ROOT/$pid/cgroup"
    return 1
}

pid_descends_from() {
    local pid="$1" ancestor="$2" parent depth=0
    while [ "$pid" != "$ancestor" ]; do
        [[ "$pid" =~ ^[1-9][0-9]*$ ]] && [ "$pid" != "1" ] || return 1
        parent="$(sed -n 's/^PPid:[[:space:]]*//p' "$PROC_ROOT/$pid/status" 2>/dev/null)" \
            || return 1
        [[ "$parent" =~ ^[1-9][0-9]*$ ]] || return 1
        pid="$parent"
        depth=$((depth + 1))
        [ "$depth" -le 64 ] || return 1
    done
}

read_frontend_running() {
    local running_sha pid extra unit_pid control_group expected actual
    [ -f "$FRONTEND_RUNNING_MARKER" ] && [ ! -L "$FRONTEND_RUNNING_MARKER" ] || return 1
    IFS=$'\t' read -r running_sha pid extra < "$FRONTEND_RUNNING_MARKER" || return 1
    is_git_sha "$running_sha" && [[ "$pid" =~ ^[1-9][0-9]*$ ]] && [ -z "$extra" ] || return 1
    unit_pid="$(frontend_pid)" || return 1
    [[ "$unit_pid" =~ ^[1-9][0-9]*$ ]] || return 1
    kill -0 "$unit_pid" 2>/dev/null || return 1
    control_group="$(frontend_control_group)" || return 1
    [[ "$control_group" = /* ]] || return 1
    pid_in_control_group "$pid" "$control_group" || return 1
    pid_descends_from "$pid" "$unit_pid" || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    expected="$(readlink -f "$RELEASES_DIR/$running_sha/frontend")" || return 1
    actual="$(readlink -f "$PROC_ROOT/$pid/cwd")" || return 1
    [ "$actual" = "$expected" ] || return 1
    printf '%s\t%s\n' "$running_sha" "$pid"
}

frontend_runtime_matches() {
    local sha="$1" running running_sha pid extra
    running="$(read_frontend_running)" || return 1
    IFS=$'\t' read -r running_sha pid extra <<< "$running"
    [ "$running_sha" = "$sha" ] && [ -n "$pid" ] && [ -z "$extra" ]
}

activate_frontend() {
    local sha="$1" pid frontend_sha
    verify_release_bundle "$sha" || return 1
    if ! write_sha_marker "$FRONTEND_RELEASE_MARKER" "$sha" \
        || ! sudo systemctl restart connex-staging-frontend \
        || ! wait_for_frontend; then
        return 1
    fi
    frontend_sha="$(live_frontend_sha)" || return 1
    [ "$frontend_sha" = "$sha" ] || return 1
    pid="$(frontend_pid)" || return 1
    systemctl is-active --quiet connex-staging-frontend \
        && [ "$pid" != "0" ] \
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
    local sha="$1" backend frontend
    ROLLBACK_IN_PROGRESS=1
    ROLLBACK_STATE=in_progress
    if ! verify_release_bundle "$sha" || ! verify_release_bundle "$DEPLOY_RETAINED"; then
        log "Rollback FAILED: rollback or retained release bundle is invalid"
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
        || ! activate_frontend "$sha"; then
        log "Rollback FAILED; component or release-marker state is uncertain"
        ROLLBACK_STATE=failed
        return 1
    fi
    backend="$(served_git_sha)" || {
        ROLLBACK_STATE=failed
        return 1
    }
    frontend="$(live_frontend_sha)" || {
        ROLLBACK_STATE=failed
        return 1
    }
    if [ "$backend" != "$sha" ] \
        || [ "$frontend" != "$sha" ] \
        || ! frontend_runtime_matches "$sha" \
        || ! write_sha_marker "$MARKER" "$sha" \
        || ! write_sha_marker "$ROLLBACK_MARKER" "$DEPLOY_RETAINED" \
        || ! rm -f "$TRANSACTION_FILE"; then
        log "Rollback FAILED; component or release-marker state is uncertain"
        ROLLBACK_STATE=failed
        return 1
    fi
    ROLLBACK_STATE=complete
    ROLLBACK_ARMED=0
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
    local expected="$1" allow_legacy_frontend="$2" backend frontend live_jar
    backend="$(served_git_sha)" || return 1
    if ! frontend="$(live_frontend_sha)"; then
        frontend=
    fi
    [ "$backend" = "$expected" ] || return 1
    live_jar="$(jar_git_sha "$LIVE_JAR")" || return 1
    [ "$live_jar" = "$expected" ] || return 1
    if [ "$frontend" = "$expected" ]; then
        frontend_runtime_matches "$expected" || return 1
    else
        [ "$allow_legacy_frontend" = "1" ] && frontend_marker_absent || return 1
    fi
    systemctl is-active --quiet connex-staging-backend \
        && systemctl is-active --quiet connex-staging-frontend
}

verify_no_change_release() {
    local deployed="$1" retained
    validate_live_components "$deployed" 0 || return 1
    verify_release_bundle "$deployed" || return 1
    retained="$(read_sha_file "$ROLLBACK_MARKER")" || return 1
    [ "$retained" != "$deployed" ] || return 1
    verify_release_bundle "$retained"
}

prepare_retained_rollback() {
    local deployed="$1"
    if [ -e "$ROLLBACK_MARKER" ] || [ -L "$ROLLBACK_MARKER" ]; then
        DEPLOY_RETAINED="$(read_sha_file "$ROLLBACK_MARKER")" || return 1
        verify_release_bundle "$DEPLOY_RETAINED" || return 1
        return 0
    fi
    frontend_marker_absent || return 1
    DEPLOY_RETAINED="$deployed"
}

logout_smoke_session() {
    [ "$SMOKE_SESSION_ACTIVE" = "1" ] || return 0
    [ -f "$SMOKE_COOKIE_JAR" ] && [ ! -L "$SMOKE_COOKIE_JAR" ] && [ -s "$SMOKE_COOKIE_JAR" ] || return 1
    awk -F '\t' 'NF == 7 && $6 == "JSESSIONID" && length($7) > 0 { found = 1 } END { exit !found }' \
        "$SMOKE_COOKIE_JAR" || return 1
    if ! curl -fsS --max-time 5 --cookie "$SMOKE_COOKIE_JAR" -X POST -o /dev/null \
        "$FRONTEND_URL/api/auth/logout" 2>/dev/null; then
        return 1
    fi
    SMOKE_SESSION_ACTIVE=0
}

cleanup_smoke_work() {
    local work sensitive scrub_status=0
    [ -n "$SMOKE_COOKIE_JAR" ] || return 0
    case "$SMOKE_COOKIE_JAR" in
        "$STATE_DIR"/.smoke.*/cookies) ;;
        *) return 1 ;;
    esac
    work="${SMOKE_COOKIE_JAR%/cookies}"
    if rm -rf -- "$work"; then
        SMOKE_COOKIE_JAR=
        return 0
    fi
    for sensitive in "$SMOKE_COOKIE_JAR" "$work/dashboard.html"; do
        if [ -f "$sensitive" ] && ! : > "$sensitive"; then
            scrub_status=1
        fi
    done
    if [ "$scrub_status" -eq 0 ]; then
        log "Smoke artifact cleanup FAILED; sensitive files were scrubbed but the directory remains"
    else
        log "Smoke artifact cleanup FAILED; credential material may remain on disk"
    fi
    return 1
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
    # The server can create a session before curl observes a transport or write failure,
    # so EXIT must attempt logout even when the login request itself returns nonzero.
    SMOKE_SESSION_ACTIVE=1
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

    set_failure_context smoke_logout authentication "Post-deploy smoke logout FAILED; session may require operator invalidation"
    logout_smoke_session || return 1

    set_failure_context smoke_cleanup authentication "Post-deploy smoke cleanup FAILED; restoring previous release"
    cleanup_smoke_work || return 1
}

process_uses_release_tree() {
    local pid="$1" resolved="$2" raw_cwd cwd state
    if ! raw_cwd="$(readlink "$PROC_ROOT/$pid/cwd" 2>/dev/null)"; then
        [ ! -d "$PROC_ROOT/$pid" ] && return 1
        state="$(sed -n 's/^State:[[:space:]]*\([A-Z]\).*/\1/p' \
            "$PROC_ROOT/$pid/status" 2>/dev/null)" || {
            [ ! -d "$PROC_ROOT/$pid" ] && return 1
            return 2
        }
        [ "$state" = "Z" ] && return 1
        return 2
    fi
    [[ "$raw_cwd" = *" (deleted)" ]] && return 1
    if ! cwd="$(readlink -f "$PROC_ROOT/$pid/cwd" 2>/dev/null)"; then
        [ ! -d "$PROC_ROOT/$pid" ] && return 1
        state="$(sed -n 's/^State:[[:space:]]*\([A-Z]\).*/\1/p' \
            "$PROC_ROOT/$pid/status" 2>/dev/null)" || {
            [ ! -d "$PROC_ROOT/$pid" ] && return 1
            return 2
        }
        [ "$state" = "Z" ] && return 1
        return 2
    fi
    if [ "$cwd" = "$resolved" ] || [[ "$cwd" = "$resolved/"* ]]; then
        return 0
    fi
    return 1
}

list_process_directories() {
    local process_dir
    for process_dir in "$PROC_ROOT"/[0-9]*; do
        [ -d "$process_dir" ] || continue
        printf '%s\n' "$process_dir" || return 1
    done
}

release_tree_in_use() {
    local release="$1" control_group="$2" deploy_uid resolved cgroup_procs pid usage process_dirs
    local process_dir process_uid
    deploy_uid="$(id -u)" || return 2
    resolved="$(readlink -f "$release")" || return 2
    cgroup_procs="$CGROUP_ROOT$control_group/cgroup.procs"
    [ -r "$cgroup_procs" ] || return 2
    while IFS= read -r pid; do
        [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 2
        usage=0
        process_uses_release_tree "$pid" "$resolved" || usage=$?
        [ "$usage" -ne 0 ] || return 0
        [ "$usage" -eq 1 ] || return 2
    done < "$cgroup_procs"
    process_dirs="$(list_process_directories)" || return 2
    while IFS= read -r process_dir; do
        [ -n "$process_dir" ] || continue
        if ! process_uid="$(stat -c '%u' "$process_dir" 2>/dev/null)"; then
            [ ! -d "$process_dir" ] || return 2
            continue
        fi
        [ "$process_uid" = "$deploy_uid" ] || continue
        pid="${process_dir##*/}"
        usage=0
        process_uses_release_tree "$pid" "$resolved" || usage=$?
        [ "$usage" -ne 0 ] || return 0
        [ "$usage" -eq 1 ] || return 2
    done <<< "$process_dirs"
    return 1
}

prune_needed_state_valid() {
    [ -f "$PRUNE_NEEDED_MARKER" ] && [ ! -L "$PRUNE_NEEDED_MARKER" ] || return 1
    awk 'NR == 1 { valid = ($0 == "pending"); next } { valid = 0 } END { exit !valid }' \
        "$PRUNE_NEEDED_MARKER"
}

ensure_prune_needed() {
    local temporary
    if [ -e "$PRUNE_NEEDED_MARKER" ] || [ -L "$PRUNE_NEEDED_MARKER" ]; then
        prune_needed_state_valid
        return $?
    fi
    temporary="$(mktemp "$STATE_DIR/.prune-needed.XXXXXX")" || return 1
    if ! printf 'pending\n' > "$temporary" || ! mv -f "$temporary" "$PRUNE_NEEDED_MARKER"; then
        rm -f "$temporary" || true
        return 1
    fi
}

quarantine_entry_eligible() {
    local marker="$1/.prune-eligible"
    [ -f "$marker" ] && [ ! -L "$marker" ] || return 1
    awk 'NR == 1 { valid = ($0 == "eligible"); next } { valid = 0 } END { exit !valid }' \
        "$marker"
}

mark_quarantine_entry_eligible() {
    local quarantine="$1" marker="$1/.prune-eligible" temporary
    if [ -e "$marker" ] || [ -L "$marker" ]; then
        quarantine_entry_eligible "$quarantine"
        return $?
    fi
    temporary="$(mktemp "$quarantine/.prune-eligible.XXXXXX")" || return 1
    if ! printf 'eligible\n' > "$temporary" || ! mv -f "$temporary" "$marker"; then
        rm -f "$temporary" || true
        return 1
    fi
}

revoke_quarantine_entry_eligibility() {
    local marker="$1/.prune-eligible"
    if [ -e "$marker" ] || [ -L "$marker" ]; then
        rm -f -- "$marker"
    fi
}

quarantine_has_entries() {
    find "$RELEASE_QUARANTINE_DIR" -mindepth 1 -maxdepth 1 -print -quit | grep -q .
}

prune_backlog_present() {
    [ -e "$PRUNE_NEEDED_MARKER" ] || [ -L "$PRUNE_NEEDED_MARKER" ] \
        || quarantine_has_entries
}

prune_releases() {
    local deployed rollback running running_sha running_pid extra control_group paths path sha usage kept=0
    local quarantine_paths quarantine eligible blocked=0
    if ! ensure_prune_needed; then
        log "Release pruning refused: prune-needed state is invalid or unwritable"
        return 1
    fi
    deployed="$(read_sha_file "$MARKER" 2>/dev/null)" || return 1
    rollback="$(read_sha_file "$ROLLBACK_MARKER" 2>/dev/null)" || return 1
    # Serving-state attestation protects the committed and rollback trees from even
    # entering quarantine. Candidate inspection only controls how quickly an atomic
    # quarantine move becomes eligible for later deletion; it is not the safety edge.
    if ! running="$(read_frontend_running)"; then
        log "Release pruning refused: frontend serving state is missing or inconsistent"
        return 1
    fi
    IFS=$'\t' read -r running_sha running_pid extra <<< "$running"
    if [ "$running_sha" != "$deployed" ] || [ -z "$running_pid" ] || [ -n "$extra" ]; then
        log "Release pruning refused: attested frontend does not match the committed release"
        return 1
    fi
    control_group="$(frontend_control_group)" || return 1
    [[ "$control_group" = /* ]] || return 1
    quarantine_paths="$(find "$RELEASE_QUARANTINE_DIR" -mindepth 1 -maxdepth 1 -print)" \
        || return 1
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        sha="$(basename "$path")" || return 1
        if ! is_git_sha "$sha" || [ ! -d "$path" ] || [ -L "$path" ] \
            || [ "$sha" = "$deployed" ] || [ "$sha" = "$rollback" ] \
            || [ "$sha" = "$running_sha" ]; then
            log "Release pruning refused: quarantine contains an invalid or protected entry"
            return 1
        fi
    done <<< "$quarantine_paths"
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        sha="$(basename "$path")" || return 1
        usage=0
        release_tree_in_use "$path" "$control_group" || usage=$?
        if [ "$usage" -eq 0 ]; then
            if ! revoke_quarantine_entry_eligibility "$path"; then
                log "Release pruning refused: could not revoke eligibility for quarantined $sha"
                blocked=1
                continue
            fi
            log "Release pruning pending: a process still uses quarantined $sha"
            blocked=1
            continue
        fi
        if [ "$usage" -ne 1 ]; then
            if ! revoke_quarantine_entry_eligibility "$path"; then
                log "Release pruning refused: could not revoke eligibility for quarantined $sha"
            fi
            log "Release pruning pending: could not resolve quarantined $sha"
            blocked=1
            continue
        fi
        eligible=0
        if [ -e "$path/.prune-eligible" ] || [ -L "$path/.prune-eligible" ]; then
            if ! quarantine_entry_eligible "$path"; then
                log "Release pruning refused: quarantined $sha has invalid eligibility state"
                blocked=1
                continue
            fi
            eligible=1
        fi
        if [ "$eligible" -eq 1 ]; then
            rm -rf -- "$path" || return 1
            log "Pruned quarantined release ${sha:0:8} after a clean later cycle"
        elif ! mark_quarantine_entry_eligible "$path"; then
            log "Release pruning refused: could not promote quarantined $sha"
            blocked=1
        else
            log "Quarantined release ${sha:0:8} was clean this cycle; deletion waits for the next run"
        fi
    done <<< "$quarantine_paths"
    paths="$(find "$RELEASES_DIR" -mindepth 1 -maxdepth 1 -type d -name '[0-9a-f]*' -printf '%T@ %p\n' \
        | sort -rn | cut -d' ' -f2-)" || return 1
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        sha="$(basename "$path")" || return 1
        if ! is_git_sha "$sha"; then
            continue
        fi
        if [ "$sha" = "$deployed" ] || [ "$sha" = "$rollback" ] || [ "$sha" = "$running_sha" ]; then
            continue
        fi
        kept=$((kept + 1))
        if [ "$kept" -gt 3 ]; then
            usage=0
            release_tree_in_use "$path" "$control_group" || usage=$?
            quarantine="$RELEASE_QUARANTINE_DIR/$sha"
            if [ -e "$quarantine" ] || [ -L "$quarantine" ]; then
                log "Release pruning refused: quarantine destination already exists for $sha"
                blocked=1
                continue
            fi
            # rename(2) within .staging is atomic. Existing cwd and open handles
            # follow the inode, so even a consumer that entered after the scan keeps
            # a complete runtime while the old public release path disappears.
            if ! mv -T -- "$path" "$quarantine"; then
                log "Release pruning refused: could not quarantine $sha"
                blocked=1
                continue
            fi
            if [ "$usage" -eq 1 ]; then
                if ! mark_quarantine_entry_eligible "$quarantine"; then
                    log "Release pruning refused: could not promote quarantined $sha"
                    blocked=1
                    continue
                fi
                log "Quarantined unused release ${sha:0:8}; deletion waits for the next run"
            elif [ "$usage" -eq 0 ]; then
                log "Release pruning pending: quarantined $sha because a process still uses it"
                blocked=1
            else
                log "Release pruning pending: quarantined $sha with indeterminate process state"
                blocked=1
            fi
        fi
    done <<< "$paths"
    if [ "$blocked" -eq 0 ] && ! quarantine_has_entries; then
        rm -f -- "$PRUNE_NEEDED_MARKER" || return 1
    fi
    [ "$blocked" -eq 0 ]
}

deployment_exit() {
    local status="$1" cleanup_status=0
    trap - EXIT INT TERM
    if [ "$SMOKE_SESSION_ACTIVE" = "1" ]; then
        if ! logout_smoke_session; then
            log "Smoke session logout FAILED during exit; authenticated server-side session may remain"
            cleanup_status=1
        fi
    fi
    if [ -n "$SMOKE_COOKIE_JAR" ]; then
        if ! cleanup_smoke_work; then
            cleanup_status=1
        fi
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
    if ! previous="$(read_sha_file "$MARKER" 2>/dev/null)"; then previous=; fi
    if is_git_sha "$previous"; then
        git reset --hard "$previous" --quiet || return 1
        DEPLOY_PREVIOUS="$previous"
    fi
    set_failure_context preflight release "Deploy refused: installed wrapper is stale; prior checkout restored and operator update required"
    return 1
}

recover_transaction() {
    local record prior target retained phase backend frontend marker rollback live_jar
    if ! record="$(read_transaction)"; then record=; fi
    if [ -z "$record" ]; then
        sudo systemctl stop connex-staging-frontend || true
        set_failure_context recovery release "Deployment recovery FAILED: transaction record is invalid"
        return 1
    fi
    IFS=$'\t' read -r prior target retained phase <<< "$record"
    DEPLOY_PREVIOUS="$prior"
    DEPLOY_TARGET="$target"
    DEPLOY_RETAINED="$retained"
    if ! verify_release_bundle "$prior" || ! verify_release_bundle "$target" \
        || ! verify_release_bundle "$retained"; then
        sudo systemctl stop connex-staging-frontend || true
        set_failure_context recovery release "Deployment recovery FAILED: a recorded release bundle is invalid"
        return 1
    fi
    if ! backend="$(served_git_sha)"; then backend=; fi
    if ! frontend="$(live_frontend_sha)"; then frontend=; fi
    if ! marker="$(read_sha_file "$MARKER" 2>/dev/null)"; then marker=; fi
    if ! rollback="$(read_sha_file "$ROLLBACK_MARKER" 2>/dev/null)"; then rollback=; fi
    if ! live_jar="$(jar_git_sha "$LIVE_JAR")"; then live_jar=; fi
    log "Recovering deploy transaction phase=$phase prior=${prior:0:8} target=${target:0:8}"

    if [ "$phase" = "committed" ] && [ "$marker" = "$target" ] \
        && [ "$rollback" = "$prior" ] \
        && [ "$backend" = "$target" ] && [ "$frontend" = "$target" ] \
        && [ "$live_jar" = "$target" ] \
        && frontend_runtime_matches "$target"; then
        set_failure_context recovery release "Recovered release is live but its prune backlog could not be reconciled"
        ensure_prune_needed || return 1
        rm -f "$TRANSACTION_FILE" || return 1
        prune_releases || return 1
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
        set_failure_context marker release "Recovery completed components but could not commit release markers"
        commit_release_markers "$prior" "$target" || return 1
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
    local pinned_target="${CONNEX_DEPLOY_TARGET:-}" transaction_record transaction_target
    local recovery_script recovery_status
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
    DEPLOY_TARGET="$pinned_target"
    mkdir -p "$STATE_DIR" "$RELEASES_DIR" "$RELEASE_QUARANTINE_DIR"

    if [ -e "$TRANSACTION_FILE" ]; then
        if transaction_record="$(read_transaction 2>/dev/null)"; then
            IFS=$'\t' read -r _ transaction_target _ <<< "$transaction_record"
            if [ "$transaction_target" != "$pinned_target" ]; then
                # Recovery must run under the deployment logic from the commit that
                # created the durable transaction. Otherwise script A can activate
                # recorded target B after the wrapper selected A.
                set_failure_context recovery release "Deploy recovery handoff FAILED"
                recovery_script="$(mktemp /tmp/connex-staging-recovery.XXXXXX)" || return 1
                if ! git show "$transaction_target:deploy/staging/connex-staging-deploy.sh" \
                    > "$recovery_script"; then
                    rm -f "$recovery_script" || log "Recovery script cleanup FAILED"
                    return 1
                fi
                log "Handing recovery for ${transaction_target:0:8} to its pinned deployment logic"
                trap - EXIT INT TERM
                recovery_status=0
                CONNEX_DEPLOY_TARGET="$transaction_target" bash "$recovery_script" "$@" \
                    || recovery_status=$?
                if ! rm -f "$recovery_script"; then
                    arm_global_cleanup
                    set_failure_context recovery release "Recovery script cleanup FAILED"
                    return 1
                fi
                exit "$recovery_status"
            fi
        fi
        recover_transaction
        return $?
    fi

    if ! DEPLOY_PREVIOUS="$(read_sha_file "$MARKER" 2>/dev/null)"; then DEPLOY_PREVIOUS=; fi
    if ! is_git_sha "$DEPLOY_TARGET" || ! is_git_sha "$DEPLOY_PREVIOUS"; then
        set_failure_context preflight release "Deploy refused: target or deployed marker is not a full git sha"
        return 1
    fi

    if [ "$DEPLOY_TARGET" = "$DEPLOY_PREVIOUS" ]; then
        if ! verify_no_change_release "$DEPLOY_PREVIOUS"; then
            set_failure_context preflight release "Deploy refused: live components or retained rollback pair are missing or invalid"
            return 1
        fi
        if prune_backlog_present; then
            set_failure_context recovery release "Release prune backlog remains unresolved"
            prune_releases || return 1
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
    if ! prepare_retained_rollback "$DEPLOY_PREVIOUS"; then
        set_failure_context preflight release "Deploy refused: retained rollback marker or bundle is invalid"
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
    commit_release_markers "$DEPLOY_PREVIOUS" "$DEPLOY_TARGET" || return 1
    set_failure_context recovery release "Release committed but transaction cleanup or safe release pruning FAILED"
    ensure_prune_needed || return 1
    rm -f "$TRANSACTION_FILE" || return 1
    prune_releases || return 1
    FAILURE_ACTIVE=0
    log "Done — release ${DEPLOY_TARGET:0:8} live as one verified frontend/backend unit"
}

main "$@"
