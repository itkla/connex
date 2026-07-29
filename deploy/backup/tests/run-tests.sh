#!/bin/bash
#
# Offline regression tests for the Connex backup shell tooling. They need no
# MySQL, Docker, or root: each case sources the real scripts with `main "$@"`
# stripped, stubs the few functions that would talk to a server, and drives the
# selection, coverage, and retention logic against a sandbox backup root.
#
# The sandbox parent is run through the real backup_validate_absolute_path
# before anything is created, so it cannot live under /tmp any more than a
# production backup root can; override it with CONNEX_BACKUP_TEST_ROOT if
# /var/tmp is unsuitable.
#
# Usage: deploy/backup/tests/run-tests.sh

set -uo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="$(cd "$TESTS_DIR/.." && pwd)"
SANDBOX_PARENT="${CONNEX_BACKUP_TEST_ROOT:-/var/tmp/connex-backup-tests}"
FAILURES=0

if ! (
    # shellcheck source=deploy/backup/connex-backup-lib.sh
    source "$BACKUP_DIR/connex-backup-lib.sh"
    backup_validate_absolute_path CONNEX_BACKUP_TEST_ROOT "$SANDBOX_PARENT"
); then
    printf 'harness error: sandbox parent %s is not a valid backup root\n' "$SANDBOX_PARENT" >&2
    exit 1
fi

mkdir -p "$SANDBOX_PARENT"
SANDBOX="$(mktemp -d "$SANDBOX_PARENT/run.XXXXXX")"
trap 'rm -rf "$SANDBOX"' EXIT

strip_main() {
    local source_file="$1"
    local destination="$2"
    awk '$0 != "main \"$@\"" { print }' "$source_file" > "$destination"
    if [ "$(wc -l < "$source_file")" -eq "$(wc -l < "$destination")" ]; then
        printf 'harness error: no main invocation found in %s\n' "$source_file" >&2
        exit 1
    fi
}

cp "$BACKUP_DIR/connex-backup-lib.sh" "$SANDBOX/connex-backup-lib.sh"
strip_main "$BACKUP_DIR/connex-binlog-archive.sh" "$SANDBOX/archive-lib.sh"
strip_main "$BACKUP_DIR/connex-backup-prune.sh" "$SANDBOX/prune-lib.sh"
strip_main "$BACKUP_DIR/connex-restore-pitr.sh" "$SANDBOX/pitr-lib.sh"

assert_status() {
    local label="$1"
    local expected="$2"
    local actual="$3"
    if [ "$expected" != "$actual" ]; then
        printf 'assert_status %s: expected %s got %s\n' "$label" "$expected" "$actual"
        return 1
    fi
}

assert_equals() {
    local label="$1"
    local expected="$2"
    local actual="$3"
    if [ "$expected" != "$actual" ]; then
        printf 'assert_equals %s: expected [%s] got [%s]\n' "$label" "$expected" "$actual"
        return 1
    fi
}

assert_contains() {
    local label="$1"
    local needle="$2"
    local file="$3"
    if ! grep -qF -- "$needle" "$file"; then
        printf 'assert_contains %s: missing [%s] in:\n%s\n' "$label" "$needle" "$(cat "$file")"
        return 1
    fi
}

assert_absent() {
    local label="$1"
    local needle="$2"
    local file="$3"
    if grep -qF -- "$needle" "$file"; then
        printf 'assert_absent %s: unexpected [%s] in:\n%s\n' "$label" "$needle" "$(cat "$file")"
        return 1
    fi
}

assert_file_exists() {
    local label="$1"
    local path="$2"
    if [ ! -e "$path" ]; then
        printf 'assert_file_exists %s: missing %s\n' "$label" "$path"
        return 1
    fi
}

assert_file_missing() {
    local label="$1"
    local path="$2"
    if [ -e "$path" ]; then
        printf 'assert_file_missing %s: still present %s\n' "$label" "$path"
        return 1
    fi
}

write_fake_binlog() {
    local path="$1"
    printf '\xfebin' > "$path"
    printf 'connex-test-payload\n' >> "$path"
}

write_binlog_sidecars() {
    local raw="$1"
    local metadata="$2"
    local checksum="$3"
    local destination="$4"
    local created_epoch="$5"
    local hash size
    hash="$(sha256sum "$raw" | awk '{print $1}')"
    size="$(stat -c '%s' "$raw")"
    printf '%s  %s\n' "$hash" "$(basename "$destination")" > "$checksum"
    {
        printf 'metadata_version\t1\n'
        printf 'file\t%s\n' "$(basename "$destination")"
        printf 'server_uuid\ttest-uuid\n'
        printf 'server_size\t%s\n' "$size"
        printf 'local_size\t%s\n' "$size"
        printf 'sha256\t%s\n' "$hash"
        printf 'file_created_epoch\t%s\n' "$created_epoch"
        printf 'last_event_epoch\t%s\n' "$((created_epoch + 600))"
    } > "$metadata"
}

