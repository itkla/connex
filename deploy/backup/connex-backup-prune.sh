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

# A binlog whose sidecars are missing or unreadable can never be classified, so
# an unconditional skip would retain customer data past the hard retention
# ceiling. Fall back to the file's own mtime and quarantine-delete it once it is
# older than the caller's ceiling. The mtime is a local fetch time, not a content
# time, so callers that can prove the file is unusable pass the short failed-run
# grace instead of the legal age.
prune_quarantine_expired_binlog() {
    local raw="$1"
    local reason="$2"
    local now="$3"
    local max_age="$4"
    local mtime age
    if ! mtime="$(stat -c %Y "$raw" 2>/dev/null)"; then
        prune_skip reason "$reason" file "$(basename "$raw")"
        return 0
    fi
    age=$((now - mtime))
    if [ "$age" -ge "$max_age" ]; then
        if prune_binlog_triplet "$raw"; then
            backup_log warn binlog_quarantine_pruned reason "$reason" file "$(basename "$raw")" age_seconds "$age"
        else
            prune_skip reason binlog_remove_failed file "$(basename "$raw")"
        fi
        return 0
    fi
    prune_skip reason "$reason" file "$(basename "$raw")" age_seconds "$age"
}

# The archive's own file naming, taken from archive-state and - when an operator
# has removed that file - from the metadata sidecars that are still there. A
# digit-shaped suffix alone is not the archive's naming: the binlog root is an
# operator-visible directory, so matching any "*.NNNNNN" would make a file such
# as legacy-export.202401 a delete target.
prune_binlog_prefix() {
    local state="$CONNEX_BACKUP_ROOT/binlog/archive-state"
    local key candidate meta name
    for key in active_file last_closed_file; do
        candidate=
        if [ -f "$state" ]; then
            candidate="$(backup_meta_value "$state" "$key" 2>/dev/null)" || candidate=
        fi
        if [[ "$candidate" =~ ^(.+)[.][0-9]+$ ]]; then
            printf '%s\n' "${BASH_REMATCH[1]}"
            return 0
        fi
    done
    while IFS= read -r meta; do
        name="$(basename "${meta%.meta}")"
        if [[ "$name" =~ ^(.+)[.][0-9]+$ ]]; then
            printf '%s\n' "${BASH_REMATCH[1]}"
            return 0
        fi
    done < <(find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type f -name '*.meta' ! -name '*.pending' -print | sort)
    return 0
}

# An orphan is a file that carries the archive's naming *and* the binary-log
# magic. Both are required: the name alone would catch operator files, and the
# magic alone would catch a binlog an operator copied in under a name of their
# own. The suffix is matched as a number rather than as six digits so a server
# that has rotated past 999999 is still classified.
prune_is_orphaned_binlog() {
    local entry="$1"
    local prefix="$2"
    local name
    name="$(basename "$entry")"
    if [ "$name" = "${name%.*}" ] || [[ ! "${name##*.}" =~ ^[0-9]+$ ]]; then
        return 1
    fi
    if [ -z "$prefix" ] || [ "${name%.*}" != "$prefix" ]; then
        return 1
    fi
    [ "$(od -An -t x1 -N4 "$entry" | tr -d ' \n')" = fe62696e ]
}

prune_binlogs() {
    local now legal_age orphan_grace meta raw created_epoch age entry name prefix
    now="$(date +%s)"
    legal_age=$(( (CONNEX_BACKUP_RETENTION_DAYS - 1) * 86400 ))
    orphan_grace=$(( CONNEX_BACKUP_FAILED_GRACE_HOURS * 3600 ))
    prefix="$(prune_binlog_prefix)"
    while IFS= read -r meta; do
        raw="${meta%.meta}"
        if ! backup_validate_binlog_triplet "$raw"; then
            prune_quarantine_expired_binlog "$raw" invalid_binlog_triplet "$now" "$legal_age"
            continue
        fi
        if ! created_epoch="$(backup_meta_value "$meta" file_created_epoch)"; then
            prune_quarantine_expired_binlog "$raw" missing_binlog_timestamp "$now" "$legal_age"
            continue
        fi
        if [[ ! "$created_epoch" =~ ^[0-9]+$ ]]; then
            prune_quarantine_expired_binlog "$raw" invalid_binlog_timestamp "$now" "$legal_age"
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
            archive-state|coverage-gap|*.meta|*.sha256)
                ;;
            *)
                # Anything with a .meta was already classified by the loop above,
                # on the legal-age clock.
                if [ -f "$entry.meta" ]; then
                    continue
                fi
                # A raw binlog with no metadata can never be validated or
                # replayed, and the archive re-fetches it while the server still
                # holds it, so the short failed-run grace is both safe and the
                # only clock that actually meets the ceiling: mtime is the local
                # fetch time, which a catch-up run sets to now for content that
                # is already nearly a month old. Everything else - operator
                # files, future sidecars, and anything at all when the archive's
                # naming cannot be determined - keeps its advisory warning
                # instead of becoming a delete target.
                if prune_is_orphaned_binlog "$entry" "$prefix"; then
                    prune_quarantine_expired_binlog "$entry" orphaned_binlog_without_metadata "$now" "$orphan_grace"
                else
                    prune_skip reason unclassifiable_binlog_entry file "$name"
                fi
                ;;
        esac
    done < <(find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type f ! -name '*.pending' -print | sort)
}

