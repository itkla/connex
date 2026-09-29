"""Regression tests for the CodeQL gate canary proof verifier.

The proof runs weekly against live evidence, so its only executable specification is this file: a
synthetic evidence pack in which every assertion holds, then one mutation per assertion proving the
verifier fails on it. A verifier that passed a mutated pack would report a blind gate as proved.
"""

import copy
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name("verify-sast-canary.py")
CANARY_NUMBER = 1600
MERGE_SHA = "1111111111111111111111111111111111111111"
BASE_SHA = "2222222222222222222222222222222222222222"
HEAD_SHA = "3333333333333333333333333333333333333333"
RUN_ID = 34099437002
ATTEMPT = 4
RERUN_STARTED_AT = "2026-09-08T01:39:28Z"
BACKEND_CATEGORY = "/language:java-kotlin"
FRONTEND_CATEGORY = "/language:javascript-typescript"
BACKEND_PATH = (
    "backend/src/test/java/ooo/klae/connex/backend/codeqlfixture/IntentionalCommandInjectionFixture.java"
)
FRONTEND_PATH = "frontend/test/fixtures/codeql/intentional-command-injection.mjs"
BINDING_DEFAULTS = {
    "--run-id": str(RUN_ID),
    "--attempt": str(ATTEMPT),
    "--rerun-started-at": RERUN_STARTED_AT,
    "--expected-head-sha": HEAD_SHA,
}
REQUIRED_CONTEXTS = [
    "Security — required",
    "Backend SAST (CodeQL)",
    "Frontend SAST (CodeQL)",
    "Backend — build & test",
]


def job(name: str, conclusion: str, **fields: object) -> dict[str, object]:
    return {
        "name": name,
        "conclusion": conclusion,
        "run_id": RUN_ID,
        "run_attempt": ATTEMPT,
        "head_sha": HEAD_SHA,
        "started_at": "2026-09-08T01:39:31Z",
        "completed_at": "2026-09-08T01:39:41Z",
        "steps": [],
        **fields,
    }


def sast_job(name: str, analyse_step: str, completed_at: str) -> dict[str, object]:
    return {
        **job(name, "failure", started_at="2026-09-08T01:39:45Z", completed_at=completed_at),
        "steps": [
            {"name": analyse_step, "conclusion": "success"},
            {"name": "Block Critical, High, or error-severity alerts", "conclusion": "failure"},
        ],
    }


def alert(number: int, rule_id: str, path: str, category: str) -> dict[str, object]:
    return {
        "number": number,
        "state": "open",
        "rule": {"id": rule_id, "security_severity_level": "critical", "severity": "error"},
        "most_recent_instance": {
            "ref": f"refs/pull/{CANARY_NUMBER}/merge",
            "state": "open",
            "category": category,
            "commit_sha": MERGE_SHA,
            "location": {"path": path, "start_line": 12, "start_column": 5},
        },
    }


def blocking(number: int, rule_id: str) -> list[dict[str, object]]:
    return [
        {
            "path": ".github",
            "annotation_level": "failure",
            "title": "",
            "message": "Process completed with exit code 1.",
        },
        {
            "path": ".github",
            "annotation_level": "failure",
            "title": f"Blocking CodeQL alert #{number}",
            "message": f"{rule_id} (critical) https://github.com/o/r/security/code-scanning/{number}",
        },
    ]


def analysis(category: str, commit_sha: str, count: int, created_at: str) -> dict[str, object]:
    return {
        "category": category,
        "commit_sha": commit_sha,
        "results_count": count,
        "created_at": created_at,
        "error": "",
    }


def check_run(name: str) -> dict[str, object]:
    return {"__typename": "CheckRun", "name": name, "conclusion": "FAILURE"}


