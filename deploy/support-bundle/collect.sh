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
# There is deliberately no journal-collection option. A systemd unit's journal cannot be scoped
# to one organization from here, so collecting it would append other tenants' activity to an
# artifact built to leave the deployment. See README.md.
#
# Exit codes: 64 usage/configuration/dependency, 65 authentication or
# authorization, 66 API transport, 67 bundle integrity.
#
# Usage: deploy/support-bundle/collect.sh --base-url URL --org-id ID \
#            --cookie-file PATH [--output PATH] [--since INSTANT] \
#            [--correlation-id ID] [--entity-type TYPE --entity-id ID] \
#            [--workspace-id ID]

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
    support_bundle_require_commands curl jq unzip zipinfo sha256sum || exit "$?"
    support_bundle_validate_arguments || exit "$?"

    WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/connex-support-bundle.XXXXXX")"
    chmod 0700 "$WORK_DIR"
    trap 'rm -rf "$WORK_DIR"' EXIT

    support_bundle_log info collect_started org_id "$ORG_ID" base_url "$BASE_URL" \
        correlation_id "${CORRELATION_ID:-none}" entity_type "${ENTITY_TYPE:-none}" \
        entity_id "${ENTITY_ID:-none}" since "${SINCE:-default}"

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
