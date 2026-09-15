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
cp "$BACKUP_DIR/shims/docker-client-lib.sh" "$SANDBOX/docker-client-lib.sh"
strip_main "$BACKUP_DIR/connex-binlog-archive.sh" "$SANDBOX/archive-lib.sh"
strip_main "$BACKUP_DIR/connex-backup-prune.sh" "$SANDBOX/prune-lib.sh"
strip_main "$BACKUP_DIR/connex-restore-pitr.sh" "$SANDBOX/pitr-lib.sh"
strip_main "$BACKUP_DIR/install.sh" "$SANDBOX/install-lib.sh"

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

case_installer_migrates_retired_database_network() {
    set +e
    # shellcheck source=deploy/backup/install.sh
    source "$SANDBOX/install-lib.sh"
    local config_root="$SANDBOX/install-config"
    local config_file="$config_root/backup.env"
    local log="$SANDBOX/install-network.log"
    local legacy_network
    mkdir -p "$config_root"
    CONFIG_ROOT="$config_root"

    for legacy_network in connex_default acme_default; do
        printf '%s\n' \
            "CONNEX_BACKUP_DOCKER_NETWORK=$legacy_network" \
            'CONNEX_BACKUP_DB_CONTAINER=custom-db' \
            > "$config_file"
        chmod 0644 "$config_file"
        : > "$log"
        install_migrate_database_network > "$log"
        assert_status "${legacy_network}_migrated" 0 "$?" || return 1
        assert_contains "${legacy_network}_automatic" 'CONNEX_BACKUP_DOCKER_NETWORK=auto' "$config_file" || return 1
        assert_absent "${legacy_network}_removed" "CONNEX_BACKUP_DOCKER_NETWORK=$legacy_network" "$config_file" || return 1
        assert_contains unrelated_setting_preserved 'CONNEX_BACKUP_DB_CONTAINER=custom-db' "$config_file" || return 1
        assert_contains migration_logged 'Migrated legacy backup Docker network' "$log" || return 1
        assert_equals migrated_mode 600 "$(stat -c '%a' "$config_file")" || return 1
    done

    printf '%s\n' 'CONNEX_BACKUP_DOCKER_NETWORK=operator_database' > "$config_file"
    : > "$log"
    install_migrate_database_network > "$log"
    assert_status custom_network_preserved 0 "$?" || return 1
    assert_contains custom_network 'CONNEX_BACKUP_DOCKER_NETWORK=operator_database' "$config_file" || return 1
    assert_equals custom_network_silent '' "$(cat "$log")" || return 1
}

case_docker_client_resolves_and_validates_database_network() {
    set +e
    # shellcheck source=deploy/backup/shims/docker-client-lib.sh
    source "$SANDBOX/docker-client-lib.sh"
    local fake_docker="$SANDBOX/fake-docker"
    local docker_log="$SANDBOX/fake-docker.log"
    local error_log="$SANDBOX/fake-docker-error.log"
    local status
    cat > "$fake_docker" <<'EOF'
#!/bin/bash
set -eu
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$1" in
    inspect)
        printf 'acme_default\nacme_db\n'
        ;;
    network)
        network="${!#}"
        if [ "${3:-}" = --format ]; then
            if [ "${4:-}" = '{{.Name}}' ]; then
                printf '%s\n' "$network"
            else
                case "$network" in
                    acme_default) printf 'default\n' ;;
                    acme_db) printf 'db\n' ;;
                    operator_database) printf 'operator\n' ;;
                    *) exit 1 ;;
                esac
            fi
        else
            case "$network" in
                acme_default|acme_db|operator_database) ;;
                *) exit 1 ;;
            esac
        fi
        ;;
    run)
        ;;
    *)
        exit 1
        ;;
