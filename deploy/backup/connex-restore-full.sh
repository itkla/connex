#!/bin/bash
#
# Restores one schema artifact from a verified COMPLETE Connex full-backup run
# into a fresh target schema. Protected, non-empty, and manifest-collision
# guards refuse by default; --force-overwrite deliberately overrides all three
# for disaster recovery onto a production schema.
# shellcheck source=deploy/backup/connex-backup-lib.sh

set -euo pipefail

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/connex-backup-lib.sh"

RESTORE_RUN_REQUEST=
RESTORE_RUN_DIR=
RESTORE_SOURCE_SCHEMA=
RESTORE_TARGET_SCHEMA=
RESTORE_FORCE=false
RESTORE_TABLE_COUNT=0
RESTORE_ROW_COUNT=0

restore_usage() {
    printf 'Usage: %s <run-dir-or-latest> --target-schema <name> [--source-schema <name>] [--force-overwrite]\n' "$(basename "$0")" >&2
}

restore_parse_arguments() {
    if [ "$#" -lt 1 ]; then
        restore_usage
        return "$EXIT_CONFIG"
    fi
    RESTORE_RUN_REQUEST="$1"
    shift
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --target-schema)
                if [ "$#" -lt 2 ]; then
                    restore_usage
                    return "$EXIT_CONFIG"
                fi
                RESTORE_TARGET_SCHEMA="$2"
                shift 2
                ;;
            --source-schema)
                if [ "$#" -lt 2 ]; then
                    restore_usage
                    return "$EXIT_CONFIG"
                fi
                RESTORE_SOURCE_SCHEMA="$2"
                shift 2
                ;;
            --force-overwrite)
                RESTORE_FORCE=true
                shift
                ;;
            *)
                restore_usage
                return "$EXIT_CONFIG"
                ;;
        esac
    done
    if [ -z "$RESTORE_TARGET_SCHEMA" ]; then
        restore_usage
        return "$EXIT_CONFIG"
    fi
    backup_validate_schema "$RESTORE_TARGET_SCHEMA" || return "$EXIT_CONFIG"
    if [ -n "$RESTORE_SOURCE_SCHEMA" ]; then
        backup_validate_schema "$RESTORE_SOURCE_SCHEMA" || return "$EXIT_CONFIG"
    fi
}

restore_run() {
    local summary
    restore_parse_arguments "$@" || return $?
    backup_load_environment || return $?
    backup_validate_common || return $?
    backup_validate_restore_profile || return "$EXIT_CONFIG"
    backup_prepare_directories || return "$EXIT_CONFIG"
    backup_acquire_lock shared lifecycle || return $?
    backup_acquire_lock exclusive restore || return $?
    BACKUP_PHASE=integrity
    RESTORE_RUN_DIR="$(backup_resolve_run "$RESTORE_RUN_REQUEST")" || {
        backup_log error restore_refused reason run_not_found run "$RESTORE_RUN_REQUEST"
        return "$EXIT_RESTORE_GUARD"
    }
    if ! backup_validate_run "$RESTORE_RUN_DIR"; then
        backup_log error restore_integrity_failed run_dir "$RESTORE_RUN_DIR"
        return "$EXIT_INTEGRITY"
    fi
    RESTORE_SOURCE_SCHEMA="$(backup_select_source_schema "$RESTORE_RUN_DIR/manifest" "$RESTORE_SOURCE_SCHEMA")" || return $?
    BACKUP_PHASE=restoring
    backup_log info restore_started run_id "$(basename "$RESTORE_RUN_DIR")" source_schema "$RESTORE_SOURCE_SCHEMA" target_schema "$RESTORE_TARGET_SCHEMA" force_overwrite "$RESTORE_FORCE"
    backup_restore_artifact "$RESTORE_RUN_DIR" "$RESTORE_SOURCE_SCHEMA" "$RESTORE_TARGET_SCHEMA" "$RESTORE_FORCE" restore || return $?
    BACKUP_PHASE=row_summary
    summary="$(backup_schema_row_summary restore "$RESTORE_TARGET_SCHEMA")" || return "$EXIT_RESTORE"
    IFS=$'\t' read -r RESTORE_TABLE_COUNT RESTORE_ROW_COUNT <<< "$summary"
    backup_log info restore_completed run_id "$(basename "$RESTORE_RUN_DIR")" source_schema "$RESTORE_SOURCE_SCHEMA" target_schema "$RESTORE_TARGET_SCHEMA" table_count "$RESTORE_TABLE_COUNT" row_count "$RESTORE_ROW_COUNT"
}

main() {
    local exit_code=0
    restore_run "$@" || exit_code=$?
    backup_finish "$exit_code" restore_summary run_id "$(basename "${RESTORE_RUN_DIR:-none}")" source_schema "${RESTORE_SOURCE_SCHEMA:-unknown}" target_schema "${RESTORE_TARGET_SCHEMA:-unknown}" table_count "$RESTORE_TABLE_COUNT" row_count "$RESTORE_ROW_COUNT"
    return "$exit_code"
}

main "$@"
