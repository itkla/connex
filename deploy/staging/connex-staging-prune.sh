#!/usr/bin/env bash
# Reclaims disk from the staging release quarantine.
#
# Why this is a separate program from connex-staging-deploy.sh
# -----------------------------------------------------------
# The deploy script moves superseded releases into the quarantine with an atomic rename and never
# unlinks them. That is deliberate: open file handles and working directories follow the inode, so
# a consumer that entered a tree between the scan and the move keeps a complete runtime. Its tests
# assert the property (`later_clean_cycle_never_unlinks_quarantine`), and the deploy path keeps it.
#
# Nothing else ever freed the space, so the volume only grew. Quarantine refilled on every deploy,
# the filesystem settled just under the deploy preflight's free-space floor, and staging stopped
# updating until someone pruned by hand. This runs on its own schedule, well away from a deploy,
# and removes only entries it can show are finished with.
#
# Safety rules, in order of application:
#   1. Only ever touches $STATE_DIR/release-quarantine. Never the live releases directory.
#   2. Refuses any entry that is not a bare git SHA directory, and never follows symlinks.
#   3. Refuses the committed, rollback, and attested-running releases even if they appear there.
#   4. Requires the entry to be older than the minimum age, so a tree quarantined by a deploy that
#      is still settling is never a candidate.
#   5. Requires the running frontend to have started after the entry was quarantined. A process
#      cannot hold a tree that was already quarantined before the process existed.
#
#   6. No live process may reference the tree. /proc is scanned for cwd, root, exe, open descriptors
#      and mapped files under the path. This is the primary proof; the age and restart gates above
#      are the belt and braces behind it.
#
# It also refuses to run at all while a deploy holds the deploy lock, so it never races the rename
# it depends on.
#
# Exit codes: 0 nothing to do or pruned cleanly, 1 refused for safety or an error.

set -Eeuo pipefail

STAGING_DIR="${CONNEX_STAGING_DIR:-/opt/connex-staging}"
STATE_DIR="$STAGING_DIR/.staging"
RELEASE_QUARANTINE_DIR="$STATE_DIR/release-quarantine"
MARKER="$STATE_DIR/deployed-sha"
ROLLBACK_MARKER="$STATE_DIR/rollback-sha"
FRONTEND_RUNNING_MARKER="$STATE_DIR/frontend-running"
LOG_TAG="connex-staging-prune"

# A tree must sit unclaimed for this long before it is a candidate.
MIN_AGE_SECONDS="${CONNEX_STAGING_PRUNE_MIN_AGE_SECONDS:-86400}"

# Entries to keep even when they are old enough, newest first, for post-mortems.
KEEP_RECENT="${CONNEX_STAGING_PRUNE_KEEP_RECENT:-2}"

LOCK_FILE="${CONNEX_DEPLOY_LOCK_FILE:-/tmp/connex-staging-deploy.lock}"

DRY_RUN=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        # A mistyped flag must never be read as "delete for real".
        *) printf '[%s] Unknown argument: %s (accepts --dry-run)\n' "$LOG_TAG" "$1" >&2; exit 1 ;;
    esac
    shift
done

log() { printf '[%s] %s\n' "$LOG_TAG" "$*"; }

is_git_sha() { [[ "$1" =~ ^[0-9a-f]{40}$ ]]; }

read_sha_file() {
    local file="$1" value
    [ -f "$file" ] || return 1
    value="$(head -n 1 -- "$file")" || return 1
    is_git_sha "$value" || return 1
    printf '%s\n' "$value"
}