esac
EOF
    chmod 0700 "$fake_docker"
    mkdir -p "$SANDBOX/backup-root" "$SANDBOX/defaults"
    : > "$docker_log"
    SHIM_ENV_LOADED=true
    CONNEX_BACKUP_ROOT="$SANDBOX/backup-root"
    CONNEX_BACKUP_DEFAULTS_DIR="$SANDBOX/defaults"
    CONNEX_BACKUP_DOCKER_IMAGE=mysql:test
    CONNEX_BACKUP_DOCKER_BIN="$fake_docker"
    CONNEX_BACKUP_DB_CONTAINER=acme-db-1
    FAKE_DOCKER_LOG="$docker_log"
    export FAKE_DOCKER_LOG

    CONNEX_BACKUP_DOCKER_NETWORK=auto
    shim_run mysql --host=db
    assert_status automatic_network 0 "$?" || return 1
    assert_contains automatic_project_network '--network acme_db' "$docker_log" || return 1

    : > "$docker_log"
    CONNEX_BACKUP_DOCKER_NETWORK=acme_default
    shim_run mysqlbinlog --version
    assert_status legacy_runtime_network 0 "$?" || return 1
    assert_contains legacy_runtime_discovery '--network acme_db' "$docker_log" || return 1

    : > "$error_log"
    CONNEX_BACKUP_DOCKER_NETWORK=stale_network
    status=0
    shim_run mysqlbinlog --version 2> "$error_log" || status=$?
    assert_status missing_network_fails_closed 64 "$status" || return 1
    assert_contains missing_network_clear_error 'Configured Connex backup Docker network does not exist: stale_network' "$error_log" || return 1

    : > "$error_log"
    CONNEX_BACKUP_DOCKER_NETWORK=operator_database
    status=0
    shim_run mysql --host=db 2> "$error_log" || status=$?
    assert_status mismatched_network_fails_closed 64 "$status" || return 1
    assert_contains mismatch_clear_error 'does not match DB container network acme_db' "$error_log" || return 1
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

case_archive_corrupt_triplet_refetched() {
    set +e
    # shellcheck source=deploy/backup/connex-binlog-archive.sh
    source "$SANDBOX/archive-lib.sh"
    local root="$SANDBOX/archive-corrupt-triplet"
    local log="$SANDBOX/archive-corrupt-triplet.log"
    local file=binlog.000030
    local destination source_file server_size fixture_created_epoch
    mkdir -p "$root/binlog" "$root/source"
    CONNEX_BACKUP_ROOT="$root"
    CONNEX_BACKUP_RETENTION_DAYS=30
    CONNEX_BACKUP_BINLOG_FETCH_MODE=stream
    CONNEX_BACKUP_BINLOG_DIR="$root/source"
    destination="$root/binlog/$file"
    source_file="$root/source/$file"
    fixture_created_epoch=$(( $(date +%s) - 3600 ))

    write_fake_binlog "$source_file"
    cp "$source_file" "$destination"
    write_binlog_sidecars \
        "$destination" "$destination.meta" "$destination.sha256" \
        "$destination" "$fixture_created_epoch"
    printf 'X' | dd of="$destination" bs=1 seek=4 conv=notrunc status=none || return 1
    server_size="$(stat -c '%s' "$source_file")"

    BINLOG_STREAM_COMMAND=(cat)
    BINLOG_STAT_COMMAND=(
        awk -v "range=$fixture_created_epoch:$((fixture_created_epoch + 600))"
        'BEGIN { print range; exit }'
    )

    ARCHIVE_SERVER_UUID=test-uuid
    : > "$log"
    archive_fetch_file "$file" "$server_size" >> "$log" 2>&1
    assert_status corrupt_triplet_refetch_status 0 "$?" || return 1
    cmp -s "$source_file" "$destination" || {
        printf 'corrupt triplet raw was not replaced from the source\n'
        return 1
    }
    backup_validate_binlog_triplet "$destination" "$ARCHIVE_SERVER_UUID" || return 1
    assert_contains corrupt_triplet_refetch_started 'binlog_fetch_started' "$log" || return 1
    assert_absent corrupt_triplet_not_resumed 'binlog_publication_resumed' "$log" || return 1
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
    # shellcheck source=deploy/backup/connex-backup-prune.sh
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

write_recording_docker() {
    cat > "$SANDBOX/recording-docker" <<'EOF'
#!/bin/bash
printf '%s\n' "$@" >> "$RECORDING_DOCKER_LOG"
case "$1" in
    inspect) printf 'fixture-network\n' ;;
    network)
        if [ "${4:-}" = '{{.Name}}' ]; then
            printf 'fixture_db\n'
        else
            printf 'db\n'
        fi
        ;;
    run)
        if [ -n "${RECORDING_DOCKER_OUTPUT:-}" ]; then
            cat "$RECORDING_DOCKER_OUTPUT"
        fi
        ;;
