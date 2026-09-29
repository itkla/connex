"""Regression tests for the SAST gate canary proof workflow's shape.

The proof only runs weekly against live state, so the properties that make it trustworthy — that it
cannot be triggered into the red-main alert's watch list, that it holds no more permission than it
needs, that no step is allowed to fail silently, and that the verifier is handed every piece of
evidence it asserts on — are pinned here instead.
"""

import importlib.util
import json
import os
import re
import stat
import subprocess
import tempfile
import textwrap
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
    "--run",
    "--run-id",
    "--attempt",
    "--rerun-started-at",
    "--expected-head-sha",
    "--jobs",
    "--backend-annotations",
    "--frontend-annotations",
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


CANARY_NUMBER = 1593
HEAD_SHA = "c" * 40
MERGE_SHA = "d" * 40
RUN_ID = 34099437002

GH_STUB = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    printf '%s\\n' "$*" >> "$GH_LOG"
    jqexpr=""; prev=""; method="GET"
    for a in "$@"; do
      [ "$prev" = "--jq" ] && jqexpr="$a"
      [ "$prev" = "--method" ] && method="$a"
      prev="$a"
    done
    emit() {
      if [ -n "$jqexpr" ]; then jq -r "$jqexpr" "$1"; else cat "$1"; fi
    }
    if [ "$1 $2" = "pr view" ]; then
      [ -f "$GH_FIXTURES/fail-pr-view" ] && exit 1
      emit "$GH_FIXTURES/pr-view.json"; exit 0
    fi
    [ "$1" = "api" ] || exit 0
    endpoint=""
    for a in "${@:2}"; do
      case "$a" in repos/*|graphql) endpoint="$a"; break ;; esac
    done
    case "$endpoint" in
      */actions/workflows/*/runs*) emit "$GH_FIXTURES/runs.json" ;;
      */actions/runs/*/rerun) exit 0 ;;
      */attempts/*/jobs*) emit "$GH_FIXTURES/attempt-jobs.json" ;;
      */attempts/*) emit "$GH_FIXTURES/attempt.json" ;;
      */actions/runs/*)
        if [ -n "$jqexpr" ]; then
          n=$(( $(cat "$GH_FIXTURES/polls" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$GH_FIXTURES/polls"
          poll="$GH_FIXTURES/poll-$n.json"; [ -f "$poll" ] || poll="$GH_FIXTURES/poll-last.json"
          emit "$poll"
        else
          [ -f "$GH_FIXTURES/fail-run" ] && exit 1
          emit "$GH_FIXTURES/run.json"
        fi ;;
      */check-runs/*/annotations*)
        id="${endpoint#*/check-runs/}"; id="${id%%/*}"
        [ -f "$GH_FIXTURES/annotations-$id.json" ] || exit 1
        emit "$GH_FIXTURES/annotations-$id.json" ;;
      */code-scanning/analyses*ref=refs/pull/*) emit "$GH_FIXTURES/ref-analyses.json" ;;
      */commits/*) printf '{"sha":"%s","parents":[{"sha":"b"},{"sha":"c"}]}' "${endpoint##*/}" ;;
      graphql) printf '{}' ;;
      *) printf '[]' ;;
    esac
    """
)


def step_run(name: str) -> str:
    workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
    return next(step for step in workflow["jobs"]["prove"]["steps"] if step.get("name") == name)[
        "run"
    ]


def canary_run(**fields: object) -> dict[str, object]:
    return {
        "id": RUN_ID,
        "run_attempt": 4,
        "event": "pull_request",
        "head_branch": "canary/sast-gate-proof",
        "head_sha": HEAD_SHA,
        "created_at": "2099-01-01T00:00:00Z",
        "pull_requests": [{"number": CANARY_NUMBER}],
        **fields,
    }


def execute_step(
    name: str, fixtures: dict[str, object], env: dict[str, str] | None = None
) -> tuple[subprocess.CompletedProcess[str], list[str], dict[str, str]]:
    """Run a workflow step's exact shell against a recording `gh` stub and a no-op `sleep`."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        bin_dir = root / "bin"
        fixture_dir = root / "fixtures"
        bin_dir.mkdir()
        fixture_dir.mkdir()
        for tool, body in (("gh", GH_STUB), ("sleep", "#!/usr/bin/env bash\nexit 0\n")):
            path = bin_dir / tool
            path.write_text(body, encoding="utf-8")
            path.chmod(path.stat().st_mode | stat.S_IEXEC)
        for file_name, document in fixtures.items():
            (fixture_dir / file_name).write_text(
                document if isinstance(document, str) else json.dumps(document), encoding="utf-8"
            )
        log = root / "gh.log"
        log.touch()
        output = root / "output"
        output.touch()
        runner_temp = root / "runner"
        runner_temp.mkdir()
        job_env = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))["jobs"]["prove"]["env"]
        result = subprocess.run(
            ["bash", "-c", step_run(name)],
            env={
                **os.environ,
                **{key: str(value) for key, value in job_env.items() if "${{" not in str(value)},
                "PATH": f"{bin_dir}:{os.environ['PATH']}",
                "REPO": "o/r",
                "GH_LOG": str(log),
                "GH_FIXTURES": str(fixture_dir),
                "GITHUB_OUTPUT": str(output),
                "RUNNER_TEMP": str(runner_temp),
                "CANARY_NUMBER": str(CANARY_NUMBER),
                **(env or {}),
            },
            capture_output=True,
            text=True,
            check=False,
        )
        outputs = dict(
            line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines() if line
        )
        return result, log.read_text(encoding="utf-8").splitlines(), outputs