case_schema_selection() {
    set +e
    # shellcheck source=deploy/backup/connex-backup-lib.sh
    source "$SANDBOX/connex-backup-lib.sh"
    local log="$SANDBOX/schema.log"
    local status
    CONNEX_BACKUP_SCHEMA_INCLUDE=
    CONNEX_BACKUP_SCHEMA_EXCLUDE=information_schema,performance_schema,mysql,sys
    CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE=

    : > "$log"
    backup_schema_selected connexdb >> "$log"
    assert_status normal_schema 0 "$?" || return 1

    : > "$log"
    backup_schema_selected mysql >> "$log"
    assert_status excluded_schema 1 "$?" || return 1

    : > "$log"
    backup_schema_selected connex_verify_20260101120000_1 >> "$log"
    status=$?
    assert_status scratch_schema 1 "$status" || return 1
    assert_contains scratch_logged 'reason=restore_verify_scratch' "$log" || return 1

    : > "$log"
    backup_schema_selected connex_verify_prod >> "$log"
    status=$?
    assert_status scratch_shaped_without_include 1 "$status" || return 1
    assert_contains scratch_shaped_logged 'schema=connex_verify_prod' "$log" || return 1

    CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE=connex_verify_prod
    : > "$log"
    backup_schema_selected connex_verify_prod >> "$log"
    status=$?
    assert_status scratch_override_wins 0 "$status" || return 1
    assert_equals scratch_override_silent '' "$(cat "$log")" || return 1

    : > "$log"
    backup_schema_selected connexdb >> "$log"
    assert_status scratch_override_keeps_other_schemas 0 "$?" || return 1
    CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE=

    CONNEX_BACKUP_SCHEMA_INCLUDE=connex_verify_prod
    : > "$log"
    backup_schema_selected connex_verify_prod >> "$log"
    status=$?
    assert_status explicit_include_wins 0 "$status" || return 1
    assert_equals no_log_for_selected '' "$(cat "$log")" || return 1

    : > "$log"
    backup_schema_selected connexdb >> "$log"
    assert_status outside_include 1 "$?" || return 1

    CONNEX_BACKUP_SCHEMA_INCLUDE=connexdb,mysql
    : > "$log"
    backup_schema_selected mysql >> "$log"
    status=$?
    assert_status include_and_exclude 1 "$status" || return 1
    assert_contains include_conflict_logged 'reason=include_overridden_by_exclude' "$log" || return 1
}