esac
EOF
    chmod 0700 "$SANDBOX/recording-docker"
    export RECORDING_DOCKER_LOG="$SANDBOX/recording-docker.log"
    : > "$RECORDING_DOCKER_LOG"
}

write_binlog_shim_environment() {
    local image="$1"
    cat > "$SANDBOX/binlog.env" <<EOF
CONNEX_BACKUP_DOCKER_BINLOG_IMAGE=$image
CONNEX_BACKUP_DOCKER_BIN=$SANDBOX/recording-docker
CONNEX_BACKUP_ROOT=$SANDBOX/binlog-inputs
CONNEX_BACKUP_DEFAULTS_DIR=$SANDBOX/credentials
CONNEX_BACKUP_DOCKER_MOUNTS=$SANDBOX/credentials:/credentials
EOF
}

case_binlog_shim_refuses_mutable_images_before_docker() {
    write_recording_docker
    local image status
    for image in percona/percona-server:8.4 'client@sha256:abcd' "client@sha256:$(printf 'a%.0s' {1..65})"; do
        write_binlog_shim_environment "$image"
        status=0
        CONNEX_BACKUP_ENV_FILE="$SANDBOX/binlog.env" bash "$BACKUP_DIR/shims/mysqlbinlog" --version > "$SANDBOX/binlog-output" 2>&1 || status=$?
        assert_status unpinned_refused 64 "$status" || return 1
        assert_equals docker_not_executed '' "$(cat "$RECORDING_DOCKER_LOG")" || return 1
    done
}

case_binlog_shim_only_mounts_readonly_binlog_inputs() {
    write_recording_docker
    local input="$SANDBOX/binlog-inputs/mysql-bin.000001"
    local image="client@sha256:$(printf 'a%.0s' {1..64})" status
    mkdir -p "$SANDBOX/binlog-inputs" "$SANDBOX/credentials"
    write_fake_binlog "$input"
    printf '[client]\npassword=synthetic-secret\n' > "$SANDBOX/credentials/source.cnf"
    cp "$SANDBOX/credentials/source.cnf" "$SANDBOX/binlog-inputs/source.cnf"
    printf 'retained\n' > "$SANDBOX/binlog-inputs/sentinel"
    write_binlog_shim_environment "$image"
    CONNEX_BACKUP_ENV_FILE="$SANDBOX/binlog.env" bash "$BACKUP_DIR/shims/mysqlbinlog" --version || return 1
    if grep -qxF -- -v "$RECORDING_DOCKER_LOG"; then return 1; fi
    : > "$RECORDING_DOCKER_LOG"
    CONNEX_BACKUP_ENV_FILE="$SANDBOX/binlog.env" bash "$BACKUP_DIR/shims/mysqlbinlog" --verify-binlog-checksum "$input" || return 1
    assert_contains immutable_image "$image" "$RECORDING_DOCKER_LOG" || return 1
    assert_contains isolated_network 'none' "$RECORDING_DOCKER_LOG" || return 1
    assert_contains read_only_container '--read-only' "$RECORDING_DOCKER_LOG" || return 1
    assert_contains read_only_input "$input:$input:ro" "$RECORDING_DOCKER_LOG" || return 1
    assert_contains no_client_configuration '--no-defaults' "$RECORDING_DOCKER_LOG" || return 1
    assert_absent no_credentials "$SANDBOX/credentials" "$RECORDING_DOCKER_LOG" || return 1
    assert_absent no_backup_tree "$SANDBOX/binlog-inputs:$SANDBOX/binlog-inputs" "$RECORDING_DOCKER_LOG" || return 1
    for input in "$SANDBOX/binlog-inputs/source.cnf" "$SANDBOX/binlog-inputs/sentinel" --read-from-remote-server --defaults-extra-file=source.cnf; do
        : > "$RECORDING_DOCKER_LOG"
        status=0
        CONNEX_BACKUP_ENV_FILE="$SANDBOX/binlog.env" bash "$BACKUP_DIR/shims/mysqlbinlog" "$input" > "$SANDBOX/binlog-output" 2>&1 || status=$?
        assert_status non_binlog_refused 64 "$status" || return 1
        assert_equals docker_not_executed '' "$(cat "$RECORDING_DOCKER_LOG")" || return 1
    done
}

