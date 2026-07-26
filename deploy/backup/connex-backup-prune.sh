#!/bin/bash
#
# Prunes Connex full backups and archived binlogs under the hard legal retention
# ceiling. Because this timer runs daily, deletion begins at retention minus one
# day, which is 29 days by default, so scheduling delay cannot carry data beyond
# 30 days. Failed and orphaned runs use a shorter grace period.
# shellcheck source=deploy/backup/connex-backup-lib.sh

set -euo pipefail

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/connex-backup-lib.sh"

PRUNE_FULL_DELETED=0
PRUNE_FAILED_DELETED=0
PRUNE_BINLOG_DELETED=0
PRUNE_BYTES_DELETED=0
PRUNE_SKIPPED=0
PRUNE_LOCK_WAIT_SECONDS=900

prune_remove_run() {
    local run_dir="$1"
    local classification="$2"
    local bytes
    bytes="$(du -sb "$run_dir" | awk '{print $1}')" || return "$EXIT_PRUNE"
    if [[ "$run_dir" != "$CONNEX_BACKUP_ROOT/full/"* ]] || [ "$run_dir" = "$CONNEX_BACKUP_ROOT/full/" ]; then
        return "$EXIT_PRUNE"
    fi
    rm -rf -- "$run_dir" || return "$EXIT_PRUNE"
    PRUNE_BYTES_DELETED=$((PRUNE_BYTES_DELETED + bytes))
    if [ "$classification" = complete ]; then
        PRUNE_FULL_DELETED=$((PRUNE_FULL_DELETED + 1))
    else
        PRUNE_FAILED_DELETED=$((PRUNE_FAILED_DELETED + 1))
    fi
    backup_log info full_run_pruned run_id "$(basename "$run_dir")" classification "$classification" bytes "$bytes"
}

prune_skip() {
    backup_log warn prune_skipped "$@"
    PRUNE_SKIPPED=$((PRUNE_SKIPPED + 1))
}

prune_newest_complete_run() {
    local run_dir
    while IFS= read -r run_dir; do
        if backup_validate_run "$run_dir" >/dev/null 2>&1; then
            printf '%s\n' "$run_dir"
            return 0
        fi
    done < <(find "$CONNEX_BACKUP_ROOT/full" -mindepth 1 -maxdepth 1 -type d -name '*Z' -exec test -f '{}/COMPLETE' ';' -print | sort -r)
    return 0
}

prune_full_runs() {
    local now legal_age grace_age run_dir run_id run_epoch age classification newest_complete
    now="$(date +%s)"
    legal_age=$(( (CONNEX_BACKUP_RETENTION_DAYS - 1) * 86400 ))
    grace_age=$(( CONNEX_BACKUP_FAILED_GRACE_HOURS * 3600 ))
    newest_complete="$(prune_newest_complete_run)"
    while IFS= read -r run_dir; do
        run_id="$(basename "$run_dir")"
        if ! run_epoch="$(backup_run_id_to_epoch "$run_id")"; then
            prune_skip reason unclassifiable_full_run path "$run_dir"
            continue
        fi
        age=$((now - run_epoch))
        if [ "$age" -lt 0 ]; then
            prune_skip reason future_full_run run_id "$run_id"
            continue
        fi
        if [ -f "$run_dir/COMPLETE" ] && [ -f "$run_dir/IN_PROGRESS" ]; then
            rm -f "$run_dir/IN_PROGRESS"
            backup_log warn prune_normalized reason stale_in_progress_marker run_id "$run_id"
        fi
        if [ -f "$run_dir/COMPLETE" ] && [ -f "$run_dir/FAILED" ]; then
            prune_skip reason conflicting_terminal_markers run_id "$run_id"
            continue
        fi
        classification=incomplete
        if [ -f "$run_dir/COMPLETE" ]; then
            classification=complete
        elif [ -f "$run_dir/FAILED" ]; then
            classification=failed
        elif [ -f "$run_dir/IN_PROGRESS" ]; then
            classification=orphaned
        fi
        if [ "$classification" = complete ]; then
            if [ "$run_dir" = "$newest_complete" ]; then
                continue
            fi
            if [ "$age" -ge "$legal_age" ]; then
                prune_remove_run "$run_dir" complete || prune_skip reason remove_failed run_id "$run_id"
            fi
            continue
        fi
        if [ "$age" -ge "$grace_age" ] || [ "$age" -ge "$legal_age" ]; then
            prune_remove_run "$run_dir" "$classification" || prune_skip reason remove_failed run_id "$run_id"
        fi
    done < <(find "$CONNEX_BACKUP_ROOT/full" -mindepth 1 -maxdepth 1 -type d -print | sort)
    if find "$CONNEX_BACKUP_ROOT/full" -mindepth 1 -maxdepth 1 ! -type d -print -quit | grep -q .; then
        prune_skip reason unexpected_full_root_entry
    fi
}

prune_binlog_triplet() {
    local raw="$1"
    local bytes
    bytes="$(stat -c '%s' "$raw")" || return "$EXIT_PRUNE"
    rm -f -- "$raw" "$raw.sha256" "$raw.meta" || return "$EXIT_PRUNE"
    PRUNE_BINLOG_DELETED=$((PRUNE_BINLOG_DELETED + 1))
    PRUNE_BYTES_DELETED=$((PRUNE_BYTES_DELETED + bytes))
    backup_log info binlog_pruned file "$(basename "$raw")" bytes "$bytes"
}

