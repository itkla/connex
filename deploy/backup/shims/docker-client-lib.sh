#!/bin/bash
#
# Shared Docker launcher for the Connex MySQL command shims. Database clients
# default to docker exec against the Compose db container and securely stream
# the mode-0600 defaults file into a temporary container file. Docker run with
# identical-path mounts remains available for external client images.

set -euo pipefail

umask 077

SHIM_ENV_LOADED=false

shim_parse_env_value() {
    local raw="$1"
    if [[ "$raw" =~ ^\"(.*)\"$ ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
        return 0
    fi
    if [[ "$raw" =~ ^\'(.*)\'$ ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
        return 0
    fi
    if [[ "$raw" =~ [[:space:]] ]]; then
        return 1
    fi
    printf '%s' "$raw"
}

shim_load_environment() {
    local env_file="${CONNEX_BACKUP_ENV_FILE:-/etc/connex-backup/backup.env}"
    local line key raw value
    local -a env_lines=()
    if [ "$SHIM_ENV_LOADED" = true ]; then
        return 0
    fi
    if [ ! -r "$env_file" ]; then
        printf 'Connex backup environment file is unreadable: %s\n' "$env_file" >&2
        return 64
    fi
    mapfile -t env_lines < "$env_file"
    for line in "${env_lines[@]}"; do
        if [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]]; then
            continue
        fi
        if [[ ! "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]]; then
            return 64
        fi
        key="${BASH_REMATCH[1]}"
        raw="${BASH_REMATCH[2]}"
        value="$(shim_parse_env_value "$raw")" || return 64
        printf -v "$key" '%s' "$value"
        export "${key?}"
    done
    SHIM_ENV_LOADED=true
}

shim_validate_path() {
    local path="$1"
    [[ "$path" == /* && "$path" != / && "$path" != /tmp && "$path" != /tmp/* ]]
}

shim_run() {
    local tool="$1"
    shift
    local backup_root defaults_dir image network docker_value mount
    local -a docker_command=()
    local -a mount_values=()
    local -a docker_args=()
    shim_load_environment
    backup_root="${CONNEX_BACKUP_ROOT:-/var/backups/connex}"
    defaults_dir="${CONNEX_BACKUP_DEFAULTS_DIR:-/etc/connex-backup}"
    image="${CONNEX_BACKUP_DOCKER_IMAGE:-mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af}"
    network="${CONNEX_BACKUP_DOCKER_NETWORK:-connex_default}"
    docker_value="${CONNEX_BACKUP_DOCKER_BIN:-docker}"
    if ! shim_validate_path "$backup_root" || ! shim_validate_path "$defaults_dir"; then
        printf 'Connex backup Docker mount paths must be safe absolute paths outside /tmp\n' >&2
        return 64
    fi
    read -r -a docker_command <<< "$docker_value"
    if [ "${#docker_command[@]}" -eq 0 ]; then
        return 64
    fi
    docker_args=(run --rm -i --user "$(id -u):$(id -g)" --network "$network" -e TZ=UTC -v "$backup_root:$backup_root" -v "$defaults_dir:$defaults_dir:ro")
    if [ -n "${CONNEX_BACKUP_DOCKER_MOUNTS:-}" ]; then
        read -r -a mount_values <<< "$CONNEX_BACKUP_DOCKER_MOUNTS"
        for mount in "${mount_values[@]}"; do
            docker_args+=(-v "$mount")
        done
    fi
    if [ "${SHIM_DIRECT_ENTRYPOINT:-false}" = true ]; then
        docker_args+=(--entrypoint "$tool" "$image")
    else
        docker_args+=("$image" "$tool")
    fi
    "${docker_command[@]}" "${docker_args[@]}" "$@"
}

shim_extract_defaults_file() {
    local argument
    for argument in "$@"; do
        case "$argument" in
            --defaults-extra-file=*)
                printf '%s\n' "${argument#--defaults-extra-file=}"
                return 0
                ;;
        esac
    done
    return 1
}

shim_exec_in_db() {
    local tool="$1"
    shift
    local defaults_file defaults_size container docker_value remote_script
    local -a docker_command=()
    local -a client_args=()
    local argument
    shim_load_environment
    defaults_file="$(shim_extract_defaults_file "$@")" || {
        printf 'Connex database client shim requires --defaults-extra-file\n' >&2
        return 64
    }
    if ! shim_validate_path "$defaults_file" || [ ! -f "$defaults_file" ] || [ -L "$defaults_file" ] || [ ! -r "$defaults_file" ]; then
        printf 'Connex database client defaults file is invalid: %s\n' "$defaults_file" >&2
        return 64
    fi
    defaults_size="$(stat -c '%s' "$defaults_file")"
    container="${CONNEX_BACKUP_DB_CONTAINER:-connex-db-1}"
    docker_value="${CONNEX_BACKUP_DOCKER_BIN:-docker}"
    read -r -a docker_command <<< "$docker_value"
    if [ "${#docker_command[@]}" -eq 0 ] || [ -z "$container" ]; then
        return 64
    fi
    for argument in "$@"; do
        case "$argument" in
            --defaults-extra-file=*)
                ;;
            *)
                client_args+=("$argument")
                ;;
        esac
    done
    remote_script="
defaults_size=\"\$1\"
shift
tool=\"\$1\"
shift
defaults_file=\"\$(mktemp)\"
chmod 0600 \"\$defaults_file\"
trap 'rm -f \"\$defaults_file\"' EXIT
dd bs=1 count=\"\$defaults_size\" of=\"\$defaults_file\" 2>/dev/null
\"\$tool\" \"--defaults-extra-file=\$defaults_file\" \"\$@\"
"
    {
        cat "$defaults_file"
        cat
    } | "${docker_command[@]}" exec -i "$container" sh -c "$remote_script" sh "$defaults_size" "$tool" "${client_args[@]}"
}

shim_run_database_client() {
    local tool="$1"
    shift
    shim_load_environment
    case "${CONNEX_BACKUP_DOCKER_CLIENT_MODE:-exec}" in
        exec)
            shim_exec_in_db "$tool" "$@"
            ;;
        run)
            shim_run "$tool" "$@"
            ;;
        *)
            printf 'Invalid CONNEX_BACKUP_DOCKER_CLIENT_MODE\n' >&2
            return 64
            ;;
    esac
}
