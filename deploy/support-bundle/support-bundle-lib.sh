#!/bin/bash
#
# Shared runtime for the Connex support bundle collect and read commands. It
# provides structured logging, dependency and argument validation, cookie-file
# safety checks, ZIP entry-name safety checks, manifest parsing, and the
# inventory/SHA-256 verification both commands depend on.
#
# The bundle itself is produced by the backend, which applies the redaction
# contract documented in docs/SUPPORT_BUNDLE.md. Nothing here may widen what a
# bundle contains: the collect command only appends an optional, closed-field
# journal projection, and the read command is strictly read-only.
#
# Exit codes: 64 usage/configuration/dependency, 65 authentication or
# authorization, 66 API transport, 67 bundle integrity, 68 journal collection,
# 69 reader failure.

set -euo pipefail

umask 077

declare -rx EXIT_USAGE=64
declare -rx EXIT_AUTH=65
declare -rx EXIT_API=66
declare -rx EXIT_INTEGRITY=67
declare -rx EXIT_JOURNAL=68
declare -rx EXIT_READ=69

declare -rx SUPPORT_BUNDLE_SCHEMA_VERSION=1

SUPPORT_BUNDLE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUPPORT_BUNDLE_STARTED_EPOCH="$(date +%s)"
SUPPORT_BUNDLE_PHASE=initializing
export SUPPORT_BUNDLE_LIB_DIR SUPPORT_BUNDLE_PHASE

support_bundle_exit_code_catalog() {
    printf '%s\n' \
        "$EXIT_USAGE" \
        "$EXIT_AUTH" \
        "$EXIT_API" \
        "$EXIT_INTEGRITY" \
        "$EXIT_JOURNAL" \
        "$EXIT_READ"
}

support_bundle_escape_log_value() {
    local value="${1-}"
    value="${value//'%'/'%25'}"
    value="${value//$'\r'/'%0D'}"
    value="${value//$'\n'/'%0A'}"
    value="${value//$'\t'/'%09'}"
    value="${value//' '/'%20'}"
    value="${value//'='/'%3D'}"
    printf '%s' "$value"
}

support_bundle_log_line() {
    local level="$1"
    local event="$2"
    shift 2
    local line key value
    line="ts=$(date -u +%Y-%m-%dT%H:%M:%SZ) level=$level event=$(support_bundle_escape_log_value "$event")"
    while [ "$#" -gt 0 ]; do
        if [ "$#" -lt 2 ]; then
            return "$EXIT_USAGE"
        fi
        key="$1"
        value="$2"
        shift 2
        if [[ ! "$key" =~ ^[a-z][a-z0-9_]*$ ]]; then
            return "$EXIT_USAGE"
        fi
        line+=" $key=$(support_bundle_escape_log_value "$value")"
    done
    printf '%s' "$line"
}

support_bundle_log() {
    local line
    line="$(support_bundle_log_line "$@")" || return "$EXIT_USAGE"
    printf '%s\n' "$line"
}

support_bundle_finish() {
    local exit_code="$1"
    local event="$2"
    shift 2
    local status=success duration line
    if [ "$exit_code" -ne 0 ]; then
        status=failure
    fi
    duration=$(( $(date +%s) - SUPPORT_BUNDLE_STARTED_EPOCH ))
    line="$(support_bundle_log_line "$([ "$exit_code" -eq 0 ] && printf info || printf error)" "$event" status "$status" exit_code "$exit_code" phase "$SUPPORT_BUNDLE_PHASE" duration_seconds "$duration" "$@")"
    printf '%s\n' "$line"
    if [ "$exit_code" -ne 0 ]; then
        printf '%s\n' "$line" >&2
    fi
}

support_bundle_require_commands() {
    local command
    for command in "$@"; do
        if ! command -v "$command" >/dev/null 2>&1; then
            support_bundle_log error dependency_missing command "$command"
            return "$EXIT_USAGE"
        fi
    done
}