case_pitr_filtered_statements() {
    set +e
    # shellcheck source=deploy/backup/connex-restore-pitr.sh
    source "$SANDBOX/pitr-lib.sh"
    local log="$SANDBOX/pitr-filtered.log"
    local unfiltered="$SANDBOX/unfiltered.decode"
    local filtered="$SANDBOX/filtered.decode"
    local status

    {
        printf '#260101 12:00:00 server id 1  end_log_pos 100 \tQuery\tthread_id=5\texec_time=0\terror_code=0\n'
        printf 'SET TIMESTAMP=1767268800/*!*/;\n'
        printf 'ALTER TABLE src.foo ADD COLUMN c INT DEFAULT 100%%\n'
        printf '/*!*/;\n'
        printf '#260101 12:00:01 server id 1  end_log_pos 200 \tQuery\tthread_id=6\texec_time=0\terror_code=0\n'
        printf 'SET TIMESTAMP=1767268801/*!*/;\n'
        printf 'BEGIN\n'
        printf '/*!*/;\n'
    } > "$unfiltered"
    : > "$filtered"

    backup_mysqlbinlog_local() {
        cat "$filtered"
    }

    PITR_SOURCE_SCHEMA=src
    PITR_TARGET_TIME='2026-01-01 13:00:00'
    PITR_BINLOG_POSITION=4
    PITR_BINLOG_FILES=("$SANDBOX/binlog.000001")
    PITR_DECODE_SCRATCH_FILE="$unfiltered"

    : > "$log"
    pitr_verify_no_statement_is_filtered_away >> "$log" 2>&1
    status=$?
    assert_status empty_filtered_decode_with_source_reference 1 "$status" || return 1
    assert_contains refusal_reason 'reason=qualified_statement_without_matching_default_database' "$log" || return 1
    assert_contains dropped_count 'dropped_events=1' "$log" || return 1
    assert_contains dropped_hash 'dropped_first_hash=' "$log" || return 1

    {
        printf '#260101 12:00:00 server id 1  end_log_pos 100 \tQuery\tthread_id=5\texec_time=0\terror_code=0\n'
        printf 'SET TIMESTAMP=1767268800/*!*/;\n'
        printf 'ALTER TABLE other.bar ADD COLUMN c INT\n'
        printf '/*!*/;\n'
        printf '#260101 12:00:01 server id 1  end_log_pos 200 \tQuery\tthread_id=6\texec_time=0\terror_code=0\n'
        printf 'SET TIMESTAMP=1767268801/*!*/;\n'
        printf 'BEGIN\n'
        printf '/*!*/;\n'
    } > "$unfiltered"

    : > "$log"
    pitr_verify_no_statement_is_filtered_away >> "$log" 2>&1
    status=$?
    assert_status idle_source_schema_window 0 "$status" || return 1
    assert_equals idle_window_silent '' "$(cat "$log")" || return 1

    {
        printf '#260101 12:00:00 server id 1  end_log_pos 100 \tQuery\tthread_id=5\texec_time=0\terror_code=0\n'
        printf 'SET TIMESTAMP=1767268800/*!*/;\n'
        printf 'ALTER TABLE src.foo ADD COLUMN c INT\n'
        printf '/*!*/;\n'
        printf '#260101 12:00:01 server id 1  end_log_pos 200 \tQuery\tthread_id=6\texec_time=0\terror_code=0\n'
        printf 'SET TIMESTAMP=1767268801/*!*/;\n'
        printf "ALTER TABLE other.bar COMMENT = '\x00binary'\n"
        printf '/*!*/;\n'
    } > "$unfiltered"
    if [ "$(tr -dc '\0' < "$unfiltered" | wc -c)" -eq 0 ]; then
        printf 'harness error: the decoded window lost its NUL byte\n'
        return 1
    fi

    : > "$log"
    pitr_verify_no_statement_is_filtered_away >> "$log" 2>&1
    status=$?
    assert_status nul_byte_does_not_blind_the_guard 1 "$status" || return 1
    assert_contains nul_refusal_reason 'reason=qualified_statement_without_matching_default_database' "$log" || return 1
    assert_contains nul_dropped_count 'dropped_events=1' "$log" || return 1

    {
        printf '#260101 12:00:00 server id 1  end_log_pos 100 \tQuery\tthread_id=5\texec_time=0\terror_code=0\n'
        printf "use \`other\`/*!*/;\n"
        printf 'SET TIMESTAMP=1767268800/*!*/;\n'
        printf 'ALTER TABLE src.foo\n'
        printf 'ADD COLUMN lost_column INT\n'
        printf '/*!*/;\n'
        printf '#260101 12:00:01 server id 1  end_log_pos 200 \tQuery\tthread_id=6\texec_time=0\terror_code=0\n'
        printf "use \`src\`/*!*/;\n"
        printf 'SET TIMESTAMP=1767268801/*!*/;\n'
        printf 'ALTER TABLE src.foo\n'
        printf 'ADD COLUMN kept_column INT\n'
        printf '/*!*/;\n'
    } > "$unfiltered"
    {
        printf '#260101 12:00:01 server id 1  end_log_pos 200 \tQuery\tthread_id=6\texec_time=0\terror_code=0\n'
        printf "use \`src\`/*!*/;\n"
        printf 'SET TIMESTAMP=1767268801/*!*/;\n'
        printf 'ALTER TABLE src.foo\n'
        printf 'ADD COLUMN kept_column INT\n'
        printf '/*!*/;\n'
    } > "$filtered"

    : > "$log"
    pitr_verify_no_statement_is_filtered_away >> "$log" 2>&1
    status=$?
    assert_status multiline_event_loss_refused 1 "$status" || return 1
    assert_contains multiline_event_loss_reason 'reason=qualified_statement_without_matching_default_database' "$log" || return 1

    {
        printf '#260101 12:00:00 server id 1  end_log_pos 100 \tQuery\tthread_id=5\texec_time=0\terror_code=0\n'
        printf "use \`src\`/*!*/;\n"
        printf 'SET TIMESTAMP=1767268800/*!*/;\n'
        printf 'ALTER TABLE src.foo ADD COLUMN duplicate_guard INT\n'
        printf '/*!*/;\n'
        printf '#260101 12:00:01 server id 1  end_log_pos 200 \tQuery\tthread_id=6\texec_time=0\terror_code=0\n'
        printf "use \`src\`/*!*/;\n"
        printf 'SET TIMESTAMP=1767268801/*!*/;\n'
        printf 'ALTER TABLE src.foo ADD COLUMN duplicate_guard INT\n'
        printf '/*!*/;\n'
    } > "$unfiltered"
    {
        printf '#260101 12:00:01 server id 1  end_log_pos 200 \tQuery\tthread_id=6\texec_time=0\terror_code=0\n'
        printf "use \`src\`/*!*/;\n"
        printf 'SET TIMESTAMP=1767268801/*!*/;\n'
        printf 'ALTER TABLE src.foo ADD COLUMN duplicate_guard INT\n'
        printf '/*!*/;\n'
    } > "$filtered"

    : > "$log"
    pitr_verify_no_statement_is_filtered_away >> "$log" 2>&1
    status=$?
    assert_status duplicate_event_loss_refused 1 "$status" || return 1
    assert_contains duplicate_event_loss_count 'dropped_events=1' "$log" || return 1

    {
        printf '#260101 12:00:00 server id 1  end_log_pos 100 \tQuery\tthread_id=5\texec_time=0\terror_code=0\n'
        printf "use \`src\`/*!*/;\n"
        printf 'SET TIMESTAMP=1767268800/*!*/;\n'
        printf 'ALTER TABLE src.foo ADD COLUMN incomplete_guard INT\n'
    } > "$unfiltered"
    : > "$filtered"

    : > "$log"
    pitr_verify_no_statement_is_filtered_away >> "$log" 2>&1
    status=$?
    assert_status incomplete_event_refused 1 "$status" || return 1
    assert_contains incomplete_event_reason 'reason=query_event_extraction' "$log" || return 1
}

