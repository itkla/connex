#!/usr/bin/env bash
# Rebuild baked signatures from the deployed release, retaining the old scanner until readiness.
set +x
set -Eeuo pipefail
umask 077

STAGING_DIR="${CONNEX_STAGING_DIR:-/opt/connex-staging}"
LOCK_FILE="${CONNEX_DEPLOY_LOCK_FILE:-/tmp/connex-staging-deploy.lock}"
WAIT_SECONDS="${CONNEX_CLAMAV_REFRESH_WAIT_SECONDS:-420}"
CONTAINER=connex-staging-clamav
PREVIOUS=connex-staging-clamav-previous
FAILED=connex-staging-clamav-failed
CANDIDATE=connex-clamav:staging-candidate
IMAGE=connex-clamav:staging
SCRATCH=""
OLD_ID=""
OLD_IMAGE=""
SWAPPING=0
BUILT=0
STAGE=preflight

# Callers supply only fixed labels, validated SHAs, or numeric values. Command output stays private.
log() { echo "[connex-staging-clamav-refresh] $*"; }

rollback() {
    local attempt current name state="" retained running
    # A failed CLI response does not prove the daemon rejected the operation. Retry from observed
    # state, and always attempt to start the original even when restoring its name fails.
    for ((attempt=0; attempt<3; attempt++)); do
        current="$(docker container inspect --format '{{.Id}}' "$CONTAINER" 2>/dev/null)" || current=""
        if [ -n "$current" ] && [ "$current" != "$OLD_ID" ]; then
            docker stop --time 30 "$current" >/dev/null 2>&1 || true
            docker rm "$current" >/dev/null 2>&1 || true
            current="$(docker container inspect --format '{{.Id}}' "$CONTAINER" 2>/dev/null)" || current=""
            name="$(docker container inspect --format '{{.Name}}' "$OLD_ID" 2>/dev/null)" || name=""
            running="$(docker container inspect --format '{{.State.Running}}' "$current" 2>/dev/null)" || running=""
            if [ -n "$current" ] && [ "$current" != "$OLD_ID" ] && [ -n "$name" ] && [ "$running" = false ]; then
                # A stopped candidate that cannot be removed must not reserve the scanner's name.
                docker rename "$current" "$FAILED" >/dev/null 2>&1 || true
                retained="$(docker container inspect --format '{{.Id}}' "$FAILED" 2>/dev/null)" || retained=""
                if [ "$retained" = "$current" ]; then log 'result=retained reason=candidate_removal'; fi
            fi
        fi
        name="$(docker container inspect --format '{{.Name}}' "$OLD_ID" 2>/dev/null)" || name=""
        if [ "$name" != "/$CONTAINER" ]; then
            docker rename "$OLD_ID" "$CONTAINER" >/dev/null 2>&1 || true
        fi
        docker start "$OLD_ID" >/dev/null 2>&1 || true
        state="$(docker container inspect --format '{{.Name}} {{.State.Running}}' "$OLD_ID" 2>/dev/null)" || state=""
        current="$(docker container inspect --format '{{.Id}}' "$CONTAINER" 2>/dev/null)" || current=""
        if [ "$state" = "/$CONTAINER true" ] && [ "$current" = "$OLD_ID" ]; then
            log 'result=restored'
            return 0
        fi
    done
    case "$state" in
        '') log 'result=failed stage=rollback reason=original_unavailable' ;;
        *' false') log 'result=failed stage=rollback reason=original_not_running' ;;
        *) log 'result=failed stage=rollback reason=restore_name' ;;
    esac
    return 1
}

cleanup() {
    local status="$?"
    trap - EXIT INT TERM
    if [ "$SWAPPING" -eq 1 ]; then
        if ! rollback; then
            log 'result=failed stage=rollback'
        fi
        status=1
    fi
    if [ "$BUILT" -eq 1 ]; then
        docker image rm "$CANDIDATE" >/dev/null 2>&1 || true
    fi
    if [ -n "$SCRATCH" ]; then
        if ! rm -rf -- "$SCRATCH" >/dev/null 2>&1; then
            log 'result=failed stage=temporary_cleanup'
            status=1
        fi
    fi
    if [ "$status" -ne 0 ]; then
        log "result=failed stage=$STAGE"
    fi
    exit "$status"
}

