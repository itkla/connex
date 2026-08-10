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
    RELEASE_QUARANTINE_DIR="$STATE_DIR/release-quarantine"
    MARKER="$STATE_DIR/deployed-sha"
    ROLLBACK_MARKER="$STATE_DIR/rollback-sha"
    FRONTEND_RELEASE_MARKER="$STATE_DIR/frontend-release"
    FRONTEND_RUNNING_MARKER="$STATE_DIR/frontend-running"
    TRANSACTION_FILE="$STATE_DIR/deploy-transaction"
    PRUNE_NEEDED_MARKER="$STATE_DIR/prune-needed"
    LIVE_JAR="$STAGING_DIR/backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
    SMOKE_LOGIN_FILE="$root/smoke-login.json"
    SMOKE_LOGIN_REQUIRED_UID="$(id -u)"
    SMOKE_LOGIN_REQUIRED_GID="$(id -g)"
    SMOKE_LOGIN_REQUIRED_MODE=640
    BACKEND_URL=http://backend.test
    FRONTEND_URL=http://frontend.test
    mkdir -p "$RELEASES_DIR" "$RELEASE_QUARANTINE_DIR" "$(dirname "$LIVE_JAR")"
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

setup_prune_fixture() {
    local root="$1" deployed="$2" rollback="$3" candidate="$4"
    local recent_one="$5" recent_two="$6" recent_three="$7"
    local fake_proc="$root/proc" fake_cgroup="$root/cgroup" control_group=/test-frontend
    load_deploy "$root"
    mkdir -p \
        "$RELEASES_DIR/$deployed/frontend" \
        "$RELEASES_DIR/$rollback" \
        "$RELEASES_DIR/$candidate" \
        "$RELEASES_DIR/$recent_one" \
        "$RELEASES_DIR/$recent_two" \
        "$RELEASES_DIR/$recent_three" \
        "$fake_proc/$$" \
        "$fake_cgroup$control_group"
    printf 'candidate-runtime\n' > "$RELEASES_DIR/$candidate/live-sentinel"
    touch -d @10 "$RELEASES_DIR/$candidate"
    touch -d @20 "$RELEASES_DIR/$recent_one"
    touch -d @30 "$RELEASES_DIR/$recent_two"
    touch -d @40 "$RELEASES_DIR/$recent_three"
    printf '%s\n' "$deployed" > "$MARKER"
    printf '%s\n' "$rollback" > "$ROLLBACK_MARKER"
    printf '%s\t%s\n' "$deployed" "$$" > "$FRONTEND_RUNNING_MARKER"
    ln -s "$RELEASES_DIR/$deployed/frontend" "$fake_proc/$$/cwd"
    printf '0::%s\n' "$control_group" > "$fake_proc/$$/cgroup"
    printf 'Name:\ttest\nState:\tS (sleeping)\nPPid:\t1\n' > "$fake_proc/$$/status"
    printf '%s\n' "$$" > "$fake_cgroup$control_group/cgroup.procs"
    PROC_ROOT="$fake_proc"
    CGROUP_ROOT="$fake_cgroup"
    frontend_pid() { printf '%s\n' "$$"; }
    frontend_control_group() { printf '/test-frontend\n'; }
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

make_wrapper_boundary_shims() {
    local fake_bin="$1"
    mkdir -p "$fake_bin"
    {
        printf '#!/bin/bash\n'
        printf 'set -eu\n'
        printf 'case "${1:-}" in\n'
        printf '    fetch)\n'
        printf '        count=0\n'
        printf '        if [ -f "$MOCK_FETCH_STATE" ]; then count="$(sed -n "1p" "$MOCK_FETCH_STATE")"; fi\n'
        printf '        count=$((count + 1))\n'
        printf '        printf "%%s\\n" "$count" > "$MOCK_FETCH_STATE"\n'
        printf '        ;;\n'
        printf '    rev-parse)\n'
        printf '        count="$(sed -n "1p" "$MOCK_FETCH_STATE")"\n'
        printf '        if [ "$count" -eq 1 ]; then printf "%%s\\n" "$MOCK_SELECTED_SHA"; else printf "%%s\\n" "$MOCK_ADVANCED_SHA"; fi\n'
        printf '        ;;\n'
        printf '    show)\n'
        printf '        case "${2:-}" in\n'
        printf '            "$MOCK_SELECTED_SHA:deploy/staging/connex-staging-deploy.sh") source="$MOCK_SELECTED_DEPLOY_SOURCE" ;;\n'
        printf '            "$MOCK_ADVANCED_SHA:deploy/staging/connex-staging-deploy.sh") source="$MOCK_ADVANCED_DEPLOY_SOURCE" ;;\n'
        printf '            *) exit 2 ;;\n'
        printf '        esac\n'
        printf '        printf "%%s\\n" "$2" >> "$MOCK_SHOW_LOG"\n'
        printf '        /bin/cat "$source"\n'
        printf '        ;;\n'
        printf '    cat-file)\n'
        printf '        [ "${2:-}" = "-e" ]\n'
        printf '        case "${3:-}" in\n'
        printf '            "$MOCK_SELECTED_SHA^{commit}"|"$MOCK_ADVANCED_SHA^{commit}") ;;\n'
        printf '            *) exit 2 ;;\n'
        printf '        esac\n'
        printf '        ;;\n'
        printf '    *)\n'
        printf '        printf "unexpected git call: %%s\\n" "$*" >&2\n'
        printf '        exit 2\n'
        printf '        ;;\n'
        printf 'esac\n'
    } > "$fake_bin/git"
    {
        printf '#!/bin/bash\n'
        printf 'case "${1:-}" in\n'
        printf '    is-active) exit 0 ;;\n'
        printf '    show)\n'
        printf '        case "$*" in\n'
        printf '            *ControlGroup*) printf "%%s\\n" "$MOCK_FRONTEND_CONTROL_GROUP" ;;\n'
        printf '            *MainPID*) printf "%%s\\n" "$MOCK_FRONTEND_PID" ;;\n'
        printf '            *) exit 2 ;;\n'
        printf '        esac\n'
        printf '        ;;\n'
        printf '    *) exit 2 ;;\n'
        printf 'esac\n'
    } > "$fake_bin/systemctl"
    {
        printf '#!/bin/bash\n'
        printf 'served_sha="${MOCK_SERVED_SHA:-$MOCK_SELECTED_SHA}"\n'
        printf 'printf '\''{"gitSha":"%%s"}\\n'\'' "$served_sha"\n'
    } > "$fake_bin/curl"
    chmod 0755 "$fake_bin/git" "$fake_bin/systemctl" "$fake_bin/curl"
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

case_failed_identity_probe_output_is_rejected() (
    local root="$SANDBOX/identity-probe" expected
    expected=2323232323232323232323232323232323232323
    load_deploy "$root"
    make_jar "$LIVE_JAR" "$expected"
    printf '%s\n' "$expected" > "$FRONTEND_RELEASE_MARKER"
    systemctl() { systemctl_active_stub "$@"; }
    frontend_runtime_matches() { return 0; }

    served_git_sha() { builtin printf '%s\n' "$expected"; return 1; }
    validate_live_components "$expected" 0 >/dev/null 2>&1
    assert_status failed_served_probe_refused 1 "$?" || return 1

    served_git_sha() { builtin printf '%s\n' "$expected"; }
    jar_git_sha() { builtin printf '%s\n' "$expected"; return 1; }
    validate_live_components "$expected" 0 >/dev/null 2>&1
    assert_status failed_jar_probe_refused 1 "$?" || return 1

    jar_git_sha() { builtin printf '%s\n' "$expected"; }
    live_frontend_sha() { builtin printf '%s\n' "$expected"; return 1; }
    validate_live_components "$expected" 0 >/dev/null 2>&1
    assert_status failed_frontend_probe_refused 1 "$?" || return 1
)

case_frontend_attestation_requires_current_unit_lineage() (
    local root="$SANDBOX/frontend-lineage" sha recorded_pid sibling_pid mock_unit_pid record
    local fake_proc mock_control_group
    sha=2424242424242424242424242424242424242424
    load_deploy "$root"
    mkdir -p "$RELEASES_DIR/$sha/frontend"
    (
        cd "$RELEASES_DIR/$sha/frontend" || exit 1
        exec sleep 300
    ) &
    recorded_pid=$!
    sleep 300 &
    sibling_pid=$!
    trap 'kill "$recorded_pid" "$sibling_pid" 2>/dev/null || true; wait "$recorded_pid" "$sibling_pid" 2>/dev/null || true' EXIT
    printf '%s\t%s\n' "$sha" "$recorded_pid" > "$FRONTEND_RUNNING_MARKER"
    mock_unit_pid=$$
    mock_control_group=/test-frontend
    fake_proc="$root/proc"
    mkdir -p "$fake_proc/$recorded_pid"
    ln -s "$RELEASES_DIR/$sha/frontend" "$fake_proc/$recorded_pid/cwd"
    printf '0::%s\n' "$mock_control_group" > "$fake_proc/$recorded_pid/cgroup"
    printf 'Name:\ttest\nPPid:\t%s\n' "$mock_unit_pid" > "$fake_proc/$recorded_pid/status"
    PROC_ROOT="$fake_proc"
    frontend_control_group() { printf '%s\n' "$mock_control_group"; }

    frontend_pid() { printf '%s\n' "$sibling_pid"; }
    read_frontend_running >/dev/null 2>&1
    assert_status stale_sibling_cannot_attest_runtime 1 "$?" || return 1

    frontend_pid() { printf '%s\n' "$mock_unit_pid"; }
    record="$(read_frontend_running)"
    assert_status current_unit_ancestor_attests_runtime 0 "$?" || return 1
    assert_equals attested_sha_and_pid "$sha"$'\t'"$recorded_pid" "$record" || return 1
)

case_frontend_attestation_rejects_wrong_cgroup() (
    local root="$SANDBOX/frontend-wrong-cgroup" sha recorded_pid fake_proc
    sha=2525252525252525252525252525252525252525
    load_deploy "$root"
    mkdir -p "$RELEASES_DIR/$sha/frontend"
    (
        cd "$RELEASES_DIR/$sha/frontend" || exit 1
        exec sleep 300
    ) &
    recorded_pid=$!
    trap 'kill "$recorded_pid" 2>/dev/null || true; wait "$recorded_pid" 2>/dev/null || true' EXIT
    printf '%s\t%s\n' "$sha" "$recorded_pid" > "$FRONTEND_RUNNING_MARKER"
    fake_proc="$root/proc"
    mkdir -p "$fake_proc/$recorded_pid"
    ln -s "$RELEASES_DIR/$sha/frontend" "$fake_proc/$recorded_pid/cwd"
    printf '0::/wrong-frontend-unit\n' > "$fake_proc/$recorded_pid/cgroup"
    printf 'Name:\ttest\nState:\tS (sleeping)\nPPid:\t%s\n' "$$" > "$fake_proc/$recorded_pid/status"
    PROC_ROOT="$fake_proc"
    frontend_pid() { printf '%s\n' "$$"; }
    frontend_control_group() { printf '/test-frontend\n'; }

    read_frontend_running >/dev/null 2>&1
    assert_status wrong_cgroup_cannot_attest_runtime 1 "$?" || return 1
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

case_no_change_requires_valid_retained_pair() (
    local root="$SANDBOX/no-change-retained" deployed retained
    deployed=3434343434343434343434343434343434343434
    retained=3535353535353535353535353535353535353535
    load_deploy "$root"
    make_bundle "$root" "$deployed"
    make_bundle "$root" "$retained" rebuilt-from-marker-commit
    printf '%s\n' "$retained" > "$ROLLBACK_MARKER"
    validate_live_components() { [ "$1" = "$deployed" ] && [ "$2" = "0" ]; }

    verify_no_change_release "$deployed"
    assert_status retained_pair_valid 0 "$?" || return 1

    printf '%s\n' "$deployed" > "$ROLLBACK_MARKER"
    verify_no_change_release "$deployed" >/dev/null 2>&1
    assert_status current_release_is_not_a_rollback_pair 1 "$?" || return 1
    printf '%s\n' "$retained" > "$ROLLBACK_MARKER"

    printf 'tampered\n' >> "$RELEASES_DIR/$retained/frontend/server.js"
    verify_no_change_release "$deployed" >/dev/null 2>&1
    assert_status corrupt_retained_pair_refused 1 "$?" || return 1

    rm -rf "$RELEASES_DIR/${retained:?}"
    verify_no_change_release "$deployed" >/dev/null 2>&1
    assert_status missing_retained_pair_refused 1 "$?" || return 1
)

case_prune_aborts_when_protected_marker_read_fails() (
    local root="$SANDBOX/prune-marker" deployed rollback sha status
    deployed=3636363636363636363636363636363636363636
    rollback=3737373737373737373737373737373737373737
    load_deploy "$root"
    for sha in \
        "$deployed" \
        "$rollback" \
        3838383838383838383838383838383838383838 \
        3939393939393939393939393939393939393939 \
        4040404040404040404040404040404040404040 \
        4141414141414141414141414141414141414141; do
        mkdir -p "$RELEASES_DIR/$sha"
    done
    read_sha_file() { builtin printf '%s\n' "$deployed"; return 1; }

    prune_releases >/dev/null 2>&1
    status=$?
    assert_status prune_marker_read_failure 1 "$status" || return 1
    for sha in \
        "$deployed" \
        "$rollback" \
        3838383838383838383838383838383838383838 \
        3939393939393939393939393939393939393939 \
        4040404040404040404040404040404040404040 \
        4141414141414141414141414141414141414141; do
        assert_file_exists "prune_preserved_$sha" "$RELEASES_DIR/$sha" || return 1
    done
)

case_consecutive_prunes_preserve_recorded_serving_release() (
    local root="$SANDBOX/consecutive-prune" deployed rollback serving serving_pid orphan_pid status output
    local first_extra second_extra third_extra fourth_extra mock_unit_pid mock_control_group
    local mock_proc_root mock_cgroup_root
    deployed=4242424242424242424242424242424242424242
    rollback=4343434343434343434343434343434343434343
    serving=4444444444444444444444444444444444444444
    first_extra=4545454545454545454545454545454545454545
    second_extra=4646464646464646464646464646464646464646
    third_extra=4747474747474747474747474747474747474747
    fourth_extra=4848484848484848484848484848484848484848
    load_deploy "$root"
    mkdir -p \
        "$RELEASES_DIR/$deployed" \
        "$RELEASES_DIR/$rollback" \
        "$RELEASES_DIR/$serving/frontend" \
        "$RELEASES_DIR/$first_extra" \
        "$RELEASES_DIR/$second_extra"
    touch -d @10 "$RELEASES_DIR/$deployed"
    touch -d @20 "$RELEASES_DIR/$rollback"
    touch -d @100 "$RELEASES_DIR/$serving"
    touch -d @200 "$RELEASES_DIR/$first_extra"
    touch -d @300 "$RELEASES_DIR/$second_extra"
    printf '%s\n' "$deployed" > "$MARKER"
    printf '%s\n' "$rollback" > "$ROLLBACK_MARKER"
    (
        cd "$RELEASES_DIR/$serving/frontend" || exit 1
        exec sleep 300
    ) &
    serving_pid=$!
    trap 'kill "$serving_pid" 2>/dev/null || true; wait "$serving_pid" 2>/dev/null || true' EXIT
    printf '%s\t%s\n' "$serving" "$serving_pid" > "$FRONTEND_RUNNING_MARKER"
    mock_unit_pid=$$
    mock_control_group=/test-frontend
    # Successful pruning must depend only on fixture-owned process state. Sampling
    # host /proc makes unrelated same-UID processes with unreadable cwd indeterminate.
    mock_proc_root="$root/proc"
    mkdir -p "$mock_proc_root/$serving_pid"
    ln -s "$RELEASES_DIR/$serving/frontend" "$mock_proc_root/$serving_pid/cwd"
    printf '0::%s\n' "$mock_control_group" > "$mock_proc_root/$serving_pid/cgroup"
    printf 'Name:\ttest\nPPid:\t%s\n' "$mock_unit_pid" > "$mock_proc_root/$serving_pid/status"
    mock_cgroup_root="$root/cgroup"
    mkdir -p "$mock_cgroup_root$mock_control_group"
    printf '%s\n' "$serving_pid" > "$mock_cgroup_root$mock_control_group/cgroup.procs"

    prune_cycle() (
        load_deploy "$root"
        PROC_ROOT="$mock_proc_root"
        CGROUP_ROOT="$mock_cgroup_root"
        frontend_pid() { printf '%s\n' "$mock_unit_pid"; }
        frontend_control_group() { printf '%s\n' "$mock_control_group"; }
        prune_releases
    )

    output="$(prune_cycle 2>&1)"
    status=$?
    assert_status first_run_refuses_uncertain_serving_state 1 "$status" || return 1
    assert_contains first_run_reports_attested_mismatch \
        'Release pruning refused: attested frontend does not match the committed release' \
        <(printf '%s\n' "$output") || return 1
    assert_file_exists first_run_preserves_serving_tree "$RELEASES_DIR/$serving" || return 1

    mkdir -p "$RELEASES_DIR/$third_extra" "$RELEASES_DIR/$fourth_extra"
    touch -d @400 "$RELEASES_DIR/$third_extra"
    touch -d @500 "$RELEASES_DIR/$fourth_extra"
    output="$(prune_cycle 2>&1)"
    status=$?
    assert_status second_run_refuses_uncertain_serving_state 1 "$status" || return 1
    assert_contains second_run_reports_attested_mismatch \
        'Release pruning refused: attested frontend does not match the committed release' \
        <(printf '%s\n' "$output") || return 1
    assert_file_exists second_run_preserves_serving_tree "$RELEASES_DIR/$serving" || return 1
    assert_file_exists refused_run_does_not_partially_prune "$RELEASES_DIR/$first_extra" || return 1

    printf '%s\n' "$serving" > "$MARKER"
    (
        cd "$RELEASES_DIR/$first_extra" || exit 1
        exec sleep 300
    ) &
    orphan_pid=$!
    mkdir -p "$mock_proc_root/$orphan_pid"
    ln -s "$RELEASES_DIR/$first_extra" "$mock_proc_root/$orphan_pid/cwd"
    printf '%s\n' "$serving_pid" > "$mock_cgroup_root$mock_control_group/cgroup.procs"
    trap 'kill "$serving_pid" "$orphan_pid" 2>/dev/null || true; wait "$serving_pid" "$orphan_pid" 2>/dev/null || true' EXIT
    output="$(prune_cycle 2>&1)"
    status=$?
    assert_status detached_process_quarantines_without_destructive_gate 0 "$status" || return 1
    assert_contains detached_process_is_reported 'Quarantined release 4545454545454545454545454545454545454545; advisory scan observed a matching consumer' \
        <(printf '%s\n' "$output") || return 1
    assert_file_missing detached_process_leaves_public_release_path "$RELEASES_DIR/$first_extra" || return 1
    assert_file_exists detached_process_runtime_survives_quarantine \
        "$RELEASE_QUARANTINE_DIR/$first_extra" || return 1
    assert_file_missing detached_process_does_not_leave_prune_marker "$PRUNE_NEEDED_MARKER" || return 1
    kill "$orphan_pid" || return 1
    wait "$orphan_pid" 2>/dev/null || true
    rm -rf "$mock_proc_root/${orphan_pid:?}"
    printf '%s\n' "$serving_pid" > "$mock_cgroup_root$mock_control_group/cgroup.procs"
    trap 'kill "$serving_pid" 2>/dev/null || true; wait "$serving_pid" 2>/dev/null || true' EXIT

    prune_cycle >/dev/null 2>&1
    assert_status reconciled_state_reports_quarantine 0 "$?" || return 1
    assert_file_exists reconciled_prune_preserves_serving_tree "$RELEASES_DIR/$serving" || return 1
    assert_file_exists reconciled_prune_keeps_terminal_quarantine "$RELEASE_QUARANTINE_DIR/$first_extra" || return 1
    prune_cycle >/dev/null 2>&1
    assert_status later_clean_cycle_only_reports 0 "$?" || return 1
    assert_file_exists later_clean_cycle_never_unlinks_quarantine "$RELEASE_QUARANTINE_DIR/$first_extra" || return 1
)

case_prune_reports_unknown_deploy_process_state() (
    local root="$SANDBOX/prune-unknown-process" deployed rollback candidate sha serving_pid mock_unit_pid
    local fake_proc fake_cgroup unknown_pid status output
    deployed=5656565656565656565656565656565656565656
    rollback=5757575757575757575757575757575757575757
    candidate=5858585858585858585858585858585858585858
    load_deploy "$root"
    for sha in \
        "$deployed" \
        "$rollback" \
        5959595959595959595959595959595959595959 \
        6060606060606060606060606060606060606060 \
        6161616161616161616161616161616161616161 \
        "$candidate"; do
        mkdir -p "$RELEASES_DIR/$sha"
    done
    mkdir -p "$RELEASES_DIR/$deployed/frontend"
    touch -d @10 "$RELEASES_DIR/$candidate"
    printf '%s\n' "$deployed" > "$MARKER"
    printf '%s\n' "$rollback" > "$ROLLBACK_MARKER"
    (
        cd "$RELEASES_DIR/$deployed/frontend" || exit 1
        exec sleep 300
    ) &
    serving_pid=$!
    trap 'kill "$serving_pid" 2>/dev/null || true; wait "$serving_pid" 2>/dev/null || true' EXIT
    printf '%s\t%s\n' "$deployed" "$serving_pid" > "$FRONTEND_RUNNING_MARKER"

    fake_proc="$root/proc"
    unknown_pid=99999999
    mkdir -p "$fake_proc/$serving_pid" "$fake_proc/$unknown_pid"
    ln -s "$RELEASES_DIR/$deployed/frontend" "$fake_proc/$serving_pid/cwd"
    ln -s "$root/missing-parent/indeterminate-cwd" "$fake_proc/$unknown_pid/cwd"
    printf '0::/test-frontend\n' > "$fake_proc/$serving_pid/cgroup"
    printf 'Name:\tunknown\nState:\tS (sleeping)\nPPid:\t1\n' > "$fake_proc/$unknown_pid/status"
    PROC_ROOT="$fake_proc"
    fake_cgroup="$root/cgroup"
    mkdir -p "$fake_cgroup/test-frontend"
    printf '%s\n' "$serving_pid" > "$fake_cgroup/test-frontend/cgroup.procs"
    CGROUP_ROOT="$fake_cgroup"
    mock_unit_pid=$$
    printf 'Name:\ttest\nPPid:\t%s\n' "$mock_unit_pid" > "$fake_proc/$serving_pid/status"
    frontend_pid() { printf '%s\n' "$mock_unit_pid"; }
    frontend_control_group() { printf '/test-frontend\n'; }

    release_tree_in_use "$RELEASES_DIR/$candidate" /test-frontend
    status=$?
    assert_status unreadable_deploy_process_is_indeterminate 2 "$status" || return 1
    output="$(prune_releases 2>&1)"
    status=$?
    assert_status unknown_deploy_process_quarantines_without_destructive_gate 0 "$status" || return 1
    assert_contains unknown_process_reports_indeterminate_candidate \
        "Quarantined release $candidate; advisory scan was indeterminate" \
        <(printf '%s\n' "$output") || return 1
    assert_file_missing unknown_process_removes_public_candidate_path "$RELEASES_DIR/$candidate" || return 1
    assert_file_exists unknown_process_preserves_quarantined_candidate \
        "$RELEASE_QUARANTINE_DIR/$candidate" || return 1
    assert_file_missing unknown_process_clears_completed_prune_marker "$PRUNE_NEEDED_MARKER" || return 1

    output="$(prune_releases 2>&1)"
    status=$?
    assert_status repeated_unknown_process_is_advisory 0 "$status" || return 1
    assert_contains repeated_unknown_process_reports_indeterminate \
        "Release quarantine report: advisory scan was indeterminate for $candidate" \
        <(printf '%s\n' "$output") || return 1

    printf 'Name:\tunknown\nState:\tZ (zombie)\nPPid:\t1\n' > "$fake_proc/$unknown_pid/status"
    prune_releases >/dev/null 2>&1
    assert_status zombie_is_reported_as_no_matching_consumer 0 "$?" || return 1
    assert_file_exists terminal_quarantine_survives_clean_cycle \
        "$RELEASE_QUARANTINE_DIR/$candidate" || return 1
    prune_releases >/dev/null 2>&1
    assert_status later_cycle_remains_advisory 0 "$?" || return 1
    assert_file_exists terminal_quarantine_survives_later_cycle "$RELEASE_QUARANTINE_DIR/$candidate" || return 1
    assert_file_missing completed_backlog_clears_prune_needed "$PRUNE_NEEDED_MARKER" || return 1
)

case_process_state_classification_ignores_terminal_processes() (
    local root="$SANDBOX/process-classification" release fake_proc status
    load_deploy "$root"
    release="$RELEASES_DIR/abababababababababababababababababababab"
    fake_proc="$root/proc"
    mkdir -p "$release" "$fake_proc/70001" "$fake_proc/70003"
    PROC_ROOT="$fake_proc"

    printf 'Name:\tzombie\nState:\tZ (zombie)\nPPid:\t1\n' > "$fake_proc/70001/status"
    process_uses_release_tree 70001 "$release"
    assert_status zombie_process_is_not_indeterminate 1 "$?" || return 1

    ln -s "$root/unreadable-live-runtime" "$fake_proc/70003/cwd"
    printf 'Name:\tlive\nState:\tS (sleeping)\nPPid:\t1\n' > "$fake_proc/70003/status"
    readlink() {
        if [ "${1:-}" = "-f" ] && [ "${2:-}" = "$fake_proc/70003/cwd" ]; then
            return 1
        fi
        command readlink "$@"
    }
    process_uses_release_tree 70003 "$release"
    status=$?
    unset -f readlink
    assert_status unreadable_live_process_remains_indeterminate 2 "$status" || return 1
)

case_vanished_process_short_circuits_status_fallback() (
    local root="$SANDBOX/process-vanished" release fake_proc status pid=79999
    load_deploy "$root"
    release="$RELEASES_DIR/abababababababababababababababababababab"
    fake_proc="$root/proc"
    mkdir -p "$release" "$fake_proc"
    PROC_ROOT="$fake_proc"
    readlink() {
        if [ "${1:-}" = "$fake_proc/$pid/cwd" ]; then
            return 1
        fi
        command readlink "$@"
    }
    sed() {
        if [ "${3:-}" = "$fake_proc/$pid/status" ]; then
            printf 'S\n'
            return 0
        fi
        command sed "$@"
    }

    process_uses_release_tree "$pid" "$release"
    status=$?
    assert_status vanished_process_is_irrelevant_before_status_fallback 1 "$status" || return 1
)

case_existing_deleted_suffix_directory_is_detected() (
    local root="$SANDBOX/process-existing-deleted-suffix" release fake_proc pid=70004
    load_deploy "$root"
    release="$RELEASES_DIR/abababababababababababababababababababab"
    fake_proc="$root/proc"
    mkdir -p "$release/live (deleted)" "$fake_proc/$pid"
    PROC_ROOT="$fake_proc"
    ln -s "$release/live (deleted)" "$fake_proc/$pid/cwd"

    process_uses_release_tree "$pid" "$release"
    assert_status existing_deleted_suffix_directory_is_in_use 0 "$?" || return 1
)

case_deleted_candidate_cwd_is_indeterminate() (
    local root="$SANDBOX/process-deleted-candidate" release fake_proc status pid=70005
    load_deploy "$root"
    release="$RELEASES_DIR/abababababababababababababababababababab"
    fake_proc="$root/proc"
    mkdir -p "$release" "$fake_proc/$pid"
    PROC_ROOT="$fake_proc"
    ln -s "$release/removed-child (deleted)" "$fake_proc/$pid/cwd"
    printf 'Name:\tdeleted-inside\nState:\tS (sleeping)\nPPid:\t1\n' > "$fake_proc/$pid/status"
    readlink() {
        if [ "${1:-}" = "-f" ] && [ "${2:-}" = "$fake_proc/$pid/cwd" ]; then
            return 1
        fi
        command readlink "$@"
    }

    process_uses_release_tree "$pid" "$release"
    status=$?
    assert_status deleted_candidate_cwd_is_not_reported_irrelevant 2 "$status" || return 1
)

case_deleted_unrelated_cwd_requires_outside_proof() (
    local root="$SANDBOX/process-deleted-unrelated" release fake_proc status pid=70006
    load_deploy "$root"
    release="$RELEASES_DIR/abababababababababababababababababababab"
    fake_proc="$root/proc"
    mkdir -p "$release" "$fake_proc/$pid"
    PROC_ROOT="$fake_proc"
    ln -s "$root/unrelated-runtime (deleted)" "$fake_proc/$pid/cwd"
    printf 'Name:\tdeleted-outside\nState:\tS (sleeping)\nPPid:\t1\n' > "$fake_proc/$pid/status"
    readlink() {
        if [ "${1:-}" = "-f" ] && [ "${2:-}" = "$fake_proc/$pid/cwd" ]; then
            return 1
        fi
        command readlink "$@"
    }

    process_uses_release_tree "$pid" "$release"
    status=$?
    assert_status deleted_unrelated_cwd_is_irrelevant_after_outside_proof 1 "$status" || return 1
)

case_cgroup_only_consumer_is_quarantined() (
    local root="$SANDBOX/prune-cgroup-only" deployed rollback candidate consumer_pid output status
    deployed=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    rollback=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    candidate=cccccccccccccccccccccccccccccccccccccccc
    setup_prune_fixture "$root" "$deployed" "$rollback" "$candidate" \
        dddddddddddddddddddddddddddddddddddddddd \
        eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
        ffffffffffffffffffffffffffffffffffffffff
    consumer_pid=71001
    mkdir -p "$PROC_ROOT/$consumer_pid"
    ln -s "$RELEASES_DIR/$candidate" "$PROC_ROOT/$consumer_pid/cwd"
    printf 'Name:\tcgroup-only\nState:\tS (sleeping)\nPPid:\t1\n' > "$PROC_ROOT/$consumer_pid/status"
    printf '%s\n' "$consumer_pid" > "$CGROUP_ROOT/test-frontend/cgroup.procs"
    list_process_directories() { return 0; }

    output="$(prune_releases 2>&1)"
    status=$?
    assert_status cgroup_only_consumer_is_advisory 0 "$status" || return 1
    assert_contains cgroup_only_consumer_reported 'advisory scan observed a matching consumer' \
        <(printf '%s\n' "$output") || return 1
    assert_file_exists cgroup_only_consumer_runtime_quarantined \
        "$RELEASE_QUARANTINE_DIR/$candidate" || return 1
)

case_same_uid_different_unit_consumer_is_quarantined() (
    local root="$SANDBOX/prune-same-uid-other-unit" deployed rollback candidate consumer_pid output status
    deployed=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    rollback=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    candidate=cccccccccccccccccccccccccccccccccccccccc
    setup_prune_fixture "$root" "$deployed" "$rollback" "$candidate" \
        dddddddddddddddddddddddddddddddddddddddd \
        eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
        ffffffffffffffffffffffffffffffffffffffff
    consumer_pid=71002
    mkdir -p "$PROC_ROOT/$consumer_pid"
    ln -s "$RELEASES_DIR/$candidate" "$PROC_ROOT/$consumer_pid/cwd"
    printf 'Name:\tother-unit\nState:\tS (sleeping)\nPPid:\t1\n' > "$PROC_ROOT/$consumer_pid/status"
    list_process_directories() { printf '%s\n' "$PROC_ROOT/$consumer_pid"; }

    output="$(prune_releases 2>&1)"
    status=$?
    assert_status same_uid_other_unit_is_advisory 0 "$status" || return 1
    assert_contains same_uid_other_unit_consumer_reported 'advisory scan observed a matching consumer' \
        <(printf '%s\n' "$output") || return 1
    assert_file_exists same_uid_other_unit_runtime_quarantined \
        "$RELEASE_QUARANTINE_DIR/$candidate" || return 1
)

case_different_uid_consumer_survives_quarantine() (
    local root="$SANDBOX/prune-different-uid" deployed rollback candidate consumer_pid other_uid status cycle
    deployed=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    rollback=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    candidate=cccccccccccccccccccccccccccccccccccccccc
    setup_prune_fixture "$root" "$deployed" "$rollback" "$candidate" \
        dddddddddddddddddddddddddddddddddddddddd \
        eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
        ffffffffffffffffffffffffffffffffffffffff
    consumer_pid=71003
    other_uid=$(( $(id -u) + 1 ))
    mkdir -p "$PROC_ROOT/$consumer_pid"
    ln -s "$RELEASES_DIR/$candidate" "$PROC_ROOT/$consumer_pid/cwd"
    printf 'Name:\tother-uid\nState:\tS (sleeping)\nPPid:\t1\n' > "$PROC_ROOT/$consumer_pid/status"
    list_process_directories() { printf '%s\n' "$PROC_ROOT/$consumer_pid"; }
    stat() {
        if [ "${1:-}" = "-c" ] && [ "${2:-}" = "%u" ] \
            && [ "${3:-}" = "$PROC_ROOT/$consumer_pid" ]; then
            printf '%s\n' "$other_uid"
            return 0
        fi
        command stat "$@"
    }

    for cycle in 1 2 3 4; do
        prune_releases >/dev/null 2>&1
        status=$?
        assert_status "different_uid_cycle_${cycle}_is_advisory" 0 "$status" || return 1
        assert_file_exists "different_uid_cycle_${cycle}_keeps_terminal_quarantine" \
            "$RELEASE_QUARANTINE_DIR/$candidate/live-sentinel" || return 1
    done
    assert_file_missing different_uid_consumer_loses_old_path "$RELEASES_DIR/$candidate" || return 1
    assert_file_missing different_uid_never_creates_eligibility_marker \
        "$RELEASE_QUARANTINE_DIR/$candidate/.prune-eligible" || return 1
)

case_cross_device_quarantine_move_fails_closed() (
    local root="$SANDBOX/prune-cross-device" deployed rollback candidate quarantine_root output status
    deployed=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    rollback=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    candidate=cccccccccccccccccccccccccccccccccccccccc
    setup_prune_fixture "$root" "$deployed" "$rollback" "$candidate" \
        dddddddddddddddddddddddddddddddddddddddd \
        eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
        ffffffffffffffffffffffffffffffffffffffff
    quarantine_root="$(mktemp -d /dev/shm/connex-staging-quarantine.XXXXXX)" || return 1
    trap 'rm -rf "$quarantine_root"' EXIT
    RELEASE_QUARANTINE_DIR="$quarantine_root/release-quarantine"
    mkdir -p "$RELEASE_QUARANTINE_DIR"
    if [ "$(stat -c '%d' "$RELEASES_DIR")" = "$(stat -c '%d' "$RELEASE_QUARANTINE_DIR")" ]; then
        printf 'cross-device fixture unexpectedly shares a device\n'
        return 1
    fi

    output="$(prune_releases 2>&1)"
    status=$?
    assert_status cross_device_quarantine_refused 1 "$status" || return 1
    assert_contains cross_device_quarantine_failure_is_loud \
        "Release pruning refused: could not quarantine $candidate" \
        <(printf '%s\n' "$output") || return 1
    assert_file_exists cross_device_candidate_remains_public "$RELEASES_DIR/$candidate/live-sentinel" || return 1
    assert_file_missing cross_device_destination_was_not_copied "$RELEASE_QUARANTINE_DIR/$candidate" || return 1
)

case_process_appearing_after_scan_survives_quarantine() (
    local root="$SANDBOX/prune-scan-race" deployed rollback candidate late_pid=0 status attempt
    local ready="$SANDBOX/prune-scan-race/consumer-ready"
    local trigger="$SANDBOX/prune-scan-race/read-after-quarantine"
    local result="$SANDBOX/prune-scan-race/consumer-result"
    deployed=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    rollback=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    candidate=cccccccccccccccccccccccccccccccccccccccc
    setup_prune_fixture "$root" "$deployed" "$rollback" "$candidate" \
        dddddddddddddddddddddddddddddddddddddddd \
        eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
        ffffffffffffffffffffffffffffffffffffffff
    trap 'if [ "$late_pid" -ne 0 ]; then kill "$late_pid" 2>/dev/null || true; wait "$late_pid" 2>/dev/null || true; fi' EXIT
    release_tree_in_use() {
        local release="$1"
        (
            cd "$release" || exit 1
            printf 'ready\n' > "$ready"
            while [ ! -e "$trigger" ]; do sleep 0.01; done
            sed -n '1p' live-sentinel > "$result"
            exec sleep 300
        ) &
        late_pid=$!
        for ((attempt = 0; attempt < 100; attempt += 1)); do
            [ -e "$ready" ] && break
            sleep 0.01
        done
        [ -e "$ready" ] || return 2
        return 1
    }

    prune_releases >/dev/null 2>&1
    status=$?
    assert_status late_consumer_does_not_prevent_quarantine 0 "$status" || return 1
    assert_file_exists late_consumer_runtime_quarantined "$RELEASE_QUARANTINE_DIR/$candidate" || return 1
    : > "$trigger"
    for ((attempt = 0; attempt < 100; attempt += 1)); do
        [ -e "$result" ] && break
        sleep 0.01
    done
    assert_file_exists late_consumer_reads_after_atomic_rename "$result" || return 1
    assert_equals late_consumer_reads_complete_runtime candidate-runtime "$(sed -n '1p' "$result")" || return 1
)

case_no_change_run_retries_prune_backlog() (
    local root="$SANDBOX/no-change-prune-retry" deployed status retry_log main_log
    deployed=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    retry_log="$root/retry.log"
    main_log="$root/main.log"
    load_deploy "$root"
    printf '%s\n' "$deployed" > "$MARKER"
    printf 'pending\n' > "$PRUNE_NEEDED_MARKER"
    guard_wrapper_contract() { return 0; }
    git() { [ "$1" = "cat-file" ] && [ "$2" = "-e" ]; }
    verify_no_change_release() { return 0; }
    prune_releases() { printf 'retried\n' > "$retry_log"; return 1; }

    (
        CONNEX_DEPLOY_TARGET="$deployed" \
            CONNEX_DEPLOY_LOCK_HELD=1 \
            main
    ) > "$main_log" 2>&1
    status=$?
    assert_status no_change_prune_failure_stays_nonzero 1 "$status" || return 1
    assert_file_exists no_change_path_retries_prune "$retry_log" || return 1
    assert_contains no_change_run_reports_quarantine_occupancy \
        'Release quarantine occupancy: 0 entries; alert threshold is more than 8' \
        "$main_log" || return 1
    assert_contains no_change_prune_failure_realerts_from_exit_trap \
        'ALERT status=failure gate=recovery component=release' \
        "$main_log" || return 1
)

case_quarantine_occupancy_alerts_above_threshold() (
    local root="$SANDBOX/quarantine-occupancy" sha output
    load_deploy "$root"
    for sha in 1 2 3 4 5 6 7 8; do
        mkdir -p "$RELEASE_QUARANTINE_DIR/$(printf '%040d' "$sha")"
    done
    output="$(report_quarantine_occupancy 2>&1)"
    assert_contains quarantine_threshold_is_reported \
        'Release quarantine occupancy: 8 entries; alert threshold is more than 8' \
        <(printf '%s\n' "$output") || return 1
    assert_absent quarantine_at_threshold_does_not_alert 'ALERT status=warning' \
        <(printf '%s\n' "$output") || return 1

    mkdir -p "$RELEASE_QUARANTINE_DIR/$(printf '%040d' 9)"
    output="$(report_quarantine_occupancy 2>&1)"
    assert_contains quarantine_over_threshold_reports_count \
        'Release quarantine occupancy: 9 entries; alert threshold is more than 8' \
        <(printf '%s\n' "$output") || return 1
    assert_contains quarantine_over_threshold_alerts \
        'ALERT status=warning gate=recovery component=release quarantine_entries=9 threshold=8' \
        <(printf '%s\n' "$output") || return 1
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

case_first_transactional_run_preserves_live_legacy_trees() (
    local root="$SANDBOX/legacy-migration" previous target fixture fake_bin reset_log
    local legacy_pid target_pid path fake_proc mock_control_group
    previous=6565656565656565656565656565656565656565
    target=6666666666666666666666666666666666666666
    load_deploy "$root"
    fixture="$root/fixture"
    fake_bin="$root/bin"
    reset_log="$root/reset.log"
    mkdir -p \
        "$fixture/frontend/ci" \
        "$fixture/frontend/public" \
        "$fixture/backend" \
        "$fake_bin" \
        "$STAGING_DIR/frontend/.next" \
        "$STAGING_DIR/frontend/.next-new" \
        "$STAGING_DIR/frontend/.next-old" \
        "$STAGING_DIR/deploy/staging"
    printf '{}\n' > "$fixture/frontend/tsconfig.json"
    printf 'public\n' > "$fixture/frontend/public/favicon.ico"
    printf 'verifier\n' > "$fixture/frontend/ci/verify_build_chunks.mjs"
    for path in .next .next-new .next-old; do
        printf 'legacy-%s\n' "$path" > "$STAGING_DIR/frontend/$path/live-sentinel"
    done
    printf 'launcher\n' > "$STAGING_DIR/deploy/staging/connex-frontend-start.sh"
    {
        printf '#!/bin/bash\n'
        printf 'set -eu\n'
        printf 'sha=\n'
        printf 'for argument in "$@"; do case "$argument" in -PgitSha=*) sha="${argument#-PgitSha=}" ;; esac; done\n'
        printf '[ -n "$sha" ]\n'
        printf 'jar_root=build/jar-root\n'
        printf 'rm -rf "$jar_root"\n'
        printf 'mkdir -p "$jar_root/BOOT-INF/classes/META-INF" build/libs\n'
        printf 'printf "build.gitSha=%%s\\n" "$sha" > "$jar_root/BOOT-INF/classes/META-INF/build-info.properties"\n'
        printf '(cd "$jar_root" && zip -q -r ../libs/backend-0.0.1-SNAPSHOT.jar BOOT-INF)\n'
    } > "$fixture/backend/gradlew"
    {
        printf '#!/bin/bash\n'
        printf 'if [ "${1:-}" = "build" ]; then\n'
        printf '    mkdir -p "$NEXT_DIST_DIR/standalone/$NEXT_DIST_DIR/server" "$NEXT_DIST_DIR/static"\n'
        printf '    printf "server\\n" > "$NEXT_DIST_DIR/standalone/server.js"\n'
        printf '    printf "route\\n" > "$NEXT_DIST_DIR/standalone/$NEXT_DIST_DIR/server/app.js"\n'
        printf '    printf "asset\\n" > "$NEXT_DIST_DIR/static/app.js"\n'
        printf 'fi\n'
    } > "$fake_bin/pnpm"
    {
        printf '#!/bin/bash\n'
        printf 'exit 0\n'
    } > "$fake_bin/node"
    chmod 0755 "$fixture/backend/gradlew" "$fake_bin/pnpm" "$fake_bin/node"
    PNPM="$fake_bin/pnpm"
    NODE_BIN="$fake_bin"
    load_frontend_environment() { return 0; }
    git() {
        case "$*" in
            "-C $STAGING_DIR archive $target") tar -C "$fixture" -cf - . ;;
            "-C $STAGING_DIR show $target:frontend/package.json")
                printf '{"scripts":{"start":"bash ../deploy/staging/connex-frontend-start.sh"}}\n'
                ;;
            "-C $STAGING_DIR cat-file -e $target:deploy/staging/connex-frontend-start.sh") return 0 ;;
            "-C $STAGING_DIR reset --hard $target --quiet")
                for path in .next .next-new .next-old; do
                    [ -f "$STAGING_DIR/frontend/$path/live-sentinel" ] || return 1
                done
                printf '%s\n' "$*" > "$reset_log"
                ;;
            "-C $STAGING_DIR diff --quiet $target -- frontend/package.json deploy/staging/connex-frontend-start.sh")
                return 0
                ;;
            *) return 2 ;;
        esac
    }

    make_bundle "$root" "$previous" rebuilt-from-marker-commit
    (
        cd "$STAGING_DIR/frontend/.next-new" || exit 1
        exec sleep 300
    ) &
    legacy_pid=$!
    target_pid=0
    trap '
        if [ "$legacy_pid" -ne 0 ]; then kill "$legacy_pid" 2>/dev/null || true; wait "$legacy_pid" 2>/dev/null || true; fi
        if [ "$target_pid" -ne 0 ]; then kill "$target_pid" 2>/dev/null || true; wait "$target_pid" 2>/dev/null || true; fi
    ' EXIT

    build_target_release "$target"
    assert_status target_built_while_legacy_process_served 0 "$?" || return 1
    for path in .next .next-new .next-old; do
        assert_file_exists "build_preserves_$path" "$STAGING_DIR/frontend/$path/live-sentinel" || return 1
    done

    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    DEPLOY_RETAINED="$previous"
    write_transaction prepared || return 1
    sudo() {
        case "$*" in
            "systemctl stop connex-staging-frontend")
                kill -0 "$legacy_pid" || return 1
                for path in .next .next-new .next-old; do
                    [ -f "$STAGING_DIR/frontend/$path/live-sentinel" ] || return 1
                done
                kill "$legacy_pid" || return 1
                wait "$legacy_pid" 2>/dev/null || true
                legacy_pid=0
                ;;
            "systemctl restart connex-staging-frontend")
                (
                    cd "$RELEASES_DIR/$target/frontend" || exit 1
                    exec sleep 300
                ) &
                target_pid=$!
                printf '%s\t%s\n' "$target" "$target_pid" > "$FRONTEND_RUNNING_MARKER"
                mkdir -p "$fake_proc/$target_pid"
                ln -s "$RELEASES_DIR/$target/frontend" "$fake_proc/$target_pid/cwd"
                printf '0::%s\n' "$mock_control_group" > "$fake_proc/$target_pid/cgroup"
                printf 'Name:\ttarget\nState:\tS (sleeping)\nPPid:\t%s\n' "$$" \
                    > "$fake_proc/$target_pid/status"
                ;;
            *) return 2 ;;
        esac
    }
    quiesce_frontend_and_switch_checkout "$target"
    assert_status legacy_frontend_quiesced_before_checkout 0 "$?" || return 1
    assert_contains checkout_switched_after_stop "reset --hard $target --quiet" "$reset_log" || return 1

    wait_for_frontend() { return 0; }
    fake_proc="$root/proc"
    mock_control_group=/test-frontend
    PROC_ROOT="$fake_proc"
    frontend_pid() { printf '%s\n' "$$"; }
    frontend_control_group() { printf '/test-frontend\n'; }
    systemctl() { systemctl_active_stub "$@"; }
    activate_frontend "$target"
    assert_status sealed_frontend_activated 0 "$?" || return 1
    for path in .next .next-new .next-old; do
        assert_file_exists "activation_preserves_$path" "$STAGING_DIR/frontend/$path/live-sentinel" || return 1
    done
    frontend_runtime_matches "$target"
    assert_status sealed_runtime_attested_after_migration 0 "$?" || return 1
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
    DEPLOY_RETAINED="$previous"
    write_transaction prepared || return 1
    sudo() { return 1; }
    git() { printf '%s\n' "$*" >> "$git_log"; return 0; }
    quiesce_frontend_and_switch_checkout "$target" >/dev/null 2>&1
    assert_status denied_stop 1 "$?" || return 1
    assert_file_missing checkout_not_switched "$git_log" || return 1
    transaction="$(read_transaction)" || return 1
    assert_equals transaction_stays_prepared \
        "$previous"$'\t'"$target"$'\t'"$previous"$'\t'prepared "$transaction" || return 1
)