case_pitr_coverage_gap_guard() {
    set +e
    # shellcheck source=deploy/backup/connex-restore-pitr.sh
    source "$SANDBOX/pitr-lib.sh"
    local root="$SANDBOX/pitr-root"
    local log="$SANDBOX/pitr-gap.log"
    local marker
    local status
    mkdir -p "$root/binlog"
    CONNEX_BACKUP_ROOT="$root"
    marker="$root/binlog/coverage-gap"
    PITR_DUMP_CAPTURE_EPOCH=2000
    PITR_TARGET_EPOCH=3000

    : > "$log"
    pitr_verify_no_coverage_gap >> "$log" 2>&1
    assert_status no_marker 0 "$?" || return 1

    printf 'gap\tbinlog.000001\t100\t900\t2026-01-01T00:00:00Z\n' > "$marker"
    : > "$log"
    pitr_verify_no_coverage_gap >> "$log" 2>&1
    assert_status gap_before_dump 0 "$?" || return 1

    printf 'gap\tbinlog.000002\t2500\t2600\t2026-01-02T00:00:00Z\n' >> "$marker"
    : > "$log"
    pitr_verify_no_coverage_gap >> "$log" 2>&1
    status=$?
    assert_status gap_inside_window 74 "$status" || return 1
    assert_contains gap_reason 'reason=archive_coverage_gap' "$log" || return 1
    assert_contains gap_file 'file=binlog.000002' "$log" || return 1

    printf 'gap\tbinlog.000003\tnot-an-epoch\t2600\t2026-01-02T00:00:00Z\n' > "$marker"
    : > "$log"
    pitr_verify_no_coverage_gap >> "$log" 2>&1
    status=$?
    assert_status unreadable_record 74 "$status" || return 1
    assert_contains unreadable_reason 'reason=unreadable_coverage_gap_record' "$log" || return 1

    printf 'unknown\tbinlog.000004\t2500\t2600\t2026-01-02T00:00:00Z\n' > "$marker"
    : > "$log"
    pitr_verify_no_coverage_gap >> "$log" 2>&1
    status=$?
    assert_status unknown_record 74 "$status" || return 1
    assert_contains unknown_reason 'reason=unreadable_coverage_gap_record' "$log" || return 1

    printf 'gap\tbinlog.000005\t2500' > "$marker"
    : > "$log"
    pitr_verify_no_coverage_gap >> "$log" 2>&1
    status=$?
    assert_status truncated_record 74 "$status" || return 1
    assert_contains truncated_reason 'reason=unreadable_coverage_gap_record' "$log" || return 1

    printf 'gap\tbinlog.000006\t2500\t2600\t2026-01-02T00:00:00Z\textra\n' > "$marker"
    : > "$log"
    pitr_verify_no_coverage_gap >> "$log" 2>&1
    status=$?
    assert_status extra_field_record 74 "$status" || return 1
    assert_contains extra_field_reason 'reason=unreadable_coverage_gap_record' "$log" || return 1

    printf 'gap\tbinlog.000007\t2600\t2500\t2026-01-02T00:00:00Z\n' > "$marker"
    : > "$log"
    pitr_verify_no_coverage_gap >> "$log" 2>&1
    status=$?
    assert_status inverted_interval 74 "$status" || return 1
    assert_contains inverted_interval_reason 'reason=unreadable_coverage_gap_record' "$log" || return 1
}

