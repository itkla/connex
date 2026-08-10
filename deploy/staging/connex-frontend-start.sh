#!/bin/bash
#
# Staging-aware frontend launcher. Local and ordinary production `pnpm start`
# retain the normal Next start behavior when no sealed staging release is active.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
STATE_DIR="${CONNEX_STAGING_STATE_DIR:-$REPO_DIR/.staging}"
ACTIVE_RELEASE="$STATE_DIR/frontend-release"
RUNNING_RELEASE="$STATE_DIR/frontend-running"

if [ -r "$ACTIVE_RELEASE" ]; then
    sha="$(sed -n '1p' "$ACTIVE_RELEASE")"
    if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
        echo "[connex-staging-frontend] Refusing invalid active release identity" >&2
        exit 1
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
