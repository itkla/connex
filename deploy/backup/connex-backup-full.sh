#!/bin/bash
#
# Creates a daily per-schema logical backup suitable for point-in-time recovery.
# A run becomes restorable only after every compressed artifact, coordinate,
# manifest entry, checksum, and optional scratch restore verification succeeds.
# Failed and interrupted runs never receive a COMPLETE marker.
# shellcheck source=deploy/backup/connex-backup-lib.sh

set -euo pipefail

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/connex-backup-lib.sh"

FULL_RUN_DIR=
FULL_RUN_ID=
FULL_RUN_STARTED_EPOCH=0
FULL_RUN_SUCCEEDED=false
FULL_SCHEMA_COUNT=0
FULL_TOTAL_BYTES=0

full_mark_failed() {
    local exit_code="$1"
    if [ -z "$FULL_RUN_DIR" ] || [ ! -d "$FULL_RUN_DIR" ] || [ "$FULL_RUN_SUCCEEDED" = true ]; then
        return 0
    fi
    rm -f "$FULL_RUN_DIR/IN_PROGRESS" "$FULL_RUN_DIR/COMPLETE"
    printf 'failed_at\t%s\nexit_code\t%s\nphase\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$exit_code" "$BACKUP_PHASE" > "$FULL_RUN_DIR/FAILED"
}

full_disk_guard() {
    local available
    available="$(df --output=avail -B1 "$CONNEX_BACKUP_ROOT" | awk 'NR == 2 { print $1 }')" || return "$EXIT_DISK_GUARD"
    if [[ ! "$available" =~ ^[0-9]+$ ]]; then
        backup_log error disk_guard reason available_bytes_unreadable root "$CONNEX_BACKUP_ROOT"
        return "$EXIT_DISK_GUARD"
    fi
    if [ "$available" -lt "$CONNEX_BACKUP_MIN_FREE_BYTES" ]; then
        backup_log error disk_guard available_bytes "$available" required_bytes "$CONNEX_BACKUP_MIN_FREE_BYTES" root "$CONNEX_BACKUP_ROOT"
        return "$EXIT_DISK_GUARD"
    fi
    backup_log info disk_guard available_bytes "$available" required_bytes "$CONNEX_BACKUP_MIN_FREE_BYTES"
}

full_source_preflight() {
    local values log_bin
    values="$(backup_mysql_query source "SELECT @@GLOBAL.log_bin, @@version, @@GLOBAL.server_uuid, @@GLOBAL.gtid_mode;")" || {
        backup_log error source_preflight reason source_query_failed
        return "$EXIT_DB_PREFLIGHT"
    }
    IFS=$'\t' read -r log_bin FULL_SERVER_VERSION FULL_SERVER_UUID FULL_GTID_MODE <<< "$values"
    if [ "$log_bin" != 1 ]; then
        backup_log error source_preflight reason binary_logging_disabled
        return "$EXIT_DB_PREFLIGHT"
    fi
    FULL_MYSQL_VERSION="$(backup_mysql source --version 2>&1 | tr '\n' ' ')"
    FULL_MYSQLDUMP_VERSION="$(backup_mysqldump source --version 2>&1 | tr '\n' ' ')"
    FULL_MYSQLBINLOG_VERSION=not_required_for_full_backup
    backup_log info source_preflight server_version "$FULL_SERVER_VERSION" server_uuid "$FULL_SERVER_UUID" gtid_mode "$FULL_GTID_MODE"
}

full_list_schemas() {
    local schema
    FULL_SCHEMAS=()
    while IFS= read -r schema; do
        backup_validate_schema "$schema" || return "$EXIT_CONFIG"
        if backup_schema_selected "$schema"; then
            FULL_SCHEMAS+=("$schema")
        fi
    done < <(backup_mysql_query source "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name;")
    if [ "${#FULL_SCHEMAS[@]}" -eq 0 ]; then
        backup_log error schema_selection reason no_schemas_selected
        return "$EXIT_CONFIG"
    fi
    backup_log info schema_selection schema_count "${#FULL_SCHEMAS[@]}" schemas "$(IFS=,; printf '%s' "${FULL_SCHEMAS[*]}")"
}