prune_staging() {
    local entry raw destination status mtime age now grace_age
    local -a files=() pending_destinations=()
    now="$(date +%s)"
    grace_age=$(( CONNEX_BACKUP_FAILED_GRACE_HOURS * 3600 ))
    while IFS= read -r entry; do
        if [[ "$entry" != "$CONNEX_BACKUP_ROOT/binlog/.fetch."* ]]; then
            prune_skip reason unexpected_binlog_directory path "$entry"
            continue
        fi
        mapfile -t files < <(find "$entry" -mindepth 1 -maxdepth 1 -type f -print)
        if [ "${#files[@]}" -eq 1 ] &&
            ! find "$entry" -mindepth 1 -maxdepth 1 ! -type f -print -quit | grep -q .; then
            raw="${files[0]}"
            destination="$CONNEX_BACKUP_ROOT/binlog/$(basename "$raw")"
            if [ ! -e "$destination" ] && [ ! -L "$destination" ] &&
                [ ! -e "$destination.pending" ] && [ ! -L "$destination.pending" ] &&
                backup_validate_binlog_components \
                    "$raw" "$destination.meta.pending" "$destination.sha256.pending" "$destination"; then
                if ! backup_sync_file "$raw" ||
                    ! mv -T -- "$raw" "$destination.pending" ||
                    ! backup_sync_directory "$CONNEX_BACKUP_ROOT/binlog" ||
                    ! backup_recover_binlog_triplet "$destination"; then
                    prune_skip reason binlog_staging_recovery_failed path "$entry"
                    continue
                fi
                rmdir "$entry" || {
                    prune_skip reason staging_remove_failed path "$entry"
                    continue
                }
                backup_log warn binlog_publication_recovered file "$(basename "$destination")"
                continue
            fi
        fi
        if ! mtime="$(stat -c %Y "$entry" 2>/dev/null)"; then
            prune_skip reason staging_age_unreadable path "$entry"
            continue
        fi
        age=$((now - mtime))
        if [ "$age" -lt "$grace_age" ]; then
            prune_skip reason interrupted_binlog_staging path "$entry" age_seconds "$age"
            continue
        fi
        rm -rf -- "$entry" || {
            prune_skip reason staging_remove_failed path "$entry"
            continue
        }
        backup_log warn abandoned_binlog_staging_pruned path "$entry" age_seconds "$age"
    done < <(find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type d -print)
    while IFS= read -r entry; do
        case "$entry" in
            *.meta.pending)
                pending_destinations+=("${entry%.meta.pending}")
                ;;
            *.sha256.pending)
                pending_destinations+=("${entry%.sha256.pending}")
                ;;
            *.pending)
                pending_destinations+=("${entry%.pending}")
                ;;
            *)
                continue
                ;;
        esac
    done < <(find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -name '*.pending' -print | sort)
    if [ "${#pending_destinations[@]}" -gt 0 ]; then
        mapfile -t pending_destinations < <(printf '%s\n' "${pending_destinations[@]}" | sort -u)
    fi
    for destination in "${pending_destinations[@]}"; do
        if backup_recover_binlog_triplet "$destination"; then
            backup_log warn binlog_publication_recovered file "$(basename "$destination")"
            continue
        else
            status=$?
        fi
        if [ "$status" -ne 1 ] && [ "$status" -ne "$EXIT_INTEGRITY" ]; then
            return "$status"
        fi
        prune_skip reason interrupted_binlog_publication file "$(basename "$destination")"
    done
    find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type f -name '.pitr-query.*' -delete
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