case_archive_rebases_missing_cursor() {
    set +e
    # shellcheck source=deploy/backup/connex-binlog-archive.sh
    source "$SANDBOX/archive-lib.sh"
    local root="$SANDBOX/archive-rebase"
    local log="$SANDBOX/archive-rebase.log"
    local fetched="$SANDBOX/archive-rebase.fetched"
    local state created_epoch
    mkdir -p "$root/binlog"
    CONNEX_BACKUP_ROOT="$root"
    CONNEX_BACKUP_RETENTION_DAYS=30
    CONNEX_BACKUP_BINLOG_FLUSH=true
    CONNEX_BACKUP_BINLOG_FETCH_MODE=stream
    state="$root/binlog/archive-state"
    created_epoch=1767830400
    {
        printf 'state_version\t1\n'
        printf 'server_uuid\ttest-uuid\n'
        printf 'last_closed_file\tbinlog.000001\n'
        printf 'active_file\tbinlog.000002\n'
        printf 'coverage_through_utc\t2026-01-01T00:00:00Z\n'
        printf 'coverage_through_epoch\t1767225600\n'
        printf 'flush_enabled\ttrue\n'
        printf 'fetch_mode\tstream\n'
    } > "$state"

    : > "$fetched"
    archive_fetch_file() {
        printf '%s\n' "$1" >> "$fetched"
        {
            printf 'metadata_version\t1\n'
            printf 'file\t%s\n' "$1"
            printf 'file_created_epoch\t%s\n' "$created_epoch"
            printf 'last_event_epoch\t%s\n' "$((created_epoch + 600))"
        } > "$CONNEX_BACKUP_ROOT/binlog/$1.meta"
        return 0
    }

    ARCHIVE_SERVER_UUID=test-uuid
    ARCHIVE_COVERAGE_EPOCH=1900000000
    ARCHIVE_COVERAGE_UTC=2030-03-17T18:26:40Z
    ARCHIVE_ACTIVE_FILE=binlog.000006
    ARCHIVE_SERVER_LOGS=(
        "$(printf 'binlog.000004\t120\tNo')"
        "$(printf 'binlog.000005\t120\tNo')"
        "$(printf 'binlog.000006\t60\tNo')"
    )

    : > "$log"
    archive_process_logs >> "$log" 2>&1
    assert_status process_returns_for_publish 0 "$?" || return 1
    assert_contains missing_cursor_logged 'reason=last_closed_file_missing' "$log" || return 1
    assert_contains rebase_logged 'rebased_to=binlog.000005' "$log" || return 1
    assert_equals deferred_exit 71 "$ARCHIVE_DEFERRED_EXIT" || return 1
    assert_equals rebased_cursor binlog.000005 "$ARCHIVE_LAST_CLOSED" || return 1
    assert_equals coverage_not_advanced 1767225600 "$ARCHIVE_COVERAGE_EPOCH" || return 1
    assert_equals available_logs_archived "$(printf 'binlog.000004\nbinlog.000005')" "$(cat "$fetched")" || return 1
    assert_file_exists gap_marker "$root/binlog/coverage-gap" || return 1
    assert_contains gap_record 'binlog.000001' "$root/binlog/coverage-gap" || return 1
    assert_contains gap_scoped_to_first_available "$(printf 'gap\tbinlog.000001\t1767225600\t%s\t' "$created_epoch")" "$root/binlog/coverage-gap" || return 1

    archive_publish_state || return 1
    assert_contains published_cursor "$(printf 'last_closed_file\tbinlog.000005')" "$state" || return 1
    assert_contains published_coverage "$(printf 'coverage_through_epoch\t1767225600')" "$state" || return 1

    ARCHIVE_DEFERRED_EXIT=0
    ARCHIVE_DEFERRED_PHASE=
    ARCHIVE_COVERAGE_PINNED=false
    ARCHIVE_LAST_CLOSED=
    : > "$fetched"
    : > "$log"
    archive_process_logs >> "$log" 2>&1
    assert_status second_run_status 0 "$?" || return 1
    assert_equals second_run_not_stalled 0 "$ARCHIVE_DEFERRED_EXIT" || return 1
    assert_equals second_run_cursor binlog.000005 "$ARCHIVE_LAST_CLOSED" || return 1
    assert_equals second_run_fetches_nothing '' "$(cat "$fetched")" || return 1
    assert_absent second_run_clean 'last_closed_file_missing' "$log" || return 1
}

