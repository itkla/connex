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
# With --include-journal the script appends only dedicated, server-attributed request-completion
# records for the requested organization. It parses Spring's ECS JSON inside journald MESSAGE,
# filters exactly on the server-owned organization field before projection, and never copies raw
# client correlation values, messages, stack traces, query strings, headers, hosts, or unknown
# fields.
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
JOURNAL_UNIT_SET=false

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
                JOURNAL_UNIT_SET=true
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
    # Normalise a trailing slash so the endpoint path is not appended after one.
    BASE_URL="${BASE_URL%/}"
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
    if [ "$JOURNAL_UNIT_SET" = true ] && [ "$INCLUDE_JOURNAL" != true ]; then
        support_bundle_log error config_error reason journal_unit_requires_include_journal
        return "$EXIT_USAGE"
    fi
    if [ "$INCLUDE_JOURNAL" = true ]; then
        if [[ ! "$JOURNAL_UNIT" =~ ^[A-Za-z0-9@._-]+$ ]]; then
            support_bundle_log error config_error reason invalid_journal_unit unit "$JOURNAL_UNIT"
            return "$EXIT_USAGE"
        fi
        support_bundle_require_commands journalctl zip || return "$EXIT_USAGE"
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

# journalctl exposes Spring's complete ECS JSON record only as the MESSAGE string. The parser
# rejects duplicate keys recursively, accepts only the one dedicated tenant-attributed event, and
# checks the server-owned organization integer before reading any projectable field. Invalid,
# unattributed, ambiguous, other-tenant, and unrelated records are dropped record-by-record.
support_bundle_journal_projection() {
    local since="$1"
    local until="$2"
    local organization_id="$3"
    local correlation_hmac="$4"
    local destination="$5"
    local projected="$WORK_DIR/journal-projected.jsonl"
    local -a journal_arguments=(
        --unit "$JOURNAL_UNIT"
        --since "$since"
        --until "$until"
        --output json
        --no-pager
    )
    if ! (
        ulimit -f "$(( SUPPORT_BUNDLE_MAX_UNCOMPRESSED_BYTES / 512 ))" 2>/dev/null
        journalctl "${journal_arguments[@]}" 2>/dev/null \
            | SUPPORT_BUNDLE_JOURNAL_ORG_ID="$organization_id" \
              SUPPORT_BUNDLE_JOURNAL_CORRELATION_HMAC="$correlation_hmac" \
              python3 -c '
import json
import os
import re
import sys
import unicodedata

TARGET_ORG = int(os.environ["SUPPORT_BUNDLE_JOURNAL_ORG_ID"])
TARGET_CORRELATION_HMAC = os.environ["SUPPORT_BUNDLE_JOURNAL_CORRELATION_HMAC"]
LOGGER = "ooo.klae.connex.backend.tenant.TenantResolutionInterceptor"
EVENT_CLASS = "http.request.completed"
METHODS = {"DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"}
CORRELATION_HMAC = re.compile(r"[0-9a-f]{64}\Z")
TIMESTAMP = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z\Z")
MAX_INPUT_CHARS = 268435456
MAX_RECORDS = 50000

class DuplicateKey(ValueError):
    pass

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKey(key)
        result[key] = value
    return result

def safe_path(value):
    return (
        isinstance(value, str)
        and 0 < len(value) <= 512
        and value.startswith("/api/")
        and "?" not in value
        and "#" not in value
        and all(unicodedata.category(character) not in {"Cc", "Zl", "Zp"} for character in value)
    )

input_chars = 0
matched = 0
for line in sys.stdin:
    input_chars += len(line)
    if input_chars > MAX_INPUT_CHARS:
        raise ValueError("journal input exceeds the projection bound")
    if len(line) > 1048576:
        continue
    try:
        journal = json.loads(line, object_pairs_hook=unique_object)
        if not isinstance(journal, dict) or not isinstance(journal.get("MESSAGE"), str):
            continue
        ecs = json.loads(journal["MESSAGE"], object_pairs_hook=unique_object)
    except (DuplicateKey, json.JSONDecodeError, TypeError, ValueError):
        continue
    if not isinstance(ecs, dict):
        continue
    organization_id = ecs.get("connexOrganizationId")
    if (
        isinstance(organization_id, bool)
        or not isinstance(organization_id, int)
        or organization_id <= 0
        or organization_id != TARGET_ORG
    ):
        continue
    log = ecs.get("log")
    correlation_hmac = ecs.get("untrustedClientAssertedCorrelationHmac")
    status = ecs.get("responseStatus")
    if (
        not isinstance(log, dict)
        or log.get("level") != "INFO"
        or log.get("logger") != LOGGER
        or ecs.get("eventClass") != EVENT_CLASS
        or not isinstance(ecs.get("@timestamp"), str)
        or TIMESTAMP.fullmatch(ecs["@timestamp"]) is None
        or not isinstance(correlation_hmac, str)
        or CORRELATION_HMAC.fullmatch(correlation_hmac) is None
        or (TARGET_CORRELATION_HMAC and correlation_hmac != TARGET_CORRELATION_HMAC)
        or ecs.get("requestMethod") not in METHODS
        or not safe_path(ecs.get("requestPath"))
        or isinstance(status, bool)
        or not isinstance(status, int)
        or status < 100
        or status > 599
    ):
        continue
    matched += 1
    if matched > MAX_RECORDS:
        raise ValueError("journal projection exceeds the record bound")
    projected = {
        "timestamp": ecs["@timestamp"],
        "level": log["level"],
        "logger": log["logger"],
        "untrustedClientAssertedCorrelationHmac": correlation_hmac,
        "method": ecs["requestMethod"],
        "path": ecs["requestPath"],
        "status": status,
        "eventClass": ecs["eventClass"],
    }
    sys.stdout.write(json.dumps(projected, ensure_ascii=True, separators=(",", ":")) + "\n")
' > "$projected"
    ); then
        support_bundle_log error journal_failed reason collection_or_projection_failed unit "$JOURNAL_UNIT"
        return "$EXIT_JOURNAL"
    fi
    : > "$destination"
    local line path redacted
    while IFS= read -r line; do
        if ! path="$(printf '%s' "$line" | jq -er '.path | select(type == "string")')"; then
            support_bundle_log error journal_failed reason projected_path_invalid
            return "$EXIT_JOURNAL"
        fi
        redacted="$(support_bundle_redact_path "$path")"
        if ! printf '%s' "$line" | jq -c --arg path "$redacted" '.path = $path' >> "$destination"; then
            support_bundle_log error journal_failed reason path_redaction_failed
            return "$EXIT_JOURNAL"
        fi
    done < "$projected"
}