def rerun_fixtures(**run_fields: object) -> dict[str, object]:
    return {
        "pr-view.json": {"headRefOid": HEAD_SHA},
        "runs.json": {"workflow_runs": [{"id": RUN_ID}]},
        "run.json": canary_run(**run_fields),
        "poll-1.json": {"status": "completed", "run_attempt": 4},
        "poll-last.json": {"status": "completed", "run_attempt": 5},
    }


class RerunStepBehaviourTest(unittest.TestCase):
    """The re-run step must bind the run to the canary head before it spends the POST."""

    step = "Re-run the canary's Security workflow"

    def posted(self, calls: list[str]) -> bool:
        return any("--method POST" in call and call.endswith("/rerun") for call in calls)

    def test_a_bound_run_is_rerun_and_exports_the_new_attempt(self) -> None:
        result, calls, outputs = execute_step(self.step, rerun_fixtures())
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertTrue(self.posted(calls))
        self.assertEqual(str(RUN_ID), outputs["run_id"])
        self.assertEqual("5", outputs["attempt"])
        self.assertEqual(HEAD_SHA, outputs["head_sha"])
        self.assertRegex(outputs["rerun_started_at"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")
        runs_query = next(call for call in calls if "/actions/workflows/security.yml/runs" in call)
        self.assertIn(f"head_sha={HEAD_SHA}", runs_query)
        self.assertIn("event=pull_request", runs_query)

    def test_the_poll_ignores_the_previous_completed_attempt(self) -> None:
        fixtures = rerun_fixtures()
        fixtures["poll-last.json"] = {"status": "completed", "run_attempt": 4}
        result, calls, _ = execute_step(self.step, fixtures)
        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("did not complete within 45 minutes", result.stdout)

    def test_every_unbound_run_is_refused_before_the_post(self) -> None:
        for field, value in (
            ("event", "push"),
            ("head_branch", "main"),
            ("head_sha", "e" * 40),
            ("pull_requests", [{"number": CANARY_NUMBER + 1}]),
            ("pull_requests", []),
        ):
            with self.subTest(field=field, value=value):
                result, calls, outputs = execute_step(self.step, rerun_fixtures(**{field: value}))
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn("is not the canary's pull_request run", result.stdout)
                self.assertFalse(self.posted(calls))
                self.assertNotIn("attempt", outputs)

    def test_an_unreadable_pull_request_head_is_refused_before_the_post(self) -> None:
        result, calls, _ = execute_step(self.step, {**rerun_fixtures(), "fail-pr-view": ""})
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.posted(calls))
        result, calls, _ = execute_step(
            self.step, {**rerun_fixtures(), "pr-view.json": {"headRefOid": ""}}
        )
        self.assertEqual(1, result.returncode)
        self.assertIn(f"could not read the head commit of pull request #{CANARY_NUMBER}", result.stdout)
        self.assertFalse(any("/actions/workflows/" in call for call in calls))
        self.assertFalse(self.posted(calls))

    def test_an_unreadable_run_is_refused_before_the_post(self) -> None:
        result, calls, _ = execute_step(self.step, {**rerun_fixtures(), "fail-run": ""})
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.posted(calls))
        for attempt in (None, "four"):
            with self.subTest(attempt=attempt):
                result, calls, _ = execute_step(
                    self.step, {**rerun_fixtures(), "run.json": canary_run(run_attempt=attempt)}
                )
                self.assertEqual(1, result.returncode)
                self.assertIn(f"Security run {RUN_ID} reported no run attempt", result.stdout)
                self.assertFalse(self.posted(calls))