def evidence() -> dict[str, object]:
    backend_alert = alert(701, "java/command-line-injection", BACKEND_PATH, BACKEND_CATEGORY)
    frontend_alert = alert(702, "js/command-line-injection", FRONTEND_PATH, FRONTEND_CATEGORY)
    return {
        "run": {
            "id": RUN_ID,
            "run_attempt": ATTEMPT,
            "event": "pull_request",
            "head_branch": "canary/sast-gate-proof",
            "head_sha": HEAD_SHA,
            "run_started_at": "2026-09-08T01:39:30Z",
            "pull_requests": [{"number": CANARY_NUMBER}],
        },
        "jobs": {
            "jobs": [
                job("Classify security impact", "success"),
                sast_job(
                    "Backend SAST (CodeQL)", "Analyze backend with CodeQL", "2026-09-08T01:45:17Z"
                ),
                sast_job(
                    "Frontend SAST (CodeQL)", "Analyze frontend with CodeQL", "2026-09-08T01:41:58Z"
                ),
                job("Security — required", "failure", completed_at="2026-09-08T01:45:25Z"),
            ]
        },
        "backend-annotations": blocking(701, "java/command-line-injection"),
        "frontend-annotations": blocking(702, "js/command-line-injection"),
        "ref-alerts": [[backend_alert, frontend_alert]],
        "pr-alerts": [[backend_alert, frontend_alert]],
        "ref-analyses": [
            analysis(BACKEND_CATEGORY, MERGE_SHA, 59, "2026-09-08T01:45:09Z"),
            analysis(FRONTEND_CATEGORY, MERGE_SHA, 9, "2026-09-08T01:41:38Z"),
            analysis(BACKEND_CATEGORY, MERGE_SHA, 59, "2026-09-07T22:54:33Z"),
            analysis(FRONTEND_CATEGORY, MERGE_SHA, 9, "2026-09-07T22:50:24Z"),
        ],
        "main-analyses": [
            analysis(BACKEND_CATEGORY, BASE_SHA, 58, "2026-09-07T01:00:00Z"),
            analysis(FRONTEND_CATEGORY, BASE_SHA, 8, "2026-09-07T01:00:00Z"),
        ],
        "merge-commit": {"sha": MERGE_SHA, "parents": [{"sha": BASE_SHA}, {"sha": HEAD_SHA}]},
        "branch": {
            "protection": {
                "required_status_checks": {
                    "contexts": REQUIRED_CONTEXTS,
                    "enforcement_level": "everyone",
                }
            }
        },
        "pull-request": {
            "number": CANARY_NUMBER,
            "isDraft": False,
            "mergeStateStatus": "BLOCKED",
            "commits": {
                "nodes": [
                    {
                        "commit": {
                            "oid": HEAD_SHA,
                            "statusCheckRollup": {
                                "contexts": {
                                    "nodes": [
                                        check_run("Security — required"),
                                        check_run("Backend SAST (CodeQL)"),
                                        check_run("Frontend SAST (CodeQL)"),
                                    ]
                                }
                            },
                        }
                    }
                ]
            },
        },
    }


