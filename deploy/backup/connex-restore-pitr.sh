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
PITR_DUMP_CAPTURE_EPOCH=0
PITR_BINLOG_POSITION=
PITR_TABLE_COUNT=0
PITR_ROW_COUNT=0
PITR_SCRATCH_FILE=
PITR_DECODE_SCRATCH_FILE=
PITR_FILTERED_SCRATCH_FILE=
PITR_QUERY_EVENT_DIRECTORY=
PITR_COUNT_FILE=
PITR_EXPECTED_EVENTS=0
PITR_APPLIED_EVENTS=0
declare -a PITR_BINLOG_FILES=()

pitr_cleanup() {
    if [ -n "$PITR_SCRATCH_FILE" ]; then
        rm -f "$PITR_SCRATCH_FILE"
    fi
    if [ -n "$PITR_DECODE_SCRATCH_FILE" ]; then
        rm -f "$PITR_DECODE_SCRATCH_FILE"
    fi
    if [ -n "$PITR_FILTERED_SCRATCH_FILE" ]; then
        rm -f "$PITR_FILTERED_SCRATCH_FILE"
    fi
    if [ -n "$PITR_QUERY_EVENT_DIRECTORY" ]; then
        rm -rf -- "$PITR_QUERY_EVENT_DIRECTORY"
    fi
    if [ -n "$PITR_COUNT_FILE" ]; then
        rm -f "$PITR_COUNT_FILE"
    fi
}

trap pitr_cleanup EXIT

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
            PITR_DUMP_CAPTURE_EPOCH="$capture_epoch"
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

