#!/bin/bash
#
# Downloads a redacted Connex support bundle for one organization and verifies
# it before publishing it to the requested output path.
#
# Authentication is an exported browser session: the endpoint requires an
# organization administrator who has completed a recent WebAuthn step-up, which
# cannot be scripted from a password. Export the session to a mode-0600
# Netscape cookie file immediately after the step-up and pass it with
# --cookie-file. The session value is never accepted on the command line, in an
# environment variable, or in a log field.
#
# With --include-journal the script may additionally append a closed-field
# projection of the systemd journal for the same window. The backend never reads
# logs from disk; that slice is an operator-side addition and carries only
# metadata fields, never raw messages or stack traces.
#
# Exit codes: 64 usage/configuration/dependency, 65 authentication or
# authorization, 66 API transport, 67 bundle integrity, 68 journal collection.
#
# Usage: deploy/support-bundle/collect.sh --base-url URL --org-id ID \
#            --cookie-file PATH [--output PATH] [--since INSTANT] \
#            [--correlation-id ID] [--entity-type TYPE --entity-id ID] \
#            [--workspace-id ID] [--include-journal --journal-unit UNIT]

# shellcheck source=deploy/support-bundle/support-bundle-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/support-bundle-lib.sh"

BASE_URL=
ORG_ID=
COOKIE_FILE=
OUTPUT=
SINCE=
CORRELATION_ID=
ENTITY_TYPE=
ENTITY_ID=
WORKSPACE_ID=
INCLUDE_JOURNAL=false
JOURNAL_UNIT=connex-backend.service

support_bundle_usage() {
    sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

support_bundle_parse_arguments() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --base-url)
                BASE_URL="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --org-id)
                ORG_ID="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --cookie-file)
                COOKIE_FILE="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --output)
                OUTPUT="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --since)
                SINCE="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --correlation-id)
                CORRELATION_ID="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --entity-type)
                ENTITY_TYPE="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --entity-id)
                ENTITY_ID="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --workspace-id)
                WORKSPACE_ID="${2-}"
                shift 2 || return "$EXIT_USAGE"
                ;;
            --include-journal)
                INCLUDE_JOURNAL=true
                shift
                ;;
            --journal-unit)
                JOURNAL_UNIT="${2-}"
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
}

support_bundle_validate_arguments() {
    if [ -z "$BASE_URL" ] || [ -z "$ORG_ID" ] || [ -z "$COOKIE_FILE" ]; then
        support_bundle_log error config_error reason missing_required_argument required "--base-url --org-id --cookie-file"
        return "$EXIT_USAGE"
    fi
    support_bundle_validate_base_url "$BASE_URL" || return "$EXIT_USAGE"
    support_bundle_validate_positive_integer org_id "$ORG_ID" || return "$EXIT_USAGE"
    support_bundle_validate_cookie_file "$COOKIE_FILE" || return "$EXIT_USAGE"
    if [ -n "$SINCE" ]; then
        support_bundle_validate_instant since "$SINCE" || return "$EXIT_USAGE"
    fi
    if [ -n "$CORRELATION_ID" ]; then
        support_bundle_validate_correlation_id "$CORRELATION_ID" || return "$EXIT_USAGE"
    fi
    # The entity filter is the only thing that unlocks workspace record events,
    # and the backend additionally requires AUDIT_READ in the resolved
    # workspace. A half-supplied pair would silently widen or narrow the slice.
    if [ -n "$ENTITY_TYPE" ] || [ -n "$ENTITY_ID" ]; then
        if [ -z "$ENTITY_TYPE" ] || [ -z "$ENTITY_ID" ]; then
            support_bundle_log error config_error reason incomplete_entity_filter required "--entity-type --entity-id"
            return "$EXIT_USAGE"
        fi
        support_bundle_validate_entity_type "$ENTITY_TYPE" || return "$EXIT_USAGE"
        support_bundle_validate_positive_integer entity_id "$ENTITY_ID" || return "$EXIT_USAGE"
        if [ -z "$WORKSPACE_ID" ]; then
            support_bundle_log error config_error reason workspace_required_for_entity_filter required "--workspace-id"
            return "$EXIT_USAGE"
        fi
    fi
    if [ -n "$WORKSPACE_ID" ]; then
        support_bundle_validate_positive_integer workspace_id "$WORKSPACE_ID" || return "$EXIT_USAGE"
    fi
    if [ "$INCLUDE_JOURNAL" = true ]; then
        if [[ ! "$JOURNAL_UNIT" =~ ^[A-Za-z0-9@._-]+$ ]]; then
            support_bundle_log error config_error reason invalid_journal_unit unit "$JOURNAL_UNIT"
            return "$EXIT_USAGE"
        fi
        support_bundle_require_commands journalctl || return "$EXIT_USAGE"
    fi
    if [ -z "$OUTPUT" ]; then
        OUTPUT="$PWD/connex-org-${ORG_ID}-support-bundle-$(date -u +%Y%m%dT%H%M%SZ).zip"
    fi
    support_bundle_validate_absolute_path output "$OUTPUT" || return "$EXIT_USAGE"
    if [ -e "$OUTPUT" ]; then
        support_bundle_log error config_error reason output_exists path "$OUTPUT"
        return "$EXIT_USAGE"
    fi
}

