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
        printf '%s' "$GH_LATEST_RUN"; exit 0 ;;
      "api repos/"*"/actions/runs/"*)
        jqexpr=""; prev=""
        for a in "$@"; do [ "$prev" = "--jq" ] && jqexpr="$a"; prev="$a"; done
        printf '{"status":"%s","conclusion":%s}' "$GH_RUN_STATUS" "$GH_RUN_CONCLUSION" \
          | jq -r "$jqexpr"; exit 0 ;;
      "issue list"*)
        c=$(( $(cat "$GH_LIST_CALLS" 2>/dev/null || echo 0) + 1 )); echo "$c" > "$GH_LIST_CALLS"
        val="$GH_ISSUE_LIST"; [ "$c" -ge 2 ] && [ -n "${GH_ISSUE_LIST_2:-}" ] && val="$GH_ISSUE_LIST_2"
        jqexpr=""; prev=""
        for a in "$@"; do [ "$prev" = "--jq" ] && jqexpr="$a"; prev="$a"; done
        if [ -n "$jqexpr" ]; then printf '%s' "$val" | jq -r "$jqexpr"
        else printf '%s' "$val"; fi
        exit 0 ;;
      "issue comment"*) log "comment ${3}"; exit 0 ;;
      "issue create"*) log "create"; exit 0 ;;
      "issue close"*) log "close ${3}"; exit 0 ;;
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
        env = {
            **os.environ,
            "PATH": f"{tmp}:{os.environ['PATH']}",
            "GH_TOKEN": "x",
            "REPO": "o/r",
            "RUN_URL": "https://run",
            "RUN_NAME": "CI",
            "RUN_ID": "1000",
            "WORKFLOW_ID": "42",
            "HEAD_SHA": CURRENT_MAIN,
            "CONCLUSION": "failure",
            "GH_LOG": str(log),
            "GH_MAIN_CALLS": str(tmp / "main.calls"),
            "GH_MAIN_FAIL_ON_CALL": "0",
            "GH_LIST_CALLS": str(tmp / "list.calls"),
            "GH_RUN_STATUS": "completed",
            "GH_RUN_CONCLUSION": '"failure"',
            "GH_ISSUE_LIST_2": "",
            "GH_CURRENT_MAIN": CURRENT_MAIN,
            "GH_LATEST_RUN": "1000",
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
        # Issue #9 open at lookup; closed before the sweep. Without the recreate, main would be red
        # with no open alert.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 9}]', "GH_ISSUE_LIST_2": "[]"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("comment 9", actions)
        self.assertIn("create", actions)

    def test_the_comment_targets_the_issue_the_sweep_keeps(self):
        # Two open issues, in gh's default newest-first order (highest number first). The sweep
        # keeps the lowest (11); the fresh comment must land there, not on the higher one (20) that
        # then gets closed as a duplicate. The order matters: with an ascending fixture a take-first
        # bug would be invisible.
        result, actions = run_step(
            {}, {"GH_ISSUE_LIST": '[{"number": 20}, {"number": 11}]'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("comment 11", actions)
        self.assertIn("close 20", actions)
        self.assertNotIn("comment 20", actions)


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