case_archive_retention_gap() {
    set +e
    # shellcheck source=deploy/backup/connex-binlog-archive.sh
    source "$SANDBOX/archive-lib.sh"
    local root="$SANDBOX/archive-gap"
    local log="$SANDBOX/archive-gap.log"
    local now old_created old_last recent_created recent_last state
    mkdir -p "$root/binlog" "$root/source"
    CONNEX_BACKUP_ROOT="$root"
    CONNEX_BACKUP_RETENTION_DAYS=30
    CONNEX_BACKUP_BINLOG_FLUSH=true
    CONNEX_BACKUP_BINLOG_FETCH_MODE=stream
    CONNEX_BACKUP_BINLOG_DIR="$root/source"
    now="$(date +%s)"
    old_created=$((now - 2548800))
    old_last=$((old_created + 600))
    recent_created=$((now - 3600))
    recent_last=$((recent_created + 600))
    state="$root/binlog/archive-state"
    {
        printf 'state_version\t1\n'
        printf 'server_uuid\ttest-uuid\n'
        printf 'last_closed_file\t\n'
        printf 'active_file\tbinlog.000001\n'
        printf 'coverage_through_utc\t2026-01-01T00:00:00Z\n'
        printf 'coverage_through_epoch\t1767225600\n'
        printf 'flush_enabled\ttrue\n'
        printf 'fetch_mode\tstream\n'
    } > "$state"

    write_fake_binlog "$root/source/binlog.000001"
    write_fake_binlog "$root/source/binlog.000002"
    write_fake_binlog "$root/source/binlog.000003"

    backup_stream_binlog() {
        cat "$1"
    }
    backup_stat_binlog() {
        case "$(basename "$1")" in
            binlog.000001)
                printf '%s:%s\n' "$old_created" "$old_last"
                ;;
            binlog.000002)
                printf '%s:%s\n' "$recent_created" "$recent_last"
                ;;
            *)
                return 1
                ;;
        esac
    }

    ARCHIVE_SERVER_UUID=test-uuid
    ARCHIVE_COVERAGE_EPOCH="$now"
    ARCHIVE_COVERAGE_UTC="$(date -u -d "@$now" +%Y-%m-%dT%H:%M:%SZ)"
    ARCHIVE_ACTIVE_FILE=binlog.000003
    ARCHIVE_SERVER_LOGS=(
        "$(printf 'binlog.000001\t24\tNo')"
        "$(printf 'binlog.000002\t24\tNo')"
        "$(printf 'binlog.000003\t24\tNo')"
    )

    : > "$log"
    archive_process_logs >> "$log" 2>&1
    assert_status gap_returns_for_publish 0 "$?" || return 1
    assert_contains skipped_logged 'reason=outside_retention' "$log" || return 1
    assert_contains gap_logged 'reason=unarchivable_file_past_retention' "$log" || return 1
    assert_equals gap_deferred_exit 71 "$ARCHIVE_DEFERRED_EXIT" || return 1
    assert_equals gap_cursor binlog.000002 "$ARCHIVE_LAST_CLOSED" || return 1
    assert_equals gap_coverage_pinned 1767225600 "$ARCHIVE_COVERAGE_EPOCH" || return 1
    assert_file_missing unarchived_old_file "$root/binlog/binlog.000001" || return 1
    assert_file_exists archived_recent_file "$root/binlog/binlog.000002" || return 1
    assert_contains gap_marker_record 'binlog.000001' "$root/binlog/coverage-gap" || return 1

    assert_equals gap_coverage_utc "$(date -u -d '@1767225600' +%Y-%m-%dT%H:%M:%SZ)" "$ARCHIVE_COVERAGE_UTC" || return 1

    archive_publish_state || return 1
    assert_contains gap_published_coverage "$(printf 'coverage_through_epoch\t1767225600')" "$state" || return 1

    # A first-ever run has no published coverage to fall back on, so a hole
    # leaves coverage at 0 and PITR fail-closed until the next clean run.
    printf 'state_version\t1\n' > "$state"
    ARCHIVE_COVERAGE_PINNED=false
    archive_pin_coverage
    assert_equals pin_without_prior_coverage 0 "$ARCHIVE_COVERAGE_EPOCH" || return 1
    assert_equals pin_without_prior_coverage_utc '' "$ARCHIVE_COVERAGE_UTC" || return 1

    archive_verify_server_retention >> "$log" 2>&1
    assert_status retention_warns_below_ceiling 0 "$?" || return 1
    assert_contains retention_warning 'reason=server_purge_lagging' "$log" || return 1
}