case_failed_transaction_write_is_rejected() (
    local root="$SANDBOX/transaction-write" previous target status
    previous=4949494949494949494949494949494949494949
    target=5050505050505050505050505050505050505050
    load_deploy "$root"
    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    DEPLOY_RETAINED="$previous"
    printf() {
        if [ "$1" = 'target_sha\t%s\n' ]; then
            return 1
        fi
        # shellcheck disable=SC2059
        builtin printf "$@"
    }
    write_transaction prepared
    status=$?
    unset -f printf
    assert_status transaction_write_failure 1 "$status" || return 1
    assert_file_missing malformed_transaction_not_published "$TRANSACTION_FILE" || return 1
)

case_failed_sha_read_is_rejected() (
    local root="$SANDBOX/sha-read" sha status
    sha=5353535353535353535353535353535353535353
    load_deploy "$root"
    sed() {
        builtin printf '%s\n' "$sha"
        return 1
    }
    read_sha_file "$MARKER" >/dev/null
    status=$?
    unset -f sed
    assert_status sha_output_with_failed_read_refused 1 "$status" || return 1
)

case_failed_marker_write_keeps_rollback_armed() (
    local root="$SANDBOX/marker-write" previous target rollback_log transaction_log alert_log status exit_status=0
    previous=5151515151515151515151515151515151515151
    target=5252525252525252525252525252525252525252
    rollback_log="$root/rollback.log"
    transaction_log="$root/transaction.log"
    alert_log="$root/alert.log"
    load_deploy "$root"
    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    ROLLBACK_ARMED=1
    RELEASE_COMMITTED=0
    write_sha_marker() {
        if [ "$1" = "$MARKER" ]; then
            return 1
        fi
        builtin printf '%s\n' "$2" > "$1" || return 1
    }
    write_transaction() { builtin printf '%s\n' "$1" > "$transaction_log"; }
    rollback_release() { builtin printf '%s\n' "$1" > "$rollback_log"; ROLLBACK_STATE=complete; return 0; }
    deploy_failure_alert() { builtin printf 'alert\n' > "$alert_log"; }
    set_failure_context marker release "marker write failed"

    commit_release_markers "$previous" "$target"
    status=$?
    assert_status marker_write_failure 1 "$status" || return 1
    assert_equals rollback_stays_armed 1 "$ROLLBACK_ARMED" || return 1
    assert_equals release_stays_uncommitted 0 "$RELEASE_COMMITTED" || return 1
    assert_file_missing committed_transaction_not_written "$transaction_log" || return 1

    ( deployment_exit "$status" ) >/dev/null 2>&1 || exit_status=$?
    assert_status deploy_exits_failed 1 "$exit_status" || return 1
    assert_equals previous_pair_rolled_back "$previous" "$(sed -n '1p' "$rollback_log")" || return 1
    assert_file_exists failure_alerted "$alert_log" || return 1
)

