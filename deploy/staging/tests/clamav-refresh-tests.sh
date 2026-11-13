#!/usr/bin/env bash
# Offline boundary tests: no Docker daemon, network access, or root privileges required.
set -Eeuo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REFRESH="${CONNEX_CLAMAV_REFRESH_TEST_SCRIPT:-$TESTS_DIR/../connex-staging-clamav-refresh.sh}"
DEPLOYED=dddddddddddddddddddddddddddddddddddddddd
OLD_ID=1111111111111111111111111111111111111111111111111111111111111111
NEW_ID=2222222222222222222222222222222222222222222222222222222222222222
OLD_IMAGE=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
NEW_IMAGE=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
LIVE=connex-staging-clamav
PREVIOUS=connex-staging-clamav-previous
FAILED=connex-staging-clamav-failed
FAILURES=0
ASSERTIONS=0

container_file() {
    local file
    for file in "$CASE/containers/"*.json; do
        [ -f "$file" ] || continue
        if [ "${file##*/}" = "$1.json" ] || [ "$(jq -r '.Id' "$file")" = "$1" ]; then
            echo "$file"
            return 0
        fi
    done
    return 1
}

stub_docker() {
    local action="$1" file name format=""; shift
    echo "$CANARY" >&2
    echo "$action" >> "$CASE/actions"
    case "$action" in
        container)
            [ "$1" = inspect ]; shift
            if [ "${1:-}" = --format ]; then format="$2"; shift 2; fi
            file="$(container_file "$1")" || return 1
            case "$format" in
                '') jq -s '.' "$file" ;;
                '{{.Id}}') jq -r '.Id' "$file" ;;
                '{{.Name}}') jq -r '.Name' "$file" ;;
                '{{.State.Running}}') jq -r '.State.Running' "$file" ;;
                '{{.Name}} {{.State.Running}}') jq -r '.Name + " " + (.State.Running | tostring)' "$file" ;;
                *)
                    [ "$SCENARIO" != health_inspect_failure ] || return 1
                    if [ "$SCENARIO" = healthy ] || [ "$SCENARIO" = create_failure ] \
                        || [ "$SCENARIO" = start_failure ] || [ "$SCENARIO" = tag_failure ] \
                        || [ "$SCENARIO" = old_container_cleanup_failure ] || [ "$SCENARIO" = old_image_in_use ] \
                        || [[ "$SCENARIO" = field_* ]] \
                        || { [ "$SCENARIO" = starting_then_healthy ] && [ -e "$CASE/slept" ]; }; then
                        echo healthy
                    elif [ "$SCENARIO" = starting_then_healthy ] || [ "$SCENARIO" = timeout ]; then
                        echo starting
                    elif { [ "$SCENARIO" = not_ready ] || [ "$SCENARIO" = curl_failure ]; } \
                        && [ ! -e "$CASE/slept" ]; then
                        echo healthy
                    else
                        echo unhealthy
                    fi ;;
            esac ;;
        build)
            : > "$CASE/build.args"
            for name in "$@"; do echo "$name" >> "$CASE/build.args"; done
            [ "$SCENARIO" != build_failure ] || return 1
            file="${!#}/Dockerfile"
            [ "$(cat "$file")" = 'deployed release fixture' ]
            touch "$CASE/built" ;;
        stop)
            shift 2
            file="$(container_file "$1")" || return 1
            [ "$SCENARIO" != stop_failure ] || return 1
            if [ "$1" = "$NEW_ID" ] && [ "$SCENARIO" = new_stop_transient ] && [ ! -e "$CASE/stop-failed" ]; then
                touch "$CASE/stop-failed"; return 1
            fi
            jq '.State.Running = false' "$file" > "$CASE/update.json"
            mv "$CASE/update.json" "$file" ;;
        rename)
            file="$(container_file "$1")" || return 1
            [ ! -e "$CASE/containers/$2.json" ] || return 1
            if [ "$SCENARIO" = rename_failure ] && [ "$2" = "$PREVIOUS" ]; then return 1; fi
            if [ "$1" = "$OLD_ID" ] && [ "$2" = "$LIVE" ]; then
                if [ "$SCENARIO" = restore_rename_failure ]; then return 1; fi
                if [ "$SCENARIO" = restore_rename_transient ] && [ ! -e "$CASE/rename-failed" ]; then
                    touch "$CASE/rename-failed"; return 1
                fi
            fi
            jq --arg name "/$2" '.Name = $name' "$file" > "$CASE/update.json"
            rm "$file"
            mv "$CASE/update.json" "$CASE/containers/$2.json"
            if [ "$SCENARIO" = restore_rename_error_completed ] && [ "$2" = "$LIVE" ]; then return 1; fi ;;
        create)
            [ ! -e "$CASE/containers/$LIVE.json" ]
            [ -e "$CASE/containers/$PREVIOUS.json" ]
            jq -e '.State.Running == false' "$CASE/containers/$PREVIOUS.json" >/dev/null
            : > "$CASE/create.args"
            while [ "$#" -gt 0 ]; do
                echo "$1" >> "$CASE/create.args"
                if [ "$1" = --env-file ]; then
                    shift
                    echo "$1" >> "$CASE/create.args"
                    [ "$(stat -c %a "$1")" = 600 ]
                    [ "$(stat -c %a "$(dirname "$1")")" = 700 ]
                    cp "$1" "$CASE/copied.env"
                    echo "$1" >> "$CASE/env.paths"
                    if [ -e "$(dirname "$1")/runtime-arguments.json" ]; then
                        cp "$(dirname "$1")/runtime-arguments.json" "$CASE/runtime-arguments.json"
                    fi
                fi
                shift
            done
            jq --arg id "$NEW_ID" --arg image "$NEW_IMAGE" \
                '.Id = $id | .Image = $image | .State.Running = false' \
                "$CASE/original.json" > "$CASE/containers/$LIVE.json"
            [ "$SCENARIO" != create_failure ] || return 1
            echo "$NEW_ID" ;;
        start)
            file="$(container_file "$1")" || return 1
            name="$(jq -r '.Id' "$file")"
            if [ "$name" = "$OLD_ID" ]; then echo attempt >> "$CASE/old-start-attempts"; fi
            if [ "$name" = "$NEW_ID" ] && [ "$SCENARIO" = start_failure ]; then return 1; fi
            if [ "$name" = "$OLD_ID" ] && [ "$SCENARIO" = rollback_start_failure ]; then return 1; fi
            if [ "$name" = "$OLD_ID" ] && [ "$SCENARIO" = restore_start_transient ] && [ ! -e "$CASE/start-failed" ]; then
                touch "$CASE/start-failed"; return 1
            fi
            local other
            for other in "$CASE/containers/"*.json; do
                [ "$other" = "$file" ] && continue
                if jq -e '.State.Running == true' "$other" >/dev/null; then return 1; fi
            done
            jq '.State.Running = true' "$file" > "$CASE/update.json"
            mv "$CASE/update.json" "$file"
            if [ "$name" = "$OLD_ID" ] && [ "$SCENARIO" = restore_start_error_completed ]; then return 1; fi
            if [ "$name" = "$NEW_ID" ] && [ "$SCENARIO" = interrupted ]; then kill -TERM "$PPID"; fi
            echo "$name" ;;
        rm)
            file="$(container_file "$1")" || return 1
            if [ "$1" = "$OLD_ID" ] && [ "$SCENARIO" = old_container_cleanup_failure ]; then return 1; fi
            if [ "$1" = "$NEW_ID" ] && [ "$SCENARIO" = removal_error_present ]; then return 1; fi
            jq -e '.State.Running == false' "$file" >/dev/null || return 1
            rm "$file"
            if [ "$1" = "$NEW_ID" ] && [ "$SCENARIO" = removal_error_gone ]; then return 1; fi ;;
        tag)
            [ "$SCENARIO" != tag_failure ] || return 1
            [ "$1" = connex-clamav:staging-candidate ] && [ "$2" = connex-clamav:staging ]
            touch "$CASE/tagged" ;;
        image)
            [ "$1" = rm ]
            if [ "$2" = "$OLD_IMAGE" ] && [ "$SCENARIO" = old_image_in_use ]; then return 1; fi
            echo "$2" >> "$CASE/removed.images" ;;
        *) return 1 ;;
    esac
}

