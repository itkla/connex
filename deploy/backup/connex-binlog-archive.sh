#!/bin/bash
#
# Archives closed MySQL binary logs for Connex point-in-time recovery. Stream
# mode copies immutable files directly from the Compose db container and checks
# server-reported size plus binary-log magic. Native mysqlbinlog raw fetch mode
# remains available. The default flush makes the timer interval the RPO.
# Server expiry is enforced against the configured 30-day legal ceiling.
# shellcheck source=deploy/backup/connex-backup-lib.sh

set -euo pipefail

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/connex-backup-lib.sh"

ARCHIVE_FETCHED=0
ARCHIVE_SKIPPED=0
ARCHIVE_BYTES=0
ARCHIVE_SERVER_UUID=
ARCHIVE_SERVER_VERSION=
ARCHIVE_COVERAGE_UTC=
ARCHIVE_COVERAGE_EPOCH=0
ARCHIVE_ACTIVE_FILE=
ARCHIVE_LAST_CLOSED=
declare -a ARCHIVE_SERVER_LOGS=()

archive_initialize_fetcher() {
    case "$CONNEX_BACKUP_BINLOG_FETCH_MODE" in
        stream)
            backup_initialize_stream_commands
            ;;
        mysqlbinlog)
            backup_initialize_mysqlbinlog
            ;;
        *)
            return "$EXIT_CONFIG"
            ;;
    esac
}

archive_preflight() {
    local values log_bin format expiry auto_purge
    values="$(backup_mysql_query source "SELECT @@GLOBAL.log_bin, @@GLOBAL.binlog_format, @@GLOBAL.binlog_expire_logs_seconds, @@GLOBAL.binlog_expire_logs_auto_purge, @@GLOBAL.server_uuid, @@version;")" || {
        backup_log error binlog_preflight reason source_query_failed
        return "$EXIT_DB_PREFLIGHT"
    }
    IFS=$'\t' read -r log_bin format expiry auto_purge ARCHIVE_SERVER_UUID ARCHIVE_SERVER_VERSION <<< "$values"
    if [ "$log_bin" != 1 ]; then
        backup_log error binlog_preflight reason binary_logging_disabled
        return "$EXIT_DB_PREFLIGHT"
    fi
    if [ "$format" != ROW ]; then
        backup_log error binlog_preflight reason binlog_format_not_row format "$format"
        return "$EXIT_DB_PREFLIGHT"
    fi
    if [[ ! "$expiry" =~ ^[0-9]+$ ]] || [ "$expiry" -le 0 ] || [ "$expiry" -gt $(((CONNEX_BACKUP_RETENTION_DAYS - 1) * 86400)) ]; then
        backup_log error binlog_preflight reason server_expiry_outside_cap expiry_seconds "$expiry" cap_seconds "$(((CONNEX_BACKUP_RETENTION_DAYS - 1) * 86400))"
        return "$EXIT_DB_PREFLIGHT"
    fi
    if [ "$auto_purge" != 1 ]; then
        backup_log error binlog_preflight reason server_auto_purge_disabled
        return "$EXIT_DB_PREFLIGHT"
    fi
    if [ "$CONNEX_BACKUP_BINLOG_FLUSH" = false ] && [ "$CONNEX_BACKUP_BINLOG_NO_FLUSH_ACK" != true ]; then
        backup_log error config_error reason no_flush_requires_ack key CONNEX_BACKUP_BINLOG_NO_FLUSH_ACK
        return "$EXIT_CONFIG"
    fi
    if [ "$CONNEX_BACKUP_BINLOG_FLUSH" = false ]; then
        backup_log warn binlog_rpo_unbounded reason flush_disabled operator_ack true
    fi
    backup_log info binlog_preflight server_version "$ARCHIVE_SERVER_VERSION" server_uuid "$ARCHIVE_SERVER_UUID" format "$format" expiry_seconds "$expiry" fetch_mode "$CONNEX_BACKUP_BINLOG_FETCH_MODE"
}

archive_rotate_and_list() {
    local coverage
    if [ "$CONNEX_BACKUP_BINLOG_FLUSH" = true ]; then
        coverage="$(backup_mysql_query source "SELECT DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%dT%H:%i:%sZ'); FLUSH BINARY LOGS;")" || return "$EXIT_BINLOG_ARCHIVE"
        ARCHIVE_COVERAGE_UTC="$(printf '%s\n' "$coverage" | sed -n '1p')"
        ARCHIVE_COVERAGE_EPOCH="$(backup_timestamp_to_epoch "$ARCHIVE_COVERAGE_UTC")" || return "$EXIT_BINLOG_ARCHIVE"
    fi
    mapfile -t ARCHIVE_SERVER_LOGS < <(backup_mysql_query source "SHOW BINARY LOGS;")
    if [ "${#ARCHIVE_SERVER_LOGS[@]}" -eq 0 ]; then
        backup_log error binlog_list reason no_binary_logs
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    IFS=$'\t' read -r ARCHIVE_ACTIVE_FILE _ _ <<< "${ARCHIVE_SERVER_LOGS[-1]}"
}