case_prune_orphan_binlogs() {
    set +e
    # shellcheck source=deploy/backup/connex-backup-prune.sh
    source "$SANDBOX/prune-lib.sh"
    local root="$SANDBOX/prune-root"
    local log="$SANDBOX/prune.log"
    local binlog_dir hash now old_created
    mkdir -p "$root/binlog"
    CONNEX_BACKUP_ROOT="$root"
    backup_set_defaults
    binlog_dir="$root/binlog"
    now="$(date +%s)"
    old_created=$((now - 2592000))

    {
        printf 'state_version\t1\n'
        printf 'server_uuid\ttest-uuid\n'
        printf 'last_closed_file\tbinlog.000012\n'
        printf 'active_file\tbinlog.000013\n'
    } > "$binlog_dir/archive-state"

    write_fake_binlog "$binlog_dir/binlog.000009"
    touch -d '25 hours ago' "$binlog_dir/binlog.000009"
    write_fake_binlog "$binlog_dir/binlog.0000013"
    touch -d '25 hours ago' "$binlog_dir/binlog.0000013"
    write_fake_binlog "$binlog_dir/binlog.000010"
    touch -d '1 hour ago' "$binlog_dir/binlog.000010"
    printf 'operator note\n' > "$binlog_dir/operator-notes.txt"
    touch -d '40 days ago' "$binlog_dir/operator-notes.txt"
    write_fake_binlog "$binlog_dir/legacy-export.202401"
    touch -d '40 days ago' "$binlog_dir/legacy-export.202401"
    printf 'gap\tbinlog.000001\t1\t2\t2026-01-01T00:00:00Z\n' > "$binlog_dir/coverage-gap"
    touch -d '40 days ago' "$binlog_dir/coverage-gap"

    write_fake_binlog "$binlog_dir/binlog.000011"
    hash="$(sha256sum "$binlog_dir/binlog.000011" | awk '{print $1}')"
    printf '%s  binlog.000011\n' "$hash" > "$binlog_dir/binlog.000011.sha256"
    {
        printf 'metadata_version\t1\n'
        printf 'file\tbinlog.000011\n'
        printf 'sha256\t%s\n' "$hash"
        printf 'file_created_epoch\t%s\n' "$old_created"
    } > "$binlog_dir/binlog.000011.meta"

    write_fake_binlog "$binlog_dir/binlog.000012"
    hash="$(sha256sum "$binlog_dir/binlog.000012" | awk '{print $1}')"
    printf '%s  binlog.000012\n' "$hash" > "$binlog_dir/binlog.000012.sha256"
    {
        printf 'metadata_version\t1\n'
        printf 'file\tbinlog.000012\n'
        printf 'sha256\t%s\n' "$hash"
        printf 'file_created_epoch\t%s\n' "$now"
    } > "$binlog_dir/binlog.000012.meta"

    : > "$log"
    prune_binlogs >> "$log" 2>&1
    assert_status prune_status 0 "$?" || return 1
    assert_file_missing expired_orphan_removed "$binlog_dir/binlog.000009" || return 1
    assert_file_missing long_suffix_orphan_removed "$binlog_dir/binlog.0000013" || return 1
    assert_file_exists young_orphan_kept "$binlog_dir/binlog.000010" || return 1
    assert_file_exists operator_file_kept "$binlog_dir/operator-notes.txt" || return 1
    assert_file_exists operator_binlog_shaped_file_kept "$binlog_dir/legacy-export.202401" || return 1
    assert_contains operator_binlog_shaped_file_warned 'file=legacy-export.202401' "$log" || return 1
    assert_file_exists coverage_gap_kept "$binlog_dir/coverage-gap" || return 1
    assert_file_exists archive_state_kept "$binlog_dir/archive-state" || return 1
    assert_file_missing expired_triplet_removed "$binlog_dir/binlog.000011" || return 1
    assert_file_exists recent_triplet_kept "$binlog_dir/binlog.000012" || return 1
    assert_contains orphan_reason 'reason=orphaned_binlog_without_metadata' "$log" || return 1
    assert_contains operator_file_warned 'file=operator-notes.txt' "$log" || return 1
    assert_absent coverage_gap_not_warned 'file=coverage-gap' "$log" || return 1
}