# The archive appends a record here for every hole it could not fill and never
# rewrites the file, because archive-state is rebuilt from scratch every run and
# would otherwise forget the hole within one timer interval. Coverage arithmetic
# alone cannot see a hole the archive already stepped over, so refuse whenever a
# recorded hole overlaps the window between the selected dump and the target.
pitr_verify_no_coverage_gap() {
    local marker="$CONNEX_BACKUP_ROOT/binlog/coverage-gap"
    local pattern line file from_epoch through_epoch detected_utc line_number=0
    pattern=$'^gap\t([A-Za-z0-9._-]+[.][0-9]+)\t([0-9]+)\t([0-9]+)\t([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z)$'
    if [ -L "$marker" ]; then
        backup_log error restore_refused reason coverage_gap_marker_not_regular path "$marker"
        return "$EXIT_RESTORE_GUARD"
    fi
    if [ ! -f "$marker" ]; then
        return 0
    fi
    while IFS= read -r line || [ -n "$line" ]; do
        line_number=$((line_number + 1))
        if [[ ! "$line" =~ $pattern ]]; then
            backup_log error restore_refused reason unreadable_coverage_gap_record line "$line_number"
            return "$EXIT_RESTORE_GUARD"
        fi
        file="${BASH_REMATCH[1]}"
        from_epoch="${BASH_REMATCH[2]}"
        through_epoch="${BASH_REMATCH[3]}"
        detected_utc="${BASH_REMATCH[4]}"
        if [ "$from_epoch" -gt "$through_epoch" ] ||
                ! backup_parse_utc_target "$detected_utc" >/dev/null 2>&1; then
            backup_log error restore_refused reason unreadable_coverage_gap_record \
                file "$file" line "$line_number"
            return "$EXIT_RESTORE_GUARD"
        fi
        if [ "$through_epoch" -lt "$PITR_DUMP_CAPTURE_EPOCH" ] || [ "$from_epoch" -gt "$PITR_TARGET_EPOCH" ]; then
            continue
        fi
        backup_log error restore_refused reason archive_coverage_gap file "$file" \
            gap_from_epoch "$from_epoch" gap_through_epoch "$through_epoch" detected_utc "$detected_utc" \
            dump_capture_epoch "$PITR_DUMP_CAPTURE_EPOCH" target_epoch "$PITR_TARGET_EPOCH"
        return "$EXIT_RESTORE_GUARD"
    done < "$marker"
    return 0
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

pitr_scan_unsafe_statements() {
    awk -v source_schema="$1" '
        function following_dot(text, position, length_value,    scan, crossed) {
            crossed = 0
            scan = position
            while (scan <= length_value && substr(text, scan, 1) ~ /[[:space:]]/) {
                if (substr(text, scan, 1) == "\n") {
                    crossed = 1
                }
                scan++
            }
            if (substr(text, scan, 1) != ".") {
                return -1
            }
            return crossed
        }
        function emit(token, crossed) {
            if (toupper(token) == "NEW" || toupper(token) == "OLD") {
                return
            }
            if (token == source_schema) {
                if (crossed == 1) {
                    print "split_schema\t" token
                }
                return
            }
            print "schema\t" token
        }
        function shift_keyword(upper) {
            previous2 = previous
            previous = upper
        }
        function classify(token,    upper) {
            upper = toupper(token)
            if (upper == "GRANT" || upper == "REVOKE" || upper == "SHUTDOWN") {
                print "statement\t" upper
            } else if ((previous == "CREATE" || previous == "DROP" || previous == "ALTER" || previous == "RENAME") && (upper == "USER" || upper == "ROLE" || upper == "DATABASE" || upper == "SCHEMA" || upper == "TABLESPACE" || upper == "LOGFILE" || upper == "SERVER")) {
                print "statement\t" previous "_" upper
            } else if (previous == "SET" && (upper == "GLOBAL" || upper == "PERSIST" || upper == "PERSIST_ONLY")) {
                print "statement\tSET_" upper
            } else if (previous == "SET" && upper == "PASSWORD") {
                print "statement\tSET_PASSWORD"
            } else if (previous2 == "SET" && previous == "DEFAULT" && upper == "ROLE") {
                print "statement\tSET_DEFAULT_ROLE"
            } else if ((previous == "INSTALL" || previous == "UNINSTALL") && (upper == "PLUGIN" || upper == "COMPONENT")) {
                print "statement\t" previous "_" upper
            } else if (previous == "RESET" && (upper == "MASTER" || upper == "REPLICA" || upper == "SLAVE" || upper == "BINARY")) {
                print "statement\tRESET_" upper
            } else if (previous == "PURGE" && (upper == "BINARY" || upper == "MASTER")) {
                print "statement\tPURGE_" upper
            } else if (previous == "CHANGE" && (upper == "MASTER" || upper == "REPLICATION")) {
                print "statement\tCHANGE_" upper
            }
            shift_keyword(upper)
        }
        { buffer = buffer $0 "\n" }
        END {
            text = buffer
            length_value = length(text)
            index_value = 1
            exec_comment = 0
            while (index_value <= length_value) {
                character = substr(text, index_value, 1)
                next_character = substr(text, index_value + 1, 1)
                if (character == "#") {
                    while (index_value <= length_value && substr(text, index_value, 1) != "\n") { index_value++ }
                } else if (character == "-" && next_character == "-" && substr(text, index_value + 2, 1) ~ /[[:space:]]/) {
                    while (index_value <= length_value && substr(text, index_value, 1) != "\n") { index_value++ }
                } else if (character == "/" && next_character == "*") {
                    if (substr(text, index_value + 2, 1) == "!") {
                        index_value += 3
                        while (index_value <= length_value && substr(text, index_value, 1) ~ /[0-9]/) { index_value++ }
                        exec_comment = 1
                    } else {
                        index_value += 2
                        while (index_value <= length_value && !(substr(text, index_value, 1) == "*" && substr(text, index_value + 1, 1) == "/")) { index_value++ }
                        index_value += 2
                    }
                } else if (exec_comment && character == "*" && next_character == "/") {
                    index_value += 2
                    exec_comment = 0
                } else if (character == "\x27") {
                    index_value++
                    while (index_value <= length_value) {
                        inner = substr(text, index_value, 1)
                        if (inner == "\\") { index_value += 2; continue }
                        if (inner == "\x27" && substr(text, index_value + 1, 1) == "\x27") { index_value += 2; continue }
                        if (inner == "\x27") { index_value++; break }
                        index_value++
                    }
                    shift_keyword("")
                } else if (character == "\"") {
                    token = ""
                    index_value++
                    while (index_value <= length_value) {
                        inner = substr(text, index_value, 1)
                        if (inner == "\"" && substr(text, index_value + 1, 1) == "\"") { token = token "\""; index_value += 2; continue }
                        if (inner == "\"") { index_value++; break }
                        token = token inner
                        index_value++
                    }
                    dot_state = following_dot(text, index_value, length_value)
                    if (dot_state >= 0) { emit(token, dot_state) }
                    shift_keyword("")
                } else if (character == "`") {
                    token = ""
                    index_value++
                    while (index_value <= length_value) {
                        inner = substr(text, index_value, 1)
                        if (inner == "`" && substr(text, index_value + 1, 1) == "`") { token = token "`"; index_value += 2; continue }
                        if (inner == "`") { index_value++; break }
                        token = token inner
                        index_value++
                    }
                    dot_state = following_dot(text, index_value, length_value)
                    if (dot_state >= 0) { emit(token, dot_state) }
                    shift_keyword("")
                } else if (character == "@") {
                    token = "@"
                    index_value++
                    while (index_value <= length_value && substr(text, index_value, 1) ~ /[@A-Za-z0-9_$.]/) {
                        token = token substr(text, index_value, 1)
                        index_value++
                    }
                    if (tolower(token) ~ /^@@(global|persist)/) { print "statement\tSET_GLOBAL_VARIABLE" }
                    shift_keyword("")
                } else if (character ~ /[A-Za-z_$]/) {
                    token_end = index_value
                    while (token_end <= length_value && substr(text, token_end, 1) ~ /[A-Za-z0-9_$-]/) { token_end++ }
                    token = substr(text, index_value, token_end - index_value)
                    dot_state = following_dot(text, token_end, length_value)
                    if (dot_state >= 0) { emit(token, dot_state) }
                    classify(token)
                    index_value = token_end
                } else {
                    index_value++
                }
            }
        }
    '
}

pitr_extract_query_events() {
    local decoded_stream="$1"
    local event_directory="$2"
    mkdir -p -- "$event_directory" || return 1
    LC_ALL=C awk -v output_directory="$event_directory" '
        function start_event() {
            event_count++
            body_path = sprintf("%s/event-%09d.sql", output_directory, event_count)
            database_path = sprintf("%s/event-%09d.db", output_directory, event_count)
            printf "%s", "" > body_path
            close(body_path)
            printf "%s", current_database > database_path
            close(database_path)
            in_query = 1
            prefix = 1
            body_lines = 0
            single_quote = 0
            double_quote = 0
            quoted_identifier = 0
            block_comment = 0
        }
        function scan_body_line(text,    index_value, length_value, character, next_character) {
            length_value = length(text)
            index_value = 1
            while (index_value <= length_value) {
                character = substr(text, index_value, 1)
                next_character = substr(text, index_value + 1, 1)
                if (single_quote) {
                    if (character == "\\") {
                        index_value += 2
                    } else if (character == "\x27" && next_character == "\x27") {
                        index_value += 2
                    } else if (character == "\x27") {
                        single_quote = 0
                        index_value++
                    } else {
                        index_value++
                    }
                } else if (double_quote) {
                    if (character == "\\") {
                        index_value += 2
                    } else if (character == "\"" && next_character == "\"") {
                        index_value += 2
                    } else if (character == "\"") {
                        double_quote = 0
                        index_value++
                    } else {
                        index_value++
                    }
                } else if (quoted_identifier) {
                    if (character == "`" && next_character == "`") {
                        index_value += 2
                    } else if (character == "`") {
                        quoted_identifier = 0
                        index_value++
                    } else {
                        index_value++
                    }
                } else if (block_comment) {
                    if (character == "*" && next_character == "/") {
                        block_comment = 0
                        index_value += 2
                    } else {
                        index_value++
                    }
                } else if (character == "#") {
                    return
                } else if (character == "-" && next_character == "-" && substr(text, index_value + 2, 1) ~ /[[:space:]]/) {
                    return
                } else if (character == "/" && next_character == "*") {
                    block_comment = 1
                    index_value += 2
                } else if (character == "\x27") {
                    single_quote = 1
                    index_value++
                } else if (character == "\"") {
                    double_quote = 1
                    index_value++
                } else if (character == "`") {
                    quoted_identifier = 1
                    index_value++
                } else {
                    index_value++
                }
            }
        }
        function append_body(text) {
            print text >> body_path
            close(body_path)
            body_lines++
            scan_body_line(text)
        }
        function known_prefix(text) {
            if (text == "") {
                return 1
            }
            if (text ~ /^use [`][^`]+[`][/][*]![*][/];$/) {
                current_database = text
                print text > database_path
                close(database_path)
                return 1
            }
            if (text ~ /^use[[:space:]]/) {
                ambiguous = 1
                return 1
            }
            if (text ~ /^SET TIMESTAMP=[0-9]+([.][0-9]+)?[/][*]![*][/];$/) {
                return 1
            }
            if (text ~ /^SET @@session[.][A-Za-z0-9_]+=.*[/][*]![*][/];$/) {
                return 1
            }
            if (text ~ /^[/][*]![0-9]+ SET @@session[.][A-Za-z0-9_]+=.*[*][/][/][*]![*][/];$/) {
                return 1
            }
            if (text ~ /^[/][*]![\\]C [A-Za-z0-9_]+ [*][/][/][*]![*][/];$/) {
                return 1
            }
            return 0
        }
        {
            if (!in_query && $0 ~ /^#[0-9]/ && $0 ~ /[[:space:]]Query[[:space:]]+thread_id=/) {
                pending_event_header = 0
                start_event()
                next
            }
            if (!in_query) {
                if (pending_event_header && $0 ~ /^[[:space:]]*Query[[:space:]]+thread_id=/) {
                    pending_event_header = 0
                    start_event()
                    next
                }
                pending_event_header = ($0 ~ /^#[0-9]/)
                next
            }
            if (!single_quote && !double_quote && !quoted_identifier && !block_comment && $0 ~ /^#[0-9]/) {
                ambiguous = 1
                next
            }
            if (!single_quote && !double_quote && !quoted_identifier && !block_comment && $0 == "/*!*/;") {
                if (prefix || body_lines == 0) {
                    ambiguous = 1
                }
                in_query = 0
                next
            }
            if (prefix && known_prefix($0)) {
                next
            }
            prefix = 0
            append_body($0)
        }
        END {
            if (in_query || single_quote || double_quote || quoted_identifier || block_comment || ambiguous) {
                exit 1
            }
        }
    ' "$decoded_stream"
}

pitr_build_query_manifest() {
    local event_directory="$1"
    local manifest="$2"
    local body database database_content hash
    : > "$manifest" || return 1
    for body in "$event_directory"/event-*.sql; do
        if [ ! -e "$body" ]; then
            continue
        fi
        database="${body%.sql}.db"
        if [ ! -f "$body" ] || [ -L "$body" ] || [ ! -f "$database" ] || [ -L "$database" ]; then
            return 1
        fi
        database_content="$(cat "$database")" || return 1
        hash="$(
            {
                printf 'database\0'
                printf '%s' "$database_content"
                printf '\0body\0'
                cat "$body"
            } | sha256sum | awk '{print $1}'
        )" || return 1
        if [[ ! "$hash" =~ ^[0-9a-f]{64}$ ]]; then
            return 1
        fi
        printf '%s\t%s\t%s\n' "$hash" "$body" "$database" >> "$manifest" || return 1
    done
}

pitr_event_targets_source() {
    local body="$1"
    local database="$2"
    [ "$(cat "$database")" = "use \`$PITR_SOURCE_SCHEMA\`/*!*/;" ] ||
        LC_ALL=C grep -aFq -- "$PITR_SOURCE_SCHEMA" "$body"
}

pitr_verify_no_statement_is_filtered_away() {
    local full_events filtered_events full_manifest filtered_manifest
    local full_hashes filtered_hashes missing_hashes unexpected_hashes
    local hash manifest_line body database dropped_events=0 first_hash=
    PITR_QUERY_EVENT_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/connex-pitr-events.XXXXXX")" || return 1
    PITR_FILTERED_SCRATCH_FILE="$PITR_QUERY_EVENT_DIRECTORY/filtered.decode"
    full_events="$PITR_QUERY_EVENT_DIRECTORY/full-events"
    filtered_events="$PITR_QUERY_EVENT_DIRECTORY/filtered-events"
    full_manifest="$PITR_QUERY_EVENT_DIRECTORY/full.manifest"
    filtered_manifest="$PITR_QUERY_EVENT_DIRECTORY/filtered.manifest"
    full_hashes="$PITR_QUERY_EVENT_DIRECTORY/full.hashes"
    filtered_hashes="$PITR_QUERY_EVENT_DIRECTORY/filtered.hashes"
    missing_hashes="$PITR_QUERY_EVENT_DIRECTORY/missing.hashes"
    unexpected_hashes="$PITR_QUERY_EVENT_DIRECTORY/unexpected.hashes"
    if ! backup_mysqlbinlog_local \
        --verify-binlog-checksum \
        --start-position="$PITR_BINLOG_POSITION" \
        --stop-datetime="$PITR_TARGET_TIME" \
        "--database=$PITR_SOURCE_SCHEMA" \
        "${PITR_BINLOG_FILES[@]}" > "$PITR_FILTERED_SCRATCH_FILE"; then
        backup_log error pitr_preflight_failed reason filtered_decode
        return 1
    fi
    if ! pitr_extract_query_events "$PITR_DECODE_SCRATCH_FILE" "$full_events" ||
        ! pitr_extract_query_events "$PITR_FILTERED_SCRATCH_FILE" "$filtered_events" ||
        ! pitr_build_query_manifest "$full_events" "$full_manifest" ||
        ! pitr_build_query_manifest "$filtered_events" "$filtered_manifest"; then
        backup_log error pitr_preflight_failed reason query_event_extraction
        return 1
    fi
    cut -f 1 "$full_manifest" | LC_ALL=C sort > "$full_hashes" || return 1
    cut -f 1 "$filtered_manifest" | LC_ALL=C sort > "$filtered_hashes" || return 1
    comm -23 "$full_hashes" "$filtered_hashes" > "$missing_hashes" || return 1
    comm -13 "$full_hashes" "$filtered_hashes" > "$unexpected_hashes" || return 1
    if [ -s "$unexpected_hashes" ]; then
        backup_log error pitr_preflight_failed reason filtered_query_event_not_in_full_stream
        return 1
    fi
    while IFS= read -r hash; do
        manifest_line="$(awk -F '\t' -v hash="$hash" '$1 == hash { print; exit }' "$full_manifest")" || return 1
        if [ -z "$manifest_line" ]; then
            backup_log error pitr_preflight_failed reason query_event_manifest_lookup hash "$hash"
            return 1
        fi
        IFS=$'\t' read -r _ body database <<< "$manifest_line"
        if pitr_event_targets_source "$body" "$database"; then
            dropped_events=$((dropped_events + 1))
            if [ -z "$first_hash" ]; then
                first_hash="$hash"
            fi
        fi
    done < "$missing_hashes"
    if [ "$dropped_events" -gt 0 ]; then
        backup_log error pitr_preflight_failed reason qualified_statement_without_matching_default_database \
            source_schema "$PITR_SOURCE_SCHEMA" dropped_events "$dropped_events" dropped_first_hash "$first_hash"
        return 1
    fi
    return 0
}

pitr_query_preflight() {
    local kind detail unsafe=false
    PITR_DECODE_SCRATCH_FILE="$(mktemp "${TMPDIR:-/tmp}/connex-pitr-decode.XXXXXX")" || return "$EXIT_PITR"
    PITR_SCRATCH_FILE="$(mktemp "${TMPDIR:-/tmp}/connex-pitr-query.XXXXXX")" || return "$EXIT_PITR"
    if ! backup_mysqlbinlog_local \
        --verify-binlog-checksum \
        --start-position="$PITR_BINLOG_POSITION" \
        --stop-datetime="$PITR_TARGET_TIME" \
        "${PITR_BINLOG_FILES[@]}" > "$PITR_DECODE_SCRATCH_FILE" ||
        ! pitr_extract_query_statements < "$PITR_DECODE_SCRATCH_FILE" > "$PITR_SCRATCH_FILE"; then
        backup_log error pitr_preflight_failed reason decode
        return "$EXIT_PITR"
    fi
    while IFS=$'\t' read -r kind detail; do
        case "$kind" in
            schema)
                backup_log error pitr_preflight_failed reason foreign_schema_reference schema "$detail"
                ;;
            split_schema)
                backup_log error pitr_preflight_failed reason unrewritable_split_schema_reference schema "$detail"
                ;;
            statement)
                backup_log error pitr_preflight_failed reason global_or_schema_level_statement statement "$detail"
                ;;
            *)
                continue
                ;;
        esac
        unsafe=true
    done < <(pitr_scan_unsafe_statements "$PITR_SOURCE_SCHEMA" < "$PITR_SCRATCH_FILE" | sort -u)
    pitr_verify_no_statement_is_filtered_away || unsafe=true
    if [ "$unsafe" = true ]; then
        if [ "$PITR_FORCE" = true ]; then
            backup_log warn pitr_preflight_overridden override "--force-overwrite" source_schema "$PITR_SOURCE_SCHEMA" target_schema "$PITR_TARGET_SCHEMA"
            return 0
        fi
        return "$EXIT_PITR"
    fi
}

pitr_rewrite_qualified_schema() {
    awk -v source_schema="$PITR_SOURCE_SCHEMA" -v target_schema="$PITR_TARGET_SCHEMA" -v count_file="$1" '
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
            if ($0 ~ /^BINLOG [\x27]/) {
                applied_events++
            }
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
                } else if (exec_comment && character == "*" && next_character == "/") {
                    output = output "*/"
                    index_value += 2
                    exec_comment = 0
                } else if (character == "#") {
                    line_comment = 1
                } else if (character == "-" && next_character == "-" && substr(text, index_value + 2, 1) ~ /[[:space:]]/) {
                    line_comment = 1
                } else if (character == "/" && next_character == "*") {
                    if (substr(text, index_value + 2, 1) == "!") {
                        output = output "/*!"
                        index_value += 3
                        while (index_value <= length_value && substr(text, index_value, 1) ~ /[0-9]/) {
                            output = output substr(text, index_value, 1)
                            index_value++
                        }
                        exec_comment = 1
                    } else {
                        output = output character next_character
                        index_value += 2
                        block_comment = 1
                    }
                } else if (character == "'"'"'") {
                    output = output character
                    index_value++
                    single_quote = 1
                } else if (character == "\"") {
                    close_index = index_value + 1
                    quoted_token = ""
                    found_close = 0
                    while (close_index <= length_value) {
                        quoted_character = substr(text, close_index, 1)
                        if (quoted_character == "\"" && substr(text, close_index + 1, 1) == "\"") {
                            quoted_token = quoted_token "\""
                            close_index += 2
                        } else if (quoted_character == "\"") {
                            found_close = 1
                            break
                        } else {
                            quoted_token = quoted_token quoted_character
                            close_index++
                        }
                    }
                    if (found_close && quoted_token == source_schema && following_dot(text, close_index + 1, length_value)) {
                        output = output "\"" target_schema "\""
                        index_value = close_index + 1
                    } else if (found_close) {
                        output = output substr(text, index_value, close_index - index_value + 1)
                        index_value = close_index + 1
                    } else {
                        output = output character
                        index_value++
                        double_quote = 1
                    }
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
        END {
            printf "%s\n", applied_events + 0 > count_file
        }
    '
}

pitr_count_source_events() {
    backup_mysqlbinlog_local \
        --verify-binlog-checksum \
        --start-position="$PITR_BINLOG_POSITION" \
        --stop-datetime="$PITR_TARGET_TIME" \
        "--database=$PITR_SOURCE_SCHEMA" \
        "${PITR_BINLOG_FILES[@]}" |
        awk '/^BINLOG [\x27]/ { count++ } END { printf "%s\n", count + 0 }'
}

pitr_replay_target_notice() {
    if [ "$CONNEX_BACKUP_DOCKER_CLIENT_MODE" = exec ] ||
        { [ "$CONNEX_BACKUP_RESTORE_DB_HOST" = "$CONNEX_BACKUP_DB_HOST" ] && [ "$CONNEX_BACKUP_RESTORE_DB_PORT" = "$CONNEX_BACKUP_DB_PORT" ]; }; then
        backup_log warn pitr_replay_target_shared reason same_server_as_source restore_host "$CONNEX_BACKUP_RESTORE_DB_HOST" restore_port "$CONNEX_BACKUP_RESTORE_DB_PORT" client_mode "$CONNEX_BACKUP_DOCKER_CLIENT_MODE"
    fi
}

pitr_replay() {
    if ! PITR_EXPECTED_EVENTS="$(pitr_count_source_events)"; then
        backup_log error pitr_replay_failed reason source_event_count source_schema "$PITR_SOURCE_SCHEMA"
        return "$EXIT_PITR"
    fi
    PITR_COUNT_FILE="$(mktemp "${TMPDIR:-/tmp}/connex-pitr-applied.XXXXXX")" || return "$EXIT_PITR"
    if ! TZ=UTC "${MYSQLBINLOG_COMMAND[@]}" \
        --verify-binlog-checksum \
        --require-row-format \
        --start-position="$PITR_BINLOG_POSITION" \
        --stop-datetime="$PITR_TARGET_TIME" \
        "--rewrite-db=$PITR_SOURCE_SCHEMA->$PITR_TARGET_SCHEMA" \
        "--database=$PITR_TARGET_SCHEMA" \
        "${PITR_BINLOG_FILES[@]}" |
        pitr_rewrite_qualified_schema "$PITR_COUNT_FILE" |
        { backup_session_preamble restore; cat; } |
        backup_mysql restore --binary-mode "$PITR_TARGET_SCHEMA"; then
        backup_log error pitr_replay_failed source_schema "$PITR_SOURCE_SCHEMA" target_schema "$PITR_TARGET_SCHEMA" target_time "$PITR_TARGET_TIME"
        return "$EXIT_PITR"
    fi
    PITR_APPLIED_EVENTS="$(cat "$PITR_COUNT_FILE")"
    if [[ ! "$PITR_APPLIED_EVENTS" =~ ^[0-9]+$ ]]; then
        backup_log error pitr_replay_failed reason applied_event_count_unreadable
        return "$EXIT_PITR"
    fi
    if [ "$PITR_EXPECTED_EVENTS" -gt 0 ] && [ "$PITR_APPLIED_EVENTS" -eq 0 ]; then
        backup_log error pitr_replay_failed reason zero_events_applied expected_row_events "$PITR_EXPECTED_EVENTS" applied_row_events "$PITR_APPLIED_EVENTS" source_schema "$PITR_SOURCE_SCHEMA" target_schema "$PITR_TARGET_SCHEMA"
        return "$EXIT_PITR"
    fi
    if [ "$PITR_APPLIED_EVENTS" -ne "$PITR_EXPECTED_EVENTS" ]; then
        backup_log warn pitr_replay_event_count_differs expected_row_events "$PITR_EXPECTED_EVENTS" applied_row_events "$PITR_APPLIED_EVENTS"
    fi
    backup_log info pitr_replay_completed expected_row_events "$PITR_EXPECTED_EVENTS" applied_row_events "$PITR_APPLIED_EVENTS"
}

pitr_run() {
    local start_file summary
    pitr_parse_arguments "$@" || return $?
    backup_load_environment || return $?
    backup_validate_common || return $?
    backup_initialize_mysqlbinlog || return "$EXIT_CONFIG"
    backup_validate_restore_profile || return "$EXIT_CONFIG"
    backup_prepare_directories || return "$EXIT_CONFIG"
    backup_acquire_lock exclusive lifecycle || return $?
    backup_acquire_lock exclusive restore || return $?
    backup_acquire_lock shared binlog || return $?
    BACKUP_PHASE=selecting_dump
    pitr_select_run || return $?
    start_file="$(backup_manifest_schema_field "$PITR_RUN_DIR/manifest" "$PITR_SOURCE_SCHEMA" binlog_file)" || return "$EXIT_INTEGRITY"
    PITR_BINLOG_POSITION="$(backup_manifest_schema_field "$PITR_RUN_DIR/manifest" "$PITR_SOURCE_SCHEMA" binlog_position)" || return "$EXIT_INTEGRITY"
    BACKUP_PHASE=validating_binlogs
    pitr_validate_sequence "$start_file" || return $?
    pitr_verify_no_coverage_gap || return $?
    pitr_query_preflight || return $?
    BACKUP_PHASE=restoring_full
    pitr_replay_target_notice
    backup_configure_sidecar_binlog "$PITR_SOURCE_SCHEMA" "$PITR_TARGET_SCHEMA"
    backup_log info pitr_started run_id "$(basename "$PITR_RUN_DIR")" source_schema "$PITR_SOURCE_SCHEMA" target_schema "$PITR_TARGET_SCHEMA" target_time "$PITR_TARGET_TIME" binlog_count "${#PITR_BINLOG_FILES[@]}" force_overwrite "$PITR_FORCE"
    backup_restore_artifact "$PITR_RUN_DIR" "$PITR_SOURCE_SCHEMA" "$PITR_TARGET_SCHEMA" "$PITR_FORCE" restore || return $?
    BACKUP_PHASE=replaying_binlogs
    pitr_replay || return $?
    BACKUP_PHASE=row_summary
    summary="$(backup_schema_row_summary restore "$PITR_TARGET_SCHEMA")" || return "$EXIT_RESTORE"
    IFS=$'\t' read -r PITR_TABLE_COUNT PITR_ROW_COUNT <<< "$summary"
    backup_log info pitr_completed run_id "$(basename "$PITR_RUN_DIR")" binlog_count "${#PITR_BINLOG_FILES[@]}" stop_time "$PITR_TARGET_TIME" table_count "$PITR_TABLE_COUNT" row_count "$PITR_ROW_COUNT" applied_row_events "$PITR_APPLIED_EVENTS"
}

main() {
    local exit_code=0
    pitr_run "$@" || exit_code=$?
    backup_finish "$exit_code" pitr_summary run_id "$(basename "${PITR_RUN_DIR:-none}")" source_schema "${PITR_SOURCE_SCHEMA:-unknown}" target_schema "${PITR_TARGET_SCHEMA:-unknown}" binlogs_replayed "${#PITR_BINLOG_FILES[@]}" stop_time "${PITR_TARGET_TIME:-unknown}" table_count "$PITR_TABLE_COUNT" row_count "$PITR_ROW_COUNT" expected_row_events "$PITR_EXPECTED_EVENTS" applied_row_events "$PITR_APPLIED_EVENTS"
    return "$exit_code"
}

main "$@"
