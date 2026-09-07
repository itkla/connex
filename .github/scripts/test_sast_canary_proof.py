"""Regression tests for the SAST gate canary proof workflow's shape.

The proof only runs weekly against live state, so the properties that make it trustworthy — that it
cannot be triggered into the red-main alert's watch list, that it holds no more permission than it
needs, that no step is allowed to fail silently, and that the verifier is handed every piece of
evidence it asserts on — are pinned here instead.
"""

import importlib.util
import re
import unittest
from pathlib import Path

import yaml


WORKFLOW_PATH = Path(__file__).parents[1] / "workflows" / "sast-canary-proof.yml"
VERIFIER_PATH = Path(__file__).with_name("verify-sast-canary.py")
SPEC = importlib.util.spec_from_file_location("verify_sast_canary", VERIFIER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the canary proof verifier")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)

FULL_COMMIT = re.compile(r"^[^/@\s]+(?:/[^/@\s]+)+@[0-9a-f]{40}$")
STEP_NAMES = (
    "Discover the canary pull request",
    "Assert the canary carries the current gate",
    "Re-run the canary's Security workflow",
    "Collect evidence",
    "Verify the proof",
    "Report a failed proof",
)
VERIFIER_FLAGS = (
    "--jobs",
    "--ref-alerts",
    "--pr-alerts",
    "--ref-analyses",
    "--main-analyses",
    "--merge-commit",
    "--branch",
    "--pull-request",
    "--canary-number",
    "--summary",
)


class SastCanaryProofWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        cls.job = cls.workflow["jobs"]["prove"]
        cls.steps = cls.job["steps"]

    def triggers(self) -> dict[str, object]:
        return self.workflow.get("on", self.workflow.get(True, {}))

    def named_step(self, name: str) -> dict[str, object]:
        return next(step for step in self.steps if step.get("name") == name)

    def test_the_proof_never_runs_on_a_push(self) -> None:
        """A push trigger would put this workflow into the red-main alert's watch list.

        `test_main_red_alert.py` requires every workflow that runs on a push to main to be watched.
        A failed proof reports through its own `sast-canary-failed` issue instead.
        """
        triggers = self.triggers()
        self.assertEqual({"schedule", "workflow_dispatch"}, set(triggers))
        self.assertEqual([{"cron": "37 21 * * 0"}], triggers["schedule"])

    def test_the_job_holds_only_the_permissions_it_uses(self) -> None:
        self.assertEqual({"contents": "read"}, self.workflow["permissions"])
        self.assertEqual(
            {
                "contents": "read",
                "actions": "write",
                "checks": "read",
                "pull-requests": "read",
                "security-events": "read",
                "issues": "write",
            },
            self.job["permissions"],
        )

    def test_concurrent_proofs_queue_rather_than_cancel(self) -> None:
        self.assertEqual(
            {"group": "sast-canary-proof", "cancel-in-progress": False},
            self.workflow["concurrency"],
        )
        self.assertEqual(75, self.job["timeout-minutes"])
        rerun = next(step for step in self.job["steps"] if step.get("name") == "Re-run the canary's Security workflow")
        self.assertLess(rerun["timeout-minutes"], self.job["timeout-minutes"],
                        "the re-run step must time out before the job so the reporting step still runs")

    def test_every_external_action_is_pinned_to_a_full_commit(self) -> None:
        uses = [step["uses"] for step in self.steps if "uses" in step]
        self.assertTrue(uses)
        for reference in uses:
            with self.subTest(uses=reference):
                self.assertRegex(reference, FULL_COMMIT)

    def test_no_step_may_fail_silently(self) -> None:
        self.assertNotIn("continue-on-error", self.job)
        for step in self.steps:
            with self.subTest(step=step.get("name", step.get("uses"))):
                self.assertNotIn("continue-on-error", step)
                if "run" in step:
                    self.assertIn("set -euo pipefail", step["run"])

    def test_the_documented_steps_run_in_order(self) -> None:
        names = [step["name"] for step in self.steps if "name" in step]
        self.assertEqual(list(STEP_NAMES), names)

    def test_the_staleness_check_covers_every_gate_file(self) -> None:
        run = self.named_step("Assert the canary carries the current gate")["run"]
        for gate_file in (
            ".github/workflows/security.yml",
            ".github/scripts/check-codeql-alerts.py",
            ".github/scripts/classify-ci-changes.py",
            ".github/codeql/backend.yml",
            ".github/codeql/frontend.yml",
        ):
            with self.subTest(gate_file=gate_file):
                self.assertIn(gate_file, run)
        self.assertIn("rebase canary/sast-gate-proof onto main", run)

    def test_the_fixtures_are_asserted_absent_from_main(self) -> None:
        """Only an explicit 404 proves a fixture is absent from main.

        A probe that treats every failure as absence would pass on a rate limit or a 5xx, so the
        one assertion that the vulnerable fixtures never reached the default branch could pass for
        the wrong reason.
        """
        run = self.named_step("Assert the canary carries the current gate")["run"]
        self.assertIn('"$BACKEND_FIXTURE" "$FRONTEND_FIXTURE"', run)
        self.assertIn("the canary fixtures must never be merged", run)
        self.assertIn("grep -q 'HTTP 404'", run)
        self.assertIn("could not prove $fixture is absent from main", run)
        self.assertNotIn("?ref=main\" >/dev/null 2>&1", run)

    def test_an_aged_canary_run_is_refused_before_the_rerun(self) -> None:
        """GitHub refuses to re-run a workflow run more than 30 days after its initial run.

        Nothing else bounds the canary's age once the gate files stop changing, so the proof must
        refuse an old run with the rebase remedy rather than surface a raw 403 from the POST.
        """
        limit = int(self.job["env"]["MAX_RUN_AGE_DAYS"])
        self.assertLess(limit, 30)
        self.assertGreaterEqual(limit, 7)
        run = self.named_step("Re-run the canary's Security workflow")["run"]
        self.assertIn(".created_at", run)
        self.assertIn('"$age_days" -gt "$MAX_RUN_AGE_DAYS"', run)
        self.assertIn("rebase canary/sast-gate-proof onto main and force-push", run)
        self.assertLess(run.index('-gt "$MAX_RUN_AGE_DAYS"'), run.index("/rerun"))

    def test_main_analyses_are_walked_page_by_page_until_the_base_commit(self) -> None:
        """A single page of main's analyses covers under a week, so the base commit must be sought.

        The count invariant compares the canary's merge commit with main's analysis of its first
        parent. With one page of 50 the base commit fell out of the window about two days after a
        rebase, and every scheduled proof failed for a reason unrelated to the gate.
        """
        self.assertGreaterEqual(int(self.job["env"]["MAIN_ANALYSES_PAGE_BUDGET"]), 5)
        run = self.named_step("Collect evidence")["run"]
        self.assertIn('for page in $(seq 1 "$MAIN_ANALYSES_PAGE_BUDGET")', run)
        self.assertIn("analyses?ref=refs/heads/main&per_page=100&page=$page", run)
        self.assertIn("jq -r '.parents[0].sha'", run)
        self.assertIn("jq -s 'add'", run)
        self.assertIn('if [ "$found" = "2" ]', run)
        self.assertLess(run.index("merge-commit.json"), run.index("main-analyses-page-"))
        self.assertNotIn("per_page=50", run)

    def test_the_workflow_and_the_verifier_name_the_same_fixtures(self) -> None:
        job_env = self.job["env"]
        self.assertEqual(
            {(fixture.category, fixture.path) for fixture in VERIFIER.FIXTURES},
            {
                (job_env["BACKEND_CATEGORY"], job_env["BACKEND_FIXTURE"]),
                (job_env["FRONTEND_CATEGORY"], job_env["FRONTEND_FIXTURE"]),
            },
        )
        self.assertEqual("canary/sast-gate-proof", job_env["CANARY_BRANCH"])
        self.assertEqual("sast-canary", job_env["CANARY_LABEL"])

    def test_the_rerun_waits_for_the_new_attempt_not_the_old_verdict(self) -> None:
        """Polling `status` alone would accept the attempt that was already completed.

        `POST /actions/runs/{id}/rerun` keeps the run id and bumps `run_attempt`, and for the first
        seconds after the call the API still reports the previous attempt as `completed`. The proof
        would then verify the evidence it was meant to regenerate.
        """
        run = self.named_step("Re-run the canary's Security workflow")["run"]
        self.assertIn("attempt_before=", run)
        self.assertIn("completed:$((attempt_before + 1))", run)

    def test_the_verifier_receives_every_input_it_asserts_on(self) -> None:
        run = self.named_step("Verify the proof")["run"]
        self.assertIn("python3 .github/scripts/verify-sast-canary.py", run)
        for flag in VERIFIER_FLAGS:
            with self.subTest(flag=flag):
                self.assertIn(f"{flag} ", run)
        self.assertIn("--require-admin-enforcement", run)
        collected = self.named_step("Collect evidence")["run"]
        for artefact in (
            "jobs.json",
            "ref-alerts.json",
            "pr-alerts.json",
            "ref-analyses.json",
            "main-analyses.json",
            "merge-commit.json",
            "branch.json",
            "pull-request.json",
        ):
            with self.subTest(artefact=artefact):
                self.assertIn(artefact, collected)
                self.assertIn(artefact, run)

    def test_a_failed_proof_reports_itself(self) -> None:
        """A cancelled or timed-out proof must report too; `failure()` alone would stay silent."""
        step = self.named_step("Report a failed proof")
        self.assertEqual("failure() || cancelled()", step["if"])
        self.assertEqual("${{ job.status }}", step["env"]["JOB_STATUS"])
        self.assertIn("$JOB_STATUS", step["run"])
        self.assertIn("$CANARY_LABEL-failed", step["run"])
        self.assertIn("SAST gate canary proof failed", step["run"])


if __name__ == "__main__":
    unittest.main()