support_bundle_validate_absolute_path() {
    local name="$1"
    local value="$2"
    if [[ "$value" != /* || "$value" == / || "$value" == *$'\n'* ]]; then
        support_bundle_log error config_error reason unsafe_path key "$name" value "$value"
        return "$EXIT_USAGE"
    fi
}

support_bundle_validate_positive_integer() {
    local name="$1"
    local value="$2"
    if [[ ! "$value" =~ ^[1-9][0-9]{0,17}$ ]]; then
        support_bundle_log error config_error reason invalid_integer key "$name" value "$value"
        return "$EXIT_USAGE"
    fi
}

# The backend enforces the same shape; rejecting locally keeps a malformed value
# out of the request URL and out of the log fields entirely.
support_bundle_validate_correlation_id() {
    local value="$1"
    if [[ ! "$value" =~ ^[A-Za-z0-9_-]{8,64}$ ]]; then
        support_bundle_log error config_error reason invalid_correlation_id
        return "$EXIT_USAGE"
    fi
}

support_bundle_validate_entity_type() {
    local value="$1"
    if [[ ! "$value" =~ ^[a-z][a-z_]{0,31}$ ]]; then
        support_bundle_log error config_error reason invalid_entity_type value "$value"
        return "$EXIT_USAGE"
    fi
}

support_bundle_validate_instant() {
    local name="$1"
    local value="$2"
    if [[ ! "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
        support_bundle_log error config_error reason invalid_instant key "$name" value "$value"
        return "$EXIT_USAGE"
    fi
    if ! date -u -d "$value" +%s >/dev/null 2>&1; then
        support_bundle_log error config_error reason invalid_instant key "$name" value "$value"
        return "$EXIT_USAGE"
    fi
}

support_bundle_validate_base_url() {
    local value="$1"
    if [[ "$value" =~ ^https://[A-Za-z0-9._~:/?#@!$\&\'\(\)*+,\;=%-]+$ ]]; then
        return 0
    fi
    # Plaintext HTTP is only ever acceptable against a loopback development
    # backend; anywhere else it would put the session cookie on the wire.
    if [[ "$value" =~ ^http://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]+)?(/.*)?$ ]]; then
        support_bundle_log warn insecure_base_url reason loopback_plaintext
        return 0
    fi
    support_bundle_log error config_error reason invalid_base_url
    return "$EXIT_USAGE"
}

# The cookie file carries a live authenticated session. A group- or
# world-readable file would hand that session to every local account, so this is
# a refusal rather than a warning.
support_bundle_validate_cookie_file() {
    local path="$1"
    local mode
    support_bundle_validate_absolute_path cookie_file "$path" || return "$EXIT_USAGE"
    if [ ! -f "$path" ] || [ -L "$path" ] || [ ! -r "$path" ]; then
        support_bundle_log error config_error reason cookie_file_unreadable path "$path"
        return "$EXIT_USAGE"
    fi
    mode="$(stat -c '%a' "$path")"
    if [ "$mode" != 600 ]; then
        support_bundle_log error config_error reason cookie_file_mode path "$path" mode "$mode" required_mode 600
        return "$EXIT_USAGE"
    fi
}

support_bundle_urlencode() {
    local value="$1"
    local index character
    for (( index = 0; index < ${#value}; index++ )); do
        character="${value:index:1}"
        case "$character" in
            [A-Za-z0-9.~_-])
                printf '%s' "$character"
                ;;
            *)
                printf '%%%02X' "'$character"
                ;;
        esac
    done
}

# Mirrors ooo.klae.connex.backend.observability.RequestPathRedactor. Any change
# to the Java rules must be reflected here and in the ported fixtures in
# tests/run-tests.sh, which re-run the Java test vectors against this function.
support_bundle_redact_path() {
    local path="$1"
    printf '%s' "$path" | awk '
        function is_numeric_id(segment) {
            return segment ~ /^[0-9]+$/
        }
        function is_hex_credential(segment) {
            return length(segment) >= 32 && segment ~ /^[0-9a-f]+$/
        }
        function is_credential_shaped(segment) {
            if (length(segment) < 22) {
                return 0
            }
            if (segment !~ /^[A-Za-z0-9_-]+$/) {
                return 0
            }
            return segment ~ /[0-9]/ && segment ~ /[A-Z]/ && segment ~ /[a-z]/
        }
        function bears_token(parent) {
            return parent == "invite" || parent == "invite-link" \
                || parent == "invites" || parent == "invite-links" \
                || parent == "unsubscribe" || parent == "content" \
                || parent == "logo" || parent == "profile-picture"
        }
        {
            count = split($0, segments, "/")
            previous = ""
            output = ""
            for (position = 1; position <= count; position++) {
                segment = segments[position]
                if (position > 1) {
                    output = output "/"
                }
                parent_bears_token = bears_token(previous) && !is_numeric_id(segment)
                if (segment != "" && (parent_bears_token || is_credential_shaped(segment) || is_hex_credential(segment))) {
                    output = output "{token}"
                } else {
                    output = output segment
                }
                previous = segment
            }
            printf "%s", output
        }
    '
}

support_bundle_validate_archive_path() {
    local path="$1"
    support_bundle_validate_absolute_path archive "$path" || return "$EXIT_USAGE"
    if [ ! -f "$path" ] || [ -L "$path" ] || [ ! -r "$path" ]; then
        support_bundle_log error archive_unreadable path "$path"
        return "$EXIT_INTEGRITY"
    fi
}

# A bundle is a closed set of flat files. Absolute paths, traversal, nesting and
# duplicates are all rejected before anything is extracted, so a hostile or
# corrupted archive cannot write outside the sandbox or shadow an entry that was
# already verified.
support_bundle_validate_entry_names() {
    local archive="$1"
    local names duplicates name
    if ! names="$(zipinfo -1 "$archive" 2>/dev/null)"; then
        support_bundle_log error archive_unreadable reason zip_listing_failed
        return "$EXIT_INTEGRITY"
    fi
    if [ -z "$names" ]; then
        support_bundle_log error archive_invalid reason empty_archive
        return "$EXIT_INTEGRITY"
    fi
    while IFS= read -r name; do
        if [ -z "$name" ]; then
            continue
        fi
        if [[ ! "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
            support_bundle_log error archive_invalid reason unsafe_entry_name entry "$name"
            return "$EXIT_INTEGRITY"
        fi
    done <<< "$names"
    duplicates="$(printf '%s\n' "$names" | sort | uniq -d)"
    if [ -n "$duplicates" ]; then
        support_bundle_log error archive_invalid reason duplicate_entry entry "$(printf '%s' "$duplicates" | head -n 1)"
        return "$EXIT_INTEGRITY"
    fi
    if ! printf '%s\n' "$names" | grep -qx 'manifest.json'; then
        support_bundle_log error archive_invalid reason manifest_missing
        return "$EXIT_INTEGRITY"
    fi
}

support_bundle_extract() {
    local archive="$1"
    local destination="$2"
    local irregular
    support_bundle_validate_entry_names "$archive" || return "$EXIT_INTEGRITY"
    if ! unzip -qq -o -DD "$archive" -d "$destination" >/dev/null 2>&1; then
        support_bundle_log error archive_invalid reason extraction_failed
        return "$EXIT_INTEGRITY"
    fi
    # A ZIP may store a symlink. Nothing but a regular file may survive extraction: the inventory
    # cross-check enumerates regular files, so a symlinked entry would be absent from that set and
    # slip through unverified, and every reader that opens a path with a plain existence test
    # would then follow the link and render whatever it points at.
    irregular="$(find "$destination" -mindepth 1 ! -type f -print -quit)"
    if [ -n "$irregular" ]; then
        support_bundle_log error archive_invalid reason non_regular_entry entry "$(basename "$irregular")"
        return "$EXIT_INTEGRITY"
    fi
}

support_bundle_manifest_schema_version() {
    local manifest="$1"
    jq -r 'if (.schemaVersion | type) == "number" then .schemaVersion else empty end' "$manifest" 2>/dev/null
}

# Verification is total in both directions: every inventory entry must exist on
# disk with the recorded length and digest, and every extracted payload file must
# appear in the inventory. A bundle with an unlisted extra file is rejected
# rather than rendered, because an unlisted file has not passed the redaction
# review that the inventory represents.
support_bundle_verify_inventory() {
    local directory="$1"
    local manifest="$directory/manifest.json"
    local schema_version listed path expected_hash expected_length actual_hash actual_length present
    if [ ! -f "$manifest" ] || [ -L "$manifest" ]; then
        support_bundle_log error manifest_invalid reason manifest_missing
        return "$EXIT_INTEGRITY"
    fi
    if ! jq -e . "$manifest" >/dev/null 2>&1; then
        support_bundle_log error manifest_invalid reason manifest_not_json
        return "$EXIT_INTEGRITY"
    fi
    schema_version="$(support_bundle_manifest_schema_version "$manifest")"
    if [ -z "$schema_version" ]; then
        support_bundle_log error manifest_invalid reason schema_version_missing
        return "$EXIT_INTEGRITY"
    fi
    if [ "$schema_version" != "$SUPPORT_BUNDLE_SCHEMA_VERSION" ]; then
        support_bundle_log error manifest_invalid reason unsupported_schema_version found "$schema_version" supported "$SUPPORT_BUNDLE_SCHEMA_VERSION"
        return "$EXIT_INTEGRITY"
    fi
    if ! jq -e '(.files | type) == "array" and (.files | length) > 0' "$manifest" >/dev/null 2>&1; then
        support_bundle_log error manifest_invalid reason inventory_missing
        return "$EXIT_INTEGRITY"
    fi
    while IFS=$'\t' read -r path expected_length expected_hash; do
        if [ -z "$path" ]; then
            continue
        fi
        if [[ ! "$path" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
            support_bundle_log error manifest_invalid reason unsafe_inventory_path entry "$path"
            return "$EXIT_INTEGRITY"
        fi
        if [ "$path" = manifest.json ]; then
            support_bundle_log error manifest_invalid reason manifest_self_listed
            return "$EXIT_INTEGRITY"
        fi
        if [[ ! "$expected_hash" =~ ^[0-9a-f]{64}$ ]]; then
            support_bundle_log error manifest_invalid reason invalid_digest entry "$path"
            return "$EXIT_INTEGRITY"
        fi
        if [ ! -f "$directory/$path" ] || [ -L "$directory/$path" ]; then
            support_bundle_log error integrity_failure reason inventory_entry_missing entry "$path"
            return "$EXIT_INTEGRITY"
        fi
        actual_length="$(stat -c '%s' "$directory/$path")"
        if [ "$expected_length" != "$actual_length" ]; then
            support_bundle_log error integrity_failure reason length_mismatch entry "$path" expected "$expected_length" actual "$actual_length"
            return "$EXIT_INTEGRITY"
        fi
        actual_hash="$(sha256sum "$directory/$path" | awk '{print $1}')"
        if [ "$expected_hash" != "$actual_hash" ]; then
            support_bundle_log error integrity_failure reason digest_mismatch entry "$path"
            return "$EXIT_INTEGRITY"
        fi
    done < <(jq -r '.files[] | [.path, (.byteLength | tostring), .sha256] | @tsv' "$manifest")
    listed="$(jq -r '.files[].path' "$manifest" | sort)"
    present="$(find "$directory" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | grep -vx 'manifest.json' | sort)"
    if [ "$listed" != "$present" ]; then
        support_bundle_log error integrity_failure reason inventory_mismatch
        return "$EXIT_INTEGRITY"
    fi
}

support_bundle_verify_archive() {
    local archive="$1"
    local directory="$2"
    support_bundle_extract "$archive" "$directory" || return "$EXIT_INTEGRITY"
    support_bundle_verify_inventory "$directory" || return "$EXIT_INTEGRITY"
}
