#!/bin/bash
#
# Producer/consumer check: runs the operator reader against an archive the Java
# exporter produced (SupportBundleArchiveDumpTest writes one to
# backend/build/support-bundle/sample-bundle.zip). The offline harness in
# run-tests.sh only exercises archives its own shell code generates, so a Java
# change the reader cannot verify or render could otherwise pass CI.
#
# Fails when read.sh refuses the archive (for example an unsupported schema
# version or an integrity mismatch), when rendering fails, or when the manifest
# inventories an entry the reader has no section for and would silently skip.
#
# Usage: deploy/support-bundle/tests/read-java-archive.sh ARCHIVE

set -uo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(cd "$TESTS_DIR/.." && pwd)"

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    printf 'usage: %s ARCHIVE (an existing Java-produced support bundle)\n' "$0" >&2
    exit 64
fi
# read.sh accepts only absolute archive paths.
ARCHIVE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

for command in jq unzip; do
    if ! command -v "$command" >/dev/null 2>&1; then
        printf 'harness error: %s is required\n' "$command" >&2
        exit 64
    fi
done

# Every entry the Java exporter may inventory, mapped to the heading read.sh
# prints for it. A new exporter entry must be added here and taught to read.sh
# together, or this check fails.
declare -A SECTION_HEADING=(
    [readiness.json]="Readiness"
    [config.json]="Configuration (allowlisted)"
    [migrations.json]="Migrations"
    [job-runs.json]="Job runs"
    [client-errors.json]="Client errors"
    [audit-slice.csv]="Audit slice"
    [journal-slice.jsonl]="Journal slice"
)

rendered="$(bash "$BUNDLE_DIR/read.sh" --archive "$ARCHIVE")"
status=$?
if [ "$status" -ne 0 ]; then
    printf 'FAIL read.sh refused or could not render the Java-produced archive (exit %s)\n' "$status"
    exit 1
fi

if ! inventory="$(unzip -p "$ARCHIVE" manifest.json | jq -er '.files[].path')"; then
    printf 'FAIL the Java-produced archive has no readable manifest inventory\n'
    exit 1
fi

failures=0
require_heading() {
    if ! grep -Fxq "== $1 ==" <<< "$rendered"; then
        printf 'FAIL rendered output is missing the "%s" section\n' "$1"
        failures=$((failures + 1))
    fi
}

require_heading "Manifest"
require_heading "Inventory (verified)"
while IFS= read -r path; do
    [ -n "$path" ] || continue
    heading="${SECTION_HEADING[$path]:-}"
    if [ -z "$heading" ]; then
        printf 'FAIL the manifest inventories %s, which read.sh has no section for\n' "$path"
        failures=$((failures + 1))
        continue
    fi
    require_heading "$heading"
done <<< "$inventory"

if [ "$failures" -ne 0 ]; then
    exit 1
fi
printf 'ok   read.sh verified and rendered every inventoried section of %s\n' "$ARCHIVE"