full_create_run() {
    FULL_RUN_STARTED_EPOCH="$(date +%s)"
    FULL_RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
    FULL_RUN_DIR="$CONNEX_BACKUP_ROOT/full/$FULL_RUN_ID"
    if ! mkdir "$FULL_RUN_DIR"; then
        backup_log error run_create reason run_directory_exists run_id "$FULL_RUN_ID"
        return "$EXIT_CONFIG"
    fi
    printf 'started_at\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$FULL_RUN_DIR/IN_PROGRESS"
    FULL_MANIFEST_TEMP="$FULL_RUN_DIR/manifest.tmp"
    {
        printf 'manifest_version\t1\n'
        printf 'run_id\t%s\n' "$FULL_RUN_ID"
        printf 'run_started_utc\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'server_version\t%s\n' "$FULL_SERVER_VERSION"
        printf 'server_uuid\t%s\n' "$FULL_SERVER_UUID"
        printf 'gtid_mode\t%s\n' "$FULL_GTID_MODE"
        printf 'mysql_client_version\t%s\n' "$(backup_escape_log_value "$FULL_MYSQL_VERSION")"
        printf 'mysqldump_client_version\t%s\n' "$(backup_escape_log_value "$FULL_MYSQLDUMP_VERSION")"
        printf 'mysqlbinlog_client_version\t%s\n' "$(backup_escape_log_value "$FULL_MYSQLBINLOG_VERSION")"
    } > "$FULL_MANIFEST_TEMP"
}

full_dump_schema() {
    local schema="$1"
    local artifact temporary started_utc completed_utc started_epoch duration bytes hash coordinates
    local binlog_file binlog_position base_table_count charset collation
    artifact="$FULL_RUN_DIR/$schema.sql.gz"
    temporary="$artifact.tmp"
    started_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    started_epoch="$(date +%s)"
    BACKUP_PHASE=dumping
    backup_log info dump_started run_id "$FULL_RUN_ID" schema "$schema"
    base_table_count="$(backup_mysql_query source "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$schema' AND table_type = 'BASE TABLE';")" || return "$EXIT_DB_PREFLIGHT"
    local charset_row
    charset_row="$(backup_mysql_query source "SELECT default_character_set_name, default_collation_name FROM information_schema.schemata WHERE schema_name = '$schema';")" || {
        backup_log error dump_charset_failed run_id "$FULL_RUN_ID" schema "$schema"
        return "$EXIT_DB_PREFLIGHT"
    }
    IFS=$'\t' read -r charset collation <<< "$charset_row"
    if [[ ! "$charset" =~ ^[A-Za-z0-9_]+$ || ! "$collation" =~ ^[A-Za-z0-9_]+$ ]]; then
        backup_log error dump_charset_invalid run_id "$FULL_RUN_ID" schema "$schema" charset "$charset" collation "$collation"
        return "$EXIT_INTEGRITY"
    fi
    if ! backup_mysqldump source \
        --single-transaction \
        --quick \
        --routines \
        --triggers \
        --events \
        --hex-blob \
        --source-data=2 \
        --set-gtid-purged=AUTO \
        "$schema" | gzip "-$CONNEX_BACKUP_GZIP_LEVEL" -c > "$temporary"; then
        rm -f "$temporary"
        backup_log error dump_failed run_id "$FULL_RUN_ID" schema "$schema"
        return "$EXIT_DUMP"
    fi
    mv "$temporary" "$artifact"
    completed_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    duration=$(( $(date +%s) - started_epoch ))
    bytes="$(stat -c '%s' "$artifact")"
    hash="$(sha256sum "$artifact" | awk '{print $1}')"
    if ! coordinates="$(backup_extract_coordinates "$artifact")"; then
        backup_log error dump_coordinate_missing run_id "$FULL_RUN_ID" schema "$schema"
        return "$EXIT_INTEGRITY"
    fi
    IFS=$'\t' read -r binlog_file binlog_position <<< "$coordinates"
    if [[ ! "$binlog_file" =~ ^[A-Za-z0-9._-]+$ || ! "$binlog_position" =~ ^[0-9]+$ ]] || [ "$binlog_position" -le 0 ]; then
        backup_log error dump_coordinate_invalid schema "$schema" binlog_file "$binlog_file" binlog_position "$binlog_position"
        return "$EXIT_INTEGRITY"
    fi
    printf 'schema\t%s\t%s.sql.gz\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$schema" "$schema" "$started_utc" "$completed_utc" "$binlog_file" "$binlog_position" \
        "$duration" "$bytes" "$hash" "$base_table_count" "$charset" "$collation" >> "$FULL_MANIFEST_TEMP"
    FULL_SCHEMA_COUNT=$((FULL_SCHEMA_COUNT + 1))
    FULL_TOTAL_BYTES=$((FULL_TOTAL_BYTES + bytes))
    backup_log info dump_completed run_id "$FULL_RUN_ID" schema "$schema" bytes "$bytes" duration_seconds "$duration" binlog_file "$binlog_file" binlog_position "$binlog_position"
}

