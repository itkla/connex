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
BACKEND_CATEGORY = "/language:java-kotlin"
FRONTEND_CATEGORY = "/language:javascript-typescript"
BACKEND_PATH = (
    "backend/src/test/java/ooo/klae/connex/backend/codeqlfixture/IntentionalCommandInjectionFixture.java"
)
FRONTEND_PATH = "frontend/test/fixtures/codeql/intentional-command-injection.mjs"
REQUIRED_CONTEXTS = [
    "Security — required",
    "Backend SAST (CodeQL)",
    "Frontend SAST (CodeQL)",
    "Backend — build & test",
]


def sast_job(name: str, analyse_step: str) -> dict[str, object]:
    return {
        "name": name,
        "conclusion": "failure",
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
            "location": {"path": path, "start_line": 12, "start_column": 5},
        },
    }


def check_run(name: str) -> dict[str, object]:
    return {"__typename": "CheckRun", "name": name, "conclusion": "FAILURE"}


def evidence() -> dict[str, object]:
    backend_alert = alert(701, "java/command-line-injection", BACKEND_PATH, BACKEND_CATEGORY)
    frontend_alert = alert(702, "js/command-line-injection", FRONTEND_PATH, FRONTEND_CATEGORY)
    return {
        "jobs": {
            "jobs": [
                {"name": "Classify security impact", "conclusion": "success", "steps": []},
                sast_job("Backend SAST (CodeQL)", "Analyze backend with CodeQL"),
                sast_job("Frontend SAST (CodeQL)", "Analyze frontend with CodeQL"),
                {"name": "Security — required", "conclusion": "failure", "steps": []},
            ]
        },
        "ref-alerts": [[backend_alert, frontend_alert]],
        "pr-alerts": [[backend_alert, frontend_alert]],
        "ref-analyses": [
            {"category": BACKEND_CATEGORY, "commit_sha": MERGE_SHA, "results_count": 59},
            {"category": FRONTEND_CATEGORY, "commit_sha": MERGE_SHA, "results_count": 9},
        ],
        "main-analyses": [
            {"category": BACKEND_CATEGORY, "commit_sha": BASE_SHA, "results_count": 58},
            {"category": FRONTEND_CATEGORY, "commit_sha": BASE_SHA, "results_count": 8},
        ],
        "merge-commit": {"sha": MERGE_SHA, "parents": [{"sha": BASE_SHA}]},
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
                            "oid": "3333333333333333333333333333333333333333",
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
            "### Jobs",
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