class VerifySastCanaryTest(unittest.TestCase):
    def run_verifier(
        self, pack: dict[str, object], *arguments: str
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            command = [sys.executable, str(SCRIPT)]
            for name, document in pack.items():
                path = root / f"{name}.json"
                path.write_text(json.dumps(document), encoding="utf-8")
                command += [f"--{name}", str(path)]
            summary = root / "summary.md"
            command += [
                "--canary-number",
                str(CANARY_NUMBER),
                "--summary",
                str(summary),
                *arguments,
            ]
            for flag, value in BINDING_DEFAULTS.items():
                if flag not in arguments:
                    command += [flag, value]
            result = subprocess.run(command, check=False, capture_output=True, text=True)
            return result, summary.read_text(encoding="utf-8") if summary.exists() else ""

    def assert_fails(self, mutate, expected: str, *arguments: str) -> None:
        pack = evidence()
        mutate(pack)
        result, _ = self.run_verifier(pack, *arguments)
        self.assertEqual(1, result.returncode, f"stdout={result.stdout} stderr={result.stderr}")
        self.assertIn(expected, result.stderr)

    def test_a_complete_evidence_pack_passes_and_writes_a_summary(self) -> None:
        result, summary = self.run_verifier(evidence(), "--require-admin-enforcement")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("CodeQL gate canary proof passed", result.stdout)
        for heading in (
            "### Run binding",
            "### Jobs",
            "### Gate verdicts",
            "### Alerts",
            "### Analysis counts",
            "### Branch protection",
            "### Mergeability",
            "### Verdict",
        ):
            self.assertIn(heading, summary)
        self.assertIn("The gate blocked the canary.", summary)

    def test_a_passing_gate_step_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["jobs"]["jobs"][1]["steps"][1]["conclusion"] = "success"
            pack["jobs"]["jobs"][1]["conclusion"] = "success"

        self.assert_fails(mutate, "'Block Critical, High, or error-severity alerts'")

    def test_a_failed_analysis_step_is_reported_as_a_tool_failure(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["jobs"]["jobs"][2]["steps"][0]["conclusion"] = "failure"

        self.assert_fails(mutate, "tool failure, not the gate")

    def test_a_green_required_job_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["jobs"]["jobs"][3]["conclusion"] = "success"

        self.assert_fails(mutate, "'Security — required' concluded 'success'")

    def test_a_skipped_classifier_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["jobs"]["jobs"][0]["conclusion"] = "skipped"

        self.assert_fails(mutate, "'Classify security impact' concluded 'skipped'")

    def test_a_missing_java_fixture_alert_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-alerts"][0] = [pack["ref-alerts"][0][1]]

        self.assert_fails(mutate, "no open java/command-line-injection alert")

    def test_a_missing_javascript_fixture_alert_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-alerts"][0] = [pack["ref-alerts"][0][0]]

        self.assert_fails(mutate, "no open js/command-line-injection alert")

    def test_a_downgraded_fixture_severity_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-alerts"][0][0]["rule"]["security_severity_level"] = "high"

        self.assert_fails(mutate, "has security severity 'high'")

    def test_an_unattributed_fixture_alert_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["pr-alerts"][0] = [pack["pr-alerts"][0][1]]

        self.assert_fails(mutate, "not attributed to the canary pull request")

    def test_a_count_invariant_off_by_one_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-analyses"][0]["results_count"] = 58

        self.assert_fails(mutate, "must add exactly one")

    def test_a_stale_merge_ref_analysis_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-analyses"][0]["commit_sha"] = "4" * 40

        self.assert_fails(mutate, "the evidence is stale")

    def test_a_base_commit_missing_from_the_main_analyses_names_the_rebase_remedy(self) -> None:
        """An aged canary is reported as an aged canary, not as a violated count invariant.

        `main` produces about a hundred analyses a week, so a base commit older than the pages the
        workflow fetches is an operational condition with one remedy — rebase the canary — and must
        be distinguishable from results being pruned.
        """
        def mutate(pack: dict[str, object]) -> None:
            for entry in pack["main-analyses"]:
                entry["commit_sha"] = "5" * 40

        self.assert_fails(mutate, "rebase canary/sast-gate-proof onto main and force-push")
        pack = evidence()
        mutate(pack)
        result, _ = self.run_verifier(pack)
        self.assertNotIn("must add exactly one", result.stderr)

    def test_a_missing_required_context_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            contexts = pack["branch"]["protection"]["required_status_checks"]["contexts"]
            pack["branch"]["protection"]["required_status_checks"]["contexts"] = [
                context for context in contexts if context != "Backend SAST (CodeQL)"
            ]

        self.assert_fails(mutate, "no longer requires the 'Backend SAST (CodeQL)' context")

    def test_administrator_bypass_fails_only_under_the_strict_flag(self) -> None:
        pack = evidence()
        pack["branch"]["protection"]["required_status_checks"]["enforcement_level"] = "non_admins"

        lenient, lenient_summary = self.run_verifier(copy.deepcopy(pack))
        self.assertEqual(0, lenient.returncode, lenient.stderr)
        self.assertIn("an administrator can merge past", lenient.stdout)
        self.assertIn("WARNING", lenient_summary)

        strict, _ = self.run_verifier(pack, "--require-admin-enforcement")
        self.assertEqual(1, strict.returncode)
        self.assertIn("an administrator can merge past", strict.stderr)

    def test_a_mergeable_canary_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["pull-request"]["mergeStateStatus"] = "CLEAN"

        self.assert_fails(mutate, "is 'CLEAN', not 'BLOCKED'")

    def test_a_draft_canary_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["pull-request"]["isDraft"] = True
            pack["pull-request"]["mergeStateStatus"] = "DRAFT"

        self.assert_fails(mutate, "is a draft")

    def test_a_successful_check_run_on_the_canary_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            nodes = pack["pull-request"]["commits"]["nodes"][0]["commit"]["statusCheckRollup"][
                "contexts"
            ]["nodes"]
            nodes[1]["conclusion"] = "SUCCESS"

        self.assert_fails(mutate, "check run on the canary concluded 'SUCCESS'")

    def test_a_run_at_another_head_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["run"]["head_sha"] = "9" * 40

        self.assert_fails(mutate, "the run is at 999999999, not the canary head")

    def test_evidence_of_another_run_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["run"]["id"] = RUN_ID + 1

        self.assert_fails(mutate, f"the run evidence is for run {RUN_ID + 1}, not the re-run {RUN_ID}")

    def test_a_run_of_another_attempt_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["run"]["run_attempt"] = ATTEMPT - 1

        self.assert_fails(mutate, f"not the re-run attempt {ATTEMPT}")

    def test_a_run_of_another_event_or_pull_request_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["run"]["event"] = "push"
            pack["run"]["pull_requests"] = []

        self.assert_fails(mutate, "triggered by 'push', not 'pull_request'")
        self.assert_fails(mutate, f"not associated with the canary pull request #{CANARY_NUMBER}")

    def test_a_run_attempt_that_started_before_the_rerun_request_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["run"]["run_started_at"] = "2026-09-07T22:48:23Z"

        self.assert_fails(mutate, "before the proof requested the re-run")

    def test_a_canary_head_that_moved_during_the_proof_fails_it(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["pull-request"]["commits"]["nodes"][0]["commit"]["oid"] = "8" * 40

        self.assert_fails(mutate, "the canary head moved during the proof")

    def test_jobs_of_another_attempt_fail_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["jobs"]["jobs"][1]["run_attempt"] = ATTEMPT - 1

        self.assert_fails(
            mutate, f"'Backend SAST (CodeQL)' belongs to run {RUN_ID} attempt {ATTEMPT - 1}"
        )

    def test_a_job_at_another_head_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["jobs"]["jobs"][2]["head_sha"] = "9" * 40

        self.assert_fails(mutate, "job 'Frontend SAST (CodeQL)' ran at")

    def test_a_merge_commit_that_does_not_merge_the_canary_head_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["merge-commit"]["parents"][1]["sha"] = "7" * 40

        self.assert_fails(mutate, "does not merge the canary head")

    def test_an_analysis_from_before_the_rerun_fails_the_proof(self) -> None:
        """A re-run reuses the merge commit, so an earlier attempt's analysis matches it exactly.

        The stale analysis has the same commit and the base-plus-one count; only its creation time
        separates it from one the re-run produced, and the verifier must not fall back to it.
        """
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-analyses"] = pack["ref-analyses"][1:]

        self.assert_fails(mutate, "outside the re-run's SAST job")

    def test_an_analysis_created_before_the_rerun_request_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["jobs"]["jobs"][1]["started_at"] = "2026-09-08T01:30:00Z"
            pack["ref-analyses"][0]["created_at"] = "2026-09-08T01:35:00Z"

        self.assert_fails(mutate, "outside the re-run's SAST job")

    def test_an_analysis_created_after_its_job_completed_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-analyses"][1]["created_at"] = "2026-09-08T01:50:00Z"

        self.assert_fails(mutate, "outside the re-run's SAST job")

    def test_an_errored_analysis_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-analyses"][0]["error"] = "SARIF upload was rejected"

        self.assert_fails(mutate, "1 errored analysis(es) skipped")

    def test_a_fixture_alert_last_seen_at_another_commit_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-alerts"][0][0]["most_recent_instance"]["commit_sha"] = "6" * 40

        self.assert_fails(mutate, f"at the analysed merge commit {MERGE_SHA[:9]}")

    def test_a_fixture_alert_on_another_ref_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["ref-alerts"][0][1]["most_recent_instance"]["ref"] = "refs/heads/main"

        self.assert_fails(mutate, "no open js/command-line-injection alert")

    def test_an_invalid_response_gate_failure_with_open_fixture_alerts_fails_the_proof(self) -> None:
        """The issue's gap: a gate that errored still fails its step while old alerts stay open."""
        def mutate(pack: dict[str, object]) -> None:
            pack["backend-annotations"] = [
                {
                    "annotation_level": "failure",
                    "title": "",
                    "message": "CodeQL alert response was invalid: expected a JSON array",
                },
                {
                    "annotation_level": "failure",
                    "title": "",
                    "message": "Process completed with exit code 2.",
                },
            ]

        self.assert_fails(mutate, "recorded no 'Blocking CodeQL alert #701' annotation")
        self.assert_fails(mutate, "reported 'CodeQL alert response was invalid'")

    def test_a_gate_failure_without_a_blocking_verdict_fails_the_proof(self) -> None:
        """A failed `gh api` fetch aborts the gate step before the checker writes any verdict."""
        def mutate(pack: dict[str, object]) -> None:
            pack["frontend-annotations"] = pack["frontend-annotations"][:1]

        self.assert_fails(mutate, "recorded no 'Blocking CodeQL alert #702' annotation")

    def test_a_blind_analysis_diagnostic_fails_the_proof_even_beside_a_verdict(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["backend-annotations"].append(
                {
                    "annotation_level": "failure",
                    "title": "",
                    "message": "CodeQL analysis cannot be gated: no results stored",
                }
            )

        self.assert_fails(mutate, "reported 'CodeQL analysis cannot be gated'")

    def test_a_blocking_verdict_for_another_alert_fails_the_proof(self) -> None:
        def mutate(pack: dict[str, object]) -> None:
            pack["frontend-annotations"] = blocking(999, "js/command-line-injection")

        self.assert_fails(mutate, "recorded no 'Blocking CodeQL alert #702' annotation")

    def test_the_issue_reproduction_no_longer_passes(self) -> None:
        """#1603: unrelated job heads, ancient errored analyses and an API-error gate diagnostic."""
        pack = evidence()
        for entry in pack["jobs"]["jobs"]:
            entry["head_sha"] = "a" * 40
        for category in (BACKEND_CATEGORY, FRONTEND_CATEGORY):
            ancient = analysis(category, MERGE_SHA, 0, "2020-01-01T00:00:00Z")
            ancient["error"] = "analysis failed"
            pack["ref-analyses"].append(ancient)
        for side in ("backend-annotations", "frontend-annotations"):
            pack[side] = [
                {
                    "annotation_level": "failure",
                    "title": "",
                    "message": "gh: Server Error (HTTP 502)",
                },
                {
                    "annotation_level": "failure",
                    "title": "",
                    "message": "Process completed with exit code 1.",
                },
            ]
        result, summary = self.run_verifier(pack, "--require-admin-enforcement")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("recorded no 'Blocking CodeQL alert #701' annotation", result.stderr)
        self.assertIn("ran at 'aaaaaaaa", result.stderr)
        self.assertIn("The proof failed.", summary)

    def test_a_malformed_timestamp_exits_two(self) -> None:
        pack = evidence()
        pack["ref-analyses"][0]["created_at"] = "yesterday"
        result, _ = self.run_verifier(pack)
        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("invalid timestamp", result.stderr)

    def test_a_malformed_rerun_request_time_exits_two(self) -> None:
        result, _ = self.run_verifier(evidence(), "--rerun-started-at", "2026-09-08")
        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("has no time zone", result.stderr)

    def test_malformed_evidence_exits_two(self) -> None:
        pack = evidence()
        pack["jobs"] = {"jobs": "not a list"}
        result, _ = self.run_verifier(pack)
        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("canary proof evidence was invalid", result.stderr)

    def test_evidence_for_another_pull_request_exits_two(self) -> None:
        pack = evidence()
        pack["pull-request"]["number"] = CANARY_NUMBER + 1
        result, _ = self.run_verifier(pack)
        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("not the canary", result.stderr)


if __name__ == "__main__":
    unittest.main()