support_bundle_build_query() {
    local query=""
    if [ -n "$CORRELATION_ID" ]; then
        query+="&correlationId=$(support_bundle_urlencode "$CORRELATION_ID")"
    fi
    if [ -n "$ENTITY_TYPE" ]; then
        query+="&entityType=$(support_bundle_urlencode "$ENTITY_TYPE")"
        query+="&entityId=$(support_bundle_urlencode "$ENTITY_ID")"
    fi
    if [ -n "$SINCE" ]; then
        query+="&since=$(support_bundle_urlencode "$SINCE")"
    fi
    printf '%s' "${query:+?${query:1}}"
}

support_bundle_classify_status() {
    local status="$1"
    case "$status" in
        200)
            return 0
            ;;
        401)
            support_bundle_log error request_rejected reason unauthenticated status "$status" remedy "sign in as an organization administrator and export a fresh cookie file"
            return "$EXIT_AUTH"
            ;;
        403)
            # The endpoint returns 403 both for a non-administrator and for an
            # administrator whose step-up has expired; the operator needs to be
            # told which remedy to reach for.
            support_bundle_log error request_rejected reason forbidden status "$status" remedy "confirm organization administrator role and repeat the WebAuthn step-up, then export the cookie file again"
            return "$EXIT_AUTH"
            ;;
        404)
            support_bundle_log error request_rejected reason not_found status "$status"
            return "$EXIT_AUTH"
            ;;
        400)
            support_bundle_log error request_rejected reason invalid_request status "$status"
            return "$EXIT_API"
            ;;
        429)
            support_bundle_log error request_rejected reason busy status "$status" remedy "retry when concurrent bundle downloads have finished"
            return "$EXIT_API"
            ;;
        *)
            support_bundle_log error request_failed status "$status"
            return "$EXIT_API"
            ;;
    esac
}

support_bundle_download() {
    local destination="$1"
    local url status content_type headers
    url="$BASE_URL/api/orgs/$ORG_ID/support-bundle$(support_bundle_build_query)"
    headers="$WORK_DIR/response-headers"
    local -a curl_arguments=(
        --silent
        --show-error
        --fail-with-body
        --no-progress-meter
        --location-trusted
        --max-redirs 0
        --proto '=https,http'
        --max-time 900
        --cookie "$COOKIE_FILE"
        --dump-header "$headers"
        --output "$destination"
        --write-out '%{http_code}'
    )
    if [ -n "$WORKSPACE_ID" ]; then
        curl_arguments+=(--header "X-Workspace-Id: $WORKSPACE_ID")
    fi
    status="$(curl "${curl_arguments[@]}" "$url" 2>/dev/null || true)"
    if [[ ! "$status" =~ ^[0-9]{3}$ ]]; then
        support_bundle_log error request_failed reason transport_failure
        return "$EXIT_API"
    fi
    support_bundle_classify_status "$status" || return $?
    # Header names are case-insensitive and the value may follow the colon with no space.
    # awk's IGNORECASE is a gawk extension that mawk — the default awk on Debian and Ubuntu —
    # silently ignores, so the whole line is lowercased first and the value is taken by cutting
    # at the colon rather than by field splitting.
    content_type="$(tr '[:upper:]' '[:lower:]' < "$headers" | tr -d '\r' \
        | sed -n 's/^content-type:[[:space:]]*//p' | head -n 1)"
    if [[ "$content_type" != application/zip* ]]; then
        support_bundle_log error request_failed reason unexpected_content_type content_type "$content_type"
        return "$EXIT_API"
    fi
}

