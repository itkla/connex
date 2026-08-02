#!/bin/bash
#
# Validates and renders a Connex support bundle produced by collect.sh or by the
# in-product download.
#
# Nothing is rendered until the whole archive has been verified: entry names are
# checked, every inventory entry is matched against its recorded length and
# SHA-256, and every extracted file is required to appear in the inventory. A
# truncated archive — one whose manifest is missing because the backend stream
# failed part-way — is refused rather than partially displayed.
#
# This command is read-only and deliberately offers no import path. To reproduce
# a reported state locally, seed a workspace with `bash gradlew seedData` and
# recreate the facts the bundle reports; see README.md.
#
# Exit codes: 64 usage/configuration/dependency, 67 bundle integrity,
# 69 rendering or filtering failure.
#
# Usage: deploy/support-bundle/read.sh --archive PATH [--correlation-id ID]
#            [--section SECTION]

# shellcheck source=deploy/support-bundle/support-bundle-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/support-bundle-lib.sh"

ARCHIVE=
CORRELATION_ID=
SECTION=all

support_bundle_usage() {
    sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

support_bundle_parse_arguments() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --archive)
                ARCHIVE="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --correlation-id)
                CORRELATION_ID="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --section)
                SECTION="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --help)
                support_bundle_usage
                exit 0
                ;;
            *)
                support_bundle_log error config_error reason unknown_argument argument "$1"
                return "$EXIT_USAGE"
                ;;
        esac
    done
    if [ -z "$ARCHIVE" ]; then
        support_bundle_log error config_error reason missing_required_argument required "--archive"
        return "$EXIT_USAGE"
    fi
    case "$SECTION" in
        all|manifest|readiness|config|migrations|job-runs|audit|journal)
            ;;
        *)
            support_bundle_log error config_error reason invalid_section section "$SECTION"
            return "$EXIT_USAGE"
            ;;
    esac
    if [ -n "$CORRELATION_ID" ]; then
        support_bundle_validate_correlation_id "$CORRELATION_ID" || return "$EXIT_USAGE"
    fi
}

support_bundle_section_wanted() {
    local section="$1"
    [ "$SECTION" = all ] || [ "$SECTION" = "$section" ]
}

support_bundle_heading() {
    printf '\n== %s ==\n' "$1"
}

# `column -t -s ,` splits on every comma, including ones inside a quoted CSV field, which silently
# shifts values under the wrong headers — exactly the misreading a support engineer must not make.
# Parse the CSV properly and re-emit it tab-separated for column.
support_bundle_render_csv() {
    python3 -c '
import csv, sys
writer = csv.writer(sys.stdout, delimiter="\t", lineterminator="\n")
for row in csv.reader(sys.stdin):
    if row:
        writer.writerow(row)
' | support_bundle_sanitize_output | column -t -s $'\t'
}

support_bundle_render_json() {
    local directory="$1"
    local file="$2"
    local title="$3"
    if [ ! -f "$directory/$file" ] || [ -L "$directory/$file" ]; then
        return 0
    fi
    support_bundle_heading "$title"
    if ! jq . "$directory/$file" | support_bundle_sanitize_output; then
        support_bundle_log error render_failed entry "$file"
        return "$EXIT_READ"
    fi
}

support_bundle_render_manifest() {
    local directory="$1"
    local manifest="$directory/manifest.json"
    support_bundle_heading "Manifest"
    jq '{schemaVersion, productVersion, generatedAt, orgId, filters, omissions}' "$manifest" \
        | support_bundle_sanitize_output || return "$EXIT_READ"
    support_bundle_heading "Inventory (verified)"
    jq -r '.files[] | "\(.path)\t\(.byteLength) bytes\t\(.sha256[0:16])..."' "$manifest" \
        | support_bundle_sanitize_output | column -t -s $'\t' || return "$EXIT_READ"
    # An omission is a deliberate statement that a source was unavailable or
    # unsafe to include, not an error; surfacing it stops a reader concluding
    # that an absent file means an absent problem.
    if jq -e '(.omissions | type) == "object" and (.omissions | length) > 0' "$manifest" >/dev/null 2>&1; then
        support_bundle_heading "Declared omissions"
        jq -r '.omissions | to_entries[] | "\(.key): \(.value)"' "$manifest" \
            | support_bundle_sanitize_output || return "$EXIT_READ"
    fi
}