prepare_arguments() {
    # This field-to-arguments registry is also the non-default HostConfig allow-list. Adding a
    # field to validation therefore requires specifying how it is reproduced.
    jq -e '
        def integer: type == "number" and floor == .;
        def option($flag): if . == null then [] else [$flag, tostring] end;
        .[0] | .HostConfig as $h |
        if (([$h.CapAdd, $h.CapDrop, $h.SecurityOpt, $h.Ulimits] | all(. == null or type == "array")) and
            ([$h.Runtime, $h.IpcMode, $h.CgroupnsMode] | all(. == null or type == "string")) and
            ($h.Tmpfs == null or ($h.Tmpfs | type == "object")) and
            ($h.LogConfig.Config == null or ($h.LogConfig.Config | type == "object")) and
            ($h.LogConfig | keys - ["Type", "Config"] | length == 0) and
            ($h.RestartPolicy | keys - ["Name", "MaximumRetryCount"] | length == 0) and
            ($h.PidsLimit == null or ($h.PidsLimit | integer and . >= -1)) and
            ($h.MemorySwappiness == null or ($h.MemorySwappiness | integer and . >= 0 and . <= 100)) and
            ($h.CpuShares == null or ($h.CpuShares | integer and . >= 0)) and
            ($h.NanoCpus == null or ($h.NanoCpus | integer and . >= 0 and . <= 9007199254740991)) and
            ($h.Init == null or ($h.Init | type == "boolean")) and
            ([$h.Ulimits[]? | (keys == ["Hard", "Name", "Soft"]) and
                (.Name | type == "string" and test("^[a-z0-9]+$")) and
                (.Soft | integer and . >= -1) and (.Hard | integer and . >= -1)] | all))
        then {
            host: {
                Memory: ["--memory", ($h.Memory | tostring)],
                MemorySwap: ["--memory-swap", ($h.MemorySwap | tostring)],
                ReadonlyRootfs: ["--read-only=" + ($h.ReadonlyRootfs | tostring)],
                ShmSize: ["--shm-size", ($h.ShmSize | tostring)],
                RestartPolicy: ["--restart", ($h.RestartPolicy | .Name +
                    if .Name == "on-failure" and .MaximumRetryCount > 0
                    then ":" + (.MaximumRetryCount | tostring) else "" end)],
                NetworkMode: ["--network", $h.NetworkMode],
                LogConfig: ["--log-driver", $h.LogConfig.Type,
                    ($h.LogConfig.Config // {} | to_entries[] | "--log-opt", (.key + "=" + .value))],
                Tmpfs: [($h.Tmpfs // {} | to_entries[] | "--tmpfs", (.key + ":" + .value))],
                PortBindings: [($h.PortBindings // {} | to_entries[] | .key as $port | .value[] |
                    "--publish", ((if .HostIp == "" then "" elif (.HostIp | contains(":"))
                    then "[" + .HostIp + "]:" else .HostIp + ":" end) + .HostPort + ":" + $port))],
                PidsLimit: ($h.PidsLimit | option("--pids-limit")),
                MemorySwappiness: ($h.MemorySwappiness | option("--memory-swappiness")),
                CpuShares: ($h.CpuShares | option("--cpu-shares")),
                NanoCpus: (if $h.NanoCpus == null then [] else ["--cpus", ($h.NanoCpus / 1000000000 | tostring)] end),
                CapDrop: [$h.CapDrop[]? | "--cap-drop", .],
                CapAdd: [$h.CapAdd[]? | "--cap-add", .],
                SecurityOpt: [$h.SecurityOpt[]? | "--security-opt", .],
                Ulimits: [$h.Ulimits[]? | "--ulimit", (.Name + "=" + (.Soft | tostring) + ":" + (.Hard | tostring))],
                Init: (if $h.Init == null then [] else ["--init=" + ($h.Init | tostring)] end),
                CgroupnsMode: (if ($h.CgroupnsMode // "") == "" then [] else ["--cgroupns", $h.CgroupnsMode] end),
                IpcMode: (if ($h.IpcMode // "") == "" then [] else ["--ipc", $h.IpcMode] end),
                Runtime: (if ($h.Runtime // "") == "" then [] else ["--runtime", $h.Runtime] end)
            },
            config: {User: [if .Config.User != "" then "--user", .Config.User else empty end]},
            network: {Aliases: [.NetworkSettings.Networks[].Aliases[]? | "--network-alias", .]}
        } else error("unsupported resource settings") end
    ' "$SCRATCH/inspect.json" > "$SCRATCH/runtime-arguments.json"
    # Known Linux Docker defaults may be omitted. Unknown fields, including future API fields,
    # are refused rather than silently inheriting daemon defaults. Masked/readonly path defaults:
    # https://github.com/moby/moby/blob/v28.5.2/oci/defaults.go
    jq -e --slurpfile arguments "$SCRATCH/runtime-arguments.json" '
        {
            AutoRemove: [false], Privileged: [false], PublishAllPorts: [false],
            Binds: [null, []], Mounts: [null, []], VolumesFrom: [null, []], VolumeDriver: [""],
            ContainerIDFile: [""], ConsoleSize: [[0, 0]], Annotations: [null, {}],
            Dns: [null, []], DnsOptions: [null, []], DnsSearch: [null, []],
            ExtraHosts: [null, []], GroupAdd: [null, []], Links: [null, []],
            Cgroup: [""], CgroupParent: [""], PidMode: [""], UTSMode: [""], UsernsMode: [""],
            OomScoreAdj: [0], OomKillDisable: [null, false], StorageOpt: [null, {}], Sysctls: [null, {}],
            Isolation: ["", "default"], MemoryReservation: [0], KernelMemory: [0], KernelMemoryTCP: [0],
            BlkioWeight: [0], BlkioWeightDevice: [null, []], BlkioDeviceReadBps: [null, []],
            BlkioDeviceWriteBps: [null, []], BlkioDeviceReadIOps: [null, []], BlkioDeviceWriteIOps: [null, []],
            CpuPeriod: [0], CpuQuota: [0], CpuRealtimePeriod: [0], CpuRealtimeRuntime: [0],
            CpusetCpus: [""], CpusetMems: [""], Devices: [null, []], DeviceCgroupRules: [null, []],
            DeviceRequests: [null, []], CpuCount: [0], CpuPercent: [0], IOMaximumIOps: [0], IOMaximumBandwidth: [0],
            MaskedPaths: [null, ["/proc/asound", "/proc/acpi", "/proc/interrupts", "/proc/kcore", "/proc/keys",
                "/proc/latency_stats", "/proc/timer_list", "/proc/timer_stats", "/proc/sched_debug",
                "/proc/scsi", "/sys/firmware", "/sys/devices/virtual/powercap"]],
            ReadonlyPaths: [null, ["/proc/bus", "/proc/fs", "/proc/irq", "/proc/sys", "/proc/sysrq-trigger"]]
        } as $defaults |
        all(.[0].HostConfig | to_entries[]; . as $field |
            ($arguments[0].host | has($field.key)) or
            (($defaults | has($field.key)) and any($defaults[$field.key][]; . == $field.value)))
    ' "$SCRATCH/inspect.json" >/dev/null
    # One argument per line avoids eval and shell interpretation of inspected values.
    jq -r '.host[], .config[], .network[] | .[]' "$SCRATCH/runtime-arguments.json" > "$SCRATCH/args"
}

prepare_settings() {
    docker container inspect "$CONTAINER" > "$SCRATCH/inspect.json"
    # Refuse configurations this narrowly scoped staging job cannot reproduce safely. In
    # particular, a signature-volume mount would hide the freshly baked databases entirely.
    jq -e '
        def integer: type == "number" and floor == .;
        def line: type == "string" and (test("[\\r\\n]") | not);
        length == 1 and (.[0] |
            (.Id | test("^[0-9a-f]{64}$")) and
            (.Image | test("^sha256:[0-9a-f]{64}$")) and
            .State.Running == true and
            .HostConfig.Privileged == false and .HostConfig.AutoRemove == false and
            (.HostConfig.Mounts // [] | length == 0) and
            ([.Mounts[]? | select(.Type != "tmpfs")] | length == 0) and
            (.NetworkSettings.Networks | length == 1) and
            (.HostConfig.NetworkMode as $mode | .NetworkSettings.Networks | keys[0] |
                . == $mode or (. == "bridge" and $mode == "default")) and
            (.HostConfig.Memory | integer) and (.HostConfig.MemorySwap | integer) and
            (.HostConfig.ShmSize | integer) and (.HostConfig.ReadonlyRootfs | type == "boolean") and
            (.HostConfig.RestartPolicy.Name | IN("no", "always", "unless-stopped", "on-failure")) and
            (.HostConfig.RestartPolicy.MaximumRetryCount | integer and . >= 0) and
            (.HostConfig.RestartPolicy | .Name == "on-failure" or .MaximumRetryCount == 0) and
            (.HostConfig.LogConfig.Type | line and length > 0) and
            ([.Config.Env[]? | select(startswith("CONNEX_CLAMAV_")) |
                line and test("^CONNEX_CLAMAV_[A-Z0-9_]+=")] | all) and
            (.HostConfig.PortBindings["8091/tcp"] | length > 0) and
            ([.HostConfig.PortBindings[]?[]? |
                (keys == ["HostIp", "HostPort"]) and (.HostIp | test("^[0-9a-fA-F:.]*$")) and
                (.HostPort | test("^[0-9]{1,5}$") and (tonumber > 0 and tonumber <= 65535))] | all)
        )
    ' "$SCRATCH/inspect.json" >/dev/null
    OLD_ID="$(jq -r '.[0].Id' "$SCRATCH/inspect.json")"
    OLD_IMAGE="$(jq -r '.[0].Image' "$SCRATCH/inspect.json")"
    jq -r '.[0].Config.Env[]? | select(startswith("CONNEX_CLAMAV_"))' \
        "$SCRATCH/inspect.json" > "$SCRATCH/env"
    chmod 0600 "$SCRATCH/env"
    prepare_arguments
    # Reject embedded line breaks before converting the generated stream to shell arguments.
    jq -e '[.[0] | .HostConfig as $h |
        ($h.LogConfig.Config // {} | to_entries[] | .key, .value),
        ($h.Tmpfs // {} | to_entries[] | .key, .value),
        $h.CapDrop[]?, $h.CapAdd[]?, $h.SecurityOpt[]?, $h.NetworkMode,
        ($h.CgroupnsMode // ""), ($h.IpcMode // ""), ($h.Runtime // ""),
        .NetworkSettings.Networks[].Aliases[]?, .Config.User |
        type == "string" and (test("[\\r\\n]") | not)] | all' \
        "$SCRATCH/inspect.json" >/dev/null
    local ip port
    ip="$(jq -r '.[0].HostConfig.PortBindings["8091/tcp"][0].HostIp' "$SCRATCH/inspect.json")"
    port="$(jq -r '.[0].HostConfig.PortBindings["8091/tcp"][0].HostPort' "$SCRATCH/inspect.json")"
    case "$ip" in
        ''|0.0.0.0) ip=127.0.0.1 ;;
        ::) ip='[::1]' ;;
        *:*) ip="[$ip]" ;;
    esac
    HEALTH_URL="http://$ip:$port/health"
}

wait_ready() {
    local deadline=$((SECONDS + WAIT_SECONDS)) health remaining
    while [ "$SECONDS" -lt "$deadline" ]; do
        health="$(docker container inspect --format \
            '{{if .State.Running}}{{if .State.Health}}{{.State.Health.Status}}{{end}}{{else}}stopped{{end}}' \
            "$CONTAINER")" || return 1
        case "$health" in
            healthy)
                if curl --fail --silent --show-error --noproxy '*' --connect-timeout 2 --max-time 5 \
                    "$HEALTH_URL" | jq -e '.ready == true' >/dev/null; then
                    return 0
                fi ;;
            starting) ;;
            *) return 1 ;;
        esac
        remaining=$((deadline - SECONDS))
        [ "$remaining" -gt 0 ] || break
        [ "$remaining" -le 5 ] || remaining=5
        sleep "$remaining"
    done
    return 1
}

main() {
    if [ "$#" -ne 0 ] || [ "$(id -u)" != 0 ]; then return 1; fi
    if ! [[ "$WAIT_SECONDS" =~ ^[0-9]{1,3}$ ]] || [ "$WAIT_SECONDS" -gt 900 ]; then return 1; fi
    # The deploy wrapper creates this file as its own user. Root must not replace it or create
    # a root-owned lock in sticky /tmp that the non-root wrapper could no longer open.
    if [ ! -f "$LOCK_FILE" ] || [ -L "$LOCK_FILE" ]; then return 1; fi
    exec 9<"$LOCK_FILE"
    if ! flock -n 9; then
        log 'result=skipped reason=lock_busy'
        return 0
    fi
    local sha
    sha="$(cat "$STAGING_DIR/.staging/deployed-sha")"
    [[ "$sha" =~ ^[0-9a-f]{40}$ ]]
    SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/connex-staging-clamav-refresh.XXXXXX")"
    if docker container inspect "$PREVIOUS" >/dev/null 2>&1; then
        STAGE=previous_container_present
        return 1
    fi
    STAGE=settings
    prepare_settings
    STAGE=archive
    # systemd runs as root while the deployment checkout belongs to the deploy user. Trust
    # only this checkout for this invocation, without changing root's global Git configuration.
    git -c safe.directory="$STAGING_DIR" -C "$STAGING_DIR" archive "$sha" clamav | tar -x -C "$SCRATCH"
    STAGE=build
    log "stage=build sha=$sha"
    # Cache reuse would keep the old prefetch layer and defeat signature refresh.
    docker build --pull --no-cache --tag "$CANDIDATE" "$SCRATCH/clamav" >/dev/null
    BUILT=1
    local -a args
    mapfile -t args < "$SCRATCH/args"
    STAGE=swap
    SWAPPING=1
    docker stop --time 30 "$OLD_ID" >/dev/null
    docker rename "$OLD_ID" "$PREVIOUS" >/dev/null
    docker create --name "$CONTAINER" "${args[@]}" --env-file "$SCRATCH/env" "$CANDIDATE" >/dev/null
    docker start "$CONTAINER" >/dev/null
    STAGE=readiness
    wait_ready
    STAGE=tag
    docker tag "$CANDIDATE" "$IMAGE" >/dev/null
    # Readiness and tagging commit the replacement. A later cleanup error must not destroy a
    # working scanner; any remaining stopped backup is preserved for operator inspection.
    SWAPPING=0
    STAGE=container_cleanup
    docker rm "$OLD_ID" >/dev/null
    if ! docker image rm "$OLD_IMAGE" >/dev/null; then
        log 'result=retained stage=image_cleanup'
    fi
    log "result=refreshed sha=$sha"
}

trap 'cleanup' EXIT
trap 'exit 1' INT TERM
# Docker, git, curl and parser diagnostics may contain environment values. Only our labels enter
# the journal, including on unexpected errors; xtrace is disabled before reading any settings.
main "$@" 2>/dev/null
