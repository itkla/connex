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

# The exit-code constants below are readonly, so sourcing this file twice — or sourcing it
# alongside deploy/backup/connex-backup-lib.sh, whose EXIT_INTEGRITY is 69 where this catalog uses
# 67 — would abort the shell on the redeclaration. Guard the definitions and refuse the collision
# explicitly rather than failing with an opaque readonly-variable error.
if [ -n "${SUPPORT_BUNDLE_LIB_LOADED:-}" ]; then
    return 0
fi
if [ -n "${EXIT_INTEGRITY:-}" ] && [ "${EXIT_INTEGRITY}" != 67 ]; then
    printf 'support-bundle-lib.sh: EXIT_INTEGRITY is already %s; refusing to load alongside a conflicting exit-code catalog\n' \
        "$EXIT_INTEGRITY" >&2
    return 64
fi
# Deliberately not exported: an exported flag would make a child process that sources
# this library return early without defining any of its functions.
declare -r SUPPORT_BUNDLE_LIB_LOADED=1

declare -rx EXIT_USAGE=64
declare -rx EXIT_AUTH=65
declare -rx EXIT_API=66
declare -rx EXIT_INTEGRITY=67
declare -rx EXIT_JOURNAL=68
declare -rx EXIT_READ=69

declare -rx SUPPORT_BUNDLE_SCHEMA_VERSION=1

# Mirrors the backend's own uncompressed ceiling.
declare -rx SUPPORT_BUNDLE_MAX_UNCOMPRESSED_BYTES=67108864