case_failed_committed_transaction_restores_release_markers() (
    local root="$SANDBOX/commit-transaction" retained previous target rollback_log alert_log status exit_status=0
    retained=6161616161616161616161616161616161616161
    previous=6262626262626262626262626262626262626262
    target=6363636363636363636363636363636363636363
    rollback_log="$root/rollback.log"
    alert_log="$root/alert.log"
    load_deploy "$root"
    make_bundle "$root" "$retained" rebuilt-from-marker-commit
    make_bundle "$root" "$previous" rebuilt-from-marker-commit
    make_bundle "$root" "$target"
    cp "$RELEASES_DIR/$target/backend.jar" "$LIVE_JAR"
    printf '%s\n' "$previous" > "$MARKER"
    printf '%s\n' "$retained" > "$ROLLBACK_MARKER"
    printf '%s\n' "$target" > "$FRONTEND_RELEASE_MARKER"
    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    DEPLOY_RETAINED="$retained"
    ROLLBACK_ARMED=1
    served_git_sha() { jar_git_sha "$LIVE_JAR"; }
    sudo() { builtin printf '%s\n' "$*" >> "$rollback_log"; }
    systemctl() { systemctl_active_stub "$@"; }
    ensure_frontend_launcher() { [ "$1" = "$target" ]; }
    frontend_runtime_matches() { [ "$1" = "$previous" ]; }
    wait_for_backend_sha() { [ "$1" = "$previous" ]; }
    verify_backend_stability() { [ "$1" = "$previous" ]; }
    wait_for_frontend() { return 0; }
    write_transaction() { return 1; }
    deploy_failure_alert() { builtin printf 'alert\n' > "$alert_log"; }
    set_failure_context marker release "committed transaction write failed"

    commit_release_markers "$previous" "$target"
    status=$?
    assert_status committed_transaction_failure 1 "$status" || return 1
    assert_equals deployed_marker_advanced_before_failure "$target" "$(read_sha_file "$MARKER")" || return 1
    assert_equals rollback_marker_advanced_before_failure "$previous" "$(read_sha_file "$ROLLBACK_MARKER")" || return 1
    assert_equals committed_transaction_keeps_rollback_armed 1 "$ROLLBACK_ARMED" || return 1
    assert_equals committed_transaction_keeps_release_uncommitted 0 "$RELEASE_COMMITTED" || return 1

    ( deployment_exit "$status" ) >/dev/null 2>&1 || exit_status=$?
    assert_status committed_transaction_failure_exit 1 "$exit_status" || return 1
    assert_equals committed_transaction_restores_deployed "$previous" "$(read_sha_file "$MARKER")" || return 1
    assert_equals committed_transaction_restores_retained "$retained" "$(read_sha_file "$ROLLBACK_MARKER")" || return 1
    assert_equals committed_transaction_restores_backend "$previous" "$(jar_git_sha "$LIVE_JAR")" || return 1
    assert_file_exists committed_transaction_failure_alerts "$alert_log" || return 1
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

case_wrapper_selected_commit_survives_remote_advance() (
    local root="$SANDBOX/wrapper-pin" selected advanced retained fake_bin fetch_state output status
    local serving_pid selected_source advanced_source logic_sentinel
    selected=5151515151515151515151515151515151515151
    advanced=5252525252525252525252525252525252525252
    retained=5353535353535353535353535353535353535353
    load_deploy "$root"
    make_bundle "$root" "$selected"
    make_bundle "$root" "$retained" rebuilt-from-marker-commit
    cp "$RELEASES_DIR/$selected/backend.jar" "$LIVE_JAR"
    printf '%s\n' "$selected" > "$MARKER"
    printf '%s\n' "$retained" > "$ROLLBACK_MARKER"
    printf '%s\n' "$selected" > "$FRONTEND_RELEASE_MARKER"
    (
        cd "$RELEASES_DIR/$selected/frontend" || exit 1
        exec sleep 300
    ) &
    serving_pid=$!
    trap 'kill "$serving_pid" 2>/dev/null || true; wait "$serving_pid" 2>/dev/null || true' EXIT
    printf '%s\t%s\n' "$selected" "$serving_pid" > "$FRONTEND_RUNNING_MARKER"

    fake_bin="$root/bin"
    fetch_state="$root/fetch-count"
    selected_source="$root/selected-deploy.sh"
    advanced_source="$root/advanced-deploy.sh"
    logic_sentinel="$root/logic-sentinel.log"
    {
        sed -n '1p' "$STAGING_DEPLOY_DIR/connex-staging-deploy.sh"
        printf 'printf "selected\\n" >> "$MOCK_LOGIC_SENTINEL"\n'
        sed -n '2,$p' "$STAGING_DEPLOY_DIR/connex-staging-deploy.sh" \
            | awk '$0 == "main \"$@\"" { print "verify_no_change_release() { return 0; }" } { print }'
    } > "$selected_source"
    {
        printf '#!/bin/bash\n'
        printf 'printf "advanced\\n" >> "$MOCK_LOGIC_SENTINEL"\n'
        printf 'exit 99\n'
    } > "$advanced_source"
    make_wrapper_boundary_shims "$fake_bin"

    unset CONNEX_DEPLOY_TARGET
    output="$(
        PATH="$fake_bin:$PATH" \
        MOCK_FETCH_STATE="$fetch_state" \
        MOCK_SELECTED_SHA="$selected" \
        MOCK_ADVANCED_SHA="$advanced" \
        MOCK_SELECTED_DEPLOY_SOURCE="$selected_source" \
        MOCK_ADVANCED_DEPLOY_SOURCE="$advanced_source" \
        MOCK_LOGIC_SENTINEL="$logic_sentinel" \
        MOCK_SHOW_LOG="$root/show.log" \
        MOCK_FRONTEND_PID="$$" \
        MOCK_FRONTEND_CONTROL_GROUP=/fixture-frontend \
        CONNEX_STAGING_DIR="$STAGING_DIR" \
        CONNEX_DEPLOY_LOCK_FILE="$root/deploy.lock" \
        bash "$STAGING_DEPLOY_DIR/connex-staging-deploy-wrapper.sh" 2>&1
    )"
    status=$?
    assert_status wrapper_and_inner_accept_selected_commit 0 "$status" || {
        printf '%s\n' "$output"
        return 1
    }
    assert_equals exactly_one_fetch 1 "$(sed -n '1p' "$fetch_state")" || return 1
    assert_contains selected_logic_loaded \
        "$selected:deploy/staging/connex-staging-deploy.sh" "$root/show.log" || return 1
    assert_absent advanced_logic_not_loaded \
        "$advanced:deploy/staging/connex-staging-deploy.sh" "$root/show.log" || return 1
    assert_contains selected_script_bytes_executed selected "$logic_sentinel" || return 1
    assert_absent advanced_script_bytes_not_executed advanced "$logic_sentinel" || return 1
    assert_equals selected_commit_remains_deployed "$selected" "$(read_sha_file "$MARKER")" || return 1
    assert_file_exists selected_runtime_survives "$RELEASES_DIR/$selected/frontend" || return 1
)

