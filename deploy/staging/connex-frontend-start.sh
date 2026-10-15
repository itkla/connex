#!/bin/bash
#
# Staging-aware frontend launcher. Local and ordinary production `pnpm start`
# retain the normal Next start behavior when no sealed staging release is active.
# A sealed release must match the backend authorized by the deploy transaction,
# including after a reboot; malformed transactions refuse startup.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
STATE_DIR="${CONNEX_STAGING_STATE_DIR:-$REPO_DIR/.staging}"
ACTIVE_RELEASE="$STATE_DIR/frontend-release"
RUNNING_RELEASE="$STATE_DIR/frontend-running"
TRANSACTION_FILE="$STATE_DIR/deploy-transaction"

# Keep the parser self-contained: pinned deploy logic runs before the checkout
# changes, so it cannot safely source a helper from a different release.
read_transaction() {
    local manifest="$TRANSACTION_FILE"
    [ -f "$manifest" ] && [ ! -L "$manifest" ] && [ -r "$manifest" ] || return 1
    awk -F '\t' '
        function sha(value) { return length(value) == 40 && value ~ /^[0-9a-f]+$/ }
        NF != 2 || $1 !~ /^(schema_version|prior_sha|target_sha|retained_sha|phase)$/ || seen[$1]++ {
            invalid = 1; next
        }
        { value[$1] = $2 }
        END {
            if (invalid || NR != 5 || value["schema_version"] != "2" ||
                !sha(value["prior_sha"]) || !sha(value["target_sha"]) || !sha(value["retained_sha"]) ||
                value["phase"] !~ /^(prepared|frontend_stopped|backend_starting|backend_live|frontend_live|forward_only|rollback_starting|rollback_live|committed)$/) exit 1
            printf "%s\t%s\t%s\t%s\n", value["prior_sha"], value["target_sha"], value["retained_sha"], value["phase"]
        }
    ' "$manifest"
}

if [ -r "$ACTIVE_RELEASE" ]; then
    sha="$(sed -n '1p' "$ACTIVE_RELEASE")"
    if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
        echo "[connex-staging-frontend] Refusing invalid active release identity" >&2
        exit 1
    fi
    if [ -e "$TRANSACTION_FILE" ] || [ -L "$TRANSACTION_FILE" ]; then
        if ! transaction="$(read_transaction)"; then
            echo "[connex-staging-frontend] Refusing sealed startup: deploy transaction is malformed or unreadable" >&2
            exit 1
        fi
        IFS=$'\t' read -r prior target _ phase <<< "$transaction"
        expected="$target"
        case "$phase" in
            prepared|frontend_stopped) expected="$sha" ;;
            rollback_starting)
                echo "[connex-staging-frontend] Refusing sealed startup: rollback backend activation is incomplete" >&2
                exit 1
                ;;
            rollback_live) expected="$prior" ;;
        esac
        if [ "$sha" != "$expected" ]; then
            echo "[connex-staging-frontend] Refusing sealed startup: frontend release does not match the deploy transaction's backend" >&2
            exit 1
        fi
    fi
    runtime="$STATE_DIR/releases/$sha/frontend"
    runtime_sha="$(sed -n '1p' "$runtime/release-sha" 2>/dev/null || true)"
    if [ "$runtime_sha" != "$sha" ] || [ ! -f "$runtime/server.js" ]; then
        echo "[connex-staging-frontend] Refusing missing or mismatched release runtime for ${sha:0:8}" >&2
        exit 1
    fi
    cd "$runtime"
    running_temporary="$(mktemp "$STATE_DIR/.frontend-running.XXXXXX")"
    if ! printf '%s\t%s\n' "$sha" "$$" > "$running_temporary" \
        || ! mv -f "$running_temporary" "$RUNNING_RELEASE"; then
        rm -f "$running_temporary"
        exit 1
    fi
    exec node server.js
fi

rm -f "$RUNNING_RELEASE"
cd "$REPO_DIR/frontend"
exec node_modules/.bin/next start