# The closed set of entries a bundle of this schema version may contain.
declare -rx SUPPORT_BUNDLE_KNOWN_ENTRIES='["readiness.json","config.json","migrations.json","audit-slice.csv","job-runs.json","client-errors.json","journal-slice.jsonl"]'

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
    # Percent-escaping the field separators is not enough. Log values carry attacker-controlled
    # strings — a rejected ZIP entry name and a rejected manifest path are both logged precisely
    # BECAUSE they failed a charset check — and a raw ESC lets the archive drive the operator's
    # terminal: ESC[2K with ESC[1A erases the failure lines just written, and ESC[8m conceals
    # everything after it including the failure summary. Drop every remaining control byte.
    printf '%s' "$value" | LC_ALL=C tr -d '\000-\010\013-\037\177'
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
    # The endpoint parses with Instant.parse, which accepts fractional seconds. Rejecting them
    # here would refuse a timestamp copied straight out of a manifest this tool produced.
    if [[ ! "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$ ]]; then
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
    # A query string, fragment or user-info component would silently corrupt the request: the
    # endpoint path is appended as text, so "https://host?tenant=x" would put /api/orgs/... inside
    # the query, and a fragment would never be transmitted at all.
    if [[ "$value" == *"?"* || "$value" == *"#"* || "$value" == *"@"* ]]; then
        support_bundle_log error config_error reason base_url_has_query_fragment_or_userinfo
        return "$EXIT_USAGE"
    fi
    if [[ "$value" =~ ^https://[A-Za-z0-9._-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$ ]]; then
        return 0
    fi
    # Plaintext HTTP is only ever acceptable against a loopback development
    # backend; anywhere else it would put the session cookie on the wire.
    if [[ "$value" =~ ^http://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$ ]]; then
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
    # The path is passed as an awk variable rather than on stdin: a path containing a newline
    # would otherwise be split into separate records, and only the first would be redacted while
    # the rest were emitted verbatim. Journal-sourced paths are attacker-influenced, so that
    # divergence from the Java redactor — which treats the whole string as one value — matters.
    awk -v raw="$path" '
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
        BEGIN {
            count = split(raw, segments, "/")
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
    ' < /dev/null
}

# Bundle content is attacker-controlled text. Terminals act on control sequences, so an omission
# reason or CSV cell carrying ESC[2K and a carriage return can repaint the line the operator just
# read and forge a success summary. Only tab and newline survive; every other control byte,
# carriage return included, is dropped before anything reaches the terminal. This affects display
# only — the archive on disk and the bytes that were hashed are never modified.
support_bundle_sanitize_output() {
    LC_ALL=C tr -d '\000-\010\013-\037\177'
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
    local irregular declared
    # The size guard runs first: it is the cheapest check and it is the one protecting the
    # operator's filesystem, so a decompression bomb must be refused before any other inspection.
    declared="$(zipinfo -t "$archive" 2>/dev/null | awk '{print $3}')"
    if [[ "$declared" =~ ^[0-9]+$ ]] \
        && [ "$declared" -gt "$SUPPORT_BUNDLE_MAX_UNCOMPRESSED_BYTES" ]; then
        support_bundle_log error archive_invalid reason uncompressed_size_exceeded \
            declared "$declared" limit "$SUPPORT_BUNDLE_MAX_UNCOMPRESSED_BYTES"
        return "$EXIT_INTEGRITY"
    fi
    support_bundle_validate_entry_names "$archive" || return "$EXIT_INTEGRITY"
    # The declared size above comes from the archive's own central directory and can lie, so
    # extraction additionally runs under a hard file-size rlimit: an oversized member is killed by
    # the kernel rather than being allowed to fill the filesystem.
    if ! ( ulimit -f "$(( SUPPORT_BUNDLE_MAX_UNCOMPRESSED_BYTES / 512 ))" 2>/dev/null
           unzip -qq -o -DD "$archive" -d "$destination" >/dev/null 2>&1 ); then
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
    local rows expected_rows rows_read verify_failure
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
    # jq's exit status must be observed BEFORE the loop. A process-substitution redirect is not
    # covered by pipefail, and errexit is disabled in the caller's `|| exit_code=$?` context, so a
    # jq abort would simply produce zero rows: every hash, length, path and self-listing check
    # would be skipped and the function would fall through and return success. Any field legal for
    # .files[].path but illegal for @tsv — an array-valued sha256, say — triggers it. The row file
    # lives outside the extraction directory so it cannot disturb the listed/present cross-check.
    rows="$(mktemp "${TMPDIR:-/tmp}/connex-support-bundle-rows.XXXXXX")"
    if ! jq -er '.files[] | [.path, (.byteLength | tostring), .sha256] | @tsv' \
            "$manifest" > "$rows" 2>/dev/null; then
        rm -f "$rows"
        support_bundle_log error manifest_invalid reason inventory_not_projectable
        return "$EXIT_INTEGRITY"
    fi
    expected_rows="$(jq -r '.files | length' "$manifest")"
    rows_read=0
    verify_failure=0
    while IFS=$'\t' read -r path expected_length expected_hash; do
        if [ -z "$path" ]; then
            continue
        fi
        rows_read=$((rows_read + 1))
        if [[ ! "$path" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
            support_bundle_log error manifest_invalid reason unsafe_inventory_path entry "$path"
            verify_failure=1
            break
        fi
        if [ "$path" = manifest.json ]; then
            support_bundle_log error manifest_invalid reason manifest_self_listed
            verify_failure=1
            break
        fi
        if [[ ! "$expected_hash" =~ ^[0-9a-f]{64}$ ]]; then
            support_bundle_log error manifest_invalid reason invalid_digest entry "$path"
            verify_failure=1
            break
        fi
        if [ ! -f "$directory/$path" ] || [ -L "$directory/$path" ]; then
            support_bundle_log error integrity_failure reason inventory_entry_missing entry "$path"
            verify_failure=1
            break
        fi
        actual_length="$(stat -c '%s' "$directory/$path")"
        if [ "$expected_length" != "$actual_length" ]; then
            support_bundle_log error integrity_failure reason length_mismatch entry "$path" expected "$expected_length" actual "$actual_length"
            verify_failure=1
            break
        fi
        actual_hash="$(sha256sum "$directory/$path" | awk '{print $1}')"
        if [ "$expected_hash" != "$actual_hash" ]; then
            support_bundle_log error integrity_failure reason digest_mismatch entry "$path"
            verify_failure=1
            break
        fi
    done < "$rows"
    rm -f "$rows"
    if [ "$verify_failure" -ne 0 ]; then
        return "$EXIT_INTEGRITY"
    fi
    # Belt and braces: even with the status checked, assert every declared row was actually
    # examined, so a truncated projection can never masquerade as a complete verification.
    if [ "$rows_read" != "$expected_rows" ]; then
        support_bundle_log error integrity_failure reason inventory_rows_unverified expected "$expected_rows" verified "$rows_read"
        return "$EXIT_INTEGRITY"
    fi
    local unexpected
    unexpected="$(jq -r --argjson known "$SUPPORT_BUNDLE_KNOWN_ENTRIES" \
        '[.files[].path] - $known | .[]' "$manifest")"
    if [ -n "$unexpected" ]; then
        support_bundle_log error manifest_invalid reason unknown_inventory_entry \
            entry "$(printf '%s' "$unexpected" | head -n 1)"
        return "$EXIT_INTEGRITY"
    fi
    # Every entry the schema requires must be present or explicitly declared as omitted; silence
    # is not an acceptable third state for a diagnostic the reader promises to render.
    local required present_or_omitted
    for required in readiness.json config.json migrations.json audit-slice.csv; do
        present_or_omitted="$(jq -r --arg path "$required" \
            'if ([.files[].path] | index($path)) != null then "present"
             elif (.omissions | has($path)) then "omitted"
             else "missing" end' "$manifest")"
        if [ "$present_or_omitted" = missing ]; then
            support_bundle_log error manifest_invalid reason required_entry_absent entry "$required"
            return "$EXIT_INTEGRITY"
        fi
    done
    listed="$(jq -r '.files[].path' "$manifest" | sort)"
    present="$(find "$directory" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | grep -vx 'manifest.json' | sort)"
    if [ "$listed" != "$present" ]; then
        support_bundle_log error integrity_failure reason inventory_mismatch
        return "$EXIT_INTEGRITY"
    fi
}

# An optional journal entry is accepted only in the exact shape produced by collect.sh. Parsing
# every line again keeps read.sh fail-closed if an archive is modified after collection or a future
# producer tries to widen the projection without updating the schema contract deliberately.
support_bundle_verify_journal_slice() {
    local directory="$1"
    local manifest="$directory/manifest.json"
    local slice="$directory/journal-slice.jsonl"
    if [ ! -f "$slice" ]; then
        if ! jq -e '.journalSlice == null' "$manifest" >/dev/null 2>&1; then
            support_bundle_log error manifest_invalid reason journal_metadata_without_entry
            return "$EXIT_INTEGRITY"
        fi
        return 0
    fi
    if ! jq -e '
        (.orgId | type) == "number"
        and .orgId > 0
        and .orgId == (.orgId | floor)
        and (.journalSlice | type) == "object"
        and (.journalSlice | keys) == ["organizationDiscriminator", "organizationId", "projection", "redactor", "unit"]
        and .journalSlice.organizationId == .orgId
        and .journalSlice.organizationDiscriminator == "connexOrganizationId"
        and .journalSlice.projection == "ecs_message_closed_fields_v1"
        and .journalSlice.redactor == "request_path_redactor_v1"
        and (.journalSlice.unit | type) == "string"
    ' "$manifest" >/dev/null 2>&1; then
        support_bundle_log error manifest_invalid reason journal_metadata_invalid
        return "$EXIT_INTEGRITY"
    fi
    local unit
    unit="$(jq -r '.journalSlice.unit' "$manifest")"
    if [[ ! "$unit" =~ ^[A-Za-z0-9@._-]+$ ]]; then
        support_bundle_log error manifest_invalid reason journal_unit_invalid
        return "$EXIT_INTEGRITY"
    fi
    if ! python3 - "$slice" <<'PY'
import json
import re
import sys
import unicodedata

ALLOWED_KEYS = {
    "timestamp", "level", "logger", "correlationId",
    "method", "path", "status", "eventClass",
}
CORRELATION = re.compile(r"[A-Za-z0-9_-]{8,64}\Z")
TIMESTAMP = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z\Z")
METHODS = {"DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"}
LOGGER = "ooo.klae.connex.backend.tenant.TenantResolutionInterceptor"
EVENT_CLASS = "http.request.completed"

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

with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        if len(line) > 4096:
            raise ValueError("journal projection line exceeds the schema bound")
        value = json.loads(line, object_pairs_hook=unique_object)
        if not isinstance(value, dict) or set(value) != ALLOWED_KEYS:
            raise ValueError("journal projection keys do not match the closed schema")
        status = value["status"]
        if (
            not isinstance(value["timestamp"], str)
            or TIMESTAMP.fullmatch(value["timestamp"]) is None
            or value["level"] != "INFO"
            or value["logger"] != LOGGER
            or not isinstance(value["correlationId"], str)
            or CORRELATION.fullmatch(value["correlationId"]) is None
            or value["method"] not in METHODS
            or not safe_path(value["path"])
            or isinstance(status, bool)
            or not isinstance(status, int)
            or status < 100
            or status > 599
            or value["eventClass"] != EVENT_CLASS
        ):
            raise ValueError("journal projection value violates the closed schema")
PY
    then
        support_bundle_log error integrity_failure reason journal_projection_invalid
        return "$EXIT_INTEGRITY"
    fi
}

# Publishes the verified archive, refusing to overwrite an existing file.
#
# `ln` is preferred because its EEXIST failure is atomic, closing the window between an earlier
# existence check and the publish. But a hardlink cannot cross a filesystem boundary, and the
# default output path routinely does: the work directory lives under TMPDIR, which is tmpfs on
# most current distributions, while the output lands on the operator's disk. So EXDEV falls back
# to an exclusive create — `set -C` makes that atomic too — followed by a copy. Every other ln
# failure is reported rather than being silently treated as "already exists".
support_bundle_publish() {
    local source="$1"
    local destination="$2"
    local ln_error
    ln_error="$(ln "$source" "$destination" 2>&1)" && return 0
    if [ -e "$destination" ] || [ -L "$destination" ]; then
        support_bundle_log error publish_refused reason output_exists path "$destination"
        return "$EXIT_USAGE"
    fi
    case "$ln_error" in
        *[Cc]ross-device*|*EXDEV*|*"Invalid cross-device link"*)
            support_bundle_log info publish_fallback reason cross_device path "$destination"
            ;;
        *)
            support_bundle_log error publish_failed reason link_failed detail "$ln_error"
            return "$EXIT_INTEGRITY"
            ;;
    esac
    # Stage on the DESTINATION filesystem and link into place, so the output path never exists in
    # a partially-written state: a concurrent reader either sees no file or sees the complete
    # archive, and a crash mid-copy leaves only the staging file behind.
    local staging
    staging="$(mktemp "${destination}.partial.XXXXXX")" || {
        support_bundle_log error publish_failed reason staging_failed path "$destination"
        return "$EXIT_INTEGRITY"
    }
    chmod 0600 "$staging"
    if ! cp "$source" "$staging"; then
        rm -f "$staging"
        support_bundle_log error publish_failed reason copy_failed path "$destination"
        return "$EXIT_INTEGRITY"
    fi
    if ! ln "$staging" "$destination" 2>/dev/null; then
        rm -f "$staging"
        support_bundle_log error publish_refused reason output_exists path "$destination"
        return "$EXIT_USAGE"
    fi
    rm -f "$staging"
}

support_bundle_verify_archive() {
    local archive="$1"
    local directory="$2"
    support_bundle_extract "$archive" "$directory" || return "$EXIT_INTEGRITY"
    support_bundle_verify_inventory "$directory" || return "$EXIT_INTEGRITY"
    support_bundle_verify_journal_slice "$directory" || return "$EXIT_INTEGRITY"
}