prune_binlogs() {
    local now legal_age meta raw created_epoch age entry name
    now="$(date +%s)"
    legal_age=$(( (CONNEX_BACKUP_RETENTION_DAYS - 1) * 86400 ))
    while IFS= read -r meta; do
        raw="${meta%.meta}"
        if ! backup_validate_binlog_triplet "$raw"; then
            prune_skip reason invalid_binlog_triplet file "$(basename "$raw")"
            continue
        fi
        if ! created_epoch="$(backup_meta_value "$meta" file_created_epoch)"; then
            prune_skip reason missing_binlog_timestamp file "$(basename "$raw")"
            continue
        fi
        if [[ ! "$created_epoch" =~ ^[0-9]+$ ]]; then
            prune_skip reason invalid_binlog_timestamp file "$(basename "$raw")" value "$created_epoch"
            continue
        fi
        age=$((now - created_epoch))
        if [ "$age" -lt 0 ]; then
            prune_skip reason future_binlog_timestamp file "$(basename "$raw")"
            continue
        fi
        if [ "$age" -ge "$legal_age" ]; then
            prune_binlog_triplet "$raw" || prune_skip reason binlog_remove_failed file "$(basename "$raw")"
        fi
    done < <(find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type f -name '*.meta' ! -name '*.pending' -print | sort)
    while IFS= read -r entry; do
        name="$(basename "$entry")"
        case "$name" in
            archive-state|*.meta|*.sha256)
                ;;
            *)
                if [ -f "$entry.meta" ] && [ -f "$entry.sha256" ]; then
                    continue
                fi
                prune_skip reason unclassifiable_binlog_entry file "$name"
                ;;
        esac
    done < <(find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type f ! -name '*.pending' -print | sort)
}

prune_staging() {
    local entry
    while IFS= read -r entry; do
        if [[ "$entry" != "$CONNEX_BACKUP_ROOT/binlog/.fetch."* ]]; then
            prune_skip reason unexpected_binlog_directory path "$entry"
            continue
        fi
        rm -rf -- "$entry" || { prune_skip reason staging_remove_failed path "$entry"; continue; }
        backup_log warn abandoned_binlog_staging_pruned path "$entry"
    done < <(find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type d -print)
    if find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type f \( -name '*.pending' -o -name '.pitr-query.*' \) -delete -print | grep -q .; then
        backup_log warn abandoned_binlog_pending_pruned
    fi
}

prune_freshness_check() {
    local newest manifest completed completed_epoch age
    newest="$(find "$CONNEX_BACKUP_ROOT/full" -mindepth 1 -maxdepth 1 -type d -name '*Z' -exec test -f '{}/COMPLETE' ';' -print | sort -r | sed -n '1p')"
    if [ -z "$newest" ]; then
        backup_log error stale_backup reason no_complete_full max_age_hours 26
        return "$EXIT_STALE_BACKUP"
    fi
    manifest="$newest/manifest"
    if ! backup_validate_run "$newest"; then
        backup_log error stale_backup reason newest_complete_invalid run_id "$(basename "$newest")"
        return "$EXIT_STALE_BACKUP"
    fi
    completed="$(backup_manifest_value "$manifest" run_capture_completed_utc)" || return "$EXIT_STALE_BACKUP"
    completed_epoch="$(backup_timestamp_to_epoch "$completed")" || return "$EXIT_STALE_BACKUP"
    age=$(( $(date +%s) - completed_epoch ))
    if [ "$age" -gt 93600 ]; then
        backup_log error stale_backup reason newest_complete_too_old run_id "$(basename "$newest")" age_seconds "$age" max_age_seconds 93600
        return "$EXIT_STALE_BACKUP"
    fi
    backup_log info backup_freshness run_id "$(basename "$newest")" age_seconds "$age"
}

prune_run() {
    backup_load_environment || return $?
    backup_validate_common || return $?
    backup_prepare_directories || return "$EXIT_CONFIG"
    backup_acquire_lock exclusive lifecycle "$PRUNE_LOCK_WAIT_SECONDS" || return $?
    backup_acquire_lock exclusive prune "$PRUNE_LOCK_WAIT_SECONDS" || return $?
    BACKUP_PHASE=pruning_staging
    prune_staging
    BACKUP_PHASE=pruning_full
    prune_full_runs
    BACKUP_PHASE=pruning_binlog
    prune_binlogs
    BACKUP_PHASE=freshness_check
    prune_freshness_check || return $?
}

main() {
    local exit_code=0
    prune_run "$@" || exit_code=$?
    backup_finish "$exit_code" prune_summary full_deleted "$PRUNE_FULL_DELETED" failed_deleted "$PRUNE_FAILED_DELETED" binlog_deleted "$PRUNE_BINLOG_DELETED" bytes_deleted "$PRUNE_BYTES_DELETED" skipped "$PRUNE_SKIPPED"
    return "$exit_code"
}

main "$@"
