#!/bin/bash
#
# Offline regression tests for the staging release transaction. The harness sources
# the real deploy script with its trailing main call removed, creates signed-looking
# test JARs and standalone runtimes, and stubs only systemd and HTTP boundaries.
# ShellCheck cannot see that the sourced deploy functions consume the reassigned
# globals and invoke the boundary stubs declared inside each isolated test case. Mock
# script bodies stay single-quoted here so their variables expand only when executed.
# shellcheck disable=SC2016,SC2030,SC2031,SC2034,SC2317

set -uo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAGING_DEPLOY_DIR="$(cd "$TESTS_DIR/.." && pwd)"
SANDBOX_PARENT="${CONNEX_STAGING_DEPLOY_TEST_ROOT:-/var/tmp/connex-staging-deploy-tests}"
FAILURES=0

for command in jq sha256sum unzip zip; do
    if ! command -v "$command" >/dev/null 2>&1; then
        printf 'harness error: %s is required\n' "$command" >&2
        exit 1
    fi
done

mkdir -p "$SANDBOX_PARENT"
SANDBOX="$(mktemp -d "$SANDBOX_PARENT/run.XXXXXX")"
trap 'rm -rf "$SANDBOX"' EXIT

awk '$0 != "main \"$@\"" { print }' \
    "$STAGING_DEPLOY_DIR/connex-staging-deploy.sh" > "$SANDBOX/deploy-lib.sh"
if [ "$(wc -l < "$STAGING_DEPLOY_DIR/connex-staging-deploy.sh")" -eq "$(wc -l < "$SANDBOX/deploy-lib.sh")" ]; then
    printf 'harness error: deploy main invocation was not removed\n' >&2
    exit 1
fi

assert_status() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" != "$actual" ]; then
        printf 'assert_status %s: expected %s got %s\n' "$label" "$expected" "$actual"
        return 1
    fi
}

assert_equals() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" != "$actual" ]; then
        printf 'assert_equals %s: expected [%s] got [%s]\n' "$label" "$expected" "$actual"
        return 1
    fi
}

assert_contains() {
    local label="$1" needle="$2" file="$3"
    if ! grep -qF -- "$needle" "$file"; then
        printf 'assert_contains %s: missing [%s] in:\n%s\n' "$label" "$needle" "$(sed -n '1,120p' "$file")"
        return 1
    fi
}

assert_absent() {
    local label="$1" needle="$2" file="$3"
    if grep -qF -- "$needle" "$file"; then
        printf 'assert_absent %s: unexpected [%s] in:\n%s\n' "$label" "$needle" "$(sed -n '1,120p' "$file")"
        return 1
    fi
}

assert_file_exists() {
    local label="$1" path="$2"
    if [ ! -e "$path" ]; then
        printf 'assert_file_exists %s: missing %s\n' "$label" "$path"
        return 1
    fi
}

assert_file_missing() {
    local label="$1" path="$2"
    if [ -e "$path" ]; then
        printf 'assert_file_missing %s: still present %s\n' "$label" "$path"
        return 1
    fi
}

load_deploy() {
    local root="$1"
    export CONNEX_STAGING_DIR="$root/staging"
    # shellcheck source=/dev/null
    source "$SANDBOX/deploy-lib.sh"
    set +e
    set -uo pipefail
    unset CONNEX_STAGING_DIR
    STAGING_DIR="$root/staging"
    STATE_DIR="$STAGING_DIR/.staging"
    RELEASES_DIR="$STATE_DIR/releases"
    MARKER="$STATE_DIR/deployed-sha"
    ROLLBACK_MARKER="$STATE_DIR/rollback-sha"
    FRONTEND_RELEASE_MARKER="$STATE_DIR/frontend-release"
    FRONTEND_RUNNING_MARKER="$STATE_DIR/frontend-running"
    TRANSACTION_FILE="$STATE_DIR/deploy-transaction"
    LIVE_JAR="$STAGING_DIR/backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
    SMOKE_LOGIN_FILE="$root/smoke-login.json"
    SMOKE_LOGIN_REQUIRED_UID="$(id -u)"
    SMOKE_LOGIN_REQUIRED_GID="$(id -g)"
    SMOKE_LOGIN_REQUIRED_MODE=640
    BACKEND_URL=http://backend.test
    FRONTEND_URL=http://frontend.test
    mkdir -p "$RELEASES_DIR" "$(dirname "$LIVE_JAR")"
}