stub_git() {
    echo "$CANARY" >&2
    [ "$#" = 7 ] && [ "$1" = -c ] && [ "$2" = "safe.directory=$CASE/staging" ] \
        && [ "$3" = -C ] && [ "$4" = "$CASE/staging" ] \
        && [ "$5" = archive ] && [ "$6" = "$DEPLOYED" ] && [ "$7" = clamav ] || return 1
    [ "$SCENARIO" != archive_failure ] || return 1
    touch "$CASE/archived"
    tar -c -C "$CASE/archive" clamav
}

stub_curl() {
    echo "$CANARY" >&2
    [ "${!#}" = http://127.0.0.1:8094/health ] || return 1
    touch "$CASE/curled"
    [ "$SCENARIO" != curl_failure ] || return 1
    if [ "$SCENARIO" = not_ready ]; then echo '{"ready":false}'; else echo '{"ready":true}'; fi
}

case "${1:-}" in
    --stub-docker) shift; stub_docker "$@"; exit ;;
    --stub-git) shift; stub_git "$@"; exit ;;
    --stub-curl) shift; stub_curl "$@"; exit ;;
    --stub-id)
        if [ "$SCENARIO" = non_root ]; then echo 1000; else echo 0; fi
        exit ;;
    --stub-sleep)
        touch "$CASE/slept"
        if [ "$SCENARIO" = timeout ]; then /bin/sleep 1; fi
        exit ;;
    --stub-rm)
        shift
        for arg in "$@"; do
            case "$arg" in
                "$CASE/tmp/connex-staging-clamav-refresh."*) echo "$CANARY" >&2 ;;
            esac
        done
        exec /bin/rm "$@" ;;