initialize_pitr_shim_fixture() {
    backup_set_defaults
    write_recording_docker
    write_binlog_shim_environment "client@sha256:$(printf 'a%.0s' {1..64})"
    mkdir -p "$SANDBOX/binlog-inputs"
    PITR_BINLOG_FILES=("$SANDBOX/binlog-inputs/mysql-bin.000001" "$SANDBOX/binlog-inputs/mysql-bin.000002")
    write_fake_binlog "${PITR_BINLOG_FILES[0]}"
    write_fake_binlog "${PITR_BINLOG_FILES[1]}"
    PITR_RUN_DIR="$SANDBOX/full/20260101T000000Z"
    export CONNEX_BACKUP_ENV_FILE="$SANDBOX/binlog.env"
    export RECORDING_DOCKER_OUTPUT="$SANDBOX/replay.decode"
    printf "BINLOG '\nY29ubmV4\n'/*!*/;\n" > "$RECORDING_DOCKER_OUTPUT"
    printf 'existing target\n' > "$SANDBOX/restore-target"
    : > "$SANDBOX/restore-effects"
    : > "$SANDBOX/replay-sql"
    export PITR_TEST_SHIM="$BACKUP_DIR/shims/mysqlbinlog"
    export PITR_TEST_FOREIGN_OPTION= PITR_TEST_REWRITE=
    cat > "$SANDBOX/replay-client" <<'EOF'
#!/bin/bash
set -euo pipefail
arguments=()
for argument in "$@"; do
    case "$argument" in
        --require-row-format)
            arguments+=("$argument")
            if [ -n "$PITR_TEST_FOREIGN_OPTION" ]; then
                arguments+=("$PITR_TEST_FOREIGN_OPTION")
            fi
            ;;
        --rewrite-db=*) arguments+=("--rewrite-db=${PITR_TEST_REWRITE:-${argument#*=}}") ;;
        *) arguments+=("$argument") ;;
    esac
done
exec bash "$PITR_TEST_SHIM" "${arguments[@]}"
EOF
    MYSQLBINLOG="bash $SANDBOX/replay-client"

    backup_load_environment() { :; }
    backup_validate_common() { :; }
    backup_validate_restore_profile() { :; }
    backup_prepare_directories() { :; }
    backup_acquire_lock() { :; }
    pitr_select_run() { :; }
    pitr_validate_sequence() { :; }
    pitr_verify_no_coverage_gap() { :; }
    backup_probe_binlog_suppression() { :; }
    backup_manifest_schema_field() {
        case "$3" in
            binlog_file) printf 'mysql-bin.000001\n' ;;
            binlog_position) printf '4\n' ;;
            *) return 1 ;;
        esac
    }
    backup_restore_artifact() {
        assert_equals forced_restore true "$4" || return 1
        cp "$RECORDING_DOCKER_LOG" "$SANDBOX/decoder-before-restore.log"
        printf 'restore\n' >> "$SANDBOX/restore-effects"
        printf 'restored target\n' > "$SANDBOX/restore-target"
    }
    backup_mysql() {
        printf 'replay\n' >> "$SANDBOX/restore-effects"
        cat > "$SANDBOX/replay-sql"
    }
    backup_schema_row_summary() { printf '1\t1\n'; }
}

assert_pitr_shim_replays() {
    local source_schema="$1" target_schema="$2" status=0 argument
    initialize_pitr_shim_fixture
    pitr_run --target-time 2026-01-01T13:00:00Z --source-schema "$source_schema" --target-schema "$target_schema" --force-overwrite \
        > "$SANDBOX/pitr-output" 2>&1 || status=$?
    assert_status replay_accepted 0 "$status" || return 1
    assert_equals restore_and_replay "$(printf 'restore\nreplay')" "$(cat "$SANDBOX/restore-effects")" || return 1
    assert_equals restored_target 'restored target' "$(cat "$SANDBOX/restore-target")" || return 1
    for argument in --verify-binlog-checksum --require-row-format --start-position=4 '--stop-datetime=2026-01-01 13:00:00' \
        "--rewrite-db=$source_schema->$target_schema" "--database=$target_schema" "${PITR_BINLOG_FILES[@]}"; do
        assert_contains replay_argument_validated_before_restore "$argument" "$SANDBOX/decoder-before-restore.log" || return 1
    done
    assert_equals dry_decode_once 1 "$(grep -cxF -- --require-row-format "$SANDBOX/decoder-before-restore.log")" || return 1
    assert_equals same_decoder_used_for_replay 2 "$(grep -cxF -- --require-row-format "$RECORDING_DOCKER_LOG")" || return 1
    assert_contains row_event_applied "BINLOG '" "$SANDBOX/replay-sql" || return 1
    assert_equals applied_event_count 1 "$PITR_APPLIED_EVENTS" || return 1
}

