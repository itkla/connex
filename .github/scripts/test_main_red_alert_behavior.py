"""Execute the red-main alert's shell step against a gh stub.

The other test asserts the step's text. Four review rounds of shell defects — an empty-array crash, a
recheck that failed open, a comment landing on the wrong issue — all survived text assertions because
none ran the script. This one does: it pulls the exact `run:` block from the workflow, puts a
recording `gh` stub on PATH, and asserts what the step actually does.
"""

import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
ALERT = ROOT / ".github/workflows/main-red-alert.yml"
STEP = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["steps"][0]["run"]

CURRENT_MAIN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

# A gh stub whose behaviour each test tunes through GH_SCENARIO. It records issue mutations to
# GH_LOG so a test can see what the step tried to do.
GH_STUB = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    log() { printf '%s\\n' "$*" >> "$GH_LOG"; }
    case "$1 $2" in
      "api repos/"*"/commits/main"*)
        n=$(( $(cat "$GH_MAIN_CALLS" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$GH_MAIN_CALLS"
        if [ "$n" = "${GH_MAIN_FAIL_ON_CALL:-0}" ]; then exit 1; fi
        printf '%s' "$GH_CURRENT_MAIN"; exit 0 ;;
      "api repos/"*"/actions/workflows/"*)
        if [ "${GH_WORKFLOW_RUNS_FAILS:-}" = "1" ]; then exit 1; fi
        case "$2" in
          *"event=${GH_SUPERSEDING_EVENT}&"*) printf '%s' "$GH_LATEST_RUN" ;;
          *) printf '%s' "$RUN_ID" ;;
        esac
        exit 0 ;;
      "api repos/"*"/issues/"*)
        if [ "${GH_ISSUE_READ_FAILS:-0}" = "1" ]; then exit 1; fi
        number="${2##*/}"; state=closed; labels='[{"name":"ci-main-red"}]'
        for failing in ${GH_ISSUE_READ_FAILS_ON:-}; do
          [ "$failing" = "$number" ] && exit 1
        done
        for open in $(cat "$GH_CREATED") ${GH_OPEN_ISSUES:-}; do
          [ "$open" = "$number" ] && state=open
        done
        for unlabeled in ${GH_UNLABELED_ISSUES:-}; do
          [ "$unlabeled" = "$number" ] && labels='[]'
        done
        jqexpr=""; prev=""
        for a in "$@"; do [ "$prev" = "--jq" ] && jqexpr="$a"; prev="$a"; done
        printf '{"state":"%s","labels":%s}' "$state" "$labels" | jq -r "$jqexpr"; exit 0 ;;
      "api repos/"*"/actions/runs/"*)
        jqexpr=""; prev=""
        for a in "$@"; do [ "$prev" = "--jq" ] && jqexpr="$a"; prev="$a"; done
        printf '{"status":"%s","conclusion":%s}' "$GH_RUN_STATUS" "$GH_RUN_CONCLUSION" \
          | jq -r "$jqexpr"; exit 0 ;;
      "issue list"*)
        c=$(( $(cat "$GH_LIST_CALLS" 2>/dev/null || echo 0) + 1 )); echo "$c" > "$GH_LIST_CALLS"
        if [ -n "${GH_LIST_FAIL_FROM:-}" ] && [ "$c" -ge "$GH_LIST_FAIL_FROM" ]; then exit 1; fi
        val="$GH_ISSUE_LIST"
        [ "$c" -ge 2 ] && [ -n "${GH_ISSUE_LIST_2:-}" ] && val="$GH_ISSUE_LIST_2"
        [ "$c" -ge 3 ] && [ -n "${GH_ISSUE_LIST_3:-}" ] && val="$GH_ISSUE_LIST_3"
        jqexpr=""; prev=""
        for a in "$@"; do [ "$prev" = "--jq" ] && jqexpr="$a"; prev="$a"; done
        if [ -n "$jqexpr" ]; then printf '%s' "$val" | jq -r "$jqexpr"
        else printf '%s' "$val"; fi
        exit 0 ;;
      "issue comment"*)
        for failing in ${GH_COMMENT_FAILS_ON:-}; do
          [ "$failing" = "$3" ] && exit 1
        done
        log "comment ${3}"; exit 0 ;;
      "issue create"*)
        log "create"
        number=$(( $(wc -l < "$GH_CREATED") + 31 )); echo "$number" >> "$GH_CREATED"
        printf 'https://github.com/o/r/issues/%s\n' "$number"; exit 0 ;;
      "issue close"*)
        comment=""; prev=""
        for a in "$@"; do [ "$prev" = "--comment" ] && comment="$a"; prev="$a"; done
        log "close ${3} $comment"; exit 0 ;;
      "label create"*) exit 0 ;;
      *) exit 0 ;;
    esac
    """
)


def run_step(env_overrides, gh_env):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        gh = tmp / "gh"
        gh.write_text(GH_STUB)
        gh.chmod(gh.stat().st_mode | stat.S_IEXEC)
        log = tmp / "gh.log"
        log.touch()
        (tmp / "main.calls").write_text("0")
        (tmp / "list.calls").write_text("0")
        (tmp / "created").write_text("")
        env = {
            **os.environ,
            "PATH": f"{tmp}:{os.environ['PATH']}",
            "GH_TOKEN": "x",
            "REPO": "o/r",
            "RUN_URL": "https://run",
            "RUN_NAME": "CI",
            "RUN_ID": "1000",
            "RUN_EVENT": "push",
            "WORKFLOW_ID": "42",
            "HEAD_SHA": CURRENT_MAIN,
            "CONCLUSION": "failure",
            "GH_LOG": str(log),
            "GH_MAIN_CALLS": str(tmp / "main.calls"),
            "GH_MAIN_FAIL_ON_CALL": "0",
            "GH_LIST_CALLS": str(tmp / "list.calls"),
            "GH_CREATED": str(tmp / "created"),
            "GH_OPEN_ISSUES": "",
            "GH_UNLABELED_ISSUES": "",
            "GH_COMMENT_FAILS_ON": "",
            "SWEEP_RELIST_DELAY_SECONDS": "0",
            "GH_ISSUE_READ_FAILS": "0",
            "GH_ISSUE_READ_FAILS_ON": "",
            "GH_RUN_STATUS": "completed",
            "GH_RUN_CONCLUSION": '"failure"',
            "GH_ISSUE_LIST_2": "",
            "GH_ISSUE_LIST_3": "",
            "GH_LIST_FAIL_FROM": "",
            "GH_WORKFLOW_RUNS_FAILS": "0",
            "GH_CURRENT_MAIN": CURRENT_MAIN,
            "GH_LATEST_RUN": "1000",
            "GH_SUPERSEDING_EVENT": "push",
            "GH_ISSUE_LIST": "[]",
            **gh_env,
            **env_overrides,
        }
        result = subprocess.run(
            ["bash", "-c", STEP], env=env, capture_output=True, text=True)
        return result, log.read_text().splitlines()


class RedMainAlertBehavior(unittest.TestCase):

    def test_a_genuine_failure_on_current_main_opens_an_issue(self):
        result, actions = run_step({}, {"GH_ISSUE_LIST": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("create", actions)

    def test_an_empty_issue_list_during_the_sweep_does_not_crash(self):
        # When the sweep's list comes back empty (a green-close landing between the create and the
        # sweep), the unguarded ${open_issues[0]} used to abort the job red under set -u.
        result, _ = run_step({}, {"GH_ISSUE_LIST": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_a_recheck_api_blip_still_reports_rather_than_failing_open(self):
        # main is genuinely red and current, but the pre-mutation recheck call errors. It must not be
        # read as "main advanced" and silently skip.
        result, actions = run_step({}, {"GH_MAIN_FAIL_ON_CALL": "2", "GH_ISSUE_LIST": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("create", actions, "a recheck API error must not suppress the alert")

    def test_a_stale_head_is_skipped(self):
        result, actions = run_step(
            {"HEAD_SHA": "b" * 40}, {"GH_ISSUE_LIST": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], actions, "a run for an older commit must not report")

    def test_a_superseded_run_is_skipped(self):
        result, actions = run_step({}, {"GH_LATEST_RUN": "2000", "GH_ISSUE_LIST": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], actions, "a superseded run must not report")

    def test_a_scheduled_failure_on_current_main_opens_an_issue(self):
        # The weekly Security scan gates main on its open alert set; a red scheduled run on the
        # current head is main being red just as much as a red push run is.
        result, actions = run_step({"RUN_EVENT": "schedule"}, {"GH_ISSUE_LIST": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("create", actions)

    def test_a_scheduled_run_is_superseded_only_by_a_later_scheduled_run(self):
        # A newer push run of the same head is not a verdict on the scheduled scan; only a newer
        # scheduled run is. The stub answers the supersession query with a higher id solely for
        # the event named in GH_SUPERSEDING_EVENT.
        by_push, actions_after_push = run_step(
            {"RUN_EVENT": "schedule"},
            {"GH_LATEST_RUN": "2000", "GH_SUPERSEDING_EVENT": "push", "GH_ISSUE_LIST": "[]"})
        by_schedule, actions_after_schedule = run_step(
            {"RUN_EVENT": "schedule"},
            {"GH_LATEST_RUN": "2000", "GH_SUPERSEDING_EVENT": "schedule", "GH_ISSUE_LIST": "[]"})
        self.assertEqual(0, by_push.returncode, by_push.stderr)
        self.assertIn("create", actions_after_push, "a push run must not supersede a scheduled run")
        self.assertEqual(0, by_schedule.returncode, by_schedule.stderr)
        self.assertEqual([], actions_after_schedule, "a later scheduled run supersedes")

    def test_a_startup_failure_on_current_main_opens_an_issue(self):
        # A workflow-file error concludes startup_failure without running a job; main is just as
        # non-green as on a failure.
        result, actions = run_step(
            {"CONCLUSION": "startup_failure"},
            {"GH_RUN_CONCLUSION": '"startup_failure"'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("create", actions)

    def test_a_startup_failure_on_current_main_is_reported(self):
        result, actions = run_step(
            {"CONCLUSION": "startup_failure"},
            {"GH_RUN_STATUS": "completed", "GH_RUN_CONCLUSION": '"startup_failure"'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("create", actions)

    def test_a_run_rerun_to_success_is_not_reported(self):
        # The failed completion fires, but the run has since been re-run green (same id, next
        # attempt). The live status must gate reporting, not the run id.
        result, actions = run_step(
            {}, {"GH_RUN_STATUS": "completed", "GH_RUN_CONCLUSION": '"success"'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], actions, "a run re-run to success must not report")

    def test_a_run_being_rerun_is_not_reported(self):
        result, actions = run_step(
            {}, {"GH_RUN_STATUS": "in_progress", "GH_RUN_CONCLUSION": "null"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], actions, "a run mid-rerun must not report")

    def test_a_target_closed_before_the_comment_is_reconverged(self):
        # Issue #9 open at lookup; closed before the sweep; the recreate then shows up on the
        # re-list. Without the recreate, main would be red with no open alert.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]', "GH_ISSUE_LIST_2": "[]",
                 "GH_ISSUE_LIST_3": '[{"number": 31}]'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("comment 9", actions)
        self.assertEqual(1, actions.count("create"))

    def test_a_list_that_lags_the_create_does_not_mint_duplicates(self):
        # The incident behind #2047-#2049: the issue list had not caught up with the create made
        # seconds earlier, the sweep read the empty list as a close and created again, twice.
        result, actions = run_step({}, {"GH_ISSUE_LIST": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, actions.count("create"), actions)

    def test_a_list_that_lags_after_a_comment_does_not_mint_a_duplicate(self):
        # Issue #9 is still open when read directly; only the list missed it.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]', "GH_ISSUE_LIST_2": "[]", "GH_OPEN_ISSUES": "9"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("comment 9", actions)
        self.assertNotIn("create", actions)

    def test_a_list_that_lags_the_recreate_does_not_recreate_again(self):
        # #9 was genuinely closed, so one recreate is right; the re-list then lags that recreate.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]', "GH_ISSUE_LIST_2": "[]",
                 "GH_ISSUE_LIST_3": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("comment 9", actions)
        self.assertEqual(1, actions.count("create"), actions)

    def test_an_unreadable_touched_issue_does_not_mint_a_duplicate(self):
        # Like a failed list, a failed direct read is no evidence of a close.
        result, actions = run_step({}, {"GH_ISSUE_LIST": "[]", "GH_ISSUE_READ_FAILS": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, actions.count("create"), actions)

    def test_a_lagging_sweep_list_gets_another_pass_to_deduplicate(self):
        # #9 was already open but the first lookup lagged it, so this run created #31. The sweep's
        # first list lags too; the next pass must still see both and close the newer one.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": "[]", "GH_ISSUE_LIST_2": "[]",
                 "GH_ISSUE_LIST_3": '[{"number": 9}, {"number": 31}]', "GH_OPEN_ISSUES": "9"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, actions.count("create"), actions)
        self.assertIn("close 31 Duplicate of #9.", actions)

    def test_a_partial_list_that_lags_the_create_still_deduplicates(self):
        # #9 was already open but the first lookup lagged it, so this run created #31; the sweep's
        # list then shows #9 while still missing #31. #31 must be closed and the report moved to #9.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": "[]", "GH_ISSUE_LIST_2": '[{"number": 9}]', "GH_OPEN_ISSUES": "9"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, actions.count("create"), actions)
        self.assertIn("close 31 Duplicate of #9.", actions)
        self.assertIn("comment 9", actions)
        self.assertLess(actions.index("comment 9"), actions.index("close 31 Duplicate of #9."), actions)

    def test_a_stale_keeper_never_closes_the_live_alert(self):
        # The list still shows #9, which was closed moments ago, and misses #31, which this run
        # created. #31 is the only live alert; it must stay open.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": "[]", "GH_ISSUE_LIST_2": '[{"number": 9}]'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, actions.count("create"), actions)
        self.assertFalse(any(action.startswith("close 31 ") for action in actions), actions)

    def test_a_closed_lowest_keeper_is_skipped_before_closing_duplicates(self):
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]',
                 "GH_ISSUE_LIST_2": '[{"number": 20}, {"number": 9}, {"number": 11}]',
                 "GH_OPEN_ISSUES": "11 20"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["comment 9", "close 20 Duplicate of #11."], actions)

    def test_an_unlabeled_lowest_keeper_is_skipped_before_closing_duplicates(self):
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]',
                 "GH_ISSUE_LIST_2": '[{"number": 20}, {"number": 9}, {"number": 11}]',
                 "GH_OPEN_ISSUES": "9 11 20", "GH_UNLABELED_ISSUES": "9"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["comment 9", "close 20 Duplicate of #11."], actions)

    def test_an_unreadable_lowest_keeper_is_skipped_before_closing_duplicates(self):
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]',
                 "GH_ISSUE_LIST_2": '[{"number": 20}, {"number": 9}, {"number": 11}]',
                 "GH_OPEN_ISSUES": "9 11 20", "GH_ISSUE_READ_FAILS_ON": "9"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["comment 9", "close 20 Duplicate of #11."], actions)

    def test_no_verified_listed_keeper_preserves_the_created_alert(self):
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": "[]", "GH_ISSUE_LIST_2": '[{"number": 9}, {"number": 20}]'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["create"], actions, "the newly created #31 must remain open")

    def test_no_verified_listed_keeper_recreates_the_invalid_reported_alert(self):
        for issue_state in ({}, {"GH_OPEN_ISSUES": "9 20", "GH_UNLABELED_ISSUES": "9 20"}):
            with self.subTest(issue_state=issue_state):
                result, actions = run_step(
                    {}, {"GH_ISSUE_LIST": '[{"number": 9}]',
                         "GH_ISSUE_LIST_2": '[{"number": 9}, {"number": 20}]', **issue_state})
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["comment 9", "create"], actions, "the replacement #31 must remain open")

    def test_unreadable_listed_keepers_do_not_close_any_alert(self):
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": "[]", "GH_ISSUE_LIST_2": '[{"number": 9}, {"number": 20}]',
                 "GH_ISSUE_READ_FAILS": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["create"], actions)

    def test_a_failed_report_repost_keeps_the_touched_alert_open(self):
        # #9 is a valid keeper, but re-posting this run's report there fails; closing #31 would
        # leave the only copy of the report on a closed duplicate.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": "[]", "GH_ISSUE_LIST_2": '[{"number": 9}]', "GH_OPEN_ISSUES": "9",
                 "GH_COMMENT_FAILS_ON": "9"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(action.startswith("close 31 ") for action in actions), actions)

    def test_a_failed_repost_only_closes_duplicates_for_a_verified_fallback(self):
        for target_open in (False, True):
            with self.subTest(target_open=target_open):
                result, actions = run_step(
                    {}, {"GH_ISSUE_LIST": '[{"number": 31}]',
                         "GH_ISSUE_LIST_2": '[{"number": 9}, {"number": 20}, {"number": 31}]',
                         "GH_OPEN_ISSUES": "9 20 31" if target_open else "9 20",
                         "GH_COMMENT_FAILS_ON": "9"})
                self.assertEqual(0, result.returncode, result.stderr)
                expected = ["comment 31"]
                if target_open:
                    expected.extend(["close 9 Duplicate of #31.", "close 20 Duplicate of #31."])
                self.assertEqual(expected, actions)

    def test_an_open_issue_that_lost_its_label_is_recreated(self):
        # A maintainer stripped ci-main-red from #9; it is open but no longer a visible alert.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]', "GH_ISSUE_LIST_2": "[]",
                 "GH_ISSUE_LIST_3": '[{"number": 31}]', "GH_OPEN_ISSUES": "9",
                 "GH_UNLABELED_ISSUES": "9"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("comment 9", actions)
        self.assertEqual(1, actions.count("create"), actions)

    def test_a_sweep_list_failure_does_not_mint_a_duplicate(self):
        # The report already landed as a comment; a transient failure of the sweep's list call must
        # not be read as "no open issues" and create a second one.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]', "GH_LIST_FAIL_FROM": "2"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("comment 9", actions)
        self.assertNotIn("create", actions)

    def test_a_supersession_lookup_failure_still_reports(self):
        # An Actions API blip is insufficient evidence of supersession; like the other checks it
        # must fall toward alerting, not abort red before any issue exists.
        result, actions = run_step({}, {"GH_WORKFLOW_RUNS_FAILS": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("create", actions)

    def test_concurrent_recreates_are_deduplicated_in_the_same_run(self):
        # Two jobs both recreate after a mid-flight close; the re-list pass must settle on one.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]', "GH_ISSUE_LIST_2": "[]",
                 "GH_ISSUE_LIST_3": '[{"number": 31}, {"number": 32}]'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, actions.count("create"))
        self.assertIn("close 32 Duplicate of #31.", actions)

    def test_the_comment_targets_the_issue_the_sweep_keeps(self):
        # Two open issues, in gh's default newest-first order (highest number first). The sweep
        # keeps the lowest (11); the fresh comment must land there, not on the higher one (20) that
        # then gets closed as a duplicate. The order matters: with an ascending fixture a take-first
        # bug would be invisible.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 20}, {"number": 11}]', "GH_OPEN_ISSUES": "11 20"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("comment 11", actions)
        self.assertIn("close 20 Duplicate of #11.", actions)
        self.assertNotIn("comment 20", actions)


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
