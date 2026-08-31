"""The red-main alert must watch every workflow that can leave main red.

A workflow missing from its trigger list fails silently: main goes red and nothing reports it,
which is the exact defect the alert exists to close (#1481). These tests keep the watch list honest
and pin the conditions under which the alert does and does not fire.
"""

import fnmatch
import pathlib
import sys
import unittest

import yaml

WORKFLOWS = pathlib.Path(__file__).resolve().parents[1] / "workflows"
ALERT = WORKFLOWS / "main-red-alert.yml"


def _triggers(document):
    # PyYAML resolves an unquoted "on" key to the boolean True.
    return document.get("on", document.get(True, {}))


def _runs_on_main_push(document):
    """Whether a push to main triggers this workflow, across the mapping forms Actions accepts.

    A bare ``push:`` (null value) or a ``push`` mapping with no ``branches`` runs on every branch,
    main included; ``branches`` and ``branches-ignore`` are glob patterns, not literals. A helper
    that only recognised ``branches: [main]`` would let those forms escape the coverage check, which
    is exactly the hollow guarantee this guards against.
    """
    on = _triggers(document)
    if "push" not in on:
        return False
    push = on["push"]
    if push is None:
        return True
    branches = push.get("branches")
    if branches is not None:
        return any(fnmatch.fnmatch("main", pattern) for pattern in branches)
    ignored = push.get("branches-ignore")
    if ignored is not None:
        return not any(fnmatch.fnmatch("main", pattern) for pattern in ignored)
    # No branch filter. A tag filter without a branch filter means tag pushes only -- the Release
    # workflow (push: tags: ['v*.*.*']) does not run on a push to main. An otherwise-unfiltered push
    # (e.g. only paths) does run on every branch.
    if "tags" in push or "tags-ignore" in push:
        return False
    return True


class MainRedAlertCoverage(unittest.TestCase):

    def test_every_workflow_that_runs_on_a_main_push_is_watched(self):
        watched = set(_triggers(yaml.safe_load(ALERT.read_text()))["workflow_run"]["workflows"])
        expected = set()
        # Both extensions are valid workflow files; the repository's own action-pin scanner treats
        # them alike, and a .yaml workflow slipping past this loop would defeat the check.
        for path in sorted([*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")]):
            if path == ALERT:
                continue
            document = yaml.safe_load(path.read_text())
            if _runs_on_main_push(document):
                expected.add(document["name"])

        self.assertEqual(
            expected,
            watched,
            "every workflow that runs on a push to main must be watched by the red-main alert; "
            "an unwatched one fails without reporting",
        )

    def test_the_main_push_predicate_recognises_every_trigger_form(self):
        self.assertTrue(_runs_on_main_push({"on": {"push": {"branches": ["main"]}}}))
        self.assertTrue(_runs_on_main_push({"on": {"push": None}}))
        self.assertTrue(_runs_on_main_push({"on": {"push": {"paths": ["src/**"]}}}))
        self.assertTrue(_runs_on_main_push({"on": {"push": {"branches": ["ma*"]}}}))
        self.assertTrue(
            _runs_on_main_push({"on": {"push": {"branches-ignore": ["release/*"]}}}))
        self.assertFalse(_runs_on_main_push({"on": {"push": {"branches": ["develop"]}}}))
        self.assertFalse(
            _runs_on_main_push({"on": {"push": {"branches-ignore": ["main"]}}}))
        self.assertFalse(_runs_on_main_push({"on": {"pull_request": None}}))
        # A tag filter with no branch filter runs on tags only, not on a branch push to main.
        self.assertFalse(_runs_on_main_push({"on": {"push": {"tags": ["v*.*.*"]}}}))

    def test_the_alert_only_reports_real_pushes(self):
        job = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["if"]
        self.assertIn("workflow_run.event == 'push'", job)

    def test_every_terminal_non_success_conclusion_is_reportable(self):
        """Pins the reportable set against GitHub's full conclusion enum.

        Every documented conclusion is either reportable or has a stated reason not to be, so a new
        "you forgot X" has to argue with a decision rather than find an omission: success and
        neutral are green, skipped ran nothing, action_required is a pending approval rather than a
        result, and stale is a superseded run whose replacement reports for itself.
        """
        job = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["if"]
        reportable = ("failure", "timed_out", "cancelled", "startup_failure")
        excluded = ("success", "neutral", "skipped", "action_required", "stale")
        for conclusion in reportable:
            self.assertIn(conclusion, job)
        for conclusion in excluded:
            self.assertNotIn(f'"{conclusion}"', job)

    def test_reporting_is_gated_on_the_run_reflecting_current_main(self):
        step = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["steps"][0]["run"]
        # Stale reruns of older commits and superseded same-commit runs must both be excluded.
        self.assertIn('repos/$REPO/commits/main', step)
        self.assertIn('HEAD_SHA" != "$current', step)
        self.assertIn("superseded", step)

    def test_the_job_can_read_the_actions_api_it_calls(self):
        doc = yaml.safe_load(ALERT.read_text())
        # The supersession check lists workflow runs; without actions: read that 403s and set -e
        # aborts before any issue is opened, so the alert silently never fires.
        self.assertEqual("read", doc["permissions"]["actions"])

    def test_supersession_considers_only_push_runs(self):
        step = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["steps"][0]["run"]
        # A later workflow_dispatch or scheduled run of the same workflow has a higher id; without
        # the event filter it would falsely mark a red push run as superseded.
        self.assertIn("event=push", step)

    def test_main_is_rechecked_before_the_issue_is_mutated(self):
        step = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["steps"][0]["run"]
        # The head is read once up front and again immediately before create/comment, so a push that
        # advances main mid-check cannot publish a report for a commit main has left behind.
        self.assertEqual(2, step.count('repos/$REPO/commits/main'))

    def test_duplicate_rolling_issues_are_converged(self):
        step = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["steps"][0]["run"]
        self.assertIn("issue close", step)
        self.assertIn("Duplicate of", step)


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