support_bundle_append_journal() {
    local staging="$1"
    local manifest="$staging/manifest.json"
    local manifest_org since until manifest_correlation_hmac entry_length entry_hash total_length
    if ! manifest_org="$(jq -er 'select((.orgId | type) == "number" and .orgId > 0 and .orgId == (.orgId | floor)) | .orgId | tostring' "$manifest")" \
        || [ "$manifest_org" != "$ORG_ID" ]; then
        support_bundle_log error journal_failed reason manifest_organization_mismatch expected "$ORG_ID"
        return "$EXIT_JOURNAL"
    fi
    if ! since="$(jq -er '.filters.since | select(type == "string")' "$manifest")" \
        || ! until="$(jq -er '.filters.until | select(type == "string")' "$manifest")"; then
        support_bundle_log error journal_failed reason manifest_window_missing
        return "$EXIT_JOURNAL"
    fi
    if ! support_bundle_validate_instant manifest_since "$since" \
        || ! support_bundle_validate_instant manifest_until "$until"; then
        support_bundle_log error journal_failed reason manifest_window_malformed
        return "$EXIT_JOURNAL"
    fi
    if ! manifest_correlation_hmac="$(jq -er '
        if .filters.untrustedClientAssertedCorrelationHmac == null then ""
        elif (.filters.untrustedClientAssertedCorrelationHmac | type) == "string"
            and (.filters.untrustedClientAssertedCorrelationHmac | test("^[0-9a-f]{64}$"))
        then .filters.untrustedClientAssertedCorrelationHmac
        else error("invalid correlation filter") end
    ' "$manifest")"; then
        support_bundle_log error journal_failed reason manifest_correlation_mismatch
        return "$EXIT_JOURNAL"
    fi
    if { [ -n "$CORRELATION_ID" ] && [ -z "$manifest_correlation_hmac" ]; } \
        || { [ -z "$CORRELATION_ID" ] && [ -n "$manifest_correlation_hmac" ]; } \
        || { [ -n "$CORRELATION_ID" ] && [ "$manifest_correlation_hmac" = "$CORRELATION_ID" ]; }; then
        support_bundle_log error journal_failed reason manifest_correlation_mismatch
        return "$EXIT_JOURNAL"
    fi
    support_bundle_journal_projection "$since" "$until" "$manifest_org" \
        "$manifest_correlation_hmac" "$staging/journal-slice.jsonl" || return "$EXIT_JOURNAL"
    entry_length="$(stat -c '%s' "$staging/journal-slice.jsonl")"
    entry_hash="$(sha256sum "$staging/journal-slice.jsonl" | awk '{print $1}')"
    if ! jq --arg path journal-slice.jsonl \
        --arg media_type application/x-ndjson \
        --argjson byte_length "$entry_length" \
        --arg sha256 "$entry_hash" \
        --arg unit "$JOURNAL_UNIT" \
        --argjson organization_id "$manifest_org" '
        .files += [{path: $path, mediaType: $media_type, byteLength: $byte_length, sha256: $sha256}]
        | .files |= sort_by(.path)
        | .journalSlice = {
            unit: $unit,
            organizationId: $organization_id,
            organizationDiscriminator: "connexOrganizationId",
            projection: "ecs_message_closed_fields_v2",
            redactor: "request_path_redactor_v1"
          }
    ' "$manifest" > "$manifest.new"; then
        support_bundle_log error journal_failed reason manifest_update_failed
        return "$EXIT_JOURNAL"
    fi
    mv "$manifest.new" "$manifest"
    total_length="$(find "$staging" -mindepth 1 -maxdepth 1 -type f -printf '%s\n' | awk '{ total += $1 } END { print total + 0 }')"
    if [ "$total_length" -gt "$SUPPORT_BUNDLE_MAX_UNCOMPRESSED_BYTES" ]; then
        support_bundle_log error journal_failed reason uncompressed_size_exceeded \
            actual "$total_length" limit "$SUPPORT_BUNDLE_MAX_UNCOMPRESSED_BYTES"
        return "$EXIT_JOURNAL"
    fi
    support_bundle_log info journal_appended unit "$JOURNAL_UNIT" org_id "$manifest_org" \
        bytes "$entry_length" records "$(wc -l < "$staging/journal-slice.jsonl")"
}