case_transaction_target_reexecs_recorded_logic() (
    local root="$SANDBOX/transaction-pin" selected recorded prior fake_bin fetch_state output status
    local serving_pid selected_source recorded_source logic_sentinel recovery_owner
    selected=6262626262626262626262626262626262626262
    recorded=6363636363636363636363636363636363636363
    prior=6464646464646464646464646464646464646464
    load_deploy "$root"
    make_bundle "$root" "$prior" rebuilt-from-marker-commit
    make_bundle "$root" "$recorded"
    cp "$RELEASES_DIR/$prior/backend.jar" "$LIVE_JAR"
    printf '%s\n' "$prior" > "$MARKER"
    printf '%s\n' "$prior" > "$ROLLBACK_MARKER"
    printf '%s\n' "$prior" > "$FRONTEND_RELEASE_MARKER"
    (
        cd "$RELEASES_DIR/$prior/frontend" || exit 1
        exec sleep 300
    ) &
    serving_pid=$!
    trap 'kill "$serving_pid" 2>/dev/null || true; wait "$serving_pid" 2>/dev/null || true' EXIT
    printf '%s\t%s\n' "$prior" "$serving_pid" > "$FRONTEND_RUNNING_MARKER"
    DEPLOY_PREVIOUS="$prior"
    DEPLOY_TARGET="$recorded"
    DEPLOY_RETAINED="$prior"
    write_transaction prepared || return 1

    fake_bin="$root/bin"
    fetch_state="$root/fetch-count"
    selected_source="$root/selected-deploy.sh"
    recorded_source="$root/recorded-deploy.sh"
    logic_sentinel="$root/logic-sentinel.log"
    recovery_owner="$root/recovery-owner"
    {
        sed -n '1p' "$STAGING_DEPLOY_DIR/connex-staging-deploy.sh"
        printf 'printf "selected\\n" >> "$MOCK_LOGIC_SENTINEL"\n'
        sed -n '2,$p' "$STAGING_DEPLOY_DIR/connex-staging-deploy.sh"
    } > "$selected_source"
    {
        printf '#!/bin/bash\n'
        printf 'set -eu\n'
        printf '[ "$CONNEX_DEPLOY_TARGET" = "$MOCK_ADVANCED_SHA" ]\n'
        printf 'printf "recorded\\n" >> "$MOCK_LOGIC_SENTINEL"\n'
        printf 'printf "recorded\\n" > "$MOCK_RECOVERY_OWNER"\n'
        printf 'rm -f "$CONNEX_STAGING_DIR/.staging/deploy-transaction"\n'
        printf 'exit 1\n'
    } > "$recorded_source"
    make_wrapper_boundary_shims "$fake_bin"
    unset CONNEX_DEPLOY_TARGET
    output="$(
        PATH="$fake_bin:$PATH" \
        MOCK_FETCH_STATE="$fetch_state" \
        MOCK_SELECTED_SHA="$selected" \
        MOCK_ADVANCED_SHA="$recorded" \
        MOCK_SELECTED_DEPLOY_SOURCE="$selected_source" \
        MOCK_ADVANCED_DEPLOY_SOURCE="$recorded_source" \
        MOCK_LOGIC_SENTINEL="$logic_sentinel" \
        MOCK_RECOVERY_OWNER="$recovery_owner" \
        MOCK_SHOW_LOG="$root/show.log" \
        MOCK_FRONTEND_PID="$$" \
        MOCK_FRONTEND_CONTROL_GROUP=/fixture-frontend \
        MOCK_SERVED_SHA="$prior" \
        CONNEX_STAGING_DIR="$STAGING_DIR" \
        CONNEX_DEPLOY_LOCK_FILE="$root/deploy.lock" \
        bash "$STAGING_DEPLOY_DIR/connex-staging-deploy-wrapper.sh" 2>&1
    )"
    status=$?
    assert_status interrupted_transaction_reports_retry 1 "$status" || {
        printf '%s\n' "$output"
        return 1
    }
    assert_equals recovery_does_not_refetch 1 "$(sed -n '1p' "$fetch_state")" || return 1
    assert_contains wrapper_selected_logic \
        "$selected:deploy/staging/connex-staging-deploy.sh" "$root/show.log" || return 1
    assert_contains recorded_recovery_logic \
        "$recorded:deploy/staging/connex-staging-deploy.sh" "$root/show.log" || return 1
    assert_contains selected_script_bytes_executed selected "$logic_sentinel" || return 1
    assert_contains recorded_script_bytes_executed recorded "$logic_sentinel" || return 1
    assert_equals recorded_script_owned_recovery recorded "$(sed -n '1p' "$recovery_owner")" || return 1
    assert_equals interrupted_recovery_keeps_prior_marker "$prior" "$(read_sha_file "$MARKER")" || return 1
    assert_file_missing prepared_transaction_consumed "$TRANSACTION_FILE" || return 1
)