esac

SANDBOX="$(mktemp -d "${CONNEX_CLAMAV_REFRESH_TEST_ROOT:-${TMPDIR:-/tmp}}/clamav-refresh-tests.XXXXXX")"
trap 'rm -rf -- "$SANDBOX"' EXIT
export HARNESS="$TESTS_DIR/clamav-refresh-tests.sh"
export CANARY='private-test-value=do-not-log'

setup() {
    export SCENARIO="$1" CASE="$SANDBOX/$1"
    mkdir -p "$CASE/staging/.staging" "$CASE/archive/clamav" "$CASE/bin" "$CASE/containers" "$CASE/tmp"
    echo "$DEPLOYED" > "$CASE/staging/.staging/deployed-sha"
    echo 'deployed release fixture' > "$CASE/archive/clamav/Dockerfile"
    touch "$CASE/deploy.lock" "$CASE/actions" "$CASE/env.paths" "$CASE/removed.images"
    cat > "$CASE/original.json" <<'JSON'
{
  "Id": "1111111111111111111111111111111111111111111111111111111111111111",
  "Image": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "Name": "/connex-staging-clamav",
  "State": {"Running": true},
  "Config": {"User": "10002", "Env": [
    "CONNEX_CLAMAV_SERVICE_TOKEN=private-test-value=do-not-log",
    "CONNEX_CLAMAV_MAX_SCAN_BYTES=268435456",
    "OTHER_SECRET=private-test-value=do-not-log"
  ]},
  "Mounts": [{"Type": "tmpfs", "Destination": "/run"}, {"Type": "tmpfs", "Destination": "/tmp"}],
  "HostConfig": {
    "Privileged": false, "AutoRemove": false,
    "Memory": 3221225472, "MemorySwap": 4294967296, "ReadonlyRootfs": true,
    "ShmSize": 134217728, "RestartPolicy": {"Name": "on-failure", "MaximumRetryCount": 3},
    "NetworkMode": "staging-scanner", "LogConfig": {"Type": "json-file", "Config": {"max-size": "32m", "max-file": "3"}},
    "Tmpfs": {"/run": "rw,nosuid,size=1g", "/tmp": "rw,noexec,nosuid,size=64m"},
    "PortBindings": {"8091/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8094"}, {"HostIp": "::1", "HostPort": "18094"}]},
    "CapDrop": ["ALL"], "CapAdd": [], "SecurityOpt": ["no-new-privileges"]
  },
  "NetworkSettings": {"Networks": {"staging-scanner": {"Aliases": ["scanner"]}}}
}
JSON
    cp "$CASE/original.json" "$CASE/containers/$LIVE.json"
    local command
    for command in docker git curl id sleep rm; do
        cat > "$CASE/bin/$command" <<'STUB'