# Any live process referencing a path keeps it alive, whatever the markers say. Covers the working
# directory, the filesystem root, the executable, open descriptors and mapped files. The scan sees
# what this user can read; the age and restart gates cover what it cannot.
path_referenced_by_any_process() {
    local path="$1" proc="${PROC_ROOT:-/proc}" pid link
    for pid in "$proc"/[0-9]*; do
        [ -d "$pid" ] || continue
        for link in "$pid/cwd" "$pid/root" "$pid/exe"; do
            link="$(readlink -- "$link" 2>/dev/null)" || continue
            case "$link" in "$path"|"$path"/*) return 0 ;; esac
        done
        while IFS= read -r link; do
            case "$link" in "$path"|"$path"/*) return 0 ;; esac
        done < <(readlink -f -- "$pid"/fd/* 2>/dev/null || true)
        if [ -r "$pid/maps" ] && grep -qF -- "$path" "$pid/maps" 2>/dev/null; then
            return 0
        fi
    done
    return 1
}

# Seconds since epoch at which the running frontend started, via its PID's /proc entry.
frontend_started_at() {
    local running pid stat_file
    [ -f "$FRONTEND_RUNNING_MARKER" ] || return 1
    running="$(head -n 1 -- "$FRONTEND_RUNNING_MARKER")" || return 1
    pid="$(printf '%s' "$running" | cut -f2)" || return 1
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    stat_file="${PROC_ROOT:-/proc}/$pid"
    [ -d "$stat_file" ] || return 1
    stat -c %Y -- "$stat_file"
}

main() {
    # Never race a deploy: it is the thing performing the renames this program reasons about.
    if [ -e "$LOCK_FILE" ]; then
        exec 9>>"$LOCK_FILE" || { log "Refused: cannot open the deploy lock"; return 1; }
        if ! flock -n 9; then
            log "A deploy holds the lock; skipping this run"
            return 0
        fi
    fi

    if [ ! -d "$RELEASE_QUARANTINE_DIR" ]; then
        log "No quarantine directory at $RELEASE_QUARANTINE_DIR; nothing to do"
        return 0
    fi

    local deployed rollback running_sha started now
    deployed="$(read_sha_file "$MARKER")" || { log "Refused: committed release marker is unreadable"; return 1; }
    rollback="$(read_sha_file "$ROLLBACK_MARKER")" || { log "Refused: rollback marker is unreadable"; return 1; }
    running_sha="$(head -n 1 -- "$FRONTEND_RUNNING_MARKER" 2>/dev/null | cut -f1)" || running_sha=""

    if ! started="$(frontend_started_at)"; then
        log "Refused: cannot establish when the running frontend started"
        return 1
    fi
    now="$(date +%s)"

    local entries path sha age index=0 pruned=0 freed=0 size
    entries="$(find "$RELEASE_QUARANTINE_DIR" -mindepth 1 -maxdepth 1 -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | cut -d' ' -f2-)" || return 1
    [ -n "$entries" ] || { log "Quarantine is empty; nothing to do"; return 0; }

    while IFS= read -r path; do
        [ -n "$path" ] || continue
        sha="$(basename -- "$path")"

        if [ -L "$path" ] || [ ! -d "$path" ] || ! is_git_sha "$sha"; then
            log "Skipped $sha: not a plain release directory"
            continue
        fi
        if [ "$sha" = "$deployed" ] || [ "$sha" = "$rollback" ] || [ "$sha" = "$running_sha" ]; then
            log "Skipped $sha: protected release"
            continue
        fi
        index=$((index + 1))
        if [ "$index" -le "$KEEP_RECENT" ]; then
            log "Skipped $sha: within the $KEEP_RECENT most recent entries"
            continue
        fi

        # rename(2) preserves mtime and updates ctime, so mtime here is when the release was built,
        # not when it was retired. Measuring age from mtime would have made a freshly quarantined
        # tree look days old and eligible for deletion.
        local quarantined_at
        quarantined_at="$(stat -c %Z -- "$path")" || continue
        age=$((now - quarantined_at))
        if [ "$age" -lt "$MIN_AGE_SECONDS" ]; then
            log "Skipped $sha: quarantined ${age}s ago, below the ${MIN_AGE_SECONDS}s minimum"
            continue
        fi
        if [ "$quarantined_at" -ge "$started" ]; then
            log "Skipped $sha: quarantined after the running frontend started"
            continue
        fi

        if path_referenced_by_any_process "$path"; then
            log "Skipped $sha: a live process still references the tree"
            continue
        fi

        size="$(du -s -B1 -- "$path" | awk 'NR == 1 { print $1 }')" || size=0
        if [ "$DRY_RUN" -eq 1 ]; then
            log "Would prune $sha (${size} bytes)"
            continue
        fi
        if find "$path" -mindepth 1 -delete && rmdir -- "$path"; then
            pruned=$((pruned + 1))
            freed=$((freed + size))
            log "Pruned $sha (${size} bytes)"
        else
            log "Refused: could not remove $sha"
            return 1
        fi
    done <<< "$entries"

    log "Done — pruned $pruned entries, reclaimed $freed bytes; $(df --output=avail -B1 "$STATE_DIR" | awk 'NR == 2 { print $1 }') bytes now available"
}

main "$@"