make_jar() {
    local path="$1" sha="$2" root
    root="$path.root"
    rm -rf "$root"
    mkdir -p "$root/BOOT-INF/classes/META-INF"
    printf 'build.gitSha=%s\n' "$sha" > "$root/BOOT-INF/classes/META-INF/build-info.properties"
    (
        cd "$root" || return 1
        zip -q -r "$path" BOOT-INF
    )
    rm -rf "$root"
}

make_runtime() {
    local path="$1" sha="$2" dist_name="${3:-.next}"
    mkdir -p "$path/public" "$path/$dist_name/server" "$path/$dist_name/static"
    printf 'server\n' > "$path/server.js"
    printf 'asset\n' > "$path/$dist_name/static/app.js"
    printf 'route\n' > "$path/$dist_name/server/app.js"
    printf 'public\n' > "$path/public/favicon.ico"
    printf '%s\n' "$sha" > "$path/release-sha"
}

make_bundle() {
    local root="$1" sha="$2" provenance="${3:-built-from-target}"
    local jar="$root/$sha.jar" runtime="$root/$sha-runtime"
    make_jar "$jar" "$sha"
    make_runtime "$runtime" "$sha"
    seal_release_bundle "$sha" "$jar" "$runtime" "$provenance"
    rm -rf "$runtime" "$jar"
}

systemctl_active_stub() {
    case "$1" in
        is-active) return 0 ;;
        show)
            case "$*" in
                *MainPID*) printf '4242\n' ;;
                *) printf 'active\n' ;;
            esac
            ;;
        *) return 0 ;;
    esac
}

case_mixed_live_release_refused() (
    local root="$SANDBOX/mixed" previous target
    previous=1111111111111111111111111111111111111111
    target=2222222222222222222222222222222222222222
    load_deploy "$root"
    make_jar "$LIVE_JAR" "$previous"
    printf '%s\n' "$previous" > "$FRONTEND_RELEASE_MARKER"
    served_git_sha() { printf '%s\n' "$target"; }
    systemctl() { systemctl_active_stub "$@"; }
    validate_live_components "$previous" 0 >/dev/null 2>&1
    assert_status backend_mismatch_refused 1 "$?" || return 1

    served_git_sha() { printf '%s\n' "$previous"; }
    printf '%s\n' "$target" > "$FRONTEND_RELEASE_MARKER"
    validate_live_components "$previous" 0 >/dev/null 2>&1
    assert_status frontend_mismatch_refused 1 "$?" || return 1

    rm -f "$FRONTEND_RELEASE_MARKER"
    validate_live_components "$previous" 1 >/dev/null 2>&1
    assert_status explicit_legacy_frontend_only 0 "$?" || return 1
    printf 'corrupt-marker\n' > "$FRONTEND_RELEASE_MARKER"
    validate_live_components "$previous" 1 >/dev/null 2>&1
    assert_status corrupt_marker_refused 1 "$?" || return 1
)