#!/usr/bin/env bash
exec bash "$HARNESS" "--stub-${0##*/}" "$@"
STUB
        chmod 0755 "$CASE/bin/$command"
    done
}

run_refresh() {
    local wait=420
    [ "$SCENARIO" != timeout ] || wait=1
    if PATH="$CASE/bin:$PATH" TMPDIR="$CASE/tmp" CONNEX_STAGING_DIR="$CASE/staging" \
        CONNEX_DEPLOY_LOCK_FILE="$CASE/deploy.lock" CONNEX_CLAMAV_REFRESH_WAIT_SECONDS="$wait" \
        bash "$REFRESH" "$@" > "$CASE/run.log" 2>&1; then
        STATUS=0
    else
        STATUS=$?
    fi
}

assert() {
    local label="$1"; shift
    ASSERTIONS=$((ASSERTIONS + 1))
    if "$@"; then echo "ok   $SCENARIO:$label"; else
        echo "FAIL $SCENARIO:$label" >&2
        FAILURES=$((FAILURES + 1))
    fi
}

temporary_files_removed() {
    [ -z "$(find "$CASE/tmp" -mindepth 1 -print -quit)" ] || return 1
    local path
    while IFS= read -r path; do [ ! -e "$path" ] || return 1; done < "$CASE/env.paths"
}

sanitized_logs() {
    ! grep -qF "$CANARY" "$CASE/run.log" || return 1
    ! grep -q 'CONNEX_CLAMAV_\|OTHER_SECRET' "$CASE/run.log" || return 1
    ! grep -qvE '^\[connex-staging-clamav-refresh\] [a-z_]+=[a-z_]+( (stage|reason)=[a-z_]+| sha=[0-9a-f]{40})*$' "$CASE/run.log"
}

assert_private_cleanup() {
    assert temporary_files_removed temporary_files_removed
    assert sanitized_logs sanitized_logs
}

assert_original_running() {
    assert old_container_running running_id "$OLD_ID"
    assert no_renamed_backup test ! -e "$CASE/containers/$PREVIOUS.json"
}

running_id() {
    jq -e --arg id "$1" '.Id == $id and .State.Running == true' "$CASE/containers/$LIVE.json" >/dev/null
}

has_pair() {
    local previous="" arg
    while IFS= read -r arg; do
        if [ "$previous" = "$1" ] && [ "$arg" = "$2" ]; then return 0; fi
        previous="$arg"
    done < "$CASE/create.args"
    return 1
}