support_bundle_render_audit() {
    local directory="$1"
    local slice="$directory/audit-slice.csv"
    local matches
    if [ ! -f "$slice" ] || [ -L "$slice" ]; then
        return 0
    fi
    if [ -z "$CORRELATION_ID" ]; then
        support_bundle_heading "Audit slice"
        support_bundle_render_csv < "$slice" || return "$EXIT_READ"
        return 0
    fi
    support_bundle_heading "Audit slice (correlation $CORRELATION_ID)"
    head -n 1 "$slice" | support_bundle_render_csv || return "$EXIT_READ"
    matches="$(tail -n +2 "$slice" | grep -F -- "$CORRELATION_ID" || true)"
    if [ -z "$matches" ]; then
        printf '(no matching rows)\n'
        return 0
    fi
    printf '%s\n' "$matches" | support_bundle_render_csv || return "$EXIT_READ"
}

support_bundle_render_journal() {
    local directory="$1"
    local slice="$directory/journal-slice.jsonl"
    local matches
    if [ ! -f "$slice" ] || [ -L "$slice" ]; then
        return 0
    fi
    support_bundle_heading "Journal slice"
    if [ -n "$CORRELATION_ID" ]; then
        matches="$(grep -F -- "$CORRELATION_ID" "$slice" || true)"
        if [ -z "$matches" ]; then
            printf '(no matching records)\n'
            return 0
        fi
        printf '%s\n' "$matches" | jq -r '[.timestamp, .level, .method, .path, .status, .eventClass] | @tsv' \
            | support_bundle_sanitize_output | column -t -s $'\t' || return "$EXIT_READ"
        return 0
    fi
    jq -r '[.timestamp, .level, .method, .path, .status, .eventClass] | @tsv' "$slice" \
        | support_bundle_sanitize_output | column -t -s $'\t' || return "$EXIT_READ"
}

main() {
    local exit_code=0
    support_bundle_parse_arguments "$@" || exit "$?"
    SUPPORT_BUNDLE_PHASE=validating
    support_bundle_require_commands jq unzip zipinfo sha256sum column python3 || exit "$?"
    support_bundle_validate_archive_path "$ARCHIVE" || exit "$?"

    WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/connex-support-bundle-read.XXXXXX")"
    chmod 0700 "$WORK_DIR"
    trap 'rm -rf "$WORK_DIR"' EXIT

    SUPPORT_BUNDLE_PHASE=verifying
    exit_code=0
    support_bundle_verify_archive "$ARCHIVE" "$WORK_DIR" || exit_code=$?
    if [ "$exit_code" -ne 0 ]; then
        support_bundle_finish "$exit_code" support_bundle_read_summary archive "$ARCHIVE"
        exit "$exit_code"
    fi
    support_bundle_log info integrity_verified entries "$(jq -r '.files | length' "$WORK_DIR/manifest.json")"

    SUPPORT_BUNDLE_PHASE=rendering
    {
        if support_bundle_section_wanted manifest; then
            support_bundle_render_manifest "$WORK_DIR" || exit_code=$?
        fi
        if [ "$exit_code" -eq 0 ] && support_bundle_section_wanted readiness; then
            support_bundle_render_json "$WORK_DIR" readiness.json "Readiness" || exit_code=$?
        fi
        if [ "$exit_code" -eq 0 ] && support_bundle_section_wanted config; then
            support_bundle_render_json "$WORK_DIR" config.json "Configuration (allowlisted)" || exit_code=$?
        fi
        if [ "$exit_code" -eq 0 ] && support_bundle_section_wanted migrations; then
            support_bundle_render_json "$WORK_DIR" migrations.json "Migrations" || exit_code=$?
        fi
        if [ "$exit_code" -eq 0 ] && support_bundle_section_wanted job-runs; then
            support_bundle_render_json "$WORK_DIR" job-runs.json "Job runs" || exit_code=$?
        fi
        if [ "$exit_code" -eq 0 ] && support_bundle_section_wanted audit; then
            support_bundle_render_audit "$WORK_DIR" || exit_code=$?
        fi
        if [ "$exit_code" -eq 0 ] && support_bundle_section_wanted journal; then
            support_bundle_render_journal "$WORK_DIR" || exit_code=$?
        fi
    } || exit_code=$?

    if [ "$exit_code" -ne 0 ]; then
        support_bundle_finish "$exit_code" support_bundle_read_summary archive "$ARCHIVE"
        exit "$exit_code"
    fi
    SUPPORT_BUNDLE_PHASE=complete
    support_bundle_finish 0 support_bundle_read_summary archive "$ARCHIVE" \
        correlation_id "${CORRELATION_ID:-none}" section "$SECTION"
}

main "$@"
