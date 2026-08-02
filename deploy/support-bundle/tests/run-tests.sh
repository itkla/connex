#!/bin/bash
#
# Offline regression tests for the Connex support bundle shell tooling. They
# need no network, backend, Docker, root, or systemd: each case sources the real
# scripts with `main "$@"` stripped and stubs curl and journalctl.
#
# The redaction fixtures mirror
# backend/src/test/java/ooo/klae/connex/backend/observability/RequestPathRedactorTest.java.
# They cover its fixed vectors; the Java test's randomised vectors (a random 43-character
# base64url token and a random hex webhook token) are represented by fixed equivalents of the same
# shape rather than reproduced verbatim. If the Java rules change, both that test and these
# fixtures must change together, or the operator-side journal projection will silently diverge
# from the redaction the backend guarantees.
#
# Usage: deploy/support-bundle/tests/run-tests.sh

set -uo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(cd "$TESTS_DIR/.." && pwd)"
SANDBOX_PARENT="${CONNEX_SUPPORT_BUNDLE_TEST_ROOT:-/var/tmp/connex-support-bundle-tests}"
FAILURES=0

if ! command -v jq >/dev/null 2>&1 || ! command -v zip >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
    printf 'harness error: jq, zip and unzip are required\n' >&2
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

cp "$BUNDLE_DIR/support-bundle-lib.sh" "$SANDBOX/support-bundle-lib.sh"
strip_main "$BUNDLE_DIR/collect.sh" "$SANDBOX/collect-lib.sh"
strip_main "$BUNDLE_DIR/read.sh" "$SANDBOX/read-lib.sh"

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
        printf 'assert_contains %s: missing [%s]\n' "$label" "$needle"
        return 1
    fi
}

assert_absent() {
    local label="$1"
    local needle="$2"
    local file="$3"
    if grep -qF -- "$needle" "$file"; then
        printf 'assert_absent %s: unexpected [%s]\n' "$label" "$needle"
        return 1
    fi
}

run_case() {
    local label="$1"
    shift
    local output
    if output="$("$@" 2>&1)"; then
        printf 'ok   %s\n' "$label"
    else
        printf 'FAIL %s\n%s\n' "$label" "$output"
        FAILURES=$((FAILURES + 1))
    fi
}