# The journal projection is built field by field from the structured journal
# record. Raw MESSAGE bodies, stack traces, headers, hosts and unknown fields are
# dropped, because a log line may quote user data or a credential that the
# backend redaction contract never had the chance to review.
support_bundle_journal_projection() {
    local since="$1"
    local until="$2"
    local destination="$3"
    local -a journal_arguments=(
        --unit "$JOURNAL_UNIT"
        --since "$since"
        --until "$until"
        --output json
        --no-pager
    )
    if ! journalctl "${journal_arguments[@]}" > "$WORK_DIR/journal.json" 2>/dev/null; then
        support_bundle_log error journal_failed reason journalctl_failed unit "$JOURNAL_UNIT"
        return "$EXIT_JOURNAL"
    fi
    if [ -n "$CORRELATION_ID" ]; then
        grep -F -- "$CORRELATION_ID" "$WORK_DIR/journal.json" > "$WORK_DIR/journal-filtered.json" || true
        mv "$WORK_DIR/journal-filtered.json" "$WORK_DIR/journal.json"
    fi
    if ! jq -c -e '
        select(type == "object")
        | {
            timestamp: (.__REALTIME_TIMESTAMP // null),
            level: (.PRIORITY // null),
            logger: (.CONNEX_LOGGER // .SYSLOG_IDENTIFIER // null),
            correlationId: (.CONNEX_CORRELATION_ID // null),
            method: (.CONNEX_REQUEST_METHOD // null),
            path: (.CONNEX_REQUEST_PATH // null),
            status: (.CONNEX_RESPONSE_STATUS // null),
            eventClass: (.CONNEX_EVENT_CLASS // null)
          }
    ' "$WORK_DIR/journal.json" > "$WORK_DIR/journal-projected.jsonl" 2>/dev/null; then
        if [ -s "$WORK_DIR/journal.json" ]; then
            support_bundle_log error journal_failed reason projection_failed
            return "$EXIT_JOURNAL"
        fi
        : > "$WORK_DIR/journal-projected.jsonl"
    fi
    # The projected path still passes through the ported redactor: a request
    # path can carry an invite or unsubscribe token in a segment.
    : > "$destination"
    local line path redacted
    while IFS= read -r line; do
        path="$(printf '%s' "$line" | jq -r '.path // ""')"
        if [ -n "$path" ]; then
            redacted="$(support_bundle_redact_path "$path")"
            line="$(printf '%s' "$line" | jq -c --arg path "$redacted" '.path = $path')"
        fi
        printf '%s\n' "$line" >> "$destination"
    done < "$WORK_DIR/journal-projected.jsonl"
}

support_bundle_append_journal() {
    local staging="$1"
    local since until entry_length entry_hash
    since="$(jq -r '.filters.since // empty' "$staging/manifest.json")"
    until="$(jq -r '.generatedAt // empty' "$staging/manifest.json")"
    if [ -z "$since" ] || [ -z "$until" ]; then
        support_bundle_log error journal_failed reason manifest_window_missing
        return "$EXIT_JOURNAL"
    fi
    # These values come from the server and are passed to journalctl, so they get the same instant
    # validation as the operator's own --since rather than being trusted because the manifest
    # hashed cleanly: the manifest attests to the payload files, not to its own field shapes.
    if ! support_bundle_validate_instant manifest_since "$since" \
        || ! support_bundle_validate_instant manifest_until "$until"; then
        support_bundle_log error journal_failed reason manifest_window_malformed
        return "$EXIT_JOURNAL"
    fi
    support_bundle_journal_projection "$since" "$until" "$staging/journal-slice.jsonl" || return "$EXIT_JOURNAL"
    entry_length="$(stat -c '%s' "$staging/journal-slice.jsonl")"
    entry_hash="$(sha256sum "$staging/journal-slice.jsonl" | awk '{print $1}')"
    if ! jq --arg path journal-slice.jsonl \
        --arg media_type application/x-ndjson \
        --argjson byte_length "$entry_length" \
        --arg sha256 "$entry_hash" \
        --arg redactor request_path_redactor_v1 \
        --arg unit "$JOURNAL_UNIT" \
        '.files += [{path: $path, mediaType: $media_type, byteLength: $byte_length, sha256: $sha256}]
         | .files |= sort_by(.path)
         | .journalSlice = {unit: $unit, redactor: $redactor, projection: "closed_fields"}' \
        "$staging/manifest.json" > "$staging/manifest.json.new"; then
        support_bundle_log error journal_failed reason manifest_update_failed
        return "$EXIT_JOURNAL"
    fi
    mv "$staging/manifest.json.new" "$staging/manifest.json"
    support_bundle_log info journal_appended unit "$JOURNAL_UNIT" bytes "$entry_length" records "$(wc -l < "$staging/journal-slice.jsonl")"
}

support_bundle_repack() {
    local staging="$1"
    local destination="$2"
    if ! ( cd "$staging" && zip --quiet --no-dir-entries -X "$destination" ./* ); then
        support_bundle_log error journal_failed reason repack_failed
        return "$EXIT_JOURNAL"
    fi
}

main() {
    local exit_code=0
    support_bundle_parse_arguments "$@" || exit "$?"
    SUPPORT_BUNDLE_PHASE=validating
    support_bundle_require_commands curl jq unzip zipinfo sha256sum || exit "$?"
    support_bundle_validate_arguments || exit "$?"
    if [ "$INCLUDE_JOURNAL" = true ]; then
        support_bundle_require_commands zip || exit "$?"
    fi

    WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/connex-support-bundle.XXXXXX")"
    chmod 0700 "$WORK_DIR"
    trap 'rm -rf "$WORK_DIR"' EXIT

    support_bundle_log info collect_started org_id "$ORG_ID" base_url "$BASE_URL" \
        correlation_id "${CORRELATION_ID:-none}" entity_type "${ENTITY_TYPE:-none}" \
        entity_id "${ENTITY_ID:-none}" since "${SINCE:-default}" include_journal "$INCLUDE_JOURNAL"

    SUPPORT_BUNDLE_PHASE=downloading
    exit_code=0
    support_bundle_download "$WORK_DIR/bundle.partial" || exit_code=$?
    if [ "$exit_code" -ne 0 ]; then
        support_bundle_finish "$exit_code" support_bundle_collect_summary org_id "$ORG_ID"
        exit "$exit_code"
    fi

    SUPPORT_BUNDLE_PHASE=verifying
    mkdir -p "$WORK_DIR/staging"
    exit_code=0
    support_bundle_verify_archive "$WORK_DIR/bundle.partial" "$WORK_DIR/staging" || exit_code=$?
    if [ "$exit_code" -ne 0 ]; then
        support_bundle_finish "$exit_code" support_bundle_collect_summary org_id "$ORG_ID"
        exit "$exit_code"
    fi

    if [ "$INCLUDE_JOURNAL" = true ]; then
        SUPPORT_BUNDLE_PHASE=journal
        exit_code=0
        support_bundle_append_journal "$WORK_DIR/staging" || exit_code=$?
        if [ "$exit_code" -ne 0 ]; then
            support_bundle_finish "$exit_code" support_bundle_collect_summary org_id "$ORG_ID"
            exit "$exit_code"
        fi
        rm -f "$WORK_DIR/bundle.partial"
        exit_code=0
        support_bundle_repack "$WORK_DIR/staging" "$WORK_DIR/bundle.partial" || exit_code=$?
        if [ "$exit_code" -ne 0 ]; then
            support_bundle_finish "$exit_code" support_bundle_collect_summary org_id "$ORG_ID"
            exit "$exit_code"
        fi
        SUPPORT_BUNDLE_PHASE=reverifying
        rm -rf "$WORK_DIR/staging-verify"
        mkdir -p "$WORK_DIR/staging-verify"
        exit_code=0
        support_bundle_verify_archive "$WORK_DIR/bundle.partial" "$WORK_DIR/staging-verify" || exit_code=$?
        if [ "$exit_code" -ne 0 ]; then
            support_bundle_finish "$exit_code" support_bundle_collect_summary org_id "$ORG_ID"
            exit "$exit_code"
        fi
    fi

    SUPPORT_BUNDLE_PHASE=publishing
    chmod 0600 "$WORK_DIR/bundle.partial"
    exit_code=0
    support_bundle_publish "$WORK_DIR/bundle.partial" "$OUTPUT" || exit_code=$?
    if [ "$exit_code" -ne 0 ]; then
        support_bundle_finish "$exit_code" support_bundle_collect_summary org_id "$ORG_ID" \
            output "$OUTPUT"
        exit "$exit_code"
    fi
    rm -f "$WORK_DIR/bundle.partial"

    SUPPORT_BUNDLE_PHASE=verifying_published
    # The summary must describe what is actually on disk, not what was verified in the work
    # directory, so the published file is re-verified and measured in place.
    rm -rf "$WORK_DIR/staging-published"
    mkdir -p "$WORK_DIR/staging-published"
    exit_code=0
    support_bundle_verify_archive "$OUTPUT" "$WORK_DIR/staging-published" || exit_code=$?
    if [ "$exit_code" -ne 0 ]; then
        support_bundle_finish "$exit_code" support_bundle_collect_summary org_id "$ORG_ID" \
            reason published_archive_invalid output "$OUTPUT"
        exit "$exit_code"
    fi

    SUPPORT_BUNDLE_PHASE=complete
    support_bundle_finish 0 support_bundle_collect_summary org_id "$ORG_ID" \
        output "$OUTPUT" bytes "$(stat -c '%s' "$OUTPUT")" \
        entries "$(zipinfo -1 "$OUTPUT" | wc -l)"
}

main "$@"
