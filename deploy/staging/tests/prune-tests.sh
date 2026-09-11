#!/usr/bin/env bash
# Offline regression tests for connex-staging-prune.sh.
#
# The reaper is the only thing in the staging tooling permitted to unlink a quarantined release, so
# these assert the refusals as hard as the successes: a protected, too-young, or
# newer-than-the-frontend tree must survive.

set -Eeuo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRUNE="$TESTS_DIR/../connex-staging-prune.sh"
SANDBOX_PARENT="${CONNEX_STAGING_PRUNE_TEST_ROOT:-/var/tmp/connex-staging-prune-tests}"
FAILURES=0

sha() { printf '%040d' "$1" | tr '0' "$2"; }

DEPLOYED="$(sha 1 a)"; ROLLBACK="$(sha 1 b)"; OLD_ONE="$(sha 1 c)"; OLD_TWO="$(sha 1 d)"
KEEP_ONE="$(sha 1 e)"; KEEP_TWO="$(sha 1 f)"; YOUNG="$(sha 1 9)"

fail() { printf 'FAIL %s: %s\n' "$1" "$2" >&2; FAILURES=$((FAILURES + 1)); }
ok() { printf 'ok   %s\n' "$1"; }

assert_dir_exists() {
    if [ -d "$2" ]; then ok "$1"; else fail "$1" "expected $2 to survive"; fi
}
assert_dir_missing() {
    if [ ! -e "$2" ]; then ok "$1"; else fail "$1" "expected $2 to be pruned"; fi
}

setup() {
    local root="$1"
    rm -rf "$root"; mkdir -p "$root/staging/.staging/release-quarantine" "$root/proc/$$"
    local state="$root/staging/.staging" q="$root/staging/.staging/release-quarantine"
    printf '%s\n' "$DEPLOYED" > "$state/deployed-sha"
    printf '%s\n' "$ROLLBACK" > "$state/rollback-sha"
    printf '%s\t%s\n' "$DEPLOYED" "$$" > "$state/frontend-running"
    # Frontend "started" one hour ago.
    touch -d "@$(( $(date +%s) - 3600 ))" "$root/proc/$$"
    for s in "$OLD_ONE" "$OLD_TWO" "$DEPLOYED" "$ROLLBACK"; do
        mkdir -p "$q/$s"; printf 'x\n' > "$q/$s/file"
        touch -d "@$(( $(date +%s) - 259200 ))" "$q/$s"   # 3 days old
    done
    # Distinct mtimes: `sort -rn` is not stable, so equal timestamps would make which entry the
    # keep-recent window retains a coin flip.
    mkdir -p "$q/$KEEP_ONE"; touch -d "@$(( $(date +%s) - 200000 ))" "$q/$KEEP_ONE"
    mkdir -p "$q/$KEEP_TWO"; touch -d "@$(( $(date +%s) - 210000 ))" "$q/$KEEP_TWO"
    mkdir -p "$q/$YOUNG"; touch -d "@$(( $(date +%s) - 60 ))" "$q/$YOUNG"  # 1 minute old
}

run() {
    local root="$1"; shift
    CONNEX_STAGING_DIR="$root/staging" PROC_ROOT="$root/proc" \
        CONNEX_STAGING_PRUNE_KEEP_RECENT=2 bash "$PRUNE" "$@"
}

main() {
    local root="$SANDBOX_PARENT/case"
    setup "$root"
    local q="$root/staging/.staging/release-quarantine"

    run "$root" --dry-run > "$root/dry.log" 2>&1 || { fail dry_run_exits_zero "$(tail -3 "$root/dry.log")"; }
    assert_dir_exists dry_run_removes_nothing "$q/$OLD_ONE"
    if grep -q "Would prune" "$root/dry.log"; then
        ok dry_run_reports_candidates
    else
        fail dry_run_reports_candidates "no candidate reported"
    fi

    run "$root" > "$root/run.log" 2>&1 || { fail prune_exits_zero "$(tail -3 "$root/run.log")"; }

    assert_dir_missing old_entries_are_pruned "$q/$OLD_ONE"
    assert_dir_missing second_old_entry_is_pruned "$q/$OLD_TWO"
    assert_dir_exists committed_release_survives "$q/$DEPLOYED"
    assert_dir_exists rollback_release_survives "$q/$ROLLBACK"
    assert_dir_exists young_entry_survives "$q/$YOUNG"
    assert_dir_exists newest_kept_entry_survives "$q/$KEEP_ONE"
    assert_dir_missing older_kept_entry_is_pruned "$q/$KEEP_TWO"

    # A frontend that started before an entry was quarantined may still hold it, so that entry
    # must survive even though it is old enough.
    setup "$root"
    touch -d "@$(( $(date +%s) - 864000 ))" "$root/proc/$$"
    run "$root" > "$root/recent.log" 2>&1 || fail recent_frontend_exits_zero "$(tail -3 "$root/recent.log")"
    assert_dir_exists entry_newer_than_frontend_survives "$q/$OLD_ONE"

    # No readable frontend marker means the reaper cannot prove anything: refuse.
    setup "$root"; rm -f "$root/staging/.staging/frontend-running"
    if run "$root" > "$root/norun.log" 2>&1; then
        fail refuses_without_frontend_evidence "expected non-zero exit"
    else
        ok refuses_without_frontend_evidence
    fi
    assert_dir_exists refusal_keeps_everything "$q/$OLD_ONE"

    rm -rf "$root"
    [ "$FAILURES" -eq 0 ] || { printf '\n%s failing assertion(s)\n' "$FAILURES" >&2; return 1; }
    printf '\nall prune assertions passed\n'
}

main "$@"