case_complete_pair_integrity_and_tamper_refusal() (
    local root="$SANDBOX/pair" sha
    sha=3333333333333333333333333333333333333333
    load_deploy "$root"
    make_bundle "$root" "$sha"
    verify_release_bundle "$sha"
    assert_status verified_pair 0 "$?" || return 1
    assert_file_exists backend_artifact "$RELEASES_DIR/$sha/backend.jar" || return 1
    assert_file_exists frontend_server "$RELEASES_DIR/$sha/frontend/server.js" || return 1
    assert_file_exists frontend_public "$RELEASES_DIR/$sha/frontend/public/favicon.ico" || return 1
    printf 'tampered\n' >> "$RELEASES_DIR/$sha/frontend/server.js"
    verify_release_bundle "$sha" >/dev/null 2>&1
    assert_status frontend_tamper_refused 1 "$?" || return 1
)

case_isolated_frontend_build_preserves_working_directory() (
    local root="$SANDBOX/isolated-build" sha source_root runtime before after node_log
    local mock_pnpm mock_node_bin
    sha=1212121212121212121212121212121212121212
    load_deploy "$root"
    source_root="$root/source"
    runtime="$root/runtime"
    node_log="$root/node.log"
    mock_pnpm="$root/pnpm"
    mock_node_bin="$root/node-bin"
    mkdir -p "$source_root/frontend/ci" "$source_root/frontend/public" "$mock_node_bin"
    printf '{}\n' > "$source_root/frontend/tsconfig.json"
    printf 'asset\n' > "$source_root/frontend/public/favicon.ico"
    printf 'verifier\n' > "$source_root/frontend/ci/verify_build_chunks.mjs"
    printf '%s\n' \
        '#!/bin/bash' \
        'if [ "${1:-}" = "build" ]; then' \
        '    mkdir -p "$NEXT_DIST_DIR/standalone" "$NEXT_DIST_DIR/static"' \
        '    printf "server\\n" > "$NEXT_DIST_DIR/standalone/server.js"' \
        '    printf "asset\\n" > "$NEXT_DIST_DIR/static/app.js"' \
        'fi' > "$mock_pnpm"
    printf '%s\n' \
        '#!/bin/bash' \
        'printf "%s\\n" "$*" >> "$MOCK_NODE_LOG"' > "$mock_node_bin/node"
    chmod +x "$mock_pnpm" "$mock_node_bin/node"
    export MOCK_NODE_LOG="$node_log"
    PNPM="$mock_pnpm"
    NODE_BIN="$mock_node_bin"
    load_frontend_environment() { return 0; }
    before="$(pwd -P)"
    build_frontend_runtime_from_source "$sha" "$source_root" .next-new "$runtime"
    assert_status isolated_build 0 "$?" || return 1
    after="$(pwd -P)"
    assert_equals working_directory_preserved "$before" "$after" || return 1
    assert_contains target_verifier "$source_root/frontend/ci/verify_build_chunks.mjs" "$node_log" || return 1
    assert_file_exists complete_runtime "$runtime/.next-new/static/app.js" || return 1
)

case_backend_activation_never_skips_target() (
    local root="$SANDBOX/activation" target sudo_log
    target=4444444444444444444444444444444444444444
    sudo_log="$root/sudo.log"
    load_deploy "$root"
    make_bundle "$root" "$target"
    served_git_sha() { printf '%s\n' "$target"; }
    sudo() { printf '%s\n' "$*" >> "$sudo_log"; }
    wait_for_backend_sha() { return 0; }
    verify_backend_stability() { return 0; }
    activate_backend "$target"
    assert_status activation_succeeds 0 "$?" || return 1
    assert_contains backend_restarted 'systemctl restart connex-staging-backend' "$sudo_log" || return 1
    assert_equals installed_target "$target" "$(jar_git_sha "$LIVE_JAR")" || return 1
)

