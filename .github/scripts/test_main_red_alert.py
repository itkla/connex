"""The red-main alert must watch every workflow that can leave main red.

A workflow missing from its trigger list fails silently: main goes red and nothing reports it,
which is the exact defect the alert exists to close (#1481). This keeps the list honest.
"""

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
    push = _triggers(document).get("push")
    return isinstance(push, dict) and "main" in (push.get("branches") or [])


class MainRedAlertCoverage(unittest.TestCase):

    def test_every_workflow_that_runs_on_a_main_push_is_watched(self):
        watched = set(_triggers(yaml.safe_load(ALERT.read_text()))["workflow_run"]["workflows"])
        expected = set()
        for path in sorted(WORKFLOWS.glob("*.yml")):
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

    def test_the_alert_only_reports_pushes(self):
        condition = _triggers(yaml.safe_load(ALERT.read_text()))
        job = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["if"]
        self.assertIn("workflow_run.event == 'push'", job)
        self.assertIsNotNone(condition)

    def test_terminal_non_success_conclusions_are_reported(self):
        job = yaml.safe_load(ALERT.read_text())["jobs"]["alert"]["if"]
        for conclusion in ("failure", "timed_out"):
            self.assertIn(conclusion, job)


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