full_integrity_check() {
    local schema
    BACKUP_PHASE=integrity
    printf 'run_capture_completed_utc\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$FULL_MANIFEST_TEMP"
    printf 'run_duration_seconds\t%s\n' "$(( $(date +%s) - FULL_RUN_STARTED_EPOCH ))" >> "$FULL_MANIFEST_TEMP"
    mv "$FULL_MANIFEST_TEMP" "$FULL_RUN_DIR/manifest"
    for schema in "${FULL_SCHEMAS[@]}"; do
        if ! gzip -t "$FULL_RUN_DIR/$schema.sql.gz"; then
            backup_log error integrity_failed reason gzip_test schema "$schema"
            return "$EXIT_INTEGRITY"
        fi
    done
    (
        cd "$FULL_RUN_DIR"
        sha256sum manifest ./*.sql.gz | sed 's#  [*][.]/#  *#; s#  [.]/#  #' > sha256sums
        sha256sum --check --strict sha256sums >/dev/null
    ) || {
        backup_log error integrity_failed reason checksum
        return "$EXIT_INTEGRITY"
    }
    backup_log info integrity_completed run_id "$FULL_RUN_ID" schema_count "$FULL_SCHEMA_COUNT" bytes "$FULL_TOTAL_BYTES"
}

full_verify_schema() {
    local schema="$1"
    local index="$2"
    local scratch expected actual
    scratch="connex_verify_${FULL_RUN_ID//[TZ]/}_${index}"
    scratch="${scratch:0:64}"
    expected="$(backup_manifest_schema_field "$FULL_RUN_DIR/manifest" "$schema" base_table_count)" || return "$EXIT_RESTORE_VERIFY"
    backup_log info restore_verify_started schema "$schema" scratch_schema "$scratch"
    backup_drop_schema verify "$scratch" || return "$EXIT_RESTORE_VERIFY"
    if ! backup_restore_artifact "$FULL_RUN_DIR" "$schema" "$scratch" true verify; then
        backup_drop_schema verify "$scratch" || true
        backup_log error restore_verify_failed reason import schema "$schema"
        return "$EXIT_RESTORE_VERIFY"
    fi
    actual="$(backup_mysql_query verify "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$scratch' AND table_type = 'BASE TABLE';")" || {
        backup_drop_schema verify "$scratch" || true
        return "$EXIT_RESTORE_VERIFY"
    }
    if [ "$actual" != "$expected" ]; then
        backup_drop_schema verify "$scratch" || true
        backup_log error restore_verify_failed reason table_count schema "$schema" expected "$expected" actual "$actual"
        return "$EXIT_RESTORE_VERIFY"
    fi
    backup_drop_schema verify "$scratch" || return "$EXIT_RESTORE_VERIFY"
    backup_log info restore_verify_completed schema "$schema" table_count "$actual"
}

full_restore_verify() {
    local schema index=0
    if [ "$CONNEX_BACKUP_RESTORE_VERIFY" != true ]; then
        return 0
    fi
    BACKUP_PHASE=restore_verify
    if ! backup_probe_binlog_suppression verify; then
        backup_log error restore_verify_failed reason session_binlog_disable_denied required_privilege BINLOG_ADMIN profile verify
        return "$EXIT_RESTORE_VERIFY"
    fi
    for schema in "${FULL_SCHEMAS[@]}"; do
        index=$((index + 1))
        full_verify_schema "$schema" "$index" || return "$EXIT_RESTORE_VERIFY"
    done
}

full_publish_complete() {
    BACKUP_PHASE=publishing
    printf 'completed_at\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$FULL_RUN_DIR/COMPLETE.tmp"
    mv "$FULL_RUN_DIR/COMPLETE.tmp" "$FULL_RUN_DIR/COMPLETE"
    rm -f "$FULL_RUN_DIR/IN_PROGRESS"
    FULL_RUN_SUCCEEDED=true
}

full_run() {
    backup_load_environment || return $?
    backup_validate_common || return $?
    backup_prepare_directories || return "$EXIT_CONFIG"
    backup_acquire_lock shared lifecycle || return $?
    backup_acquire_lock exclusive full || return $?
    BACKUP_PHASE=disk_guard
    full_disk_guard || return $?
    BACKUP_PHASE=source_preflight
    full_source_preflight || return $?
    full_list_schemas || return $?
    full_create_run || return $?
    local schema
    for schema in "${FULL_SCHEMAS[@]}"; do
        full_dump_schema "$schema" || return $?
    done
    full_integrity_check || return $?
    full_restore_verify || return $?
    full_publish_complete || return "$EXIT_INTEGRITY"
}

main() {
    local exit_code=0
    full_run "$@" || exit_code=$?
    full_mark_failed "$exit_code"
    backup_finish "$exit_code" backup_summary run_id "${FULL_RUN_ID:-none}" schema_count "$FULL_SCHEMA_COUNT" bytes "$FULL_TOTAL_BYTES"
    return "$exit_code"
}

main "$@"