main() {
    setup lock_busy
    exec 8<"$CASE/deploy.lock"
    flock -n 8
    run_refresh
    flock -u 8
    exec 8<&-
    assert skipped_success test "$STATUS" = 0
    assert no_docker_calls test ! -s "$CASE/actions"
    assert skip_logged grep -q 'result=skipped reason=lock_busy' "$CASE/run.log"
    assert_original_running
    assert_private_cleanup

    local scenario
    for scenario in build_failure archive_failure invalid_sha missing_lock unknown_argument non_root unsupported_mount unsupported_mount_options multiline_env; do
        setup "$scenario"
        case "$scenario" in
            invalid_sha) echo invalid > "$CASE/staging/.staging/deployed-sha" ;;
            missing_lock) rm "$CASE/deploy.lock" ;;
            unsupported_mount) jq '.Mounts += [{"Type":"bind"}]' "$CASE/original.json" > "$CASE/containers/$LIVE.json" ;;
            unsupported_mount_options) jq '.HostConfig.Mounts = [{"Type":"tmpfs","Target":"/run","TmpfsOptions":{"SizeBytes":1048576}}]' \
                "$CASE/original.json" > "$CASE/containers/$LIVE.json" ;;
            multiline_env) jq '.Config.Env += ["CONNEX_CLAMAV_BAD=one\nOTHER=two"]' "$CASE/original.json" > "$CASE/containers/$LIVE.json" ;;
        esac
        if [ "$scenario" = unknown_argument ]; then run_refresh --unknown; else run_refresh; fi
        assert failed test "$STATUS" -ne 0
        assert no_container_mutation test "$(grep -cE '^(stop|rename|create|start|rm|tag)$' "$CASE/actions" || true)" = 0
        assert_original_running
        assert_private_cleanup
    done

    setup previous_container_present
    cp "$CASE/original.json" "$CASE/containers/$PREVIOUS.json"
    run_refresh
    assert failed test "$STATUS" -ne 0
    assert old_container_running running_id "$OLD_ID"
    assert previous_container_retained test -e "$CASE/containers/$PREVIOUS.json"
    assert no_build test ! -e "$CASE/built"
    assert_private_cleanup

    for scenario in healthy starting_then_healthy; do
        setup "$scenario"
        run_refresh
        assert succeeded test "$STATUS" = 0
        assert new_container_running running_id "$NEW_ID"
        assert backup_removed test ! -e "$CASE/containers/$PREVIOUS.json"
        assert deployed_source_used test -e "$CASE/archived"
        assert cache_disabled grep -qx -- --no-cache "$CASE/build.args"
        assert base_pulled grep -qx -- --pull "$CASE/build.args"
        assert http_readiness_checked test -e "$CASE/curled"
        assert canonical_image_tagged test -e "$CASE/tagged"
        assert old_image_pruned grep -qx "$OLD_IMAGE" "$CASE/removed.images"
        assert temporary_tag_removed grep -qx 'connex-clamav:staging-candidate' "$CASE/removed.images"
        assert memory_preserved has_pair --memory 3221225472
        assert swap_preserved has_pair --memory-swap 4294967296
        assert read_only_preserved grep -qx -- --read-only=true "$CASE/create.args"
        assert tmpfs_run_preserved has_pair --tmpfs /run:rw,nosuid,size=1g
        assert tmpfs_tmp_preserved has_pair --tmpfs /tmp:rw,noexec,nosuid,size=64m
        assert shm_preserved has_pair --shm-size 134217728
        assert restart_preserved has_pair --restart on-failure:3
        assert network_preserved has_pair --network staging-scanner
        assert alias_preserved has_pair --network-alias scanner
        assert ipv4_binding_preserved has_pair --publish 127.0.0.1:8094:8091/tcp
        assert ipv6_binding_preserved has_pair --publish '[::1]:18094:8091/tcp'
        assert log_driver_preserved has_pair --log-driver json-file
        assert log_options_preserved has_pair --log-opt max-size=32m
        assert capabilities_preserved has_pair --cap-drop ALL
        assert security_options_preserved has_pair --security-opt no-new-privileges
        assert user_preserved has_pair --user 10002
        jq -r '.Config.Env[] | select(startswith("CONNEX_CLAMAV_"))' "$CASE/original.json" > "$CASE/expected.env"
        assert only_scanner_env_copied cmp -s "$CASE/expected.env" "$CASE/copied.env"
        assert_private_cleanup
    done

    for scenario in unhealthy not_ready curl_failure timeout health_inspect_failure stop_failure rename_failure create_failure start_failure tag_failure interrupted; do
        setup "$scenario"
        run_refresh
        assert failed test "$STATUS" -ne 0
        assert_original_running
        assert old_image_retained test "$(grep -cF "$OLD_IMAGE" "$CASE/removed.images" || true)" = 0
        assert rollback_logged grep -q 'result=restored' "$CASE/run.log"
        assert_private_cleanup
    done

    for scenario in removal_error_gone removal_error_present restore_rename_error_completed restore_rename_transient restore_start_error_completed restore_start_transient new_stop_transient; do
        setup "$scenario"
        run_refresh
        assert failed test "$STATUS" -ne 0
        assert_original_running
        assert rollback_confirmed grep -q 'result=restored' "$CASE/run.log"
        if [ "$scenario" = removal_error_present ]; then
            assert failed_candidate_retained test -e "$CASE/containers/$FAILED.json"
            assert failed_candidate_stopped jq -e '.State.Running == false' "$CASE/containers/$FAILED.json"
            assert failed_candidate_reported grep -q 'result=retained reason=candidate_removal' "$CASE/run.log"
        else
            assert failed_candidate_absent test ! -e "$CASE/containers/$FAILED.json"
        fi
        if [ "$scenario" = new_stop_transient ]; then
            assert running_candidate_stop_retried test "$(wc -l < "$CASE/old-start-attempts")" = 2
        fi
        assert_private_cleanup
    done

    setup restore_rename_failure
    run_refresh
    assert failed test "$STATUS" -ne 0
    assert original_started_even_if_rename_failed jq -e '.State.Running == true' "$CASE/containers/$PREVIOUS.json"
    assert unrecoverable_name_reported grep -q 'result=failed stage=rollback reason=restore_name' "$CASE/run.log"
    assert bounded_attempts test "$(wc -l < "$CASE/old-start-attempts")" = 3
    assert_private_cleanup

    setup rollback_start_failure
    run_refresh
    assert failed test "$STATUS" -ne 0
    assert original_retained test "$(jq -r '.Id' "$CASE/containers/$LIVE.json")" = "$OLD_ID"
    assert rollback_failure_reported grep -q 'result=failed stage=rollback' "$CASE/run.log"
    assert unrecoverable_start_reported grep -q 'result=failed stage=rollback reason=original_not_running' "$CASE/run.log"
    assert bounded_attempts test "$(wc -l < "$CASE/old-start-attempts")" = 3
    assert_private_cleanup

    security_field_cases

    setup old_container_cleanup_failure
    run_refresh
    assert failed test "$STATUS" -ne 0
    assert healthy_replacement_kept running_id "$NEW_ID"
    assert stopped_original_kept test -e "$CASE/containers/$PREVIOUS.json"
    assert backup_stopped jq -e '.State.Running == false' "$CASE/containers/$PREVIOUS.json"
    assert_private_cleanup

    setup old_image_in_use
    run_refresh
    assert succeeded test "$STATUS" = 0
    assert healthy_replacement_kept running_id "$NEW_ID"
    assert shared_image_retained grep -q 'result=retained stage=image_cleanup' "$CASE/run.log"
    assert_private_cleanup

    echo "$ASSERTIONS assertions; $FAILURES failures"
    [ "$FAILURES" -eq 0 ]
}