case_pitr_shim_accepts_actual_replay_arguments() {
    source "$SANDBOX/pitr-lib.sh"
    assert_pitr_shim_replays src_1-db target_2-db
}

case_pitr_shim_accepts_dollar_in_source_schema() {
    source "$SANDBOX/pitr-lib.sh"
    assert_pitr_shim_replays 'tenant$1' target_2-db
}

case_pitr_shim_accepts_dollar_in_target_schema() {
    source "$SANDBOX/pitr-lib.sh"
    assert_pitr_shim_replays src_1-db 'target$name'
}

assert_pitr_shim_refused_before_restore() {
    local status=0
    : > "$RECORDING_DOCKER_LOG"
    pitr_run --target-time 2026-01-01T13:00:00Z --source-schema src --target-schema target --force-overwrite \
        > "$SANDBOX/pitr-output" 2>&1 || status=$?
    assert_status replay_options_refused 64 "$status" || return 1
    assert_contains refused_during_preflight 'event=pitr_preflight_failed reason=replay_decode' "$SANDBOX/pitr-output" || return 1
    assert_equals target_preserved 'existing target' "$(cat "$SANDBOX/restore-target")" || return 1
    assert_equals no_restore_or_replay '' "$(cat "$SANDBOX/restore-effects")" || return 1
    assert_absent invalid_replay_never_reaches_docker --require-row-format "$RECORDING_DOCKER_LOG" || return 1
    pitr_cleanup
}

case_pitr_shim_refuses_foreign_option_before_restore() {
    source "$SANDBOX/pitr-lib.sh"
    initialize_pitr_shim_fixture
    local option
    for option in --read-from-remote-server --result-file=/output --defaults-extra-file=/credentials/source.cnf --force-read --; do
        PITR_TEST_FOREIGN_OPTION="$option"
        assert_pitr_shim_refused_before_restore || return 1
    done
}

case_pitr_shim_refuses_malformed_rewrite_before_restore() {
    source "$SANDBOX/pitr-lib.sh"
    initialize_pitr_shim_fixture
    local rewrite
    for rewrite in 'src->' '->target' 'src=>target' 'src->target->other' 'src ->target' 'src->target;id' \
        'src->$(id)' 'src->`id`' 'src->${name}' '$(id)->target' 'src;id->target' 'src->target|id' 'src->target&' 'src->target/name' \
        'src->target.name' 'src->target*' $'src->target\n' "src->$(printf 'a%.0s' {1..65})" \
        "$(printf 'a%.0s' {1..65})->target"; do
        PITR_TEST_REWRITE="$rewrite"
        assert_pitr_shim_refused_before_restore || return 1
    done
}

initialize_tls_fixture() {
    backup_set_defaults
    local client="$SANDBOX/tls-client"
    cat > "$client" <<'EOF'
#!/bin/bash
set -eu
mode=DISABLED
query=false
print_defaults=false
for argument in "$@"; do
    case "$argument" in
        --ssl-mode=*) mode="${argument#*=}" ;;
        --execute=*) query=true ;;
        --print-defaults) print_defaults=true ;;
    esac
done
if [ "$print_defaults" = true ]; then
    [ "${TLS_DEFAULTS_FAIL:-false}" = false ] || exit 1
    printf '%s\n' "--password=synthetic-secret ${TLS_EFFECTIVE_OPTIONS:-}"
    exit 0
fi
printf '%s\n' "$*" >> "$TLS_CLIENT_LOG"
if [ "$mode" = VERIFY_IDENTITY ] && [ "$TLS_FIXTURE" != trusted ]; then
    exit 1
fi
if [ "$query" = false ]; then
    printf 'transfer\n' >> "$TLS_TRANSFER_LOG"
