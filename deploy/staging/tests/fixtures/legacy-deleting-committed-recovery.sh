#!/bin/bash

set -uo pipefail

# Immutable regression fixture for the legacy committed-recovery cleanup. That
# cleanup deleted an eligible quarantine entry after a later clean cycle; current
# production logic must never hand committed recovery back to this behavior.
state_dir="${CONNEX_STAGING_DIR:?}/.staging"
transaction_file="$state_dir/deploy-transaction"
quarantine_dir="$state_dir/release-quarantine"
phase="$(awk -F '\t' '$1 == "phase" { print $2 }' "$transaction_file")" || exit 1
[ "$phase" = "committed" ] || exit 1

while IFS= read -r path; do
    [ -f "$path/.prune-eligible" ] || continue
    rm -rf -- "$path" || exit 1
done < <(find "$quarantine_dir" -mindepth 1 -maxdepth 1 -type d -print)

rm -f "$transaction_file"
