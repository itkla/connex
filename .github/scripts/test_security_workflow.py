import unittest
from pathlib import Path

import yaml


WORKFLOW_PATH = Path(__file__).parents[1] / "workflows" / "security.yml"
CODEQL_CONFIG_PATH = Path(__file__).parents[1] / "codeql" / "codeql-config.yml"
CODEQL_REVISION = "ff2f1c621b7f889edc0d3c761ac2e6a3f8cdb0dd"
CODEQL_CONFIG_INPUT = "./.github/codeql/codeql-config.yml"


class SecurityWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        cls.codeql_config_text = CODEQL_CONFIG_PATH.read_text(encoding="utf-8")
        cls.codeql_config = yaml.safe_load(cls.codeql_config_text)

    def job(self, name: str) -> dict[str, object]:
        return self.workflow["jobs"][name]

    def steps(self, name: str) -> list[dict[str, object]]:
        return self.job(name)["steps"]

    def named_step(self, job: str, name: str) -> dict[str, object]:
        return next(step for step in self.steps(job) if step.get("name") == name)

    def test_codeql_jobs_have_minimal_elevated_permissions(self) -> None:
        self.assertEqual({"contents": "read"}, self.workflow["permissions"])
        for job_name in ("backend-sast", "frontend-sast"):
            with self.subTest(job=job_name):
                self.assertEqual(
                    {"contents": "read", "security-events": "write"},
                    self.job(job_name)["permissions"],
                )
                self.assertNotIn("continue-on-error", str(self.job(job_name)))

    def test_codeql_filter_is_exact_and_has_fixed_term_approval_metadata(self) -> None:
        self.assertEqual(
            [
                {
                    "exclude": {
                        "id": "java/potentially-weak-cryptographic-algorithm"
                    }
                }
            ],
            self.codeql_config["query-filters"],
        )
        for required_annotation in (
            "Tracking issue: #1295",
            "Reason: HIBP k-anonymity Range API mandates SHA-1; index key only, never password storage.",
            "Owner: Hunter Nakagawa (Founder)",
            "Approver: Security Owner role",
            "Expiry: 2027-02-14",
            "Re-review: 2027-01-14",
            "Additional re-evaluation triggers: HIBP offers a stronger hash mode; the data flow changes;",
            "the query is materially updated.",
        ):
            with self.subTest(annotation=required_annotation):
                self.assertIn(required_annotation, self.codeql_config_text)
        self.assertNotIn("paths-ignore", self.codeql_config)

    def test_backend_codeql_uses_manual_java_26_build(self) -> None:
        job = self.job("backend-sast")
        self.assertEqual("needs.classify.outputs.backend_sast == 'true'", job["if"])
        setup = next(
            step
            for step in self.steps("backend-sast")
            if "actions/setup-java@" in step.get("uses", "")
        )
        self.assertEqual("26", setup["with"]["java-version"])
        initialize = self.named_step("backend-sast", "Initialize backend CodeQL database")
        self.assertEqual(f"github/codeql-action/init@{CODEQL_REVISION}", initialize["uses"])
        self.assertEqual("java-kotlin", initialize["with"]["languages"])
        self.assertEqual("manual", initialize["with"]["build-mode"])
        self.assertEqual("security-extended", initialize["with"]["queries"])
        self.assertEqual(CODEQL_CONFIG_INPUT, initialize["with"]["config-file"])
        self.assertEqual("backend", initialize["with"]["source-root"])
        self.assertNotIn("config", initialize["with"])
        build = self.named_step("backend-sast", "Compile backend for CodeQL extraction")
        self.assertEqual("backend", build["working-directory"])
        self.assertIn("compileJava", build["run"])
        self.assertTrue(build["run"].endswith("compileTestJava"))

    def test_frontend_codeql_is_buildless_and_scoped(self) -> None:
        job = self.job("frontend-sast")
        self.assertEqual("needs.classify.outputs.frontend_sast == 'true'", job["if"])
        initialize = self.named_step("frontend-sast", "Initialize frontend CodeQL database")
        self.assertEqual(f"github/codeql-action/init@{CODEQL_REVISION}", initialize["uses"])
        self.assertEqual("javascript-typescript", initialize["with"]["languages"])
        self.assertEqual("none", initialize["with"]["build-mode"])
        self.assertEqual("security-extended", initialize["with"]["queries"])
        self.assertEqual(CODEQL_CONFIG_INPUT, initialize["with"]["config-file"])
        self.assertEqual("frontend", initialize["with"]["source-root"])
        self.assertNotIn("config", initialize["with"])

    def test_pr_alert_gate_waits_for_analysis_and_filters_by_pr(self) -> None:
        for job_name, language in (
            ("backend-sast", "java-kotlin"),
            ("frontend-sast", "javascript-typescript"),
        ):
            with self.subTest(job=job_name):
                analyze = next(
                    step
                    for step in self.steps(job_name)
                    if step.get("uses", "").startswith("github/codeql-action/analyze@")
                )
                self.assertEqual(
                    f"github/codeql-action/analyze@{CODEQL_REVISION}", analyze["uses"]
                )
                self.assertIs(True, analyze["with"]["wait-for-processing"])
                self.assertEqual(f"/language:{language}", analyze["with"]["category"])
                gate = self.named_step(
                    job_name, "Block Critical, High, or error-severity alerts"
                )
                self.assertEqual(
                    "github.event_name == 'pull_request' || "
                    "github.event_name == 'merge_group'",
                    gate["if"],
                )
                self.assertIn('-f "pr=$PR_NUMBER"', gate["run"])
                self.assertIn('-f "ref=$ANALYSIS_REF"', gate["run"])
                self.assertIn("--method GET --paginate --slurp", gate["run"])
                self.assertIn("--paginate --slurp", gate["run"])
                self.assertIn("Unsupported CodeQL gate event", gate["run"])
                self.assertIn(
                    'check-codeql-alerts.py "$alerts_file" "$ANALYSIS_CATEGORY"',
                    gate["run"],
                )
                self.assertEqual(f"/language:{language}", gate["env"]["ANALYSIS_CATEGORY"])
                self.assertEqual("${{ github.ref }}", gate["env"]["ANALYSIS_REF"])
                self.assertEqual("${{ github.event_name }}", gate["env"]["EVENT_NAME"])

    def test_required_job_rejects_selected_skipped_scans(self) -> None:
        required = self.job("required")
        self.assertIn("backend-sast", required["needs"])
        self.assertIn("frontend-sast", required["needs"])
        gate = self.named_step("required", "Require every selected security job to succeed")
        self.assertEqual(
            "${{ needs.classify.outputs.backend_sast }}",
            gate["env"]["BACKEND_SAST_SELECTED"],
        )
        self.assertEqual(
            "${{ needs.classify.outputs.frontend_sast }}",
            gate["env"]["FRONTEND_SAST_SELECTED"],
        )
        self.assertIn('"$BACKEND_SAST_SELECTED:$BACKEND_SAST_RESULT"', gate["run"])
        self.assertIn('"$FRONTEND_SAST_SELECTED:$FRONTEND_SAST_RESULT"', gate["run"])
        self.assertIn("true:success|false:skipped", gate["run"])


if __name__ == "__main__":
    unittest.main()