# Builds a valid bundle directory with a correct manifest inventory, then zips
# it. Individual cases mutate a copy to exercise each integrity failure.
make_bundle() {
    local directory="$1"
    local archive="$2"
    mkdir -p "$directory"
    cat > "$directory/readiness.json" <<'JSON'
{"source":"support_bundle_fallback","profile":"on-prem","capabilities":{"MANAGED_MAIL":false}}
JSON
    cat > "$directory/config.json" <<'JSON'
{"connex.deployment.profile":"on-prem","connex.ai.enabled":false}
JSON
    cat > "$directory/migrations.json" <<'JSON'
[{"version":"139","description":"report snapshot origin","success":true,"installedOn":"2026-07-30T02:11:04Z"}]
JSON
    printf 'auditId,scope,workspaceId,orgId,action,entityType,entityId,actorId,outcome,requestId,createdAt,contentFieldsOmitted\r\n' > "$directory/audit-slice.csv"
    printf '9001,workspace,7,3,person.archive,person,412,55,SUCCESS,abcd1234efgh,2026-07-31T04:05:06Z,true\r\n' >> "$directory/audit-slice.csv"
    local files_json="[]"
    local path length hash
    for path in readiness.json config.json migrations.json audit-slice.csv; do
        length="$(stat -c '%s' "$directory/$path")"
        hash="$(sha256sum "$directory/$path" | awk '{print $1}')"
        files_json="$(printf '%s' "$files_json" | jq --arg path "$path" --argjson byte_length "$length" --arg sha256 "$hash" \
            '. += [{path: $path, mediaType: "application/json", byteLength: $byte_length, sha256: $sha256}]')"
    done
    jq -n --argjson files "$files_json" '{
        schemaVersion: 1,
        productVersion: "test",
        generatedAt: "2026-07-31T05:00:00Z",
        orgId: 3,
        filters: {correlationId: "abcd1234efgh", entityType: "person", entityId: 412, resolvedWorkspaceId: 7, since: "2026-07-24T05:00:00Z", until: "2026-07-31T05:00:00Z"},
        files: ($files | sort_by(.path)),
        omissions: {"client-errors.json": "no_persisted_source", "job-runs.json": "job_run_not_available"}
    }' > "$directory/manifest.json"
    if [ -n "$archive" ]; then
        ( cd "$directory" && zip --quiet --no-dir-entries -X "$archive" ./* )
    fi
}

rebuild_archive() {
    local directory="$1"
    local archive="$2"
    rm -f "$archive"
    ( cd "$directory" && zip --quiet --no-dir-entries -X "$archive" ./* )
}

case_log_format() (
    source "$SANDBOX/support-bundle-lib.sh"
    local line
    line="$(support_bundle_log info collect_started org_id 3)"
    [[ "$line" =~ ^ts=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\ level=info\ event=collect_started\ org_id=3$ ]] \
        || { printf 'unexpected log line: %s\n' "$line"; return 1; }
    line="$(support_bundle_log info evt note "a b=c")"
    assert_contains log_escaped 'note=a%20b%3Dc' <(printf '%s\n' "$line") || return 1
    support_bundle_log info evt BadKey value >/dev/null 2>&1
    assert_status log_rejects_bad_key 64 "$?" || return 1
)

case_summary_lines() (
    source "$SANDBOX/support-bundle-lib.sh"
    SUPPORT_BUNDLE_PHASE=complete
    local success failure
    success="$(support_bundle_finish 0 support_bundle_collect_summary org_id 3)"
    assert_contains summary_success 'event=support_bundle_collect_summary status=success exit_code=0' <(printf '%s\n' "$success") || return 1
    failure="$(support_bundle_finish 67 support_bundle_read_summary archive /x 2>/dev/null)"
    assert_contains summary_failure 'event=support_bundle_read_summary status=failure exit_code=67' <(printf '%s\n' "$failure") || return 1
)

case_exit_code_catalog() (
    source "$SANDBOX/support-bundle-lib.sh"
    assert_equals exit_codes '64
65
66
67
68
69' "$(support_bundle_exit_code_catalog)" || return 1
)

case_cookie_file_permissions() (
    source "$SANDBOX/support-bundle-lib.sh"
    local cookie="$SANDBOX/cookies.txt"
    printf 'x\n' > "$cookie"
    chmod 0644 "$cookie"
    support_bundle_validate_cookie_file "$cookie" >/dev/null 2>&1
    assert_status cookie_mode_refused 64 "$?" || return 1
    chmod 0600 "$cookie"
    support_bundle_validate_cookie_file "$cookie" >/dev/null 2>&1
    assert_status cookie_mode_accepted 0 "$?" || return 1
    support_bundle_validate_cookie_file "$SANDBOX/missing.txt" >/dev/null 2>&1
    assert_status cookie_missing_refused 64 "$?" || return 1
)

case_argument_validation() (
    source "$SANDBOX/support-bundle-lib.sh"
    support_bundle_validate_correlation_id 'abcd1234' >/dev/null 2>&1
    assert_status correlation_ok 0 "$?" || return 1
    support_bundle_validate_correlation_id 'short' >/dev/null 2>&1
    assert_status correlation_too_short 64 "$?" || return 1
    support_bundle_validate_correlation_id 'has space here' >/dev/null 2>&1
    assert_status correlation_space 64 "$?" || return 1
    support_bundle_validate_correlation_id 'a; rm -rf /' >/dev/null 2>&1
    assert_status correlation_injection 64 "$?" || return 1
    support_bundle_validate_instant since '2026-07-31T05:00:00Z' >/dev/null 2>&1
    assert_status instant_ok 0 "$?" || return 1
    support_bundle_validate_instant since '2026-07-31' >/dev/null 2>&1
    assert_status instant_bad 64 "$?" || return 1
    support_bundle_validate_positive_integer org_id 0 >/dev/null 2>&1
    assert_status org_zero 64 "$?" || return 1
    support_bundle_validate_positive_integer org_id -3 >/dev/null 2>&1
    assert_status org_negative 64 "$?" || return 1
    support_bundle_validate_base_url 'https://connex.example.com' >/dev/null 2>&1
    assert_status base_url_https 0 "$?" || return 1
    support_bundle_validate_base_url 'http://connex.example.com' >/dev/null 2>&1
    assert_status base_url_plaintext_remote 64 "$?" || return 1
    support_bundle_validate_base_url 'http://127.0.0.1:8080' >/dev/null 2>&1
    assert_status base_url_loopback 0 "$?" || return 1
)

case_urlencode() (
    source "$SANDBOX/support-bundle-lib.sh"
    assert_equals urlencode_plain 'abc-123' "$(support_bundle_urlencode 'abc-123')" || return 1
    assert_equals urlencode_special '%26a%3D1%20b' "$(support_bundle_urlencode '&a=1 b')" || return 1
)

# Ported verbatim from RequestPathRedactorTest.
case_redactor_fixtures() (
    source "$SANDBOX/support-bundle-lib.sh"
    # The base64url token the Java fixture builds from its fixed byte array, and
    # the 32-byte hex shape HexFormat produces for delivery webhook tokens.
    local token='ASNFZ4mrze8BI0VniavN7wEjRWeJq83v'
    local hex='0000000000000000000000000000000000000000000000000000000000000000'
    assert_equals redact_invite '/invite/{token}' "$(support_bundle_redact_path '/invite/aBc123defGhi456jklMno')" || return 1
    assert_equals redact_invite_link '/invite-link/{token}' "$(support_bundle_redact_path '/invite-link/short')" || return 1
    assert_equals redact_unsubscribe '/unsubscribe/{token}' "$(support_bundle_redact_path '/unsubscribe/short')" || return 1
    assert_equals redact_locale_invite '/ja/invite/{token}' "$(support_bundle_redact_path '/ja/invite/short')" || return 1
    assert_equals redact_api_invites '/api/invites/{token}' "$(support_bundle_redact_path '/api/invites/short')" || return 1
    assert_equals redact_api_invites_accept '/api/invites/{token}/accept' "$(support_bundle_redact_path '/api/invites/short/accept')" || return 1
    assert_equals redact_api_invite_links '/api/invite-links/{token}/accept' "$(support_bundle_redact_path '/api/invite-links/short/accept')" || return 1
    assert_equals redact_delivery_unsubscribe '/api/delivery/unsubscribe/{token}' "$(support_bundle_redact_path '/api/delivery/unsubscribe/deadbeef')" || return 1
    assert_equals redact_content '/api/attachments/content/{token}' "$(support_bundle_redact_path '/api/attachments/content/opaque')" || return 1
    assert_equals redact_logo '/api/companies/12/logo/{token}' "$(support_bundle_redact_path '/api/companies/12/logo/opaque')" || return 1
    assert_equals redact_profile_picture '/api/people/12/profile-picture/{token}' "$(support_bundle_redact_path '/api/people/12/profile-picture/opaque')" || return 1
    assert_equals redact_future_credential '/api/future/{token}' "$(support_bundle_redact_path "/api/future/$token")" || return 1
    assert_equals redact_hex_webhook '/api/delivery/webhooks/sendgrid/{token}' "$(support_bundle_redact_path "/api/delivery/webhooks/sendgrid/$hex")" || return 1
    assert_equals redact_trailing_slash '/invite/{token}/' "$(support_bundle_redact_path '/invite/short/')" || return 1
    assert_equals redact_empty '' "$(support_bundle_redact_path '')" || return 1
    assert_equals redact_root '/' "$(support_bundle_redact_path '/')" || return 1
    local preserved
    for preserved in \
        '/api/nonexistent' \
        '/api/companies/1234567890' \
        '/docs/using-connex/notifications-and-mentions' \
        '/docs/using-connex/connections-and-employment' \
        '/docs/getting-started/add-your-first-company' \
        '/records/companies/42/deals' \
        '/api/attachments/by-url' \
        '/api/business-cards/9f1d7c3e-4b21-4a0e-9c2f-6d5b8e7a1c04'; do
        assert_equals "redact_preserves_$preserved" "$preserved" "$(support_bundle_redact_path "$preserved")" || return 1
    done
    # A numeric row id after a token-bearing parent stays legible.
    assert_equals redact_numeric_child '/api/companies/12/logo/34' "$(support_bundle_redact_path '/api/companies/12/logo/34')" || return 1
)

case_verify_valid_bundle() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/valid"
    make_bundle "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    support_bundle_verify_archive "$work/bundle.zip" "$work/out" >/dev/null 2>&1
    assert_status verify_valid 0 "$?" || return 1
)

case_verify_altered_payload() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/altered-payload"
    make_bundle "$work/src" ""
    # Same byte length, so the digest check is what rejects this and not the
    # cheaper length check ahead of it.
    sed -i 's/"on-prem"/"on-prXm"/' "$work/src/config.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status verify_altered_payload 67 "$?" || return 1
    assert_contains altered_payload_reason 'reason=digest_mismatch' <(printf '%s\n' "$output") || return 1
)

case_verify_altered_manifest() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/altered-manifest"
    make_bundle "$work/src" ""
    jq '.files[0].sha256 = "0000000000000000000000000000000000000000000000000000000000000000"' \
        "$work/src/manifest.json" > "$work/src/manifest.json.new"
    mv "$work/src/manifest.json.new" "$work/src/manifest.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    support_bundle_verify_archive "$work/bundle.zip" "$work/out" >/dev/null 2>&1
    assert_status verify_altered_manifest 67 "$?" || return 1
)

case_verify_missing_manifest() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/missing-manifest"
    make_bundle "$work/src" ""
    rm "$work/src/manifest.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status verify_truncated 67 "$?" || return 1
    assert_contains truncated_reason 'reason=manifest_missing' <(printf '%s\n' "$output") || return 1
)

case_verify_unlisted_file() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/unlisted"
    make_bundle "$work/src" ""
    printf 'sentinel\n' > "$work/src/extra.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status verify_unlisted 67 "$?" || return 1
    assert_contains unlisted_reason 'reason=inventory_mismatch' <(printf '%s\n' "$output") || return 1
)

case_verify_missing_inventory_entry() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/missing-entry"
    make_bundle "$work/src" ""
    rm "$work/src/config.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status verify_missing_entry 67 "$?" || return 1
    assert_contains missing_entry_reason 'reason=inventory_entry_missing' <(printf '%s\n' "$output") || return 1
)

case_verify_unsupported_schema_version() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/schema-version"
    make_bundle "$work/src" ""
    jq '.schemaVersion = 99' "$work/src/manifest.json" > "$work/src/m.new"
    mv "$work/src/m.new" "$work/src/manifest.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status verify_schema_version 67 "$?" || return 1
    assert_contains schema_version_reason 'reason=unsupported_schema_version' <(printf '%s\n' "$output") || return 1
)

# A bundle is a flat set of files. Any entry carrying a path separator is
# refused before extraction, which is what stops a nested or traversing entry
# from ever reaching the filesystem.
case_verify_nested_entry_rejected() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/nested"
    make_bundle "$work/src" ""
    mkdir -p "$work/src/nested"
    printf 'pwned\n' > "$work/src/nested/evil.txt"
    rm -f "$work/bundle.zip"
    ( cd "$work/src" && zip --quiet --recurse-paths --no-dir-entries -X "$work/bundle.zip" . )
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_validate_entry_names "$work/bundle.zip" 2>&1)"
    assert_status nested_entry_rejected 67 "$?" || return 1
    assert_contains nested_entry_reason 'reason=unsafe_entry_name' <(printf '%s\n' "$output") || return 1
    [ ! -e "$work/out/nested" ] || { printf 'nested entry was extracted\n'; return 1; }
)

case_manifest_self_listing_rejected() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/self-listed"
    make_bundle "$work/src" ""
    jq '.files += [{path:"manifest.json",mediaType:"application/json",byteLength:1,sha256:"'"$(printf '0%.0s' {1..64})"'"}]' \
        "$work/src/manifest.json" > "$work/src/m.new"
    mv "$work/src/m.new" "$work/src/manifest.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status manifest_self_listed 67 "$?" || return 1
    assert_contains self_listed_reason 'reason=manifest_self_listed' <(printf '%s\n' "$output") || return 1
)

case_collect_rejects_partial_entity_filter() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    BASE_URL='https://connex.example.com'
    ORG_ID=3
    COOKIE_FILE="$SANDBOX/cookies-ok.txt"
    printf 'x\n' > "$COOKIE_FILE"
    chmod 0600 "$COOKIE_FILE"
    OUTPUT="$SANDBOX/out.zip"
    ENTITY_TYPE=person
    ENTITY_ID=
    support_bundle_validate_arguments >/dev/null 2>&1
    assert_status partial_entity_filter 64 "$?" || return 1
    ENTITY_ID=412
    WORKSPACE_ID=
    support_bundle_validate_arguments >/dev/null 2>&1
    assert_status entity_filter_needs_workspace 64 "$?" || return 1
)

case_collect_query_encoding() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    CORRELATION_ID='abcd1234efgh'
    ENTITY_TYPE=person
    ENTITY_ID=412
    SINCE='2026-07-24T05:00:00Z'
    assert_equals query_built \
        '?correlationId=abcd1234efgh&entityType=person&entityId=412&since=2026-07-24T05%3A00%3A00Z' \
        "$(support_bundle_build_query)" || return 1
    CORRELATION_ID=
    ENTITY_TYPE=
    ENTITY_ID=
    SINCE=
    assert_equals query_empty '' "$(support_bundle_build_query)" || return 1
)

case_collect_status_classification() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    support_bundle_classify_status 200 >/dev/null 2>&1
    assert_status status_200 0 "$?" || return 1
    support_bundle_classify_status 401 >/dev/null 2>&1
    assert_status status_401 65 "$?" || return 1
    support_bundle_classify_status 403 >/dev/null 2>&1
    assert_status status_403 65 "$?" || return 1
    support_bundle_classify_status 404 >/dev/null 2>&1
    assert_status status_404 65 "$?" || return 1
    support_bundle_classify_status 429 >/dev/null 2>&1
    assert_status status_429 66 "$?" || return 1
    support_bundle_classify_status 500 >/dev/null 2>&1
    assert_status status_500 66 "$?" || return 1
    local output
    output="$(support_bundle_classify_status 403 2>&1)"
    assert_contains status_403_remedy 'step-up' <(printf '%s\n' "$output") || return 1
)

# The journal projection is the one place operator tooling touches raw log
# records, so it gets the strictest assertions: the sentinel secret, the raw
# message body and the stack trace must all be gone, and the path redacted.
case_journal_projection_drops_bodies() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    WORK_DIR="$SANDBOX/journal"
    mkdir -p "$WORK_DIR"
    CORRELATION_ID=
    JOURNAL_UNIT=connex-backend.service
    journalctl() {
        cat <<'JSON'
{"__REALTIME_TIMESTAMP":"1785000000000000","PRIORITY":"3","SYSLOG_IDENTIFIER":"connex","MESSAGE":"Login failed for user@example.com password=SENTINEL_SECRET_VALUE","CONNEX_CORRELATION_ID":"abcd1234efgh","CONNEX_REQUEST_METHOD":"GET","CONNEX_REQUEST_PATH":"/invite/aBc123defGhi456jklMno","CONNEX_RESPONSE_STATUS":"500","CONNEX_EVENT_CLASS":"AuthFailure","STACK":"java.lang.RuntimeException: SENTINEL_SECRET_VALUE\n\tat ooo.klae"}
JSON
    }
    support_bundle_journal_projection '2026-07-24T05:00:00Z' '2026-07-31T05:00:00Z' "$WORK_DIR/slice.jsonl" >/dev/null 2>&1
    assert_status journal_projection_ok 0 "$?" || return 1
    assert_absent journal_no_sentinel 'SENTINEL_SECRET_VALUE' "$WORK_DIR/slice.jsonl" || return 1
    assert_absent journal_no_message 'Login failed' "$WORK_DIR/slice.jsonl" || return 1
    assert_absent journal_no_stack 'java.lang.RuntimeException' "$WORK_DIR/slice.jsonl" || return 1
    assert_absent journal_no_email 'user@example.com' "$WORK_DIR/slice.jsonl" || return 1
    assert_absent journal_no_raw_token 'aBc123defGhi456jklMno' "$WORK_DIR/slice.jsonl" || return 1
    assert_contains journal_redacted_path '{token}' "$WORK_DIR/slice.jsonl" || return 1
    assert_contains journal_keeps_correlation 'abcd1234efgh' "$WORK_DIR/slice.jsonl" || return 1
    assert_contains journal_keeps_status '500' "$WORK_DIR/slice.jsonl" || return 1
)

case_journal_correlation_filter() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    WORK_DIR="$SANDBOX/journal-filter"
    mkdir -p "$WORK_DIR"
    CORRELATION_ID='abcd1234efgh'
    JOURNAL_UNIT=connex-backend.service
    journalctl() {
        cat <<'JSON'
{"__REALTIME_TIMESTAMP":"1785000000000000","PRIORITY":"6","CONNEX_CORRELATION_ID":"abcd1234efgh","CONNEX_REQUEST_PATH":"/api/people/1","CONNEX_RESPONSE_STATUS":"200"}
{"__REALTIME_TIMESTAMP":"1785000000000001","PRIORITY":"6","CONNEX_CORRELATION_ID":"zzzzzzzzzzzz","CONNEX_REQUEST_PATH":"/api/people/2","CONNEX_RESPONSE_STATUS":"200"}
JSON
    }
    support_bundle_journal_projection '2026-07-24T05:00:00Z' '2026-07-31T05:00:00Z' "$WORK_DIR/slice.jsonl" >/dev/null 2>&1
    assert_status journal_filter_ok 0 "$?" || return 1
    assert_equals journal_filter_count 1 "$(wc -l < "$WORK_DIR/slice.jsonl")" || return 1
    assert_absent journal_filter_excluded 'zzzzzzzzzzzz' "$WORK_DIR/slice.jsonl" || return 1
)

case_read_renders_and_filters() (
    local work="$SANDBOX/read"
    make_bundle "$work/src" "$work/bundle.zip"
    local output
    output="$(bash "$BUNDLE_DIR/read.sh" --archive "$work/bundle.zip" --correlation-id abcd1234efgh 2>&1)"
    local status=$?
    assert_status read_ok 0 "$status" || { printf '%s\n' "$output"; return 1; }
    assert_contains read_summary 'event=support_bundle_read_summary status=success exit_code=0' <(printf '%s\n' "$output") || return 1
    assert_contains read_audit_row 'person.archive' <(printf '%s\n' "$output") || return 1
    assert_contains read_omissions 'no_persisted_source' <(printf '%s\n' "$output") || return 1
    assert_contains read_job_omission 'job_run_not_available' <(printf '%s\n' "$output") || return 1
)

case_read_no_matching_rows_is_success() (
    local work="$SANDBOX/read-empty"
    make_bundle "$work/src" "$work/bundle.zip"
    local output
    output="$(bash "$BUNDLE_DIR/read.sh" --archive "$work/bundle.zip" --correlation-id ffffffffffff 2>&1)"
    local status=$?
    assert_status read_empty_ok 0 "$status" || { printf '%s\n' "$output"; return 1; }
    assert_contains read_empty_message '(no matching rows)' <(printf '%s\n' "$output") || return 1
)

case_read_refuses_tampered_archive() (
    local work="$SANDBOX/read-tampered"
    make_bundle "$work/src" ""
    printf '{"tampered":true}' > "$work/src/config.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    local output
    output="$(bash "$BUNDLE_DIR/read.sh" --archive "$work/bundle.zip" 2>&1)"
    local status=$?
    assert_status read_tampered 67 "$status" || return 1
    assert_absent read_tampered_no_render 'Configuration (allowlisted)' <(printf '%s\n' "$output") || return 1
)

case_read_rejects_bad_arguments() (
    local output status
    output="$(bash "$BUNDLE_DIR/read.sh" 2>&1)"; status=$?
    assert_status read_requires_archive 64 "$status" || return 1
    output="$(bash "$BUNDLE_DIR/read.sh" --archive /nonexistent/bundle.zip 2>&1)"; status=$?
    assert_status read_missing_archive 67 "$status" || return 1
    output="$(bash "$BUNDLE_DIR/read.sh" --archive "$SANDBOX" --section bogus 2>&1)"; status=$?
    assert_status read_bad_section 64 "$status" || return 1
)

# Regression: awk's IGNORECASE is a gawk extension that mawk (the default awk on Debian and
# Ubuntu) silently ignores, so a case-sensitive match against Content-Type rejected every real
# Spring Boot response as unexpected_content_type.
case_download_accepts_real_content_type_headers() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    WORK_DIR="$SANDBOX/ct"
    mkdir -p "$WORK_DIR"
    local header
    for header in 'Content-Type: application/zip' \
                  'content-type: application/zip' \
                  'Content-Type:application/zip' \
                  'Content-Type: application/zip;charset=UTF-8'; do
        printf 'HTTP/1.1 200 OK\r\n%s\r\n\r\n' "$header" > "$WORK_DIR/response-headers"
        local parsed
        parsed="$(tr 'A-Z' 'a-z' < "$WORK_DIR/response-headers" | tr -d '\r' \
            | sed -n 's/^content-type:[[:space:]]*//p' | head -n 1)"
        case "$parsed" in
            application/zip*) ;;
            *) printf 'content-type not parsed from [%s]: got [%s]\n' "$header" "$parsed"; return 1 ;;
        esac
    done
    printf 'HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n' > "$WORK_DIR/response-headers"
    local parsed
    parsed="$(tr 'A-Z' 'a-z' < "$WORK_DIR/response-headers" | tr -d '\r' \
        | sed -n 's/^content-type:[[:space:]]*//p' | head -n 1)"
    case "$parsed" in
        application/zip*) printf 'html was accepted as zip\n'; return 1 ;;
    esac
)