case_denied_frontend_stop_prevents_checkout_switch() (
    local root="$SANDBOX/stop-denied" previous target git_log transaction
    previous=4545454545454545454545454545454545454545
    target=4646464646464646464646464646464646464646
    git_log="$root/git.log"
    load_deploy "$root"
    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    write_transaction prepared || return 1
    sudo() { return 1; }
    git() { printf '%s\n' "$*" >> "$git_log"; return 0; }
    quiesce_frontend_and_switch_checkout "$target" >/dev/null 2>&1
    assert_status denied_stop 1 "$?" || return 1
    assert_file_missing checkout_not_switched "$git_log" || return 1
    transaction="$(read_transaction)" || return 1
    assert_equals transaction_stays_prepared "$previous"$'\t'"$target"$'\t'prepared "$transaction" || return 1
)

case_stale_installed_wrapper_restores_prior_checkout() (
    local root="$SANDBOX/stale-wrapper" previous reset_log
    previous=4747474747474747474747474747474747474747
    reset_log="$root/reset.log"
    load_deploy "$root"
    printf '%s\n' "$previous" > "$MARKER"
    CONNEX_DEPLOY_LOCK_HELD=1
    git() { printf '%s\n' "$*" > "$reset_log"; return 0; }
    guard_wrapper_contract '' >/dev/null 2>&1
    assert_status stale_wrapper_refused 1 "$?" || return 1
    assert_contains prior_checkout_restored "reset --hard $previous --quiet" "$reset_log" || return 1
    assert_equals prior_recorded "$previous" "$DEPLOY_PREVIOUS" || return 1
)

case_pair_rollback_restores_exact_artifacts() (
    local root="$SANDBOX/rollback" previous target sudo_log
    previous=5555555555555555555555555555555555555555
    target=6666666666666666666666666666666666666666
    sudo_log="$root/sudo.log"
    load_deploy "$root"
    make_bundle "$root" "$previous" rebuilt-from-marker-commit
    make_bundle "$root" "$target"
    cp "$RELEASES_DIR/$target/backend.jar" "$LIVE_JAR"
    printf '%s\n' "$target" > "$FRONTEND_RELEASE_MARKER"
    DEPLOY_TARGET="$target"
    served_git_sha() { jar_git_sha "$LIVE_JAR"; }
    sudo() { printf '%s\n' "$*" >> "$sudo_log"; }
    systemctl() { systemctl_active_stub "$@"; }
    ensure_frontend_launcher() { printf 'launcher %s\n' "$1" >> "$sudo_log"; [ "$1" = "$target" ]; }
    frontend_runtime_matches() { [ "$1" = "$previous" ]; }
    wait_for_backend_sha() { [ "$1" = "$previous" ]; }
    verify_backend_stability() { [ "$1" = "$previous" ]; }
    wait_for_frontend() { return 0; }
    rollback_release "$previous"
    assert_status paired_rollback 0 "$?" || return 1
    assert_equals backend_restored "$previous" "$(jar_git_sha "$LIVE_JAR")" || return 1
    assert_equals frontend_restored "$previous" "$(read_sha_file "$FRONTEND_RELEASE_MARKER")" || return 1
    assert_contains frontend_stopped 'systemctl stop connex-staging-frontend' "$sudo_log" || return 1
    assert_contains sealed_launcher "launcher $target" "$sudo_log" || return 1
    assert_contains backend_restarted 'systemctl restart connex-staging-backend' "$sudo_log" || return 1
    assert_contains frontend_restarted 'systemctl restart connex-staging-frontend' "$sudo_log" || return 1
)

install_smoke_fixture() {
    printf '{"username":"smoke-user","password":"smoke-password"}\n' > "$SMOKE_LOGIN_FILE"
    chmod 0640 "$SMOKE_LOGIN_FILE"
}