archive_verify_server_retention() {
    local oldest_file range created_epoch age legal_age ceiling_age
    IFS=$'\t' read -r oldest_file _ _ <<< "${ARCHIVE_SERVER_LOGS[0]}"
    if [[ ! "$oldest_file" =~ ^[A-Za-z0-9._-]+$ ]]; then
        backup_log error binlog_list reason invalid_server_entry file "$oldest_file"
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    if ! range="$(archive_source_time_range "$oldest_file" 2>/dev/null)"; then
        backup_log warn binlog_server_retention reason oldest_file_unreadable file "$oldest_file"
        return 0
    fi
    IFS=$'\t' read -r created_epoch _ <<< "$range"
    age=$(( $(date +%s) - created_epoch ))
    legal_age=$(( (CONNEX_BACKUP_RETENTION_DAYS - 1) * 86400 ))
    ceiling_age=$(( CONNEX_BACKUP_RETENTION_DAYS * 86400 ))
    if [ "$age" -ge "$ceiling_age" ]; then
        backup_log error binlog_server_retention reason server_binlog_past_legal_ceiling file "$oldest_file" age_seconds "$age" ceiling_seconds "$ceiling_age" remedy "PURGE BINARY LOGS BEFORE"
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    if [ "$age" -ge "$legal_age" ]; then
        backup_log warn binlog_server_retention reason server_purge_lagging file "$oldest_file" age_seconds "$age" prune_threshold_seconds "$legal_age"
        return 0
    fi
    backup_log info binlog_server_retention file "$oldest_file" age_seconds "$age" prune_threshold_seconds "$legal_age"
}

archive_state_value() {
    local key="$1"
    local state="$CONNEX_BACKUP_ROOT/binlog/archive-state"
    if [ ! -f "$state" ]; then
        return 1
    fi
    backup_meta_value "$state" "$key"
}

archive_validate_state() {
    local state_uuid
    state_uuid="$(archive_state_value server_uuid || true)"
    if [ -n "$state_uuid" ] && [ "$state_uuid" != "$ARCHIVE_SERVER_UUID" ]; then
        backup_log error binlog_state reason server_uuid_changed previous_uuid "$state_uuid" current_uuid "$ARCHIVE_SERVER_UUID"
        return "$EXIT_BINLOG_ARCHIVE"
    fi
}

archive_event_stamp_to_epoch() {
    local stamp="$1"
    if [[ ! "$stamp" =~ ^([0-9]{2})([0-9]{2})([0-9]{2})[[:space:]]([0-9]{1,2}:[0-9]{2}:[0-9]{2})$ ]]; then
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    date -u -d "20${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]} ${BASH_REMATCH[4]} UTC" +%s
}

archive_decode_time_range() {
    local path="$1"
    local stamps first last first_epoch last_epoch
    stamps="$(backup_mysqlbinlog_local --base64-output=DECODE-ROWS "$path" | sed -n 's/^#\([0-9]\{6\}\)[[:space:]][[:space:]]*\([0-9]\{1,2\}:[0-9]\{2\}:[0-9]\{2\}\)[[:space:]].*/\1 \2/p')" || return "$EXIT_BINLOG_ARCHIVE"
    first="$(printf '%s\n' "$stamps" | sed -n '1p')"
    last="$(printf '%s\n' "$stamps" | sed -n '$p')"
    first_epoch="$(archive_event_stamp_to_epoch "$first")" || return "$EXIT_BINLOG_ARCHIVE"
    last_epoch="$(archive_event_stamp_to_epoch "$last")" || return "$EXIT_BINLOG_ARCHIVE"
    printf '%s\t%s\n' "$first_epoch" "$last_epoch"
}

