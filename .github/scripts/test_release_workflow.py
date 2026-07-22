import unittest
from pathlib import Path

import yaml


WORKFLOW_PATH = Path(__file__).parents[1] / "workflows" / "release.yml"
DEPLOYMENT_PATH = Path(__file__).parents[2] / "docs" / "DEPLOYMENT.md"


class ReleaseWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))

    def steps(self, job: str) -> list[dict[str, object]]:
        return self.workflow["jobs"][job]["steps"]

    def named_step(self, job: str, name: str) -> dict[str, object]:
        return next(step for step in self.steps(job) if step.get("name") == name)

    def test_transaction_recovery_precedes_moving_head_and_ci_checks(self) -> None:
        names = [step.get("name") for step in self.steps("metadata")]
        recovery = names.index("Detect a committed release transaction from an earlier attempt")
        moving_head = names.index("Require release commit to remain the main head")
        trusted_ci = names.index("Require successful trusted workflows for the release commit")

        self.assertLess(recovery, moving_head)
        self.assertLess(recovery, trusted_ci)
        self.assertEqual(
            "steps.transaction.outputs.exists != 'true'",
            self.named_step("metadata", "Require release commit to remain the main head")["if"],
        )
        self.assertEqual(
            "steps.transaction.outputs.exists != 'true'",
            self.named_step("metadata", "Require successful trusted workflows for the release commit")["if"],
        )

    def test_buildkit_check_matches_the_current_inspection_field(self) -> None:
        checks = [
            step["run"]
            for job in ("candidate-images", "promote")
            for step in self.steps(job)
            if step.get("name") == "Verify pinned build toolchain"
        ]

        self.assertEqual(2, len(checks))
        self.assertTrue(all("BuildKit version: v0.31.1" in check for check in checks))

    def test_transaction_and_candidates_are_attempt_scoped(self) -> None:
        upload = self.named_step("release-set-smoke", "Upload committed release transaction")
        self.assertEqual(
            "release-transaction-${{ github.run_id }}-${{ github.run_attempt }}",
            upload["with"]["name"],
        )
        candidate_check = self.named_step("release-set-smoke", "Require complete candidates from this attempt")
        self.assertIn("${GITHUB_RUN_ATTEMPT}", candidate_check["run"])
        self.assertIn("Re-run all jobs", candidate_check["run"])

    def test_promotion_reresolves_transaction_for_failed_job_retries(self) -> None:
        resolver = self.named_step("promote", "Resolve the committed release transaction")
        promote_download = next(
            step for step in self.steps("promote") if step.get("uses", "").startswith("actions/download-artifact@")
        )
        release_download = next(
            step for step in self.steps("release") if step.get("uses", "").startswith("actions/download-artifact@")
        )

        self.assertIn("release-transaction-${GITHUB_RUN_ID}-", resolver["run"])
        self.assertEqual("${{ steps.transaction.outputs.name }}", promote_download["with"]["name"])
        self.assertEqual("${{ needs.promote.outputs.transaction_name }}", release_download["with"]["name"])

    def test_publication_has_explicit_repository_and_exact_assets(self) -> None:
        publish = self.named_step("release", "Publish the complete verified release atomically")
        self.assertEqual("${{ github.repository }}", publish["env"]["GH_REPO"])
        self.assertIn("diff -u /tmp/expected-release-assets /tmp/actual-release-assets", publish["run"])
        self.assertIn("gh release verify", publish["run"])
        self.assertEqual(
            4,
            WORKFLOW_PATH.read_text(encoding="utf-8").count("verify-release-preconditions.sh"),
        )

    def test_qualification_and_sbom_are_recomputed_and_bound(self) -> None:
        workflow_source = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertEqual(2, workflow_source.count("benchmark/verify_report.py"))
        expected_argument_counts = {
            "--base-url": 2,
            "--requests-per-minute": 3,
            "--backend-image-reference": 2,
            "--frontend-image-reference": 2,
            "--ocr-image-reference": 2,
        }
        for argument, expected_count in expected_argument_counts.items():
            self.assertEqual(expected_count, workflow_source.count(argument))
        self.assertIn("verify-attested-sbom.py", workflow_source)
        self.assertNotIn("all(. == true)", workflow_source)
        self.assertNotIn("CONNEX_*_IMAGE", DEPLOYMENT_PATH.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