case_pair_rollback_restores_exact_artifacts() (
    local root="$SANDBOX/rollback" retained previous target sudo_log
    retained=5454545454545454545454545454545454545454
    previous=5555555555555555555555555555555555555555
    target=6666666666666666666666666666666666666666
    sudo_log="$root/sudo.log"
    load_deploy "$root"
    make_bundle "$root" "$retained" rebuilt-from-marker-commit
    make_bundle "$root" "$previous" rebuilt-from-marker-commit
    make_bundle "$root" "$target"
    cp "$RELEASES_DIR/$target/backend.jar" "$LIVE_JAR"
    printf '%s\n' "$target" > "$MARKER"
    printf '%s\n' "$retained" > "$ROLLBACK_MARKER"
    printf '%s\n' "$target" > "$FRONTEND_RELEASE_MARKER"
    DEPLOY_TARGET="$target"
    DEPLOY_RETAINED="$retained"
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
    assert_equals deployed_marker_restored "$previous" "$(read_sha_file "$MARKER")" || return 1
    assert_equals retained_marker_restored "$retained" "$(read_sha_file "$ROLLBACK_MARKER")" || return 1
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
            printf 'frontend.test\tFALSE\t/\tFALSE\t0\tJSESSIONID\toffline-session-secret\n' > "$cookie_jar"
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
    if [ -n "${SMOKE_FAIL_AFTER_URL:-}" ] && [ "$url" = "$SMOKE_FAIL_AFTER_URL" ]; then
        return 23
    fi
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
        "$FRONTEND_URL/dashboard" \
        "$FRONTEND_URL/api/auth/logout"; do
        assert_contains "smoke_$expected" "$expected" "$SMOKE_CALL_LOG" || return 1
    done
)

