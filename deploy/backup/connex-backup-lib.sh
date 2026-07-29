#!/bin/bash
#
# Shared runtime for the Connex on-prem database backup, archive, prune, and
# restore commands. It provides strict configuration loading, Docker/native
# MySQL client dispatch, structured logging, locking, integrity validation,
# manifest access, and restore safety primitives.
#
# Exit codes: 64 configuration, 65 lock held, 66 disk guard, 67 database
# preflight, 68 dump, 69 integrity, 70 restore verification, 71 binlog archive,
# 72 prune, 73 stale backup, 74 restore guard, 75 full restore, 76 PITR replay.

set -euo pipefail

umask 077

declare -rx EXIT_CONFIG=64
declare -rx EXIT_LOCK_HELD=65
declare -rx EXIT_DISK_GUARD=66
declare -rx EXIT_DB_PREFLIGHT=67
declare -rx EXIT_DUMP=68
declare -rx EXIT_INTEGRITY=69
declare -rx EXIT_RESTORE_VERIFY=70
declare -rx EXIT_BINLOG_ARCHIVE=71
declare -rx EXIT_PRUNE=72
declare -rx EXIT_STALE_BACKUP=73
declare -rx EXIT_RESTORE_GUARD=74
declare -rx EXIT_RESTORE=75
declare -rx EXIT_PITR=76

BACKUP_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_SCRIPT_STARTED_EPOCH="$(date +%s)"
BACKUP_PHASE=initializing
export BACKUP_PHASE
declare -a MYSQL_COMMAND=()
declare -a MYSQLDUMP_COMMAND=()
declare -a MYSQLBINLOG_COMMAND=()
declare -a BINLOG_STREAM_COMMAND=()
declare -a BINLOG_STAT_COMMAND=()
declare -a BACKUP_LOCK_FDS=()
BACKUP_SUPPRESS_RESTORE_BINLOG=false

backup_exit_code_catalog() {
    printf '%s\n' \
        "$EXIT_CONFIG" \
        "$EXIT_LOCK_HELD" \
        "$EXIT_DISK_GUARD" \
        "$EXIT_DB_PREFLIGHT" \
        "$EXIT_DUMP" \
        "$EXIT_INTEGRITY" \
        "$EXIT_RESTORE_VERIFY" \
        "$EXIT_BINLOG_ARCHIVE" \
        "$EXIT_PRUNE" \
        "$EXIT_STALE_BACKUP" \
        "$EXIT_RESTORE_GUARD" \
        "$EXIT_RESTORE" \
        "$EXIT_PITR"
}

backup_escape_log_value() {
    local value="${1-}"
    value="${value//'%'/'%25'}"
    value="${value//$'\r'/'%0D'}"
    value="${value//$'\n'/'%0A'}"
    value="${value//$'\t'/'%09'}"
    value="${value//' '/'%20'}"
    value="${value//'='/'%3D'}"
    printf '%s' "$value"
}

backup_log_line() {
    local level="$1"
    local event="$2"
    shift 2
    local line key value
    line="ts=$(date -u +%Y-%m-%dT%H:%M:%SZ) level=$level event=$(backup_escape_log_value "$event")"
    while [ "$#" -gt 0 ]; do
        if [ "$#" -lt 2 ]; then
            return "$EXIT_CONFIG"
        fi
        key="$1"
        value="$2"
        shift 2
        if [[ ! "$key" =~ ^[a-z][a-z0-9_]*$ ]]; then
            return "$EXIT_CONFIG"
        fi
        line+=" $key=$(backup_escape_log_value "$value")"
    done
    printf '%s' "$line"
}

backup_log() {
    local line
    line="$(backup_log_line "$@")" || return "$EXIT_CONFIG"
    printf '%s\n' "$line"
}

backup_finish() {
    local exit_code="$1"
    local event="$2"
    shift 2
    local status=success duration line
    if [ "$exit_code" -ne 0 ]; then
        status=failure
    fi
    duration=$(( $(date +%s) - BACKUP_SCRIPT_STARTED_EPOCH ))
    line="$(backup_log_line "$([ "$exit_code" -eq 0 ] && printf info || printf error)" "$event" status "$status" exit_code "$exit_code" phase "$BACKUP_PHASE" duration_seconds "$duration" "$@")"
    printf '%s\n' "$line"
    if [ "$exit_code" -ne 0 ]; then
        printf '%s\n' "$line" >&2
    fi
}

backup_parse_env_value() {
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
        return "$EXIT_CONFIG"
    fi
    printf '%s' "$raw"
}