case_smoke_credentials_require_safe_exact_schema() (
    local root="$SANDBOX/smoke-credentials"
    load_deploy "$root"
    install_smoke_fixture
    validate_smoke_login_file
    assert_status valid_credentials 0 "$?" || return 1
    SMOKE_LOGIN_REQUIRED_GID=999999
    validate_smoke_login_file >/dev/null 2>&1
    assert_status wrong_group_refused 1 "$?" || return 1
    SMOKE_LOGIN_REQUIRED_GID="$(id -g)"
    chmod 0644 "$SMOKE_LOGIN_FILE"
    validate_smoke_login_file >/dev/null 2>&1
    assert_status world_readable_refused 1 "$?" || return 1
    chmod 0640 "$SMOKE_LOGIN_FILE"
    printf '{"username":"smoke","password":"secret","token":"must-not-exist"}\n' > "$SMOKE_LOGIN_FILE"
    validate_smoke_login_file >/dev/null 2>&1
    assert_status extra_secret_key_refused 1 "$?" || return 1
)

smoke_curl_stub() {
    local output='' url='' argument='' body='' cookie_jar='' cookie='' data_binary=''
    while [ "$#" -gt 0 ]; do
        argument="$1"
        case "$argument" in
            -o|--output|--max-time|--cookie-jar|--cookie|-H|--data-binary|-X|-w)
                shift
                [ "$#" -gt 0 ] || return 2
                if [ "$argument" = "-o" ] || [ "$argument" = "--output" ]; then
                    output="$1"
                elif [ "$argument" = "--cookie-jar" ]; then
                    cookie_jar="$1"
                elif [ "$argument" = "--cookie" ]; then
                    cookie="$1"
                elif [ "$argument" = "--data-binary" ]; then
                    data_binary="$1"
                fi
                ;;
            http://*) url="$argument" ;;
        esac
        shift
    done
    printf 'url=%s data=%s cookie=%s\n' "$url" "$data_binary" "$cookie" >> "$SMOKE_CALL_LOG"
    if [ -n "${SMOKE_FAIL_URL:-}" ] && [ "$url" = "$SMOKE_FAIL_URL" ]; then
        return 22
    fi
    case "$url" in
        "$FRONTEND_URL/") body='<html><main>front</main></html>' ;;
        "$BACKEND_URL/api/health/ready") body='{"status":"UP"}' ;;
        "$FRONTEND_URL/api/version") body="{\"gitSha\":\"$SMOKE_TARGET\"}" ;;
        "$FRONTEND_URL/api/capabilities")
            body='{"sso":false,"socialLogin":{"google":false,"microsoft":false},"connectedAccounts":{"google":false,"microsoft":false},"connectedCapture":{"google":false,"microsoft":false},"mailManaged":false,"businessCardScanning":false,"businessCardImport":true,"campaignDelivery":false}'
            [ -z "${SMOKE_CAPABILITIES_BODY:-}" ] || body="$SMOKE_CAPABILITIES_BODY"
            ;;
        "$FRONTEND_URL/auth/login") body='<input id="login-username">' ;;
        "$FRONTEND_URL/api/auth/login")
            [ "$data_binary" = "@$SMOKE_LOGIN_FILE" ] || return 22
            [ -n "$cookie_jar" ] || return 22
            printf 'JSESSIONID\toffline-session-secret\n' > "$cookie_jar"
            body=''
            ;;
        "$FRONTEND_URL/dashboard")
            [ "$cookie" = "$SMOKE_COOKIE_JAR" ] || return 22
            grep -q 'offline-session-secret' "$cookie" || return 22
            body='<main data-app-main>dashboard</main>'
            ;;
        "$FRONTEND_URL/api/auth/logout") body='' ;;
        *) return 22 ;;
    esac
    if [ -n "$output" ] && [ "$output" != "/dev/null" ]; then
        printf '%s\n' "$body" > "$output"
    fi
}