security_field_cases() {
    cat > "$SANDBOX/field-cases.json" <<'JSON'
[
  ["Memory", 3221225472, "--memory", "3221225472"],
  ["MemorySwap", 4294967296, "--memory-swap", "4294967296"],
  ["ReadonlyRootfs", true, "--read-only=true", null],
  ["ShmSize", 134217728, "--shm-size", "134217728"],
  ["RestartPolicy", {"Name":"on-failure","MaximumRetryCount":3}, "--restart", "on-failure:3"],
  ["NetworkMode", "staging-scanner", "--network", "staging-scanner"],
  ["LogConfig", {"Type":"json-file","Config":{"max-size":"32m"}}, "--log-opt", "max-size=32m"],
  ["Tmpfs", {"/run":"rw,nosuid,size=1g"}, "--tmpfs", "/run:rw,nosuid,size=1g"],
  ["PortBindings", {"8091/tcp":[{"HostIp":"127.0.0.1","HostPort":"8094"}]}, "--publish", "127.0.0.1:8094:8091/tcp"],
  ["PidsLimit", 128, "--pids-limit", "128"],
  ["MemorySwappiness", 0, "--memory-swappiness", "0"],
  ["CpuShares", 512, "--cpu-shares", "512"],
  ["NanoCpus", 1500000000, "--cpus", "1.5"],
  ["CapDrop", ["ALL"], "--cap-drop", "ALL"],
  ["CapAdd", ["NET_BIND_SERVICE"], "--cap-add", "NET_BIND_SERVICE"],
  ["SecurityOpt", ["no-new-privileges"], "--security-opt", "no-new-privileges"],
  ["Ulimits", [{"Name":"nofile","Soft":1024,"Hard":2048}], "--ulimit", "nofile=1024:2048"],
  ["Init", true, "--init=true", null],
  ["CgroupnsMode", "private", "--cgroupns", "private"],
  ["IpcMode", "private", "--ipc", "private"],
  ["Runtime", "runc", "--runtime", "runc"]
]
JSON
    local row field flag argument
    : > "$SANDBOX/accepted-fields"
    : > "$SANDBOX/reproduced-fields"
    jq -c '.[]' "$SANDBOX/field-cases.json" > "$SANDBOX/field-cases.jsonl"
    while IFS= read -r row; do
        field="$(jq -r '.[0]' <<< "$row")"
        flag="$(jq -r '.[2]' <<< "$row")"
        argument="$(jq -r '.[3] // empty' <<< "$row")"
        setup "field_$field"
        jq --argjson row "$row" '.HostConfig[$row[0]] = $row[1]' "$CASE/original.json" > "$CASE/containers/$LIVE.json"
        run_refresh
        assert field_accepted test "$STATUS" = 0
        if [ "$STATUS" = 0 ]; then echo "$field" >> "$SANDBOX/accepted-fields"; fi
        if [ -n "$argument" ]; then
            assert field_reproduced has_pair "$flag" "$argument"
            if has_pair "$flag" "$argument"; then echo "$field" >> "$SANDBOX/reproduced-fields"; fi
        else
            assert field_reproduced grep -qx -- "$flag" "$CASE/create.args"
            if grep -qx -- "$flag" "$CASE/create.args"; then echo "$field" >> "$SANDBOX/reproduced-fields"; fi
        fi
        assert_private_cleanup
    done < "$SANDBOX/field-cases.jsonl"
    assert validation_and_reproduction_lists_match cmp -s "$SANDBOX/accepted-fields" "$SANDBOX/reproduced-fields"
    jq -r '.host | keys[]' "$CASE/runtime-arguments.json" > "$SANDBOX/registry-fields" 2>/dev/null || : > "$SANDBOX/registry-fields"
    sort "$SANDBOX/accepted-fields" > "$SANDBOX/sorted-fields"
    assert registry_fields_have_boundary_coverage cmp -s "$SANDBOX/registry-fields" "$SANDBOX/sorted-fields"

    setup field_User
    jq '.Config.User = "10003:10003"' "$CASE/original.json" > "$CASE/containers/$LIVE.json"
    run_refresh
    assert user_accepted test "$STATUS" = 0
    assert user_reproduced has_pair --user 10003:10003
    assert_private_cleanup

    setup field_defaults
    jq '.HostConfig += {
        ContainerIDFile:"", Binds:null, Mounts:[], VolumesFrom:null, VolumeDriver:"", ConsoleSize:[0,0],
        Annotations:{}, CgroupnsMode:"private", IpcMode:"private", Runtime:"runc", Isolation:"",
        Dns:[], DnsOptions:[], DnsSearch:[], ExtraHosts:null, GroupAdd:null, Links:null,
        Cgroup:"", CgroupParent:"", PidMode:"", UTSMode:"", UsernsMode:"", OomScoreAdj:0,
        PublishAllPorts:false, OomKillDisable:false, StorageOpt:{}, Sysctls:{},
        MemoryReservation:0, MemorySwappiness:null, PidsLimit:null, CpuShares:0, NanoCpus:0,
        CapAdd:null, Ulimits:null, Init:false, KernelMemory:0, KernelMemoryTCP:0,
        BlkioWeight:0, BlkioWeightDevice:[], BlkioDeviceReadBps:[], BlkioDeviceWriteBps:[],
        BlkioDeviceReadIOps:[], BlkioDeviceWriteIOps:[], CpuPeriod:0, CpuQuota:0,
        CpuRealtimePeriod:0, CpuRealtimeRuntime:0, CpusetCpus:"", CpusetMems:"",
        Devices:[], DeviceCgroupRules:null, DeviceRequests:null, CpuCount:0, CpuPercent:0,
        IOMaximumIOps:0, IOMaximumBandwidth:0,
        MaskedPaths:["/proc/asound","/proc/acpi","/proc/interrupts","/proc/kcore","/proc/keys",
            "/proc/latency_stats","/proc/timer_list","/proc/timer_stats","/proc/sched_debug",
            "/proc/scsi","/sys/firmware","/sys/devices/virtual/powercap"],
        ReadonlyPaths:["/proc/bus","/proc/fs","/proc/irq","/proc/sys","/proc/sysrq-trigger"]
    }' "$CASE/original.json" > "$CASE/containers/$LIVE.json"
    run_refresh
    assert full_default_host_config_accepted test "$STATUS" = 0
    assert explicit_false_init_preserved grep -qx -- --init=false "$CASE/create.args"
    assert_private_cleanup

    for row in '{"MemoryReservation":1048576}' '{"PidMode":"host"}' '{"GroupAdd":["1"]}' \
        '{"FutureSecurityLimit":128}' '{"FutureDefault":false}' '{"MemorySwappiness":101}' \
        '{"Ulimits":[{"Name":"nofile","Soft":1024}]}' '{"MaskedPaths":["/custom"]}' '{"ReadonlyPaths":[]}' \
        '{"User":"10003"}' '{"CapAdd":false}' '{"SecurityOpt":false}' '{"Ulimits":false}' \
        '{"Runtime":false}' '{"Tmpfs":false}' '{"RestartPolicy":{"Name":"always","MaximumRetryCount":3}}' \
        '{"PortBindings":{"8091/tcp":[{"HostIp":"127.0.0.1","HostPort":"8094","ExtraSecurity":false}]}}'; do
        field="$(jq -r 'keys[0]' <<< "$row")"
        setup "refused_$field"
        jq --argjson extra "$row" '.HostConfig += $extra' "$CASE/original.json" > "$CASE/containers/$LIVE.json"
        run_refresh
        assert unsupported_field_refused test "$STATUS" -ne 0
        assert no_container_mutation test "$(grep -cE '^(stop|rename|create|start|rm|tag)$' "$CASE/actions" || true)" = 0
        assert_original_running
        assert_private_cleanup
    done
}

main "$@"