case_interrupted_publication_recovery() {
    set +e
    source "$SANDBOX/prune-lib.sh"
    local root="$SANDBOX/publication-recovery"
    local binlog_dir="$root/binlog"
    local log="$SANDBOX/publication-recovery.log"
    local destination staging_raw outside
    mkdir -p "$binlog_dir"
    CONNEX_BACKUP_ROOT="$root"
    backup_set_defaults

    destination="$binlog_dir/binlog.000020"
    write_fake_binlog "$destination"
    write_binlog_sidecars \
        "$destination" "$destination.meta.pending" "$destination.sha256.pending" \
        "$destination" 1767225600
    : > "$log"
    prune_staging >> "$log" 2>&1
    assert_file_exists interrupted_raw_preserved "$destination" || return 1
    assert_file_exists interrupted_meta_finalized "$destination.meta" || return 1
    assert_file_exists interrupted_checksum_finalized "$destination.sha256" || return 1
    assert_file_missing interrupted_meta_pending_removed "$destination.meta.pending" || return 1
    assert_file_missing interrupted_checksum_pending_removed "$destination.sha256.pending" || return 1
    backup_validate_binlog_triplet "$destination" || return 1

    destination="$binlog_dir/binlog.000021"
    write_fake_binlog "$destination.pending"
    write_binlog_sidecars \
        "$destination.pending" "$destination.meta" "$destination.sha256.pending" \
        "$destination" 1767225600
    : > "$log"
    prune_staging >> "$log" 2>&1
    assert_file_exists mixed_raw_finalized "$destination" || return 1
    assert_file_exists mixed_meta_preserved "$destination.meta" || return 1
    assert_file_exists mixed_checksum_finalized "$destination.sha256" || return 1
    backup_validate_binlog_triplet "$destination" || return 1

    destination="$binlog_dir/binlog.000022"
    mkdir "$binlog_dir/.fetch.recovery"
    staging_raw="$binlog_dir/.fetch.recovery/binlog.000022"
    write_fake_binlog "$staging_raw"
    write_binlog_sidecars \
        "$staging_raw" "$destination.meta.pending" "$destination.sha256.pending" \
        "$destination" 1767225600
    : > "$log"
    prune_staging >> "$log" 2>&1
    assert_file_exists staged_raw_finalized "$destination" || return 1
    assert_file_missing staged_directory_removed "$binlog_dir/.fetch.recovery" || return 1
    backup_validate_binlog_triplet "$destination" || return 1

    destination="$binlog_dir/binlog.000023"
    write_fake_binlog "$destination.pending"
    : > "$log"
    prune_staging >> "$log" 2>&1
    assert_file_exists incomplete_raw_retained "$destination.pending" || return 1
    assert_contains incomplete_raw_logged 'reason=interrupted_binlog_publication' "$log" || return 1

    destination="$binlog_dir/binlog.000024"
    outside="$root/outside"
    write_fake_binlog "$outside"
    ln -s "$outside" "$destination.pending"
    : > "$log"
    prune_staging >> "$log" 2>&1
    if [ ! -L "$destination.pending" ]; then
        printf 'pending symlink was unexpectedly replaced\n'
        return 1
    fi
    assert_file_exists symlink_target_preserved "$outside" || return 1
    assert_contains symlink_pending_logged 'reason=interrupted_binlog_publication' "$log" || return 1
}

run_case() {
    local name="$1"
    local output status=0
    output="$("$name")" || status=$?
    if [ "$status" -eq 0 ]; then
        printf 'ok   %s\n' "$name"
        return 0
    fi
    printf 'FAIL %s\n%s\n' "$name" "$output"
    FAILURES=$((FAILURES + 1))
}

run_case case_schema_selection
run_case case_pitr_filtered_statements
run_case case_pitr_coverage_gap_guard
run_case case_archive_rebases_missing_cursor
run_case case_archive_retention_gap
run_case case_prune_orphan_binlogs
run_case case_interrupted_publication_recovery

if [ "$FAILURES" -ne 0 ]; then
    printf '%s case(s) failed\n' "$FAILURES" >&2
    exit 1
fi
printf 'all cases passed\n'