case_post_deploy_smoke_covers_all_gates() (
    local root="$SANDBOX/smoke" expected
    SMOKE_TARGET=7777777777777777777777777777777777777777
    SMOKE_CALL_LOG="$root/calls.log"
    export SMOKE_TARGET SMOKE_CALL_LOG
    load_deploy "$root"
    install_smoke_fixture
    curl() { smoke_curl_stub "$@"; }
    post_deploy_smoke "$SMOKE_TARGET"
    assert_status complete_smoke 0 "$?" || return 1
    assert_contains login_payload_file "data=@$SMOKE_LOGIN_FILE" "$SMOKE_CALL_LOG" || return 1
    assert_absent login_username smoke-user "$SMOKE_CALL_LOG" || return 1
    assert_absent login_password smoke-password "$SMOKE_CALL_LOG" || return 1
    for expected in \
        "$FRONTEND_URL/" \
        "$BACKEND_URL/api/health/ready" \
        "$FRONTEND_URL/api/version" \
        "$FRONTEND_URL/api/capabilities" \
        "$FRONTEND_URL/auth/login" \
        "$FRONTEND_URL/api/auth/login" \
        "$FRONTEND_URL/dashboard"; do
        assert_contains "smoke_$expected" "$expected" "$SMOKE_CALL_LOG" || return 1
    done
)

case_incomplete_capabilities_fail_closed() (
    local root="$SANDBOX/smoke-capabilities"
    SMOKE_TARGET=8787878787878787878787878787878787878787
    SMOKE_CALL_LOG="$root/calls.log"
    SMOKE_CAPABILITIES_BODY='{"sso":false,"socialLogin":{},"connectedAccounts":{},"connectedCapture":{},"mailManaged":false,"businessCardScanning":false,"businessCardImport":true,"campaignDelivery":false}'
    export SMOKE_TARGET SMOKE_CALL_LOG
    load_deploy "$root"
    install_smoke_fixture
    curl() { smoke_curl_stub "$@"; }
    post_deploy_smoke "$SMOKE_TARGET" >/dev/null 2>&1
    assert_status incomplete_capabilities_refused 1 "$?" || return 1
    assert_equals capabilities_gate smoke_capabilities "$FAILURE_GATE" || return 1
)

case_each_smoke_gate_fails_closed() (
    local root="$SANDBOX/smoke-fail" failed_url status
    SMOKE_TARGET=8888888888888888888888888888888888888888
    SMOKE_CALL_LOG="$root/calls.log"
    export SMOKE_TARGET SMOKE_CALL_LOG
    load_deploy "$root"
    install_smoke_fixture
    curl() { smoke_curl_stub "$@"; }
    for failed_url in \
        "$FRONTEND_URL/" \
        "$BACKEND_URL/api/health/ready" \
        "$FRONTEND_URL/api/version" \
        "$FRONTEND_URL/api/capabilities" \
        "$FRONTEND_URL/auth/login" \
        "$FRONTEND_URL/api/auth/login" \
        "$FRONTEND_URL/dashboard"; do
        SMOKE_FAIL_URL="$failed_url"
        export SMOKE_FAIL_URL
        post_deploy_smoke "$SMOKE_TARGET" >/dev/null 2>&1
        status=$?
        assert_status "failed_gate_$failed_url" 1 "$status" || return 1
        rm -rf "$STATE_DIR"/.smoke.*
        SMOKE_COOKIE_JAR=
    done
)