archive_source_time_range() {
    local file="$1"
    local path="$CONNEX_BACKUP_BINLOG_DIR/$file"
    local values created_epoch last_event_epoch
    if [ "$CONNEX_BACKUP_BINLOG_FETCH_MODE" = stream ]; then
        values="$(backup_stat_binlog "$path")" || return "$EXIT_BINLOG_ARCHIVE"
        IFS=: read -r created_epoch last_event_epoch <<< "$values"
        if [[ ! "$created_epoch" =~ ^[0-9]+$ || ! "$last_event_epoch" =~ ^[0-9]+$ ]] || [ "$created_epoch" -le 0 ] || [ "$last_event_epoch" -lt "$created_epoch" ]; then
            return "$EXIT_BINLOG_ARCHIVE"
        fi
        printf '%s\t%s\n' "$created_epoch" "$last_event_epoch"
        return 0
    fi
    archive_decode_time_range "$CONNEX_BACKUP_ROOT/binlog/$file"
}

archive_magic_valid() {
    local path="$1"
    [ "$(od -An -t x1 -N4 "$path" | tr -d ' \n')" = fe62696e ]
}

archive_existing_matches() {
    local destination="$1"
    local server_size="$2"
    local meta="$destination.meta"
    local recorded_server_size recorded_local_size local_size
    if [ ! -e "$destination" ] && [ ! -e "$destination.meta" ] && [ ! -e "$destination.sha256" ]; then
        return 1
    fi
    if ! backup_validate_binlog_triplet "$destination"; then
        rm -f "$destination" "$destination.meta" "$destination.sha256" "$destination.meta.pending" "$destination.sha256.pending"
        backup_log warn binlog_partial_removed file "$(basename "$destination")"
        return 1
    fi
    recorded_server_size="$(backup_meta_value "$meta" server_size)" || return "$EXIT_BINLOG_ARCHIVE"
    recorded_local_size="$(backup_meta_value "$meta" local_size)" || return "$EXIT_BINLOG_ARCHIVE"
    local_size="$(stat -c '%s' "$destination")"
    if [ "$recorded_server_size" != "$server_size" ] || [ "$recorded_local_size" != "$local_size" ]; then
        rm -f "$destination" "$destination.meta" "$destination.sha256"
        backup_log warn binlog_stale_removed file "$(basename "$destination")" server_size "$server_size" recorded_server_size "$recorded_server_size" local_size "$local_size" recorded_local_size "$recorded_local_size"
        return 1
    fi
    return 0
}

archive_stream_attempt() {
    local file="$1"
    local server_size="$2"
    local temporary_file="$3"
    local source_path="$CONNEX_BACKUP_BINLOG_DIR/$file"
    local local_size
    rm -f "$temporary_file"
    if ! backup_stream_binlog "$source_path" > "$temporary_file"; then
        return 1
    fi
    local_size="$(stat -c '%s' "$temporary_file")" || return 1
    if [ "$local_size" != "$server_size" ]; then
        return 1
    fi
    archive_magic_valid "$temporary_file"
}

archive_fetch_stream() {
    local file="$1"
    local server_size="$2"
    local temporary_file="$3"
    local attempt
    for attempt in 1 2; do
        if archive_stream_attempt "$file" "$server_size" "$temporary_file"; then
            return 0
        fi
        backup_log warn binlog_stream_retry file "$file" attempt "$attempt" expected_size "$server_size"
    done
    return "$EXIT_BINLOG_ARCHIVE"
}

archive_fetch_mysqlbinlog() {
    local file="$1"
    local server_size="$2"
    local temporary_directory="$3"
    local temporary_file="$temporary_directory/$file"
    if ! backup_mysqlbinlog_remote source --read-from-remote-server --raw --result-file="$temporary_directory/" "$file"; then
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    if [ ! -f "$temporary_file" ]; then
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    if [ "$(stat -c '%s' "$temporary_file")" != "$server_size" ]; then
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    if ! archive_magic_valid "$temporary_file"; then
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    backup_mysqlbinlog_local --verify-binlog-checksum "$temporary_file" >/dev/null
}

archive_fetch_to_temporary() {
    local file="$1"
    local server_size="$2"
    local temporary_directory="$3"
    case "$CONNEX_BACKUP_BINLOG_FETCH_MODE" in
        stream)
            archive_fetch_stream "$file" "$server_size" "$temporary_directory/$file"
            ;;
        mysqlbinlog)
            archive_fetch_mysqlbinlog "$file" "$server_size" "$temporary_directory"
            ;;
        *)
            return "$EXIT_CONFIG"
            ;;
    esac
}