def collect_fixtures() -> dict[str, object]:
    return {
        "attempt.json": canary_run(run_attempt=5),
        "attempt-jobs.json": {
            "jobs": [
                {"id": 11, "name": "Backend SAST (CodeQL)"},
                {"id": 12, "name": "Frontend SAST (CodeQL)"},
                {"id": 13, "name": "Security — required"},
            ]
        },
        "annotations-11.json": [{"title": "Blocking CodeQL alert #163"}],
        "annotations-12.json": [{"title": "Blocking CodeQL alert #162"}],
        "ref-analyses.json": [
            {"category": "/language:java-kotlin", "commit_sha": MERGE_SHA, "created_at": "2"},
            {"category": "/language:javascript-typescript", "commit_sha": MERGE_SHA, "created_at": "2"},
            {"category": "/language:java-kotlin", "commit_sha": "f" * 40, "created_at": "1"},
        ],
    }


COLLECT_ENV = {"RUN_ID": str(RUN_ID), "ATTEMPT": "5", "FRESHNESS_MERGE_SHA": MERGE_SHA}


class CollectStepBehaviourTest(unittest.TestCase):
    """Evidence must come from the re-run attempt, and every fetch must fail the step."""

    step = "Collect evidence"

    def test_evidence_is_pinned_to_the_attempt_and_annotations_are_fetched_per_sast_job(self) -> None:
        result, calls, _ = execute_step(self.step, collect_fixtures(), COLLECT_ENV)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn(f"api repos/o/r/actions/runs/{RUN_ID}/attempts/5", calls)
        self.assertIn(f"api repos/o/r/actions/runs/{RUN_ID}/attempts/5/jobs?per_page=100", calls)
        self.assertIn("api repos/o/r/check-runs/11/annotations?per_page=100", calls)
        self.assertIn("api repos/o/r/check-runs/12/annotations?per_page=100", calls)
        self.assertNotIn(f"api repos/o/r/actions/runs/{RUN_ID}/jobs?per_page=100", calls)
        self.assertIn(f"api repos/o/r/commits/{MERGE_SHA}", calls)

    def test_a_missing_or_duplicated_sast_job_fails_the_step(self) -> None:
        for jobs in (
            [{"id": 12, "name": "Frontend SAST (CodeQL)"}],
            [
                {"id": 11, "name": "Backend SAST (CodeQL)"},
                {"id": 14, "name": "Backend SAST (CodeQL)"},
                {"id": 12, "name": "Frontend SAST (CodeQL)"},
            ],
        ):
            with self.subTest(jobs=jobs):
                fixtures = {**collect_fixtures(), "attempt-jobs.json": {"jobs": jobs}}
                result, _, _ = execute_step(self.step, fixtures, COLLECT_ENV)
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn("expected exactly one 'Backend SAST (CodeQL)' job", result.stdout)

    def test_a_failed_annotation_fetch_fails_the_step(self) -> None:
        fixtures = collect_fixtures()
        del fixtures["annotations-12.json"]
        result, _, _ = execute_step(self.step, fixtures, COLLECT_ENV)
        self.assertNotEqual(0, result.returncode, result.stdout)

    def test_a_rerun_that_analysed_another_merge_commit_fails_the_step(self) -> None:
        fixtures = collect_fixtures()
        fixtures["ref-analyses.json"][0]["created_at"] = "0"
        result, _, _ = execute_step(self.step, fixtures, COLLECT_ENV)
        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("not the merge commit", result.stdout)


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
        self.assertIn('attempt="$((attempt_before + 1))"', run)
        self.assertIn('"completed:$attempt"', run)
        self.assertLess(run.index("rerun_started_at="), run.index("/rerun"))

    def test_the_verifier_receives_every_input_it_asserts_on(self) -> None:
        run = self.named_step("Verify the proof")["run"]
        self.assertIn("python3 .github/scripts/verify-sast-canary.py", run)
        for flag in VERIFIER_FLAGS:
            with self.subTest(flag=flag):
                self.assertIn(f"{flag} ", run)
        self.assertIn("--require-admin-enforcement", run)
        collected = self.named_step("Collect evidence")["run"]
        for artefact in (
            "run.json",
            "jobs.json",
            "annotations-backend.json",
            "annotations-frontend.json",
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