case_failed_logout_blocks_deploy() (
    local root="$SANDBOX/smoke-logout" cookie_path status
    SMOKE_TARGET=7979797979797979797979797979797979797979
    SMOKE_CALL_LOG="$root/calls.log"
    export SMOKE_TARGET SMOKE_CALL_LOG
    load_deploy "$root"
    SMOKE_FAIL_URL="$FRONTEND_URL/api/auth/logout"
    export SMOKE_FAIL_URL
    install_smoke_fixture
    curl() { smoke_curl_stub "$@"; }

    post_deploy_smoke "$SMOKE_TARGET" >/dev/null 2>&1
    status=$?
    cookie_path="$SMOKE_COOKIE_JAR"
    assert_status logout_failure_blocks_deploy 1 "$status" || return 1
    assert_equals logout_failure_gate smoke_logout "$FAILURE_GATE" || return 1
    assert_equals failed_logout_leaves_retry_armed 1 "$SMOKE_SESSION_ACTIVE" || return 1
    assert_file_exists cookie_available_for_exit_cleanup "$cookie_path" || return 1
    unset SMOKE_FAIL_URL
    printf 'frontend.test\tFALSE\t/\tFALSE\t0\tconnex_workspace\tworkspace-id\n' > "$cookie_path"
    logout_smoke_session >/dev/null 2>&1
    assert_status workspace_cookie_cannot_claim_logout 1 "$?" || return 1
    assert_equals workspace_cookie_leaves_logout_armed 1 "$SMOKE_SESSION_ACTIVE" || return 1
    printf 'frontend.test\tFALSE\t/\tFALSE\t0\tJSESSIONID\toffline-session-secret\n' > "$cookie_path"
    logout_smoke_session || return 1
    assert_equals logout_retry_invalidates_session 0 "$SMOKE_SESSION_ACTIVE" || return 1
    cleanup_smoke_work || return 1
    assert_file_missing logout_failure_cookie_removed "$cookie_path" || return 1
)