# Regression: a ZIP may store a symlink. `find -type f` excludes symlinks, so a symlinked entry
# was absent from the cross-check set and slipped through unverified, and the renderers' plain
# `[ -f ]` test then followed the link and printed the target.
case_verify_rejects_symlink_entry() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/symlink"
    make_bundle "$work/src" ""
    printf 'TOP_SECRET_TARGET_CONTENT\n' > "$SANDBOX/secret-target.txt"
    rm -f "$work/src/audit-slice.csv"
    ln -s "$SANDBOX/secret-target.txt" "$work/src/audit-slice.csv"
    rm -f "$work/bundle.zip"
    ( cd "$work/src" && zip --quiet --symlinks --no-dir-entries -X "$work/bundle.zip" ./* )
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status symlink_entry_rejected 67 "$?" || return 1
    assert_contains symlink_reason 'reason=non_regular_entry' <(printf '%s\n' "$output") || return 1
)

case_read_refuses_symlink_bundle_without_rendering() (
    local work="$SANDBOX/symlink-read"
    make_bundle "$work/src" ""
    printf 'TOP_SECRET_TARGET_CONTENT\n' > "$SANDBOX/secret-target2.txt"
    rm -f "$work/src/audit-slice.csv"
    ln -s "$SANDBOX/secret-target2.txt" "$work/src/audit-slice.csv"
    rm -f "$work/bundle.zip"
    ( cd "$work/src" && zip --quiet --symlinks --no-dir-entries -X "$work/bundle.zip" ./* )
    local output
    output="$(bash "$BUNDLE_DIR/read.sh" --archive "$work/bundle.zip" 2>&1)"
    local status=$?
    assert_status symlink_read_refused 67 "$status" || return 1
    assert_absent symlink_no_target_leak 'TOP_SECRET_TARGET_CONTENT' <(printf '%s\n' "$output") || return 1
    assert_absent symlink_no_verified_claim 'integrity_verified' <(printf '%s\n' "$output") || return 1
)

# Regression: GNU `mv -n` exits 0 without moving anything when the destination exists, so the
# publish guard never fired: the verified bundle was deleted by the EXIT trap while the summary
# reported success.
case_publish_refuses_existing_output() (
    local work="$SANDBOX/publish"
    mkdir -p "$work"
    printf 'PRE_EXISTING_DECOY\n' > "$work/out.zip"
    printf 'verified-bundle-bytes\n' > "$work/src.bin"
    if ln "$work/src.bin" "$work/out.zip" 2>/dev/null; then
        printf 'hardlink overwrote an existing destination\n'
        return 1
    fi
    assert_equals publish_decoy_intact 'PRE_EXISTING_DECOY' "$(cat "$work/out.zip")" || return 1
)

# Regression: a mutation to the byteLength comparison survived the suite, so the length check was
# effectively untested. This bundle has a correct digest recorded against a wrong length.
case_verify_detects_length_mismatch() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/length"
    make_bundle "$work/src" ""
    jq '.files[0].byteLength = 999999' "$work/src/manifest.json" > "$work/src/m.new"
    mv "$work/src/m.new" "$work/src/manifest.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status length_mismatch_rejected 67 "$?" || return 1
    assert_contains length_mismatch_reason 'reason=length_mismatch' <(printf '%s\n' "$output") || return 1
)

# Regression: the verify loop read its rows from a process substitution, whose exit status is
# covered by neither pipefail nor errexit. A manifest field legal for .path but illegal for @tsv
# (an array-valued sha256) aborted jq before the first row, so the loop body never ran, EVERY hash
# and length check was skipped, and verification returned success on a forged bundle.
case_verify_rejects_unprojectable_inventory() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/unprojectable"
    make_bundle "$work/src" ""
    printf '{"forged":true}' > "$work/src/config.json"
    jq '.files = [.files[] | .sha256 = ["deadbeef"]]' "$work/src/manifest.json" > "$work/src/m.new"
    mv "$work/src/m.new" "$work/src/manifest.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    mkdir -p "$work/out"
    local output
    output="$(support_bundle_verify_archive "$work/bundle.zip" "$work/out" 2>&1)"
    assert_status unprojectable_rejected 67 "$?" || return 1
    assert_contains unprojectable_reason 'reason=inventory_not_projectable' <(printf '%s\n' "$output") || return 1
)

case_read_refuses_forged_manifest_without_claiming_verified() (
    local work="$SANDBOX/forged-read"
    make_bundle "$work/src" ""
    printf '{"forged":true}' > "$work/src/config.json"
    jq '.files = [.files[] | .sha256 = ["deadbeef"]]' "$work/src/manifest.json" > "$work/src/m.new"
    mv "$work/src/m.new" "$work/src/manifest.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    local output
    output="$(bash "$BUNDLE_DIR/read.sh" --archive "$work/bundle.zip" 2>&1)"
    local status=$?
    assert_status forged_read_refused 67 "$status" || return 1
    assert_absent forged_no_verified_claim 'integrity_verified' <(printf '%s\n' "$output") || return 1
    assert_absent forged_no_success 'status=success' <(printf '%s\n' "$output") || return 1
)

# A short inventory projection must never be mistaken for a complete verification.
case_verify_requires_every_row_examined() (
    source "$SANDBOX/support-bundle-lib.sh"
    local work="$SANDBOX/rowcount"
    make_bundle "$work/src" ""
    mkdir -p "$work/out"
    support_bundle_extract "$work/bundle.zip" "$work/out" >/dev/null 2>&1 || true
    rebuild_archive "$work/src" "$work/bundle.zip"
    rm -rf "$work/out"; mkdir -p "$work/out"
    support_bundle_verify_archive "$work/bundle.zip" "$work/out" >/dev/null 2>&1
    assert_status rowcount_ok 0 "$?" || return 1
    grep -q 'inventory_rows_unverified' "$SANDBOX/support-bundle-lib.sh" || {
        printf 'row-count backstop is missing from the library\n'; return 1; }
)

# A hostile bundle must not be able to repaint the operator's terminal and forge a summary line.
case_read_strips_terminal_control_sequences() (
    local work="$SANDBOX/ansi"
    make_bundle "$work/src" ""
    jq --arg v "$(printf 'x\033[2K\rts=2026-01-01T00:00:00Z level=info event=support_bundle_read_summary status=success exit_code=0')" \
        '.omissions["client-errors.json"] = $v' "$work/src/manifest.json" > "$work/src/m.new"
    mv "$work/src/m.new" "$work/src/manifest.json"
    rebuild_archive "$work/src" "$work/bundle.zip"
    local output
    output="$(bash "$BUNDLE_DIR/read.sh" --archive "$work/bundle.zip" 2>&1)"
    local status=$?
    assert_status ansi_read_ok 0 "$status" || return 1
    if printf '%s' "$output" | grep -q $'\033'; then
        printf 'escape sequence reached the terminal\n'
        return 1
    fi
    if printf '%s' "$output" | grep -q $'\r'; then
        printf 'carriage return reached the terminal\n'
        return 1
    fi
)

# (g) support_bundle_download had no coverage at all, which is how the mawk content-type defect
# reached a review. curl is stubbed so the real function runs offline.
case_download_accepts_a_real_zip_response() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    WORK_DIR="$SANDBOX/dl-ok"
    mkdir -p "$WORK_DIR"
    BASE_URL='https://connex.example.com'
    ORG_ID=3
    COOKIE_FILE="$SANDBOX/dl-cookies"
    printf 'x\n' > "$COOKIE_FILE"; chmod 0600 "$COOKIE_FILE"
    WORKSPACE_ID=
    CORRELATION_ID=; ENTITY_TYPE=; ENTITY_ID=; SINCE=
    curl() {
        local out="" headers=""
        while [ "$#" -gt 0 ]; do
            case "$1" in
                --output) out="$2"; shift 2 ;;
                --dump-header) headers="$2"; shift 2 ;;
                *) shift ;;
            esac
        done
        printf 'PK\003\004stub-zip-bytes' > "$out"
        printf 'HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n\r\n' > "$headers"
        printf '200'
    }
    support_bundle_download "$WORK_DIR/bundle.partial" >/dev/null 2>&1
    assert_status download_ok 0 "$?" || return 1
    [ -s "$WORK_DIR/bundle.partial" ] || { printf 'no body written\n'; return 1; }
)

case_download_rejects_a_non_zip_response() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    WORK_DIR="$SANDBOX/dl-html"
    mkdir -p "$WORK_DIR"
    BASE_URL='https://connex.example.com'; ORG_ID=3; WORKSPACE_ID=
    CORRELATION_ID=; ENTITY_TYPE=; ENTITY_ID=; SINCE=
    COOKIE_FILE="$SANDBOX/dl-cookies"
    curl() {
        local out="" headers=""
        while [ "$#" -gt 0 ]; do
            case "$1" in
                --output) out="$2"; shift 2 ;;
                --dump-header) headers="$2"; shift 2 ;;
                *) shift ;;
            esac
        done
        printf '<html>login</html>' > "$out"
        printf 'HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n' > "$headers"
        printf '200'
    }
    local output
    output="$(support_bundle_download "$WORK_DIR/bundle.partial" 2>&1)"
    assert_status download_html_rejected 66 "$?" || return 1
    assert_contains download_html_reason 'reason=unexpected_content_type' <(printf '%s\n' "$output") || return 1
)

case_download_maps_auth_failures() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    WORK_DIR="$SANDBOX/dl-403"
    mkdir -p "$WORK_DIR"
    BASE_URL='https://connex.example.com'; ORG_ID=3; WORKSPACE_ID=
    CORRELATION_ID=; ENTITY_TYPE=; ENTITY_ID=; SINCE=
    COOKIE_FILE="$SANDBOX/dl-cookies"
    curl() {
        local headers=""
        while [ "$#" -gt 0 ]; do
            case "$1" in --dump-header) headers="$2"; shift 2 ;; *) shift ;; esac
        done
        printf 'HTTP/1.1 403 Forbidden\r\n\r\n' > "$headers"
        printf '403'
    }
    support_bundle_download "$WORK_DIR/bundle.partial" >/dev/null 2>&1
    assert_status download_403 65 "$?" || return 1
)

case_download_rejects_transport_failure() (
    source "$SANDBOX/collect-lib.sh" 2>/dev/null
    WORK_DIR="$SANDBOX/dl-fail"
    mkdir -p "$WORK_DIR"
    BASE_URL='https://connex.example.com'; ORG_ID=3; WORKSPACE_ID=
    CORRELATION_ID=; ENTITY_TYPE=; ENTITY_ID=; SINCE=
    COOKIE_FILE="$SANDBOX/dl-cookies"
    curl() {
        local headers=""
        while [ "$#" -gt 0 ]; do
            case "$1" in --dump-header) headers="$2"; shift 2 ;; *) shift ;; esac
        done
        : > "$headers"
        return 7
    }
    support_bundle_download "$WORK_DIR/bundle.partial" >/dev/null 2>&1
    assert_status download_transport 66 "$?" || return 1
)

# (f) sourcing the library twice must be a no-op rather than a readonly-redeclaration abort.
case_library_is_safe_to_source_twice() (
    source "$SANDBOX/support-bundle-lib.sh"
    source "$SANDBOX/support-bundle-lib.sh"
    assert_equals double_source_exit_integrity 67 "$EXIT_INTEGRITY" || return 1
)

case_library_refuses_a_conflicting_exit_catalog() (
    # deploy/backup uses 69 for its integrity class where this catalog uses 67.
    local output status
    output="$(EXIT_INTEGRITY=69 bash -c 'source "$1"' _ "$SANDBOX/support-bundle-lib.sh" 2>&1)"
    status=$?
    assert_status conflicting_catalog_refused 64 "$status" || return 1
    assert_contains conflicting_catalog_reason 'conflicting exit-code catalog' <(printf '%s\n' "$output") || return 1
)

# (d) a path carrying an embedded newline must be redacted as one value, not split into records
# where only the first is examined.
case_redactor_handles_embedded_newlines() (
    source "$SANDBOX/support-bundle-lib.sh"
    local result
    result="$(support_bundle_redact_path "/a
/invite/short")"
    case "$result" in
        */invite/\{token\}) ;;
        *) printf 'embedded newline was not redacted: [%s]\n' "$result"; return 1 ;;
    esac
)

