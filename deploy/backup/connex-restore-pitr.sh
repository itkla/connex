#!/bin/bash
#
# Restores the newest eligible Connex full-backup artifact and replays its
# contiguous archived binary logs to an exact UTC target time. All integrity,
# coverage, target, and Query-event safety checks finish before the target
# schema is created or overwritten.
# shellcheck source=deploy/backup/connex-backup-lib.sh

set -euo pipefail

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/connex-backup-lib.sh"

PITR_TARGET_INPUT=
PITR_TARGET_TIME=
PITR_TARGET_EPOCH=0
PITR_SOURCE_SCHEMA=
PITR_TARGET_SCHEMA=
PITR_FORCE=false
PITR_RUN_DIR=
PITR_BINLOG_POSITION=
PITR_TABLE_COUNT=0
PITR_ROW_COUNT=0
declare -a PITR_BINLOG_FILES=()

pitr_usage() {
    printf 'Usage: %s --target-time <UTC timestamp> --target-schema <name> [--source-schema <name>] [--force-overwrite]\n' "$(basename "$0")" >&2
}

pitr_parse_arguments() {
    local parsed
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --target-time)
                if [ "$#" -lt 2 ]; then
                    pitr_usage
                    return "$EXIT_CONFIG"
                fi
                PITR_TARGET_INPUT="$2"
                shift 2
                ;;
            --target-schema)
                if [ "$#" -lt 2 ]; then
                    pitr_usage
                    return "$EXIT_CONFIG"
                fi
                PITR_TARGET_SCHEMA="$2"
                shift 2
                ;;
            --source-schema)
                if [ "$#" -lt 2 ]; then
                    pitr_usage
                    return "$EXIT_CONFIG"
                fi
                PITR_SOURCE_SCHEMA="$2"
                shift 2
                ;;
            --force-overwrite)
                PITR_FORCE=true
                shift
                ;;
            *)
                pitr_usage
                return "$EXIT_CONFIG"
                ;;
        esac
    done
    if [ -z "$PITR_TARGET_INPUT" ] || [ -z "$PITR_TARGET_SCHEMA" ]; then
        pitr_usage
        return "$EXIT_CONFIG"
    fi
    if ! parsed="$(backup_parse_utc_target "$PITR_TARGET_INPUT")"; then
        backup_log error config_error reason invalid_utc_target target_time "$PITR_TARGET_INPUT"
        return "$EXIT_CONFIG"
    fi
    IFS=$'\t' read -r PITR_TARGET_TIME PITR_TARGET_EPOCH <<< "$parsed"
    backup_validate_schema "$PITR_TARGET_SCHEMA" || return "$EXIT_CONFIG"
    if [ -n "$PITR_SOURCE_SCHEMA" ]; then
        backup_validate_schema "$PITR_SOURCE_SCHEMA" || return "$EXIT_CONFIG"
    fi
}

pitr_select_run() {
    local candidate manifest source capture capture_epoch schema_count
    while IFS= read -r candidate; do
        manifest="$candidate/manifest"
        if ! backup_validate_run "$candidate"; then
            backup_log error pitr_integrity_failed reason invalid_complete_run run_id "$(basename "$candidate")"
            return "$EXIT_INTEGRITY"
        fi
        if [ -n "$PITR_SOURCE_SCHEMA" ]; then
            if ! source="$(backup_select_source_schema "$manifest" "$PITR_SOURCE_SCHEMA" 2>/dev/null)"; then
                continue
            fi
        else
            schema_count="$(backup_manifest_schema_names "$manifest" | wc -l)"
            if [ "$schema_count" -ne 1 ]; then
                backup_log error restore_refused reason source_schema_ambiguous run_id "$(basename "$candidate")" schema_count "$schema_count" override "--source-schema"
                return "$EXIT_RESTORE_GUARD"
            fi
            source="$(backup_manifest_schema_names "$manifest")"
        fi
        capture="$(backup_manifest_schema_field "$manifest" "$source" capture_completed)" || return "$EXIT_INTEGRITY"
        capture_epoch="$(backup_timestamp_to_epoch "$capture")" || return "$EXIT_INTEGRITY"
        if [ "$capture_epoch" -le "$PITR_TARGET_EPOCH" ]; then
            PITR_RUN_DIR="$candidate"
            PITR_SOURCE_SCHEMA="$source"
            return 0
        fi
    done < <(find "$CONNEX_BACKUP_ROOT/full" -mindepth 1 -maxdepth 1 -type d -name '*Z' -exec test -f '{}/COMPLETE' ';' -print | sort -r)
    backup_log error restore_refused reason no_full_dump_before_target target_time "$PITR_TARGET_TIME" source_schema "${PITR_SOURCE_SCHEMA:-unspecified}"
    return "$EXIT_RESTORE_GUARD"
}