case_login_transport_failure_after_session_creation_logs_out_on_exit() (
    local root="$SANDBOX/smoke-login-transport" cookie_path rollback_log alert_log status exit_status=0
    SMOKE_TARGET=8282828282828282828282828282828282828282
    SMOKE_CALL_LOG="$root/calls.log"
    rollback_log="$root/rollback.log"
    alert_log="$root/alert.log"
    export SMOKE_TARGET SMOKE_CALL_LOG
    load_deploy "$root"
    SMOKE_FAIL_AFTER_URL="$FRONTEND_URL/api/auth/login"
    export SMOKE_FAIL_AFTER_URL
    install_smoke_fixture
    curl() { smoke_curl_stub "$@"; }
    rollback_release() { builtin printf '%s\n' "$1" > "$rollback_log"; ROLLBACK_STATE=complete; return 0; }
    deploy_failure_alert() { builtin printf 'alert\n' > "$alert_log"; }
    DEPLOY_PREVIOUS=8585858585858585858585858585858585858585
    ROLLBACK_ARMED=1

    post_deploy_smoke "$SMOKE_TARGET" >/dev/null 2>&1
    status=$?
    cookie_path="$SMOKE_COOKIE_JAR"
    assert_status login_transport_failure 1 "$status" || return 1
    assert_equals login_failure_gate smoke_login "$FAILURE_GATE" || return 1
    assert_equals login_failure_arms_logout 1 "$SMOKE_SESSION_ACTIVE" || return 1
    assert_contains login_failure_received_session offline-session-secret "$cookie_path" || return 1
    unset SMOKE_FAIL_AFTER_URL

    ( deployment_exit "$status" ) >/dev/null 2>&1 || exit_status=$?
    assert_status login_transport_failure_exit 1 "$exit_status" || return 1
    assert_contains login_failure_exit_attempted_logout "$FRONTEND_URL/api/auth/logout" "$SMOKE_CALL_LOG" || return 1
    assert_file_missing login_failure_exit_removed_cookie "$cookie_path" || return 1
    assert_equals login_transport_failure_rolls_back "$DEPLOY_PREVIOUS" "$(sed -n '1p' "$rollback_log")" || return 1
    assert_file_exists login_transport_failure_alerts "$alert_log" || return 1
)

case_failed_authenticated_route_logs_out_on_exit() (
    local root="$SANDBOX/smoke-exit-logout" cookie_path rollback_log alert_log status exit_status=0
    SMOKE_TARGET=8383838383838383838383838383838383838383
    SMOKE_CALL_LOG="$root/calls.log"
    rollback_log="$root/rollback.log"
    alert_log="$root/alert.log"
    export SMOKE_TARGET SMOKE_CALL_LOG
    load_deploy "$root"
    SMOKE_FAIL_URL="$FRONTEND_URL/dashboard"
    export SMOKE_FAIL_URL
    install_smoke_fixture
    curl() { smoke_curl_stub "$@"; }
    rollback_release() { builtin printf '%s\n' "$1" > "$rollback_log"; ROLLBACK_STATE=complete; return 0; }
    deploy_failure_alert() { builtin printf 'alert\n' > "$alert_log"; }
    DEPLOY_PREVIOUS=8484848484848484848484848484848484848484
    ROLLBACK_ARMED=1

    post_deploy_smoke "$SMOKE_TARGET" >/dev/null 2>&1
    status=$?
    cookie_path="$SMOKE_COOKIE_JAR"
    assert_status authenticated_route_failure 1 "$status" || return 1
    assert_equals session_armed_before_exit 1 "$SMOKE_SESSION_ACTIVE" || return 1
    unset SMOKE_FAIL_URL

    ( deployment_exit "$status" ) >/dev/null 2>&1 || exit_status=$?
    assert_status authenticated_route_failure_exit 1 "$exit_status" || return 1
    assert_contains exit_attempted_logout "$FRONTEND_URL/api/auth/logout" "$SMOKE_CALL_LOG" || return 1
    assert_file_missing exit_removed_cookie "$cookie_path" || return 1
    assert_equals authenticated_route_failure_rolls_back "$DEPLOY_PREVIOUS" "$(sed -n '1p' "$rollback_log")" || return 1
    assert_file_exists authenticated_route_failure_alerts "$alert_log" || return 1
)