support_bundle_repack() {
    local staging="$1"
    local destination="$2"
    if ! ( cd "$staging" && zip --quiet --no-dir-entries -X "$destination" ./* ); then
        support_bundle_log error journal_failed reason repack_failed
        return "$EXIT_JOURNAL"
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
        413)
            support_bundle_log error request_rejected reason bundle_too_large status "$status" \
                remedy "narrow the window with --since, or add --entity-type/--entity-id, then collect again"
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




main() {
    local exit_code=0
    support_bundle_parse_arguments "$@" || exit "$?"
    SUPPORT_BUNDLE_PHASE=validating
    support_bundle_require_commands curl jq unzip zipinfo sha256sum python3 || exit "$?"
    support_bundle_validate_arguments || exit "$?"

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
            support_bundle_finish "$EXIT_JOURNAL" support_bundle_collect_summary org_id "$ORG_ID"
            exit "$EXIT_JOURNAL"
        fi
        exit_code=0
        support_bundle_repack "$WORK_DIR/staging" "$WORK_DIR/bundle.repacked" || exit_code=$?
        if [ "$exit_code" -ne 0 ]; then
            support_bundle_finish "$EXIT_JOURNAL" support_bundle_collect_summary org_id "$ORG_ID"
            exit "$EXIT_JOURNAL"
        fi
        SUPPORT_BUNDLE_PHASE=reverifying
        mkdir -p "$WORK_DIR/staging-repacked"
        if ! support_bundle_verify_archive "$WORK_DIR/bundle.repacked" "$WORK_DIR/staging-repacked"; then
            support_bundle_log error journal_failed reason repacked_archive_invalid
            support_bundle_finish "$EXIT_JOURNAL" support_bundle_collect_summary org_id "$ORG_ID"
            exit "$EXIT_JOURNAL"
        fi
        mv "$WORK_DIR/bundle.repacked" "$WORK_DIR/bundle.partial"
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