fi
EOF
    chmod 0700 "$client"
    MYSQL_COMMAND=("$client")
    MYSQLDUMP_COMMAND=("$client")
    MYSQLBINLOG_COMMAND=("$client")
    export TLS_CLIENT_LOG="$SANDBOX/tls-client.log" TLS_TRANSFER_LOG="$SANDBOX/tls-transfer.log"
    printf '[client]\nssl-mode=DISABLED\n' > "$SANDBOX/tls.cnf"
    chmod 0600 "$SANDBOX/tls.cnf"
    CONNEX_BACKUP_SOURCE_DEFAULTS_FILE="$SANDBOX/tls.cnf"
    CONNEX_BACKUP_VERIFY_DEFAULTS_FILE="$SANDBOX/tls.cnf"
    CONNEX_BACKUP_RESTORE_DEFAULTS_FILE="$SANDBOX/tls.cnf"
    CONNEX_BACKUP_DB_HOST=db.example.test
    CONNEX_BACKUP_VERIFY_DB_HOST=verify.example.test
    CONNEX_BACKUP_RESTORE_DB_HOST=restore.example.test
    CONNEX_BACKUP_SOURCE_ALLOW_LOOPBACK_PLAINTEXT=false
    CONNEX_BACKUP_VERIFY_ALLOW_LOOPBACK_PLAINTEXT=false
    CONNEX_BACKUP_RESTORE_ALLOW_LOOPBACK_PLAINTEXT=false
    : > "$TLS_CLIENT_LOG"
    : > "$TLS_TRANSFER_LOG"
}

case_remote_tls_refuses_unverified_transfers() {
    source "$SANDBOX/connex-backup-lib.sh"
    initialize_tls_fixture
    local fixture profile status command
    for fixture in plaintext untrusted mismatched_identity; do
        export TLS_FIXTURE="$fixture"
        for profile in source verify restore; do
            for command in backup_mysql backup_mysqldump backup_mysqlbinlog_remote; do
                status=0
                "$command" "$profile" --ssl-mode=DISABLED < /dev/null || status=$?
                if [ "$status" -eq 0 ]; then
                    printf '%s %s transferred with %s transport\n' "$command" "$profile" "$fixture"
                    return 1
                fi
                assert_equals no_data_transfer '' "$(cat "$TLS_TRANSFER_LOG")" || return 1
            done
        done
    done
}

case_remote_tls_enforces_identity_on_every_client() {
    source "$SANDBOX/connex-backup-lib.sh"
    initialize_tls_fixture
    export TLS_FIXTURE=trusted
    local profile command
    for profile in source verify restore; do
        for command in backup_mysql backup_mysqldump backup_mysqlbinlog_remote; do
            : > "$TLS_CLIENT_LOG"
            "$command" "$profile" --ssl-mode=DISABLED < /dev/null || return 1
            assert_contains identity_enforced '--ssl-mode=VERIFY_IDENTITY' "$TLS_CLIENT_LOG" || return 1
            assert_equals last_option_enforces_identity '--ssl-mode=VERIFY_IDENTITY' "$(tail -1 "$TLS_CLIENT_LOG" | awk '{print $NF}')" || return 1
        done
    done
    assert_equals all_transfers_allowed 9 "$(wc -l < "$TLS_TRANSFER_LOG")" || return 1
}

case_tls_plaintext_exception_is_explicit_and_profile_scoped() {
    source "$SANDBOX/connex-backup-lib.sh"
    initialize_tls_fixture
    export TLS_FIXTURE=plaintext
    local host status
    CONNEX_BACKUP_DB_HOST=127.0.0.1
    status=0
    backup_mysql source < /dev/null || status=$?
    assert_status loopback_secure_by_default 1 "$status" || return 1
    CONNEX_BACKUP_SOURCE_ALLOW_LOOPBACK_PLAINTEXT=true
    backup_mysql source < /dev/null || return 1
    for host in db localhost.example.test 127.0.0.2 192.168.1.10; do
        CONNEX_BACKUP_DB_HOST="$host"
        : > "$TLS_CLIENT_LOG"
        status=0
        backup_mysql source < /dev/null 2> "$SANDBOX/tls-error" || status=$?
        assert_status remote_exception_refused 64 "$status" || return 1
        assert_equals refused_before_client '' "$(cat "$TLS_CLIENT_LOG")" || return 1
    done
    CONNEX_BACKUP_VERIFY_DB_HOST=127.0.0.1
    CONNEX_BACKUP_RESTORE_DB_HOST=127.0.0.1
    for host in verify restore; do
        status=0
        backup_mysql "$host" < /dev/null || status=$?
        assert_status source_exception_not_inherited 1 "$status" || return 1
    done
}