case_backend_live_transaction_recovers_and_commits() (
    local root="$SANDBOX/recovery" previous target
    previous=9999999999999999999999999999999999999999
    target=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    load_deploy "$root"
    make_bundle "$root" "$previous" rebuilt-from-marker-commit
    make_bundle "$root" "$target"
    cp "$RELEASES_DIR/$previous/backend.jar" "$LIVE_JAR"
    printf '%s\n' "$previous" > "$MARKER"
    printf '%s\n' "$previous" > "$FRONTEND_RELEASE_MARKER"
    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    write_transaction backend_live || return 1
    served_git_sha() { printf '%s\n' "$target"; }
    validate_smoke_login_file() { return 0; }
    systemctl() { systemctl_active_stub "$@"; }
    sudo() { return 0; }
    ensure_frontend_launcher() { [ "$1" = "$target" ]; }
    wait_for_backend_sha() { [ "$1" = "$target" ]; }
    verify_backend_stability() { [ "$1" = "$target" ]; }
    activate_frontend() { write_sha_marker "$FRONTEND_RELEASE_MARKER" "$1"; }
    post_deploy_smoke() { return 0; }
    recover_transaction
    assert_status recovered 0 "$?" || return 1
    assert_equals committed_marker "$target" "$(read_sha_file "$MARKER")" || return 1
    assert_equals rollback_marker "$previous" "$(read_sha_file "$ROLLBACK_MARKER")" || return 1
    assert_equals frontend_marker "$target" "$(read_sha_file "$FRONTEND_RELEASE_MARKER")" || return 1
    assert_equals target_backend_reinstalled "$target" "$(jar_git_sha "$LIVE_JAR")" || return 1
    assert_file_missing transaction_removed "$TRANSACTION_FILE" || return 1
)

case_frontend_stopped_transaction_rolls_back() (
    local root="$SANDBOX/recovery-rollback" previous target rollback_log
    previous=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    target=cccccccccccccccccccccccccccccccccccccccc
    rollback_log="$root/rollback.log"
    load_deploy "$root"
    make_bundle "$root" "$previous" rebuilt-from-marker-commit
    make_bundle "$root" "$target"
    printf '%s\n' "$previous" > "$MARKER"
    printf '%s\n' "$previous" > "$FRONTEND_RELEASE_MARKER"
    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    write_transaction frontend_stopped || return 1
    served_git_sha() { printf '%s\n' "$previous"; }
    rollback_release() { printf '%s\n' "$1" > "$rollback_log"; rm -f "$TRANSACTION_FILE"; return 0; }
    recover_transaction >/dev/null 2>&1
    assert_status recovery_reports_interruption 1 "$?" || return 1
    assert_equals exact_previous_rollback "$previous" "$(sed -n '1p' "$rollback_log")" || return 1
    assert_file_missing transaction_removed "$TRANSACTION_FILE" || return 1
)

case_alert_is_actionable_and_secret_free() (
    local root="$SANDBOX/alert" previous target alert_file injected_secret username password session status=0
    previous=dddddddddddddddddddddddddddddddddddddddd
    target=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
    alert_file="$root/alert.log"
    load_deploy "$root"
    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    ROLLBACK_STATE=not_attempted
    printf '%s\n' "$previous" > "$MARKER"
    printf '%s\n' "$target" > "$FRONTEND_RELEASE_MARKER"
    printf '%s\n' "$previous" > "$ROLLBACK_MARKER"
    served_git_sha() { printf '%s\n' "$target"; }
    set_failure_context smoke_authenticated_route frontend "unused"
    username=alert-smoke-user
    password=alert-smoke-password
    session=alert-session-cookie
    injected_secret=attacker-controlled-credential
    printf '{"username":"%s","password":"%s"}\n' "$username" "$password" > "$SMOKE_LOGIN_FILE"
    chmod 0640 "$SMOKE_LOGIN_FILE"
    mkdir -p "$root/smoke"
    SMOKE_COOKIE_JAR="$root/smoke/cookies"
    printf 'JSESSIONID\t%s\n' "$session" > "$SMOKE_COOKIE_JAR"
    ROLLBACK_ARMED=1
    rollback_release() { ROLLBACK_STATE=complete; ROLLBACK_ARMED=0; return 0; }
    ( deployment_exit 1 ) > "$alert_file" 2>&1 || status=$?
    assert_status deployment_failure 1 "$status" || return 1
    assert_equals failed_release_not_committed "$previous" "$(read_sha_file "$MARKER")" || return 1
    assert_contains gate 'gate=smoke_authenticated_route' "$alert_file" || return 1
    assert_contains component 'component=frontend' "$alert_file" || return 1
    assert_contains target "target_sha=$target" "$alert_file" || return 1
    assert_contains backend "backend_sha=$target" "$alert_file" || return 1
    assert_contains frontend "frontend_sha=$target" "$alert_file" || return 1
    assert_contains rollback 'rollback_state=complete' "$alert_file" || return 1
    assert_absent smoke_username "$username" "$alert_file" || return 1
    assert_absent smoke_password "$password" "$alert_file" || return 1
    assert_absent session_secret "$session" "$alert_file" || return 1
    FAILURE_GATE="$injected_secret"
    FAILURE_COMPONENT="$injected_secret"
    DEPLOY_TARGET="$injected_secret"
    ROLLBACK_STATE="$injected_secret"
    printf '%s\n' "$injected_secret" > "$MARKER"
    printf '%s\n' "$injected_secret" > "$FRONTEND_RELEASE_MARKER"
    printf '%s\n' "$injected_secret" > "$ROLLBACK_MARKER"
    served_git_sha() { printf '%s\n' "$injected_secret"; }
    deploy_failure_alert >> "$alert_file" 2>&1
    assert_absent injected_state "$injected_secret" "$alert_file" || return 1
)