archive_publish_file() {
    local file="$1"
    local server_size="$2"
    local temporary_directory="$3"
    local destination="$CONNEX_BACKUP_ROOT/binlog/$file"
    local temporary_file="$temporary_directory/$file"
    local hash local_size time_range created_epoch last_event_epoch created_utc last_event_utc
    hash="$(sha256sum "$temporary_file" | awk '{print $1}')"
    local_size="$(stat -c '%s' "$temporary_file")"
    if [ "$CONNEX_BACKUP_BINLOG_FETCH_MODE" = stream ]; then
        time_range="$(archive_source_time_range "$file")" || return "$EXIT_BINLOG_ARCHIVE"
    else
        time_range="$(archive_decode_time_range "$temporary_file")" || return "$EXIT_BINLOG_ARCHIVE"
    fi
    IFS=$'\t' read -r created_epoch last_event_epoch <<< "$time_range"
    if [ "$created_epoch" -gt "$(date +%s)" ] || [ "$last_event_epoch" -gt "$(date +%s)" ]; then
        backup_log error binlog_timestamp_failed file "$file"
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    created_utc="$(date -u -d "@$created_epoch" +%Y-%m-%dT%H:%M:%SZ)"
    last_event_utc="$(date -u -d "@$last_event_epoch" +%Y-%m-%dT%H:%M:%SZ)"
    if [ $(( $(date +%s) - created_epoch )) -ge $(( (CONNEX_BACKUP_RETENTION_DAYS - 1) * 86400 )) ]; then
        rm -f "$temporary_file"
        ARCHIVE_SKIPPED=$((ARCHIVE_SKIPPED + 1))
        backup_log warn binlog_skipped file "$file" reason outside_retention file_created_utc "$created_utc"
        return 0
    fi
    printf '%s  %s\n' "$hash" "$file" > "$destination.sha256.pending"
    {
        printf 'metadata_version\t1\n'
        printf 'file\t%s\n' "$file"
        printf 'server_uuid\t%s\n' "$ARCHIVE_SERVER_UUID"
        printf 'server_size\t%s\n' "$server_size"
        printf 'local_size\t%s\n' "$local_size"
        printf 'sha256\t%s\n' "$hash"
        printf 'file_created_epoch\t%s\n' "$created_epoch"
        printf 'file_created_utc\t%s\n' "$created_utc"
        printf 'last_event_epoch\t%s\n' "$last_event_epoch"
        printf 'last_event_utc\t%s\n' "$last_event_utc"
        printf 'archived_at_utc\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'fetch_mode\t%s\n' "$CONNEX_BACKUP_BINLOG_FETCH_MODE"
    } > "$destination.meta.pending"
    mv "$temporary_file" "$destination"
    mv "$destination.sha256.pending" "$destination.sha256"
    mv "$destination.meta.pending" "$destination.meta"
    ARCHIVE_FETCHED=$((ARCHIVE_FETCHED + 1))
    ARCHIVE_BYTES=$((ARCHIVE_BYTES + local_size))
    backup_log info binlog_fetch_completed file "$file" server_size "$server_size" local_size "$local_size" sha256 "$hash" file_created_utc "$created_utc" fetch_mode "$CONNEX_BACKUP_BINLOG_FETCH_MODE"
}

archive_fetch_file() {
    local file="$1"
    local server_size="$2"
    local destination="$CONNEX_BACKUP_ROOT/binlog/$file"
    local temporary_directory
    if archive_existing_matches "$destination" "$server_size"; then
        ARCHIVE_SKIPPED=$((ARCHIVE_SKIPPED + 1))
        backup_log info binlog_skipped file "$file" reason already_archived size "$server_size"
        return 0
    else
        local existing_status=$?
        if [ "$existing_status" -eq "$EXIT_BINLOG_ARCHIVE" ]; then
            return "$EXIT_BINLOG_ARCHIVE"
        fi
    fi
    temporary_directory="$(mktemp -d "$CONNEX_BACKUP_ROOT/binlog/.fetch.XXXXXX")" || return "$EXIT_BINLOG_ARCHIVE"
    backup_log info binlog_fetch_started file "$file" server_size "$server_size" fetch_mode "$CONNEX_BACKUP_BINLOG_FETCH_MODE"
    if ! archive_fetch_to_temporary "$file" "$server_size" "$temporary_directory"; then
        rm -rf "$temporary_directory"
        backup_log error binlog_fetch_failed file "$file" fetch_mode "$CONNEX_BACKUP_BINLOG_FETCH_MODE"
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    if ! archive_publish_file "$file" "$server_size" "$temporary_directory"; then
        rm -rf "$temporary_directory"
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    rmdir "$temporary_directory"
}