pitr_validate_sequence() {
    local start_file="$1"
    local state="$CONNEX_BACKUP_ROOT/binlog/archive-state"
    local coverage server_uuid manifest_uuid meta file base prefix suffix previous_suffix=-1 found_start=false
    coverage="$(backup_meta_value "$state" coverage_through_epoch)" || {
        backup_log error restore_refused reason archive_coverage_missing
        return "$EXIT_RESTORE_GUARD"
    }
    if [[ ! "$coverage" =~ ^[0-9]+$ ]] || [ "$coverage" -lt "$PITR_TARGET_EPOCH" ]; then
        backup_log error restore_refused reason target_not_archived target_epoch "$PITR_TARGET_EPOCH" coverage_epoch "$coverage"
        return "$EXIT_RESTORE_GUARD"
    fi
    server_uuid="$(backup_meta_value "$state" server_uuid)" || return "$EXIT_INTEGRITY"
    manifest_uuid="$(backup_manifest_value "$PITR_RUN_DIR/manifest" server_uuid)" || return "$EXIT_INTEGRITY"
    if [ "$server_uuid" != "$manifest_uuid" ]; then
        backup_log error restore_refused reason source_uuid_mismatch dump_uuid "$manifest_uuid" archive_uuid "$server_uuid"
        return "$EXIT_RESTORE_GUARD"
    fi
    if [[ ! "$start_file" =~ ^(.+)[.]([0-9]+)$ ]]; then
        backup_log error restore_refused reason invalid_start_binlog file "$start_file"
        return "$EXIT_RESTORE_GUARD"
    fi
    prefix="${BASH_REMATCH[1]}"
    while IFS= read -r meta; do
        file="$(basename "${meta%.meta}")"
        if [[ ! "$file" =~ ^(.+)[.]([0-9]+)$ ]]; then
            return "$EXIT_INTEGRITY"
        fi
        base="${BASH_REMATCH[1]}"
        suffix="${BASH_REMATCH[2]}"
        if [ "$base" != "$prefix" ]; then
            continue
        fi
        if [ "$file" = "$start_file" ]; then
            found_start=true
        fi
        if [ "$found_start" = false ]; then
            continue
        fi
        if [ "$previous_suffix" -ge 0 ] && [ "$((10#$suffix))" -ne $((previous_suffix + 1)) ]; then
            backup_log error restore_refused reason binlog_gap previous_suffix "$previous_suffix" current_suffix "$((10#$suffix))"
            return "$EXIT_RESTORE_GUARD"
        fi
        if ! backup_validate_binlog_triplet "$CONNEX_BACKUP_ROOT/binlog/$file"; then
            backup_log error pitr_integrity_failed reason invalid_binlog file "$file"
            return "$EXIT_INTEGRITY"
        fi
        PITR_BINLOG_FILES+=("$CONNEX_BACKUP_ROOT/binlog/$file")
        previous_suffix=$((10#$suffix))
    done < <(find "$CONNEX_BACKUP_ROOT/binlog" -mindepth 1 -maxdepth 1 -type f -name "$prefix.*.meta" -print | sort)
    if [ "$found_start" = false ] || [ "${#PITR_BINLOG_FILES[@]}" -eq 0 ]; then
        backup_log error restore_refused reason starting_binlog_missing file "$start_file"
        return "$EXIT_RESTORE_GUARD"
    fi
}

pitr_extract_query_statements() {
    awk '
        /Query[[:space:]]+thread_id=/ {
            in_query = 1
            next
        }
        in_query && /^SET TIMESTAMP=/ {
            next
        }
        in_query && /^\/[*]![*]\// {
            print separator
            in_query = 0
            separator = "\n"
            next
        }
        in_query {
            print
        }
    '
}

pitr_query_preflight() {
    local decoded="$CONNEX_BACKUP_ROOT/binlog/.pitr-query.$$.txt"
    local schema pattern unsafe=false
    local -a other_schemas=()
    while IFS= read -r schema; do
        if [ "$schema" != "$PITR_SOURCE_SCHEMA" ]; then
            other_schemas+=("$schema")
        fi
    done < <(backup_manifest_schema_names "$PITR_RUN_DIR/manifest")
    if ! backup_mysqlbinlog_local \
        --verify-binlog-checksum \
        --start-position="$PITR_BINLOG_POSITION" \
        --stop-datetime="$PITR_TARGET_TIME" \
        "${PITR_BINLOG_FILES[@]}" | pitr_extract_query_statements > "$decoded"; then
        rm -f "$decoded"
        backup_log error pitr_preflight_failed reason decode
        return "$EXIT_PITR"
    fi
    for schema in "${other_schemas[@]}"; do
        pattern="(\`${schema}\`[[:space:]]*[.]|(^|[^A-Za-z0-9_\$-])${schema}[[:space:]]*[.])"
        if grep -Eiq "$pattern" "$decoded"; then
            backup_log error pitr_preflight_failed reason unsafe_cross_schema_statement schema "$schema"
            unsafe=true
        fi
    done
    if grep -Eiq "(\`(information_schema|performance_schema|mysql|sys)\`[[:space:]]*[.]|(^|[^A-Za-z0-9_\$-])(information_schema|performance_schema|mysql|sys)[[:space:]]*[.])" "$decoded"; then
        backup_log error pitr_preflight_failed reason unsafe_system_schema_statement
        unsafe=true
    fi
    rm -f "$decoded"
    if [ "$unsafe" = true ]; then
        return "$EXIT_PITR"
    fi
}

pitr_rewrite_qualified_schema() {
    awk -v source_schema="$PITR_SOURCE_SCHEMA" -v target_schema="$PITR_TARGET_SCHEMA" '
        function identifier_character(character) {
            return character ~ /[A-Za-z0-9_$-]/
        }
        function following_dot(text, position, length_value, scan) {
            scan = position
            while (scan <= length_value && substr(text, scan, 1) ~ /[[:space:]]/) {
                scan++
            }
            return substr(text, scan, 1) == "."
        }
        {
            text = $0
            length_value = length(text)
            output = ""
            index_value = 1
            line_comment = 0
            while (index_value <= length_value) {
                character = substr(text, index_value, 1)
                next_character = substr(text, index_value + 1, 1)
                if (line_comment) {
                    output = output substr(text, index_value)
                    index_value = length_value + 1
                } else if (block_comment) {
                    output = output character
                    if (character == "*" && next_character == "/") {
                        output = output next_character
                        index_value += 2
                        block_comment = 0
                    } else {
                        index_value++
                    }
                } else if (single_quote) {
                    output = output character
                    if (character == "\\") {
                        output = output next_character
                        index_value += 2
                    } else if (character == "'"'"'" && next_character == "'"'"'") {
                        output = output next_character
                        index_value += 2
                    } else if (character == "'"'"'") {
                        index_value++
                        single_quote = 0
                    } else {
                        index_value++
                    }
                } else if (double_quote) {
                    output = output character
                    if (character == "\\") {
                        output = output next_character
                        index_value += 2
                    } else if (character == "\"" && next_character == "\"") {
                        output = output next_character
                        index_value += 2
                    } else if (character == "\"") {
                        index_value++
                        double_quote = 0
                    } else {
                        index_value++
                    }
                } else if (character == "#") {
                    line_comment = 1
                } else if (character == "-" && next_character == "-" && substr(text, index_value + 2, 1) ~ /[[:space:]]/) {
                    line_comment = 1
                } else if (character == "/" && next_character == "*") {
                    output = output character next_character
                    index_value += 2
                    block_comment = 1
                } else if (character == "'"'"'") {
                    output = output character
                    index_value++
                    single_quote = 1
                } else if (character == "\"") {
                    output = output character
                    index_value++
                    double_quote = 1
                } else if (character == "`") {
                    token = ""
                    token_end = index_value + 1
                    while (token_end <= length_value) {
                        token_character = substr(text, token_end, 1)
                        if (token_character == "`" && substr(text, token_end + 1, 1) == "`") {
                            token = token "``"
                            token_end += 2
                        } else if (token_character == "`") {
                            break
                        } else {
                            token = token token_character
                            token_end++
                        }
                    }
                    if (token_end <= length_value && token == source_schema && following_dot(text, token_end + 1, length_value)) {
                        output = output "`" target_schema "`"
                    } else {
                        output = output substr(text, index_value, token_end - index_value + 1)
                    }
                    index_value = token_end + 1
                } else if (character ~ /[A-Za-z_$]/) {
                    token_end = index_value
                    while (token_end <= length_value && identifier_character(substr(text, token_end, 1))) {
                        token_end++
                    }
                    token = substr(text, index_value, token_end - index_value)
                    if (token == source_schema && following_dot(text, token_end, length_value)) {
                        output = output target_schema
                    } else {
                        output = output token
                    }
                    index_value = token_end
                } else {
                    output = output character
                    index_value++
                }
            }
            print output
        }
    '
}

pitr_replay() {
    if ! TZ=UTC "${MYSQLBINLOG_COMMAND[@]}" \
        --verify-binlog-checksum \
        --require-row-format \
        --start-position="$PITR_BINLOG_POSITION" \
        --stop-datetime="$PITR_TARGET_TIME" \
        "--rewrite-db=$PITR_SOURCE_SCHEMA->$PITR_TARGET_SCHEMA" \
        "--database=$PITR_TARGET_SCHEMA" \
        "${PITR_BINLOG_FILES[@]}" |
        pitr_rewrite_qualified_schema |
        backup_mysql restore --binary-mode "$PITR_TARGET_SCHEMA"; then
        backup_log error pitr_replay_failed source_schema "$PITR_SOURCE_SCHEMA" target_schema "$PITR_TARGET_SCHEMA" target_time "$PITR_TARGET_TIME"
        return "$EXIT_PITR"
    fi
}

pitr_run() {
    local start_file summary
    pitr_parse_arguments "$@" || return $?
    backup_load_environment || return $?
    backup_validate_common || return $?
    backup_initialize_mysqlbinlog || return "$EXIT_CONFIG"
    backup_validate_restore_profile || return "$EXIT_CONFIG"
    backup_prepare_directories || return "$EXIT_CONFIG"
    backup_acquire_lock shared lifecycle || return $?
    backup_acquire_lock exclusive restore || return $?
    backup_acquire_lock shared binlog || return $?
    BACKUP_PHASE=selecting_dump
    pitr_select_run || return $?
    start_file="$(backup_manifest_schema_field "$PITR_RUN_DIR/manifest" "$PITR_SOURCE_SCHEMA" binlog_file)" || return "$EXIT_INTEGRITY"
    PITR_BINLOG_POSITION="$(backup_manifest_schema_field "$PITR_RUN_DIR/manifest" "$PITR_SOURCE_SCHEMA" binlog_position)" || return "$EXIT_INTEGRITY"
    BACKUP_PHASE=validating_binlogs
    pitr_validate_sequence "$start_file" || return $?
    pitr_query_preflight || return $?
    BACKUP_PHASE=restoring_full
    backup_log info pitr_started run_id "$(basename "$PITR_RUN_DIR")" source_schema "$PITR_SOURCE_SCHEMA" target_schema "$PITR_TARGET_SCHEMA" target_time "$PITR_TARGET_TIME" binlog_count "${#PITR_BINLOG_FILES[@]}" force_overwrite "$PITR_FORCE"
    backup_restore_artifact "$PITR_RUN_DIR" "$PITR_SOURCE_SCHEMA" "$PITR_TARGET_SCHEMA" "$PITR_FORCE" restore || return $?
    BACKUP_PHASE=replaying_binlogs
    pitr_replay || return $?
    BACKUP_PHASE=row_summary
    summary="$(backup_schema_row_summary restore "$PITR_TARGET_SCHEMA")" || return "$EXIT_RESTORE"
    IFS=$'\t' read -r PITR_TABLE_COUNT PITR_ROW_COUNT <<< "$summary"
    backup_log info pitr_completed run_id "$(basename "$PITR_RUN_DIR")" binlog_count "${#PITR_BINLOG_FILES[@]}" stop_time "$PITR_TARGET_TIME" table_count "$PITR_TABLE_COUNT" row_count "$PITR_ROW_COUNT"
}

main() {
    local exit_code=0
    pitr_run "$@" || exit_code=$?
    backup_finish "$exit_code" pitr_summary run_id "$(basename "${PITR_RUN_DIR:-none}")" source_schema "${PITR_SOURCE_SCHEMA:-unknown}" target_schema "${PITR_TARGET_SCHEMA:-unknown}" binlogs_replayed "${#PITR_BINLOG_FILES[@]}" stop_time "${PITR_TARGET_TIME:-unknown}" table_count "$PITR_TABLE_COUNT" row_count "$PITR_ROW_COUNT"
    return "$exit_code"
}

main "$@"