case_failed_smoke_cleanup_scrubs_and_does_not_block_rollback() (
    local root="$SANDBOX/smoke-cleanup" cookie_path dashboard_path rollback_log alert_log exit_log status exit_status=0
    SMOKE_TARGET=8080808080808080808080808080808080808080
    SMOKE_CALL_LOG="$root/calls.log"
    rollback_log="$root/rollback.log"
    alert_log="$root/alert.log"
    exit_log="$root/exit.log"
    export SMOKE_TARGET SMOKE_CALL_LOG
    load_deploy "$root"
    install_smoke_fixture
    curl() { smoke_curl_stub "$@"; }
    rm() {
        if [ "${1:-}" = "-rf" ] && [ "${2:-}" = "--" ] \
            && [[ "${3:-}" = "$STATE_DIR"/.smoke.* ]]; then
            return 1
        fi
        command rm "$@"
    }
    rollback_release() { builtin printf '%s\n' "$1" > "$rollback_log"; ROLLBACK_STATE=complete; return 0; }
    deploy_failure_alert() { builtin printf 'alert\n' > "$alert_log"; }
    DEPLOY_PREVIOUS=8181818181818181818181818181818181818181
    ROLLBACK_ARMED=1

    post_deploy_smoke "$SMOKE_TARGET" >/dev/null 2>&1
    status=$?
    cookie_path="$SMOKE_COOKIE_JAR"
    dashboard_path="${cookie_path%/cookies}/dashboard.html"
    assert_status cleanup_failure_blocks_deploy 1 "$status" || return 1
    assert_equals cleanup_failure_gate smoke_cleanup "$FAILURE_GATE" || return 1
    assert_file_exists undeletable_cookie_file_still_present "$cookie_path" || return 1
    assert_absent cookie_secret_scrubbed offline-session-secret "$cookie_path" || return 1
    assert_absent dashboard_response_scrubbed data-app-main "$dashboard_path" || return 1

    ( deployment_exit "$status" ) > "$exit_log" 2>&1 || exit_status=$?
    assert_status cleanup_failure_exit 1 "$exit_status" || return 1
    assert_equals cleanup_failure_still_rolls_back "$DEPLOY_PREVIOUS" "$(sed -n '1p' "$rollback_log")" || return 1
    assert_file_exists cleanup_failure_still_alerts "$alert_log" || return 1
    assert_contains cleanup_failure_logged 'Smoke artifact cleanup FAILED' "$exit_log" || return 1
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

case_live_transaction_recovers_commits_and_prunes() (
    local root previous target phase prune_log
    previous=9999999999999999999999999999999999999999
    target=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    for phase in backend_live frontend_live; do
        root="$SANDBOX/recovery-$phase"
        prune_log="$root/prune.log"
        load_deploy "$root"
        make_bundle "$root" "$previous" rebuilt-from-marker-commit
        make_bundle "$root" "$target"
        cp "$RELEASES_DIR/$previous/backend.jar" "$LIVE_JAR"
        printf '%s\n' "$previous" > "$MARKER"
        printf '%s\n' "$previous" > "$FRONTEND_RELEASE_MARKER"
        DEPLOY_PREVIOUS="$previous"
        DEPLOY_TARGET="$target"
        DEPLOY_RETAINED="$previous"
        write_transaction "$phase" || return 1
        served_git_sha() { printf '%s\n' "$target"; }
        validate_smoke_login_file() { return 0; }
        systemctl() { systemctl_active_stub "$@"; }
        sudo() { return 0; }
        ensure_frontend_launcher() { [ "$1" = "$target" ]; }
        wait_for_backend_sha() { [ "$1" = "$target" ]; }
        verify_backend_stability() { [ "$1" = "$target" ]; }
        activate_frontend() { write_sha_marker "$FRONTEND_RELEASE_MARKER" "$1"; }
        post_deploy_smoke() { return 0; }
        ensure_prune_needed() {
            printf 'ensure\n' >> "$prune_log"
            printf 'pending\n' > "$PRUNE_NEEDED_MARKER"
        }
        prune_releases() {
            [ ! -e "$TRANSACTION_FILE" ] || return 1
            prune_needed_state_valid || return 1
            printf 'prune\n' >> "$prune_log"
            rm -f "$PRUNE_NEEDED_MARKER"
        }

        recover_transaction
        assert_status "${phase}_recovered" 0 "$?" || return 1
        assert_equals "${phase}_committed_marker" "$target" "$(read_sha_file "$MARKER")" || return 1
        assert_equals "${phase}_rollback_marker" "$previous" "$(read_sha_file "$ROLLBACK_MARKER")" || return 1
        assert_equals "${phase}_frontend_marker" "$target" "$(read_sha_file "$FRONTEND_RELEASE_MARKER")" || return 1
        assert_equals "${phase}_target_backend_reinstalled" "$target" "$(jar_git_sha "$LIVE_JAR")" || return 1
        assert_file_missing "${phase}_transaction_removed" "$TRANSACTION_FILE" || return 1
        assert_equals "${phase}_prune_sequence" $'ensure\nprune' "$(sed -n '1,2p' "$prune_log")" || return 1
    done
)

case_recovery_commit_failure_rolls_back_and_restores_markers() (
    local root="$SANDBOX/recovery-commit-failure" retained previous target alert_log status exit_status=0
    retained=8686868686868686868686868686868686868686
    previous=8989898989898989898989898989898989898989
    target=9090909090909090909090909090909090909090
    alert_log="$root/alert.log"
    load_deploy "$root"
    make_bundle "$root" "$retained" rebuilt-from-marker-commit
    make_bundle "$root" "$previous" rebuilt-from-marker-commit
    make_bundle "$root" "$target"
    printf '%s\n' "$previous" > "$MARKER"
    printf '%s\n' "$retained" > "$ROLLBACK_MARKER"
    printf '%s\n' "$previous" > "$FRONTEND_RELEASE_MARKER"
    DEPLOY_PREVIOUS="$previous"
    DEPLOY_TARGET="$target"
    DEPLOY_RETAINED="$retained"
    write_transaction backend_live || return 1
    cp "$RELEASES_DIR/$target/backend.jar" "$LIVE_JAR"
    served_git_sha() { jar_git_sha "$LIVE_JAR"; }
    validate_smoke_login_file() { return 0; }
    systemctl() { systemctl_active_stub "$@"; }
    sudo() { return 0; }
    ensure_frontend_launcher() { [ "$1" = "$target" ]; }
    wait_for_backend_sha() { return 0; }
    verify_backend_stability() { return 0; }
    frontend_runtime_matches() { return 0; }
    wait_for_frontend() { return 0; }
    post_deploy_smoke() { return 0; }
    write_transaction() { return 1; }
    deploy_failure_alert() { builtin printf 'alert\n' > "$alert_log"; }

    recover_transaction
    status=$?
    assert_status recovery_commit_failure 1 "$status" || return 1
    assert_equals recovery_commit_keeps_rollback_armed 1 "$ROLLBACK_ARMED" || return 1
    assert_equals recovery_commit_keeps_release_uncommitted 0 "$RELEASE_COMMITTED" || return 1
    assert_equals recovery_commit_advanced_deployed "$target" "$(read_sha_file "$MARKER")" || return 1
    assert_equals recovery_commit_advanced_rollback "$previous" "$(read_sha_file "$ROLLBACK_MARKER")" || return 1

    ( deployment_exit "$status" ) >/dev/null 2>&1 || exit_status=$?
    assert_status recovery_commit_failure_exit 1 "$exit_status" || return 1
    assert_equals recovery_commit_restores_deployed "$previous" "$(read_sha_file "$MARKER")" || return 1
    assert_equals recovery_commit_restores_retained "$retained" "$(read_sha_file "$ROLLBACK_MARKER")" || return 1
    assert_equals recovery_commit_restores_backend "$previous" "$(jar_git_sha "$LIVE_JAR")" || return 1
    assert_file_exists recovery_commit_failure_alerts "$alert_log" || return 1
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
    DEPLOY_RETAINED="$previous"
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
run_case case_failed_identity_probe_output_is_rejected
run_case case_frontend_attestation_requires_current_unit_lineage
run_case case_frontend_attestation_rejects_wrong_cgroup
run_case case_complete_pair_integrity_and_tamper_refusal
run_case case_no_change_requires_valid_retained_pair
run_case case_prune_aborts_when_protected_marker_read_fails
run_case case_consecutive_prunes_preserve_recorded_serving_release
run_case case_prune_reports_unknown_deploy_process_state
run_case case_process_state_classification_ignores_terminal_processes
run_case case_vanished_process_short_circuits_status_fallback
run_case case_existing_deleted_suffix_directory_is_detected
run_case case_deleted_candidate_cwd_is_indeterminate
run_case case_deleted_unrelated_cwd_requires_outside_proof
run_case case_cgroup_only_consumer_is_quarantined
run_case case_same_uid_different_unit_consumer_is_quarantined
run_case case_different_uid_consumer_survives_quarantine
run_case case_cross_device_quarantine_move_fails_closed
run_case case_process_appearing_after_scan_survives_quarantine
run_case case_no_change_run_retries_prune_backlog
run_case case_quarantine_occupancy_alerts_above_threshold
run_case case_isolated_frontend_build_preserves_working_directory
run_case case_first_transactional_run_preserves_live_legacy_trees
run_case case_backend_activation_never_skips_target
run_case case_denied_frontend_stop_prevents_checkout_switch
run_case case_failed_transaction_write_is_rejected
run_case case_failed_sha_read_is_rejected
run_case case_failed_marker_write_keeps_rollback_armed
run_case case_failed_committed_transaction_restores_release_markers
run_case case_stale_installed_wrapper_restores_prior_checkout
run_case case_wrapper_selected_commit_survives_remote_advance
run_case case_transaction_target_reexecs_recorded_logic
run_case case_pair_rollback_restores_exact_artifacts
run_case case_smoke_credentials_require_safe_exact_schema
run_case case_post_deploy_smoke_covers_all_gates
run_case case_failed_logout_blocks_deploy
run_case case_login_transport_failure_after_session_creation_logs_out_on_exit
run_case case_failed_authenticated_route_logs_out_on_exit
run_case case_failed_smoke_cleanup_scrubs_and_does_not_block_rollback
run_case case_incomplete_capabilities_fail_closed
run_case case_each_smoke_gate_fails_closed
run_case case_live_transaction_recovers_commits_and_prunes
run_case case_recovery_commit_failure_rolls_back_and_restores_markers
run_case case_frontend_stopped_transaction_rolls_back
run_case case_alert_is_actionable_and_secret_free
run_case case_frontend_launcher_uses_sealed_runtime

if [ "$FAILURES" -ne 0 ]; then
    printf '%s case(s) failed\n' "$FAILURES" >&2
    exit 1
fi
printf 'all staging deploy cases passed\n'