backup_load_environment() {
    local env_file="${CONNEX_BACKUP_ENV_FILE:-/etc/connex-backup/backup.env}"
    local line key raw value line_number=0
    local -a env_lines=()
    if [ ! -r "$env_file" ]; then
        backup_log error config_error reason env_file_unreadable path "$env_file"
        return "$EXIT_CONFIG"
    fi
    mapfile -t env_lines < "$env_file"
    for line in "${env_lines[@]}"; do
        line_number=$((line_number + 1))
        if [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]]; then
            continue
        fi
        if [[ ! "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]]; then
            backup_log error config_error reason invalid_env_line path "$env_file" line "$line_number"
            return "$EXIT_CONFIG"
        fi
        key="${BASH_REMATCH[1]}"
        raw="${BASH_REMATCH[2]}"
        if ! value="$(backup_parse_env_value "$raw")"; then
            backup_log error config_error reason invalid_env_value key "$key" line "$line_number"
            return "$EXIT_CONFIG"
        fi
        printf -v "$key" '%s' "$value"
        export "${key?}"
    done
    CONNEX_BACKUP_ENV_FILE="$env_file"
    export CONNEX_BACKUP_ENV_FILE
}

backup_set_defaults() {
    : "${CONNEX_BACKUP_ROOT:=/var/backups/connex}"
    : "${CONNEX_BACKUP_RETENTION_DAYS:=30}"
    : "${CONNEX_BACKUP_MIN_FREE_BYTES:=5368709120}"
    : "${CONNEX_BACKUP_FAILED_GRACE_HOURS:=24}"
    : "${CONNEX_BACKUP_SCHEMA_INCLUDE:=}"
    : "${CONNEX_BACKUP_SCHEMA_EXCLUDE:=information_schema,performance_schema,mysql,sys}"
    : "${CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE:=}"
    : "${CONNEX_BACKUP_DB_HOST:=db}"
    : "${CONNEX_BACKUP_DB_PORT:=3306}"
    : "${CONNEX_BACKUP_DB_USER:=root}"
    : "${CONNEX_BACKUP_SOURCE_DEFAULTS_FILE:=/etc/connex-backup/source.cnf}"
    : "${CONNEX_BACKUP_RESTORE_VERIFY:=true}"
    : "${CONNEX_BACKUP_VERIFY_DB_HOST:=$CONNEX_BACKUP_DB_HOST}"
    : "${CONNEX_BACKUP_VERIFY_DB_PORT:=$CONNEX_BACKUP_DB_PORT}"
    : "${CONNEX_BACKUP_VERIFY_DB_USER:=$CONNEX_BACKUP_DB_USER}"
    : "${CONNEX_BACKUP_VERIFY_DEFAULTS_FILE:=$CONNEX_BACKUP_SOURCE_DEFAULTS_FILE}"
    : "${CONNEX_BACKUP_RESTORE_DB_HOST:=$CONNEX_BACKUP_DB_HOST}"
    : "${CONNEX_BACKUP_RESTORE_DB_PORT:=$CONNEX_BACKUP_DB_PORT}"
    : "${CONNEX_BACKUP_RESTORE_DB_USER:=$CONNEX_BACKUP_DB_USER}"
    : "${CONNEX_BACKUP_RESTORE_DEFAULTS_FILE:=$CONNEX_BACKUP_SOURCE_DEFAULTS_FILE}"
    : "${CONNEX_BACKUP_PROTECTED_SCHEMAS:=connex_pub}"
    : "${CONNEX_BACKUP_PROTECTED_SCHEMA_PATTERN:=^connexdb$|^connex_pub$|prod}"
    : "${CONNEX_BACKUP_BINLOG_FETCH_MODE:=stream}"
    : "${CONNEX_BACKUP_BINLOG_FLUSH:=true}"
    : "${CONNEX_BACKUP_BINLOG_NO_FLUSH_ACK:=false}"
    : "${CONNEX_BACKUP_DB_CONTAINER:=connex-db-1}"
    : "${CONNEX_BACKUP_BINLOG_DIR:=/var/lib/mysql}"
    : "${CONNEX_BACKUP_BINLOG_STREAM:=docker exec -i $CONNEX_BACKUP_DB_CONTAINER cat}"
    : "${CONNEX_BACKUP_BINLOG_STAT:=docker exec -i $CONNEX_BACKUP_DB_CONTAINER stat -c %W:%Y}"
    : "${CONNEX_BACKUP_GZIP_LEVEL:=6}"
    : "${CONNEX_BACKUP_LOCK_DIR:=/run/connex-backup}"
    : "${CONNEX_BACKUP_DOCKER_NETWORK:=connex_default}"
    : "${CONNEX_BACKUP_DOCKER_IMAGE:=mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af}"
    : "${CONNEX_BACKUP_DOCKER_BINLOG_IMAGE:=}"
    : "${CONNEX_BACKUP_DOCKER_CLIENT_MODE:=exec}"
    : "${CONNEX_BACKUP_DOCKER_MOUNTS:=}"
    : "${CONNEX_BACKUP_DOCKER_BIN:=docker}"
    : "${CONNEX_BACKUP_DEFAULTS_DIR:=/etc/connex-backup}"
    : "${CONNEX_BACKUP_FULL_CALENDAR:=*-*-* 03:30:00 Asia/Tokyo}"
    : "${CONNEX_BACKUP_BINLOG_CALENDAR:=*:0/15}"
    : "${CONNEX_BACKUP_PRUNE_CALENDAR:=*-*-* 04,16:30:00 Asia/Tokyo}"
    : "${MYSQL:=$BACKUP_LIB_DIR/shims/mysql}"
    : "${MYSQLDUMP:=$BACKUP_LIB_DIR/shims/mysqldump}"
    : "${MYSQLBINLOG:=$BACKUP_LIB_DIR/shims/mysqlbinlog}"
    export CONNEX_BACKUP_ROOT CONNEX_BACKUP_RETENTION_DAYS
    export CONNEX_BACKUP_MIN_FREE_BYTES CONNEX_BACKUP_FAILED_GRACE_HOURS
    export CONNEX_BACKUP_SCHEMA_INCLUDE CONNEX_BACKUP_SCHEMA_EXCLUDE
    export CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE
    export CONNEX_BACKUP_DB_HOST CONNEX_BACKUP_DB_PORT CONNEX_BACKUP_DB_USER
    export CONNEX_BACKUP_SOURCE_DEFAULTS_FILE CONNEX_BACKUP_RESTORE_VERIFY
    export CONNEX_BACKUP_VERIFY_DB_HOST CONNEX_BACKUP_VERIFY_DB_PORT
    export CONNEX_BACKUP_VERIFY_DB_USER CONNEX_BACKUP_VERIFY_DEFAULTS_FILE
    export CONNEX_BACKUP_RESTORE_DB_HOST CONNEX_BACKUP_RESTORE_DB_PORT
    export CONNEX_BACKUP_RESTORE_DB_USER CONNEX_BACKUP_RESTORE_DEFAULTS_FILE
    export CONNEX_BACKUP_PROTECTED_SCHEMAS CONNEX_BACKUP_PROTECTED_SCHEMA_PATTERN
    export CONNEX_BACKUP_BINLOG_FETCH_MODE CONNEX_BACKUP_BINLOG_FLUSH
    export CONNEX_BACKUP_BINLOG_NO_FLUSH_ACK CONNEX_BACKUP_DB_CONTAINER
    export CONNEX_BACKUP_BINLOG_DIR CONNEX_BACKUP_BINLOG_STREAM
    export CONNEX_BACKUP_BINLOG_STAT
    export CONNEX_BACKUP_GZIP_LEVEL CONNEX_BACKUP_LOCK_DIR
    export CONNEX_BACKUP_DOCKER_NETWORK CONNEX_BACKUP_DOCKER_IMAGE
    export CONNEX_BACKUP_DOCKER_BINLOG_IMAGE CONNEX_BACKUP_DOCKER_CLIENT_MODE
    export CONNEX_BACKUP_DOCKER_MOUNTS CONNEX_BACKUP_DOCKER_BIN
    export CONNEX_BACKUP_DEFAULTS_DIR MYSQL MYSQLDUMP MYSQLBINLOG
}

backup_validate_integer() {
    local name="$1"
    local value="$2"
    local minimum="$3"
    local maximum="$4"
    if [[ ! "$value" =~ ^[0-9]+$ ]] || [ "$value" -lt "$minimum" ] || [ "$value" -gt "$maximum" ]; then
        backup_log error config_error reason invalid_integer key "$name" value "$value" minimum "$minimum" maximum "$maximum"
        return "$EXIT_CONFIG"
    fi
}

backup_validate_boolean() {
    local name="$1"
    local value="$2"
    if [ "$value" != true ] && [ "$value" != false ]; then
        backup_log error config_error reason invalid_boolean key "$name" value "$value"
        return "$EXIT_CONFIG"
    fi
}

backup_validate_absolute_path() {
    local name="$1"
    local value="$2"
    if [[ "$value" != /* || "$value" == / || "$value" == /tmp || "$value" == /tmp/* || "$value" == *$'\n'* ]]; then
        backup_log error config_error reason unsafe_path key "$name" value "$value"
        return "$EXIT_CONFIG"
    fi
}

backup_validate_schema() {
    local schema="$1"
    if [[ ! "$schema" =~ ^[A-Za-z0-9_\$-]+$ ]] || [ "${#schema}" -gt 64 ]; then
        backup_log error config_error reason invalid_schema schema "$schema"
        return "$EXIT_CONFIG"
    fi
}

backup_validate_schema_list() {
    local name="$1"
    local value="$2"
    local schema
    local -a schemas=()
    if [ -z "$value" ]; then
        return 0
    fi
    IFS=',' read -r -a schemas <<< "$value"
    for schema in "${schemas[@]}"; do
        if [ -z "$schema" ]; then
            backup_log error config_error reason empty_schema_list_entry key "$name"
            return "$EXIT_CONFIG"
        fi
        backup_validate_schema "$schema" || return "$EXIT_CONFIG"
    done
}

backup_validate_defaults_file() {
    local name="$1"
    local path="$2"
    local mode
    backup_validate_absolute_path "$name" "$path" || return "$EXIT_CONFIG"
    if [ ! -f "$path" ] || [ -L "$path" ] || [ ! -r "$path" ]; then
        backup_log error config_error reason invalid_defaults_file key "$name" path "$path"
        return "$EXIT_CONFIG"
    fi
    mode="$(stat -c '%a' "$path")"
    if [ "$mode" != 600 ]; then
        backup_log error config_error reason defaults_file_mode key "$name" path "$path" mode "$mode" required_mode 600
        return "$EXIT_CONFIG"
    fi
}

backup_validate_pattern() {
    local pattern="$1"
    local result=0
    printf '' | grep -E "$pattern" >/dev/null 2>&1 || result=$?
    if [ "$result" -gt 1 ]; then
        backup_log error config_error reason invalid_protected_schema_pattern pattern "$pattern"
        return "$EXIT_CONFIG"
    fi
}

backup_parse_command() {
    local value="$1"
    local output_name="$2"
    local -n output="$output_name"
    read -r -a output <<< "$value"
    if [ "${#output[@]}" -eq 0 ]; then
        backup_log error config_error reason empty_command command "$output_name"
        return "$EXIT_CONFIG"
    fi
    if ! command -v "${output[0]}" >/dev/null 2>&1; then
        backup_log error config_error reason command_not_found command "${output[0]}"
        return "$EXIT_CONFIG"
    fi
}

backup_initialize_commands() {
    backup_parse_command "$MYSQL" MYSQL_COMMAND || return "$EXIT_CONFIG"
    backup_parse_command "$MYSQLDUMP" MYSQLDUMP_COMMAND || return "$EXIT_CONFIG"
}

backup_initialize_mysqlbinlog() {
    backup_parse_command "$MYSQLBINLOG" MYSQLBINLOG_COMMAND || return "$EXIT_CONFIG"
}

backup_initialize_stream_commands() {
    backup_parse_command "$CONNEX_BACKUP_BINLOG_STREAM" BINLOG_STREAM_COMMAND || return "$EXIT_CONFIG"
    backup_parse_command "$CONNEX_BACKUP_BINLOG_STAT" BINLOG_STAT_COMMAND || return "$EXIT_CONFIG"
}

backup_validate_common() {
    backup_set_defaults
    backup_validate_absolute_path CONNEX_BACKUP_ROOT "$CONNEX_BACKUP_ROOT" || return "$EXIT_CONFIG"
    backup_validate_absolute_path CONNEX_BACKUP_LOCK_DIR "$CONNEX_BACKUP_LOCK_DIR" || return "$EXIT_CONFIG"
    backup_validate_integer CONNEX_BACKUP_RETENTION_DAYS "$CONNEX_BACKUP_RETENTION_DAYS" 1 30 || return "$EXIT_CONFIG"
    backup_validate_integer CONNEX_BACKUP_MIN_FREE_BYTES "$CONNEX_BACKUP_MIN_FREE_BYTES" 0 9223372036854775807 || return "$EXIT_CONFIG"
    backup_validate_integer CONNEX_BACKUP_FAILED_GRACE_HOURS "$CONNEX_BACKUP_FAILED_GRACE_HOURS" 1 168 || return "$EXIT_CONFIG"
    backup_validate_integer CONNEX_BACKUP_DB_PORT "$CONNEX_BACKUP_DB_PORT" 1 65535 || return "$EXIT_CONFIG"
    backup_validate_integer CONNEX_BACKUP_VERIFY_DB_PORT "$CONNEX_BACKUP_VERIFY_DB_PORT" 1 65535 || return "$EXIT_CONFIG"
    backup_validate_integer CONNEX_BACKUP_RESTORE_DB_PORT "$CONNEX_BACKUP_RESTORE_DB_PORT" 1 65535 || return "$EXIT_CONFIG"
    backup_validate_integer CONNEX_BACKUP_GZIP_LEVEL "$CONNEX_BACKUP_GZIP_LEVEL" 1 9 || return "$EXIT_CONFIG"
    backup_validate_boolean CONNEX_BACKUP_RESTORE_VERIFY "$CONNEX_BACKUP_RESTORE_VERIFY" || return "$EXIT_CONFIG"
    backup_validate_boolean CONNEX_BACKUP_BINLOG_FLUSH "$CONNEX_BACKUP_BINLOG_FLUSH" || return "$EXIT_CONFIG"
    backup_validate_boolean CONNEX_BACKUP_BINLOG_NO_FLUSH_ACK "$CONNEX_BACKUP_BINLOG_NO_FLUSH_ACK" || return "$EXIT_CONFIG"
    backup_validate_schema_list CONNEX_BACKUP_SCHEMA_INCLUDE "$CONNEX_BACKUP_SCHEMA_INCLUDE" || return "$EXIT_CONFIG"
    backup_validate_schema_list CONNEX_BACKUP_SCHEMA_EXCLUDE "$CONNEX_BACKUP_SCHEMA_EXCLUDE" || return "$EXIT_CONFIG"
    backup_validate_schema_list CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE "$CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE" || return "$EXIT_CONFIG"
    backup_validate_schema_list CONNEX_BACKUP_PROTECTED_SCHEMAS "$CONNEX_BACKUP_PROTECTED_SCHEMAS" || return "$EXIT_CONFIG"
    backup_validate_pattern "$CONNEX_BACKUP_PROTECTED_SCHEMA_PATTERN" || return "$EXIT_CONFIG"
    backup_validate_defaults_file CONNEX_BACKUP_SOURCE_DEFAULTS_FILE "$CONNEX_BACKUP_SOURCE_DEFAULTS_FILE" || return "$EXIT_CONFIG"
    if [ "$CONNEX_BACKUP_RESTORE_VERIFY" = true ]; then
        backup_validate_defaults_file CONNEX_BACKUP_VERIFY_DEFAULTS_FILE "$CONNEX_BACKUP_VERIFY_DEFAULTS_FILE" || return "$EXIT_CONFIG"
    fi
    if [ "$CONNEX_BACKUP_BINLOG_FETCH_MODE" != stream ] && [ "$CONNEX_BACKUP_BINLOG_FETCH_MODE" != mysqlbinlog ]; then
        backup_log error config_error reason invalid_binlog_fetch_mode value "$CONNEX_BACKUP_BINLOG_FETCH_MODE"
        return "$EXIT_CONFIG"
    fi
    if [ "$CONNEX_BACKUP_DOCKER_CLIENT_MODE" != exec ] && [ "$CONNEX_BACKUP_DOCKER_CLIENT_MODE" != run ]; then
        backup_log error config_error reason invalid_docker_client_mode value "$CONNEX_BACKUP_DOCKER_CLIENT_MODE"
        return "$EXIT_CONFIG"
    fi
    backup_validate_absolute_path CONNEX_BACKUP_BINLOG_DIR "$CONNEX_BACKUP_BINLOG_DIR" || return "$EXIT_CONFIG"
    backup_initialize_commands || return "$EXIT_CONFIG"
}

backup_validate_restore_profile() {
    backup_validate_defaults_file CONNEX_BACKUP_RESTORE_DEFAULTS_FILE "$CONNEX_BACKUP_RESTORE_DEFAULTS_FILE"
}

backup_profile_values() {
    local profile="$1"
    case "$profile" in
        source)
            printf '%s\t%s\t%s\t%s\n' "$CONNEX_BACKUP_DB_HOST" "$CONNEX_BACKUP_DB_PORT" "$CONNEX_BACKUP_DB_USER" "$CONNEX_BACKUP_SOURCE_DEFAULTS_FILE"
            ;;
        verify)
            printf '%s\t%s\t%s\t%s\n' "$CONNEX_BACKUP_VERIFY_DB_HOST" "$CONNEX_BACKUP_VERIFY_DB_PORT" "$CONNEX_BACKUP_VERIFY_DB_USER" "$CONNEX_BACKUP_VERIFY_DEFAULTS_FILE"
            ;;
        restore)
            printf '%s\t%s\t%s\t%s\n' "$CONNEX_BACKUP_RESTORE_DB_HOST" "$CONNEX_BACKUP_RESTORE_DB_PORT" "$CONNEX_BACKUP_RESTORE_DB_USER" "$CONNEX_BACKUP_RESTORE_DEFAULTS_FILE"
            ;;
        *)
            return "$EXIT_CONFIG"
            ;;
    esac
}

backup_mysql() {
    local profile="$1"
    shift
    local host port user defaults_file
    IFS=$'\t' read -r host port user defaults_file < <(backup_profile_values "$profile")
    "${MYSQL_COMMAND[@]}" "--defaults-extra-file=$defaults_file" --protocol=TCP --host="$host" --port="$port" --user="$user" "$@"
}

backup_profile_suppresses_binlog() {
    local profile="$1"
    case "$profile" in
        verify)
            return 0
            ;;
        restore)
            [ "$BACKUP_SUPPRESS_RESTORE_BINLOG" = true ]
            ;;
        *)
            return 1
            ;;
    esac
}

backup_session_preamble() {
    local profile="$1"
    if backup_profile_suppresses_binlog "$profile"; then
        printf 'SET SESSION sql_log_bin = 0;\n'
    fi
}

backup_probe_binlog_suppression() {
    local profile="$1"
    if ! backup_profile_suppresses_binlog "$profile"; then
        return 0
    fi
    if backup_mysql_query "$profile" "SELECT 1;" >/dev/null 2>&1; then
        return 0
    fi
    return "$EXIT_DB_PREFLIGHT"
}

backup_mysql_query() {
    local profile="$1"
    local query="$2"
    backup_mysql "$profile" --batch --skip-column-names --raw --execute="$(backup_session_preamble "$profile" | tr '\n' ' ')$query"
}

backup_mysqldump() {
    local profile="$1"
    shift
    local host port user defaults_file
    IFS=$'\t' read -r host port user defaults_file < <(backup_profile_values "$profile")
    "${MYSQLDUMP_COMMAND[@]}" "--defaults-extra-file=$defaults_file" --protocol=TCP --host="$host" --port="$port" --user="$user" "$@"
}

backup_mysqlbinlog_remote() {
    local profile="$1"
    shift
    local host port user defaults_file
    IFS=$'\t' read -r host port user defaults_file < <(backup_profile_values "$profile")
    "${MYSQLBINLOG_COMMAND[@]}" "--defaults-extra-file=$defaults_file" --host="$host" --port="$port" --user="$user" "$@"
}

backup_mysqlbinlog_local() {
    TZ=UTC "${MYSQLBINLOG_COMMAND[@]}" "$@"
}

backup_stream_binlog() {
    "${BINLOG_STREAM_COMMAND[@]}" "$@"
}

backup_stat_binlog() {
    "${BINLOG_STAT_COMMAND[@]}" "$@"
}

backup_prepare_directories() {
    mkdir -p "$CONNEX_BACKUP_ROOT/full" "$CONNEX_BACKUP_ROOT/binlog" "$CONNEX_BACKUP_LOCK_DIR"
    chmod 0700 "$CONNEX_BACKUP_ROOT" "$CONNEX_BACKUP_ROOT/full" "$CONNEX_BACKUP_ROOT/binlog" "$CONNEX_BACKUP_LOCK_DIR"
}

backup_acquire_lock() {
    local mode="$1"
    local name="$2"
    local wait_seconds="${3:-0}"
    local fd flag
    exec {fd}>"$CONNEX_BACKUP_LOCK_DIR/$name.lock"
    if [ "$mode" = shared ]; then
        flag=-s
    else
        flag=-x
    fi
    if [ "$wait_seconds" -gt 0 ]; then
        if ! flock -w "$wait_seconds" "$flag" "$fd"; then
            backup_log error lock_held lock "$name" mode "$mode" waited_seconds "$wait_seconds"
            return "$EXIT_LOCK_HELD"
        fi
    elif ! flock -n "$flag" "$fd"; then
        backup_log error lock_held lock "$name" mode "$mode"
        return "$EXIT_LOCK_HELD"
    fi
    BACKUP_LOCK_FDS+=("$fd")
}

backup_schema_in_list() {
    local schema="$1"
    local list="$2"
    local item
    local -a items=()
    if [ -z "$list" ]; then
        return 1
    fi
    IFS=',' read -r -a items <<< "$list"
    for item in "${items[@]}"; do
        if [ "$schema" = "$item" ]; then
            return 0
        fi
    done
    return 1
}

backup_schema_selected() {
    local schema="$1"
    if [ -n "$CONNEX_BACKUP_SCHEMA_INCLUDE" ] && ! backup_schema_in_list "$schema" "$CONNEX_BACKUP_SCHEMA_INCLUDE"; then
        return 1
    fi
    if backup_schema_in_list "$schema" "$CONNEX_BACKUP_SCHEMA_EXCLUDE"; then
        # Naming a schema in both lists is a configuration conflict, and a
        # backup that quietly drops a schema the operator asked for is the
        # failure this guard exists to make visible.
        if backup_schema_in_list "$schema" "$CONNEX_BACKUP_SCHEMA_INCLUDE"; then
            backup_log warn schema_skipped reason include_overridden_by_exclude schema "$schema"
        fi
        return 1
    fi
    # A restore-verify scratch schema abandoned by an interrupted run holds a
    # copy of source data; it must never be discovered and backed up as if it
    # were customer data of its own. An operator who genuinely owns a schema
    # with that prefix names it in CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE, which
    # only lifts this prefix rule; CONNEX_BACKUP_SCHEMA_INCLUDE would work too
    # but is an allowlist, so reaching for it here would silently drop every
    # other schema from the backup. The drop is never silent either way.
    if [[ "$schema" == connex_verify_* ]] &&
        ! backup_schema_in_list "$schema" "$CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE" &&
        ! backup_schema_in_list "$schema" "$CONNEX_BACKUP_SCHEMA_INCLUDE"; then
        backup_log info schema_skipped reason restore_verify_scratch schema "$schema"
        return 1
    fi
    return 0
}

backup_extract_coordinates() {
    local artifact="$1"
    local coordinates count
    coordinates="$(gzip -dc "$artifact" | sed -n "s/^-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='\\([^']*\\)', SOURCE_LOG_POS=\\([0-9][0-9]*\\);$/\\1\\t\\2/p")" || return "$EXIT_INTEGRITY"
    count="$(printf '%s\n' "$coordinates" | awk 'NF { count++ } END { print count + 0 }')"
    if [ "$count" -ne 1 ]; then
        return "$EXIT_INTEGRITY"
    fi
    printf '%s\n' "$coordinates"
}

backup_atomic_write() {
    local destination="$1"
    local temporary="${destination}.tmp.$$"
    shift
    "$@" > "$temporary"
    mv "$temporary" "$destination"
}

backup_manifest_value() {
    local manifest="$1"
    local key="$2"
    awk -F '\t' -v key="$key" '$1 == key { print $2; count++ } END { if (count != 1) exit 1 }' "$manifest"
}

backup_manifest_schema_names() {
    local manifest="$1"
    awk -F '\t' '$1 == "schema" { print $2 }' "$manifest"
}

backup_manifest_schema_field() {
    local manifest="$1"
    local schema="$2"
    local field="$3"
    awk -F '\t' -v schema="$schema" -v field="$field" '
        $1 == "schema" && $2 == schema {
            if (field == "artifact") print $3
            if (field == "capture_started") print $4
            if (field == "capture_completed") print $5
            if (field == "binlog_file") print $6
            if (field == "binlog_position") print $7
            if (field == "duration_seconds") print $8
            if (field == "bytes") print $9
            if (field == "sha256") print $10
            if (field == "base_table_count") print $11
            if (field == "charset") print $12
            if (field == "collation") print $13
            count++
        }
        END {
            if (count != 1) exit 1
        }
    ' "$manifest"
}

backup_validate_checksum_manifest() {
    local run_dir="$1"
    local checksum_file="$run_dir/sha256sums"
    local hash path clean_path
    if [ ! -f "$checksum_file" ] || [ -L "$checksum_file" ]; then
        return "$EXIT_INTEGRITY"
    fi
    while read -r hash path; do
        clean_path="${path#\*}"
        if [[ ! "$hash" =~ ^[0-9a-f]{64}$ ]]; then
            return "$EXIT_INTEGRITY"
        fi
        if [ "$clean_path" != manifest ] && [[ ! "$clean_path" =~ ^[A-Za-z0-9_\$-]+[.]sql[.]gz$ ]]; then
            return "$EXIT_INTEGRITY"
        fi
        if [ ! -f "$run_dir/$clean_path" ] || [ -L "$run_dir/$clean_path" ]; then
            return "$EXIT_INTEGRITY"
        fi
    done < "$checksum_file"
    (
        cd "$run_dir"
        sha256sum --check --strict sha256sums >/dev/null
    ) || return "$EXIT_INTEGRITY"
}

backup_validate_run() {
    local run_dir="$1"
    local manifest="$run_dir/manifest"
    local schema artifact schema_count=0 artifact_count
    if [ ! -d "$run_dir" ] || [ -L "$run_dir" ] || [ ! -f "$run_dir/COMPLETE" ] || [ -e "$run_dir/FAILED" ] || [ -e "$run_dir/IN_PROGRESS" ]; then
        return "$EXIT_INTEGRITY"
    fi
    if [ ! -f "$manifest" ] || [ -L "$manifest" ]; then
        return "$EXIT_INTEGRITY"
    fi
    backup_manifest_value "$manifest" manifest_version >/dev/null || return "$EXIT_INTEGRITY"
    backup_manifest_value "$manifest" run_id >/dev/null || return "$EXIT_INTEGRITY"
    if ! backup_validate_checksum_manifest "$run_dir"; then
        return "$EXIT_INTEGRITY"
    fi
    while IFS= read -r schema; do
        schema_count=$((schema_count + 1))
        backup_validate_schema "$schema" >/dev/null || return "$EXIT_INTEGRITY"
        artifact="$(backup_manifest_schema_field "$manifest" "$schema" artifact)" || return "$EXIT_INTEGRITY"
        if [ "$artifact" != "$schema.sql.gz" ]; then
            return "$EXIT_INTEGRITY"
        fi
        gzip -t "$run_dir/$artifact" || return "$EXIT_INTEGRITY"
        backup_manifest_schema_field "$manifest" "$schema" binlog_file >/dev/null || return "$EXIT_INTEGRITY"
        backup_manifest_schema_field "$manifest" "$schema" binlog_position >/dev/null || return "$EXIT_INTEGRITY"
    done < <(backup_manifest_schema_names "$manifest")
    artifact_count="$(find "$run_dir" -maxdepth 1 -type f -name '*.sql.gz' -printf '.' | wc -c)"
    if [ "$schema_count" -eq 0 ] || [ "$schema_count" -ne "$artifact_count" ]; then
        return "$EXIT_INTEGRITY"
    fi
}

backup_resolve_run() {
    local requested="$1"
    local full_root="$CONNEX_BACKUP_ROOT/full"
    local candidate resolved
    if [ "$requested" = latest ]; then
        candidate="$(find "$full_root" -mindepth 1 -maxdepth 1 -type d -name '*Z' -exec test -f '{}/COMPLETE' ';' -print | sort -r | sed -n '1p')"
        if [ -z "$candidate" ]; then
            return "$EXIT_RESTORE_GUARD"
        fi
    else
        candidate="$requested"
        if [[ "$candidate" != /* ]]; then
            candidate="$full_root/$candidate"
        fi
    fi
    resolved="$(realpath -e "$candidate")" || return "$EXIT_RESTORE_GUARD"
    if [[ "$resolved" != "$full_root/"* ]]; then
        return "$EXIT_RESTORE_GUARD"
    fi
    printf '%s\n' "$resolved"
}

backup_select_source_schema() {
    local manifest="$1"
    local requested="$2"
    local schema count=0 selected=
    while IFS= read -r schema; do
        count=$((count + 1))
        if [ -n "$requested" ] && [ "$schema" = "$requested" ]; then
            selected="$schema"
        fi
        if [ -z "$requested" ]; then
            selected="$schema"
        fi
    done < <(backup_manifest_schema_names "$manifest")
    if [ -n "$requested" ] && [ -z "$selected" ]; then
        backup_log error restore_refused reason source_schema_not_in_manifest source_schema "$requested"
        return "$EXIT_RESTORE_GUARD"
    fi
    if [ -z "$requested" ] && [ "$count" -ne 1 ]; then
        backup_log error restore_refused reason source_schema_ambiguous schema_count "$count" override "--source-schema"
        return "$EXIT_RESTORE_GUARD"
    fi
    printf '%s\n' "$selected"
}

backup_schema_exists() {
    local profile="$1"
    local schema="$2"
    local count
    count="$(backup_mysql_query "$profile" "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$schema';")" || return "$EXIT_DB_PREFLIGHT"
    [ "$count" -gt 0 ]
}

backup_schema_object_count() {
    local profile="$1"
    local schema="$2"
    backup_mysql_query "$profile" "SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$schema') + (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = '$schema') + (SELECT COUNT(*) FROM information_schema.events WHERE event_schema = '$schema') + (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = '$schema');"
}

backup_schema_row_summary() {
    local profile="$1"
    local schema="$2"
    local table quoted_table rows table_list total=0 tables=0
    local -a table_names=()
    table_list="$(backup_mysql_query "$profile" "SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' AND table_type = 'BASE TABLE' ORDER BY table_name;")" || return "$EXIT_RESTORE"
    mapfile -t table_names <<< "$table_list"
    for table in "${table_names[@]}"; do
        if [ -z "$table" ]; then
            continue
        fi
        backup_validate_schema "$table" >/dev/null || return "$EXIT_RESTORE"
        quoted_table="\`$table\`"
        rows="$(backup_mysql_query "$profile" "SELECT COUNT(*) FROM \`$schema\`.$quoted_table;")" || return "$EXIT_RESTORE"
        total=$((total + rows))
        tables=$((tables + 1))
    done
    printf '%s\t%s\n' "$tables" "$total"
}

backup_schema_is_protected() {
    local schema="$1"
    local lower_schema="${schema,,}"
    if backup_schema_in_list "$lower_schema" "${CONNEX_BACKUP_PROTECTED_SCHEMAS,,}"; then
        return 0
    fi
    shopt -s nocasematch
    local matched=1
    if [[ "$lower_schema" =~ $CONNEX_BACKUP_PROTECTED_SCHEMA_PATTERN ]]; then
        matched=0
    fi
    shopt -u nocasematch
    return "$matched"
}

backup_schema_collides() {
    local manifest="$1"
    local source_schema="$2"
    local target_schema="$3"
    local schema
    while IFS= read -r schema; do
        if [ "$schema" = "$target_schema" ] && [ "$schema" != "$source_schema" ]; then
            return 0
        fi
    done < <(backup_manifest_schema_names "$manifest")
    return 1
}

backup_prepare_restore_target() {
    local manifest="$1"
    local source_schema="$2"
    local target_schema="$3"
    local force="$4"
    local profile="$5"
    local object_count=0 charset collation exists=false
    backup_validate_schema "$target_schema" || return "$EXIT_RESTORE_GUARD"
    if backup_schema_is_protected "$target_schema"; then
        if [ "$force" != true ]; then
            backup_log error restore_refused reason protected_schema target_schema "$target_schema" override "--force-overwrite"
            return "$EXIT_RESTORE_GUARD"
        fi
        backup_log warn restore_guard_overridden reason protected_schema target_schema "$target_schema" override "--force-overwrite"
    fi
    if backup_schema_collides "$manifest" "$source_schema" "$target_schema"; then
        if [ "$force" != true ]; then
            backup_log error restore_refused reason source_manifest_schema_collision target_schema "$target_schema" override "--force-overwrite"
            return "$EXIT_RESTORE_GUARD"
        fi
        backup_log warn restore_guard_overridden reason source_manifest_schema_collision target_schema "$target_schema" override "--force-overwrite"
    fi
    if backup_schema_exists "$profile" "$target_schema"; then
        exists=true
        object_count="$(backup_schema_object_count "$profile" "$target_schema")" || return "$EXIT_DB_PREFLIGHT"
    fi
    if [ "$object_count" -gt 0 ] && [ "$force" != true ]; then
        backup_log error restore_refused reason existing_nonempty_schema target_schema "$target_schema" object_count "$object_count" override "--force-overwrite"
        return "$EXIT_RESTORE_GUARD"
    fi
    if [ "$object_count" -gt 0 ]; then
        backup_log warn restore_guard_overridden reason existing_nonempty_schema target_schema "$target_schema" object_count "$object_count" override "--force-overwrite"
    fi
    charset="$(backup_manifest_schema_field "$manifest" "$source_schema" charset)" || return "$EXIT_INTEGRITY"
    collation="$(backup_manifest_schema_field "$manifest" "$source_schema" collation)" || return "$EXIT_INTEGRITY"
    if [[ ! "$charset" =~ ^[A-Za-z0-9_]+$ || ! "$collation" =~ ^[A-Za-z0-9_]+$ ]]; then
        return "$EXIT_INTEGRITY"
    fi
    if [ "$exists" = true ]; then
        backup_mysql_query "$profile" "DROP DATABASE \`$target_schema\`;" >/dev/null || return "$EXIT_RESTORE"
    fi
    backup_mysql_query "$profile" "CREATE DATABASE \`$target_schema\` CHARACTER SET $charset COLLATE $collation;" >/dev/null || return "$EXIT_RESTORE"
}

backup_filter_dump_global_state() {
    awk '
        skipping {
            if ($0 ~ /;[[:space:]]*$/) skipping = 0
            next
        }
        /^SET @@GLOBAL[.]GTID_PURGED/ {
            if ($0 !~ /;[[:space:]]*$/) skipping = 1
            next
        }
        /^SET @@SESSION[.]SQL_LOG_BIN/ {
            next
        }
        { print }
    '
}

backup_configure_sidecar_binlog() {
    local source_schema="$1"
    local target_schema="$2"
    if [ "$source_schema" = "$target_schema" ]; then
        backup_log info restore_binlog_mode mode logged reason in_place_recovery target_schema "$target_schema"
        return 0
    fi
    BACKUP_SUPPRESS_RESTORE_BINLOG=true
    if backup_probe_binlog_suppression restore; then
        backup_log info restore_binlog_mode mode suppressed reason sidecar_restore target_schema "$target_schema"
        return 0
    fi
    BACKUP_SUPPRESS_RESTORE_BINLOG=false
    backup_log warn restore_binlog_mode mode logged reason session_binlog_disable_denied required_privilege BINLOG_ADMIN target_schema "$target_schema"
}

backup_restore_artifact() {
    local run_dir="$1"
    local source_schema="$2"
    local target_schema="$3"
    local force="$4"
    local profile="$5"
    local artifact manifest
    manifest="$run_dir/manifest"
    artifact="$(backup_manifest_schema_field "$manifest" "$source_schema" artifact)" || return "$EXIT_INTEGRITY"
    backup_prepare_restore_target "$manifest" "$source_schema" "$target_schema" "$force" "$profile" || return $?
    if ! { backup_session_preamble "$profile"; gzip -dc "$run_dir/$artifact"; } | backup_filter_dump_global_state | backup_mysql "$profile" --binary-mode "$target_schema"; then
        backup_log error restore_import_failed source_schema "$source_schema" target_schema "$target_schema"
        return "$EXIT_RESTORE"
    fi
}

backup_drop_schema() {
    local profile="$1"
    local schema="$2"
    backup_validate_schema "$schema" >/dev/null || return "$EXIT_RESTORE"
    backup_mysql_query "$profile" "DROP DATABASE IF EXISTS \`$schema\`;" >/dev/null
}

backup_timestamp_to_epoch() {
    local timestamp="$1"
    date -u -d "$timestamp" +%s
}

backup_run_id_to_epoch() {
    local run_id="$1"
    if [[ ! "$run_id" =~ ^([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})Z$ ]]; then
        return 1
    fi
    date -u -d "${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]} ${BASH_REMATCH[4]}:${BASH_REMATCH[5]}:${BASH_REMATCH[6]} UTC" +%s
}

backup_parse_utc_target() {
    local input="$1"
    local normalized epoch
    if [[ ! "$input" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}[T\ ][0-9]{2}:[0-9]{2}:[0-9]{2}Z?$ ]]; then
        return "$EXIT_CONFIG"
    fi
    normalized="${input/T/ }"
    normalized="${normalized%Z}"
    epoch="$(date -u -d "$normalized UTC" +%s)" || return "$EXIT_CONFIG"
    if [ "$(date -u -d "@$epoch" '+%Y-%m-%d %H:%M:%S')" != "$normalized" ]; then
        return "$EXIT_CONFIG"
    fi
    printf '%s\t%s\n' "$normalized" "$epoch"
}

backup_meta_value() {
    local meta="$1"
    local key="$2"
    awk -F '\t' -v key="$key" '$1 == key { print $2; count++ } END { if (count != 1) exit 1 }' "$meta"
}

backup_sync_file() {
    local path="$1"
    if [ ! -f "$path" ] || [ -L "$path" ]; then
        return "$EXIT_INTEGRITY"
    fi
    sync -d -- "$path" || return "$EXIT_INTEGRITY"
}

backup_sync_directory() {
    local path="$1"
    if [ ! -d "$path" ] || [ -L "$path" ]; then
        return "$EXIT_INTEGRITY"
    fi
    sync -f -- "$path" || return "$EXIT_INTEGRITY"
}

backup_validate_binlog_components() {
    local path="$1"
    local meta="$2"
    local checksum="$3"
    local destination="$4"
    local expected_server_uuid="${5:-}"
    local expected_hash recorded_file recorded_server_uuid component properties file_type links extra
    if [ ! -f "$path" ] || [ -L "$path" ] || [ ! -f "$meta" ] || [ -L "$meta" ] || [ ! -f "$checksum" ] || [ -L "$checksum" ]; then
        return "$EXIT_INTEGRITY"
    fi
    for component in "$path" "$meta" "$checksum"; do
        properties="$(stat -c '%F:%h' -- "$component" 2>/dev/null)" ||
            return "$EXIT_INTEGRITY"
        IFS=: read -r file_type links extra <<< "$properties"
        if [ -n "$extra" ] || [ "$file_type" != "regular file" ] || [ "$links" != 1 ]; then
            return "$EXIT_INTEGRITY"
        fi
    done
    read -r expected_hash recorded_file < "$checksum"
    if [[ ! "$expected_hash" =~ ^[0-9a-f]{64}$ ]] ||
        [ "$recorded_file" != "$(basename "$destination")" ]; then
        return "$EXIT_INTEGRITY"
    fi
    if [ "$(sha256sum "$path" | awk '{print $1}')" != "$expected_hash" ]; then
        return "$EXIT_INTEGRITY"
    fi
    if [ "$(backup_meta_value "$meta" file)" != "$(basename "$destination")" ]; then
        return "$EXIT_INTEGRITY"
    fi
    if [ "$(backup_meta_value "$meta" sha256)" != "$expected_hash" ]; then
        return "$EXIT_INTEGRITY"
    fi
    recorded_server_uuid="$(backup_meta_value "$meta" server_uuid 2>/dev/null || true)"
    if [ -n "$expected_server_uuid" ] && [ "$recorded_server_uuid" != "$expected_server_uuid" ]; then
        return "$EXIT_INTEGRITY"
    fi
    if [ "$(od -An -t x1 -N4 "$path" | tr -d ' \n')" != fe62696e ]; then
        return "$EXIT_INTEGRITY"
    fi
}

backup_validate_binlog_triplet() {
    local path="$1"
    local expected_server_uuid="${2:-}"
    backup_validate_binlog_components \
        "$path" "$path.meta" "$path.sha256" "$path" "$expected_server_uuid"
}

backup_binlog_namespace_absent() {
    local path="$1"
    local component
    for component in \
        "$path" "$path.meta" "$path.sha256" \
        "$path.pending" "$path.meta.pending" "$path.sha256.pending"; do
        if [ -e "$component" ] || [ -L "$component" ]; then
            return 1
        fi
    done
}

backup_recover_binlog_triplet() {
    local path="$1"
    local expected_server_uuid="${2:-}"
    local raw meta checksum final pending component
    if backup_validate_binlog_triplet "$path" "$expected_server_uuid"; then
        if [ -e "$path.pending" ] || [ -L "$path.pending" ] ||
            [ -e "$path.meta.pending" ] || [ -L "$path.meta.pending" ] ||
            [ -e "$path.sha256.pending" ] || [ -L "$path.sha256.pending" ]; then
            return "$EXIT_INTEGRITY"
        fi
        return 0
    fi
    if backup_binlog_namespace_absent "$path"; then
        return 1
    fi
    while IFS=$'\t' read -r final pending; do
        if { [ -e "$final" ] || [ -L "$final" ]; } &&
            { [ -e "$pending" ] || [ -L "$pending" ]; }; then
            return "$EXIT_INTEGRITY"
        fi
        component=
        if [ -e "$final" ] || [ -L "$final" ]; then
            component="$final"
        elif [ -e "$pending" ] || [ -L "$pending" ]; then
            component="$pending"
        else
            return "$EXIT_INTEGRITY"
        fi
        if [ "$final" = "$path" ]; then
            raw="$component"
        elif [ "$final" = "$path.meta" ]; then
            meta="$component"
        else
            checksum="$component"
        fi
    done <<EOF
$path	$path.pending
$path.meta	$path.meta.pending
$path.sha256	$path.sha256.pending
EOF
    backup_validate_binlog_components \
        "$raw" "$meta" "$checksum" "$path" "$expected_server_uuid" ||
        return "$EXIT_INTEGRITY"
    if [ "$checksum" = "$path.sha256.pending" ]; then
        mv -T -- "$checksum" "$path.sha256" || return "$EXIT_INTEGRITY"
    fi
    if [ "$meta" = "$path.meta.pending" ]; then
        mv -T -- "$meta" "$path.meta" || return "$EXIT_INTEGRITY"
    fi
    if [ "$raw" = "$path.pending" ]; then
        mv -T -- "$raw" "$path" || return "$EXIT_INTEGRITY"
    fi
    backup_sync_directory "$(dirname "$path")" || return "$EXIT_INTEGRITY"
    backup_validate_binlog_triplet "$path" "$expected_server_uuid"
}