case_tls_option_terminator_refused_before_client() {
    source "$SANDBOX/connex-backup-lib.sh"
    initialize_tls_fixture
    export TLS_FIXTURE=trusted
    local command status
    for command in backup_mysql backup_mysqldump backup_mysqlbinlog_remote; do
        status=0
        "$command" source -- < /dev/null 2> "$SANDBOX/tls-error" || status=$?
        assert_status option_terminator_refused 64 "$status" || return 1
        assert_equals refused_before_client '' "$(cat "$TLS_CLIENT_LOG")" || return 1
    done
}

case_tls_plaintext_refuses_effective_endpoint_overrides() {
    source "$SANDBOX/connex-backup-lib.sh"
    initialize_tls_fixture
    export TLS_FIXTURE=plaintext
    CONNEX_BACKUP_DB_HOST=127.0.0.1
    CONNEX_BACKUP_SOURCE_ALLOW_LOOPBACK_PLAINTEXT=true
    local command option status
    for command in backup_mysql backup_mysqldump backup_mysqlbinlog_remote; do
        for option in --dns-srv-name --dns_srv_name --loose-dns-srv-name --loose_dns_srv_name --dns; do
            export TLS_EFFECTIVE_OPTIONS="$option=remote.example.test"
            status=0
            "$command" source < /dev/null > "$SANDBOX/tls-output" 2>&1 || status=$?
            assert_status effective_endpoint_override_refused 64 "$status" || return 1
            assert_equals no_data_transfer '' "$(cat "$TLS_TRANSFER_LOG")" || return 1
            assert_absent defaults_not_logged synthetic-secret "$SANDBOX/tls-output" || return 1

            export TLS_EFFECTIVE_OPTIONS=
            status=0
            "$command" source "$option=remote.example.test" < /dev/null > "$SANDBOX/tls-output" 2>&1 || status=$?
            assert_status argument_endpoint_override_refused 64 "$status" || return 1
            assert_equals no_data_transfer '' "$(cat "$TLS_TRANSFER_LOG")" || return 1
        done
        export TLS_DEFAULTS_FAIL=true
        status=0
        "$command" source < /dev/null > "$SANDBOX/tls-output" 2>&1 || status=$?
        assert_status unverifiable_defaults_refused 64 "$status" || return 1
        assert_equals no_data_transfer '' "$(cat "$TLS_TRANSFER_LOG")" || return 1
        export TLS_DEFAULTS_FAIL=false
    done
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

run_case case_installer_migrates_retired_database_network
run_case case_docker_client_resolves_and_validates_database_network
run_case case_binlog_shim_refuses_mutable_images_before_docker
run_case case_binlog_shim_only_mounts_readonly_binlog_inputs
run_case case_pitr_shim_accepts_actual_replay_arguments
run_case case_pitr_shim_accepts_dollar_in_source_schema
run_case case_pitr_shim_accepts_dollar_in_target_schema
run_case case_pitr_shim_refuses_foreign_option_before_restore
run_case case_pitr_shim_refuses_malformed_rewrite_before_restore
run_case case_remote_tls_refuses_unverified_transfers
run_case case_remote_tls_enforces_identity_on_every_client
run_case case_tls_plaintext_exception_is_explicit_and_profile_scoped
run_case case_tls_option_terminator_refused_before_client
run_case case_tls_plaintext_refuses_effective_endpoint_overrides
run_case case_schema_selection
run_case case_pitr_filtered_statements
run_case case_pitr_coverage_gap_guard
run_case case_archive_rebases_missing_cursor
run_case case_archive_retention_gap
run_case case_archive_corrupt_triplet_refetched
run_case case_prune_orphan_binlogs
run_case case_interrupted_publication_recovery

if [ "$FAILURES" -ne 0 ]; then
    printf '%s case(s) failed\n' "$FAILURES" >&2
    exit 1
fi
printf 'all cases passed\n'