case_frontend_launcher_uses_sealed_runtime() (
    local root="$SANDBOX/launcher" state fake_bin launch_log target running_sha
    target=ffffffffffffffffffffffffffffffffffffffff
    state="$root/state"
    fake_bin="$root/bin"
    launch_log="$root/launch.log"
    mkdir -p "$state/releases/$target/frontend" "$fake_bin"
    printf '%s\n' "$target" > "$state/frontend-release"
    printf '%s\n' "$target" > "$state/releases/$target/frontend/release-sha"
    printf 'server\n' > "$state/releases/$target/frontend/server.js"
    {
        printf '#!/bin/bash\n'
        # shellcheck disable=SC2016
        printf 'printf "%%s\\n" "$*" > "$CONNEX_LAUNCH_TEST_LOG"\n'
    } > "$fake_bin/node"
    chmod 0755 "$fake_bin/node"
    PATH="$fake_bin:$PATH" \
        CONNEX_STAGING_STATE_DIR="$state" \
        CONNEX_LAUNCH_TEST_LOG="$launch_log" \
        bash "$STAGING_DEPLOY_DIR/connex-frontend-start.sh"
    assert_equals standalone_server server.js "$(sed -n '1p' "$launch_log")" || return 1
    running_sha="$(cut -f1 "$state/frontend-running")"
    assert_equals attested_runtime "$target" "$running_sha" || return 1
)

run_case() {
    local name="$1" output status=0
    output="$("$name" 2>&1)" || status=$?
    if [ "$status" -eq 0 ]; then
        printf 'ok   %s\n' "$name"
        return 0
    fi
    printf 'FAIL %s\n%s\n' "$name" "$output"
    FAILURES=$((FAILURES + 1))
}

run_case case_mixed_live_release_refused
run_case case_complete_pair_integrity_and_tamper_refusal
run_case case_isolated_frontend_build_preserves_working_directory
run_case case_backend_activation_never_skips_target
run_case case_denied_frontend_stop_prevents_checkout_switch
run_case case_stale_installed_wrapper_restores_prior_checkout
run_case case_pair_rollback_restores_exact_artifacts
run_case case_smoke_credentials_require_safe_exact_schema
run_case case_post_deploy_smoke_covers_all_gates
run_case case_incomplete_capabilities_fail_closed
run_case case_each_smoke_gate_fails_closed
run_case case_backend_live_transaction_recovers_and_commits
run_case case_frontend_stopped_transaction_rolls_back
run_case case_alert_is_actionable_and_secret_free
run_case case_frontend_launcher_uses_sealed_runtime

if [ "$FAILURES" -ne 0 ]; then
    printf '%s case(s) failed\n' "$FAILURES" >&2
    exit 1
fi
printf 'all staging deploy cases passed\n'