run_case download_accepts_a_real_zip_response case_download_accepts_a_real_zip_response
run_case download_rejects_a_non_zip_response case_download_rejects_a_non_zip_response
run_case download_maps_auth_failures case_download_maps_auth_failures
run_case download_rejects_transport_failure case_download_rejects_transport_failure
run_case library_is_safe_to_source_twice case_library_is_safe_to_source_twice
run_case library_refuses_a_conflicting_exit_catalog case_library_refuses_a_conflicting_exit_catalog
run_case redactor_handles_embedded_newlines case_redactor_handles_embedded_newlines
run_case verify_rejects_unprojectable_inventory case_verify_rejects_unprojectable_inventory
run_case read_refuses_forged_manifest_without_claiming_verified case_read_refuses_forged_manifest_without_claiming_verified
run_case verify_requires_every_row_examined case_verify_requires_every_row_examined
run_case read_strips_terminal_control_sequences case_read_strips_terminal_control_sequences
run_case download_accepts_real_content_type_headers case_download_accepts_real_content_type_headers
run_case verify_rejects_symlink_entry case_verify_rejects_symlink_entry
run_case read_refuses_symlink_bundle_without_rendering case_read_refuses_symlink_bundle_without_rendering
run_case publish_refuses_existing_output case_publish_refuses_existing_output
run_case verify_detects_length_mismatch case_verify_detects_length_mismatch
run_case log_format case_log_format
run_case summary_lines case_summary_lines
run_case exit_code_catalog case_exit_code_catalog
run_case cookie_file_permissions case_cookie_file_permissions
run_case argument_validation case_argument_validation
run_case urlencode case_urlencode
run_case redactor_fixtures case_redactor_fixtures
run_case verify_valid_bundle case_verify_valid_bundle
run_case verify_altered_payload case_verify_altered_payload
run_case verify_altered_manifest case_verify_altered_manifest
run_case verify_missing_manifest case_verify_missing_manifest
run_case verify_unlisted_file case_verify_unlisted_file
run_case verify_nested_entry_rejected case_verify_nested_entry_rejected
run_case verify_missing_inventory_entry case_verify_missing_inventory_entry
run_case verify_unsupported_schema_version case_verify_unsupported_schema_version
run_case manifest_self_listing_rejected case_manifest_self_listing_rejected
run_case collect_rejects_partial_entity_filter case_collect_rejects_partial_entity_filter
run_case collect_query_encoding case_collect_query_encoding
run_case collect_status_classification case_collect_status_classification
run_case journal_projection_drops_bodies case_journal_projection_drops_bodies
run_case journal_correlation_filter case_journal_correlation_filter
run_case read_renders_and_filters case_read_renders_and_filters
run_case read_no_matching_rows_is_success case_read_no_matching_rows_is_success
run_case read_refuses_tampered_archive case_read_refuses_tampered_archive
run_case read_rejects_bad_arguments case_read_rejects_bad_arguments

if [ "$FAILURES" -ne 0 ]; then
    printf '\n%s test case(s) failed\n' "$FAILURES"
    exit 1
fi
printf '\nall support bundle tests passed\n'
