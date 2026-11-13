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
    local current name
    current="$(docker container inspect --format '{{.Id}}' "$CONTAINER" 2>/dev/null)" || current=""
    if [ -n "$current" ] && [ "$current" != "$OLD_ID" ]; then
        docker stop --time 30 "$current" >/dev/null 2>&1 || return 1
        docker rm "$current" >/dev/null 2>&1 || return 1
    fi
    name="$(docker container inspect --format '{{.Name}}' "$OLD_ID" 2>/dev/null)" || return 1
    if [ "$name" != "/$CONTAINER" ]; then
        docker rename "$OLD_ID" "$CONTAINER" >/dev/null 2>&1 || return 1
    fi
    docker start "$OLD_ID" >/dev/null 2>&1 || return 1
    log 'result=restored'
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
            (.HostConfig.RestartPolicy.MaximumRetryCount | integer) and
            (.HostConfig.LogConfig.Type | line and length > 0) and
            ([.Config.Env[]? | select(startswith("CONNEX_CLAMAV_")) |
                line and test("^CONNEX_CLAMAV_[A-Z0-9_]+=")] | all) and
            (.HostConfig.PortBindings["8091/tcp"] | length > 0) and
            ([.HostConfig.PortBindings[]?[]? |
                (.HostIp | test("^[0-9a-fA-F:.]*$")) and
                (.HostPort | test("^[0-9]{1,5}$") and (tonumber > 0 and tonumber <= 65535))] | all)
        )
    ' "$SCRATCH/inspect.json" >/dev/null
    OLD_ID="$(jq -r '.[0].Id' "$SCRATCH/inspect.json")"
    OLD_IMAGE="$(jq -r '.[0].Image' "$SCRATCH/inspect.json")"
    jq -r '.[0].Config.Env[]? | select(startswith("CONNEX_CLAMAV_"))' \
        "$SCRATCH/inspect.json" > "$SCRATCH/env"
    chmod 0600 "$SCRATCH/env"
    # One argument per line avoids eval and shell interpretation of inspected values.
    jq -r '
        .[0] | .HostConfig as $h |
        "--memory", ($h.Memory | tostring), "--memory-swap", ($h.MemorySwap | tostring),
        ("--read-only=" + ($h.ReadonlyRootfs | tostring)), "--shm-size", ($h.ShmSize | tostring),
        "--restart", ($h.RestartPolicy | .Name +
            if .Name == "on-failure" and .MaximumRetryCount > 0
            then ":" + (.MaximumRetryCount | tostring) else "" end),
        "--network", $h.NetworkMode, "--log-driver", $h.LogConfig.Type,
        ($h.LogConfig.Config // {} | to_entries[] | "--log-opt", (.key + "=" + .value)),
        ($h.Tmpfs // {} | to_entries[] | "--tmpfs", (.key + ":" + .value)),
        ($h.PortBindings // {} | to_entries[] | .key as $port | .value[] |
            "--publish", ((if .HostIp == "" then "" elif (.HostIp | contains(":"))
                then "[" + .HostIp + "]:" else .HostIp + ":" end) + .HostPort + ":" + $port)),
        ($h.CapDrop[]? | "--cap-drop", .), ($h.CapAdd[]? | "--cap-add", .),
        ($h.SecurityOpt[]? | "--security-opt", .),
        (.NetworkSettings.Networks[].Aliases[]? | "--network-alias", .),
        (if .Config.User != "" then "--user", .Config.User else empty end)
    ' "$SCRATCH/inspect.json" > "$SCRATCH/args"
    # Reject embedded line breaks before converting the generated stream to shell arguments.
    jq -e '[.[0] | .HostConfig as $h |
        ($h.LogConfig.Config // {} | to_entries[] | .key, .value),
        ($h.Tmpfs // {} | to_entries[] | .key, .value),
        $h.CapDrop[]?, $h.CapAdd[]?, $h.SecurityOpt[]?,
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