archive_process_logs() {
    local last_archived line file server_size
    local seen_state=false processed_last=
    last_archived="$(archive_state_value last_closed_file || true)"
    if [ -z "$last_archived" ]; then
        seen_state=true
    fi
    for line in "${ARCHIVE_SERVER_LOGS[@]}"; do
        IFS=$'\t' read -r file server_size _ <<< "$line"
        if [ "$file" = "$ARCHIVE_ACTIVE_FILE" ]; then
            continue
        fi
        if [[ ! "$file" =~ ^[A-Za-z0-9._-]+$ || ! "$server_size" =~ ^[0-9]+$ ]]; then
            backup_log error binlog_list reason invalid_server_entry file "$file" size "$server_size"
            return "$EXIT_BINLOG_ARCHIVE"
        fi
        if [ "$seen_state" = false ]; then
            if [ "$file" = "$last_archived" ]; then
                seen_state=true
            fi
            continue
        fi
        archive_fetch_file "$file" "$server_size" || return $?
        processed_last="$file"
    done
    if [ -n "$last_archived" ] && [ "$seen_state" = false ]; then
        backup_log error binlog_state reason last_closed_file_missing file "$last_archived"
        return "$EXIT_BINLOG_ARCHIVE"
    fi
    if [ -z "$processed_last" ]; then
        processed_last="$last_archived"
    fi
    if [ -z "$processed_last" ] && [ "${#ARCHIVE_SERVER_LOGS[@]}" -gt 1 ]; then
        IFS=$'\t' read -r processed_last _ _ <<< "${ARCHIVE_SERVER_LOGS[-2]}"
    fi
    ARCHIVE_LAST_CLOSED="$processed_last"
}

archive_publish_state() {
    local state="$CONNEX_BACKUP_ROOT/binlog/archive-state"
    local temporary="$state.tmp.$$"
    if [ "$CONNEX_BACKUP_BINLOG_FLUSH" = false ] && [ -n "$ARCHIVE_LAST_CLOSED" ] && [ -f "$CONNEX_BACKUP_ROOT/binlog/$ARCHIVE_LAST_CLOSED.meta" ]; then
        ARCHIVE_COVERAGE_EPOCH="$(backup_meta_value "$CONNEX_BACKUP_ROOT/binlog/$ARCHIVE_LAST_CLOSED.meta" last_event_epoch)" || return "$EXIT_BINLOG_ARCHIVE"
        ARCHIVE_COVERAGE_UTC="$(date -u -d "@$ARCHIVE_COVERAGE_EPOCH" +%Y-%m-%dT%H:%M:%SZ)"
    fi
    {
        printf 'state_version\t1\n'
        printf 'server_uuid\t%s\n' "$ARCHIVE_SERVER_UUID"
        printf 'last_closed_file\t%s\n' "$ARCHIVE_LAST_CLOSED"
        printf 'active_file\t%s\n' "$ARCHIVE_ACTIVE_FILE"
        printf 'updated_at_utc\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'coverage_through_utc\t%s\n' "$ARCHIVE_COVERAGE_UTC"
        printf 'coverage_through_epoch\t%s\n' "$ARCHIVE_COVERAGE_EPOCH"
        printf 'flush_enabled\t%s\n' "$CONNEX_BACKUP_BINLOG_FLUSH"
        printf 'fetch_mode\t%s\n' "$CONNEX_BACKUP_BINLOG_FETCH_MODE"
    } > "$temporary"
    mv "$temporary" "$state"
}

archive_run() {
    backup_load_environment || return $?
    backup_validate_common || return $?
    archive_initialize_fetcher || return $?
    backup_prepare_directories || return "$EXIT_CONFIG"
    backup_acquire_lock shared lifecycle || return $?
    backup_acquire_lock exclusive binlog || return $?
    BACKUP_PHASE=binlog_preflight
    archive_preflight || return $?
    archive_validate_state || return $?
    BACKUP_PHASE=binlog_rotation
    archive_rotate_and_list || return $?
    archive_verify_server_retention || return $?
    BACKUP_PHASE=binlog_fetch
    archive_process_logs || return $?
    BACKUP_PHASE=binlog_publish
    archive_publish_state || return "$EXIT_BINLOG_ARCHIVE"
}

main() {
    local exit_code=0
    archive_run "$@" || exit_code=$?
    backup_finish "$exit_code" binlog_archive_summary fetched "$ARCHIVE_FETCHED" skipped "$ARCHIVE_SKIPPED" bytes "$ARCHIVE_BYTES" active_file "${ARCHIVE_ACTIVE_FILE:-unknown}" coverage_through "${ARCHIVE_COVERAGE_UTC:-unknown}" fetch_mode "${CONNEX_BACKUP_BINLOG_FETCH_MODE:-unknown}"
    return "$exit_code"
}

main "$@"
