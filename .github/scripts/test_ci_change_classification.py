import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("classify-ci-changes.py")
SPEC = importlib.util.spec_from_file_location("classify_ci_changes", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Unable to load CI classifier")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiChangeClassificationTest(unittest.TestCase):
    def classify(self, *paths: str, event_name: str = "pull_request") -> dict[str, bool]:
        categories, _ = MODULE.classify_paths(list(paths), event_name)
        return categories

    def test_documentation_only_selects_no_expensive_jobs(self) -> None:
        categories = self.classify("README.md", "docs/CI_POLICY.md", "frontend/AGENTS.md")
        self.assertFalse(any(categories.values()))

    def test_runtime_mdx_is_frontend_code_not_documentation(self) -> None:
        categories = self.classify("frontend/app/help/page.mdx")
        self.assertTrue(categories["frontend"])
        self.assertTrue(categories["frontend_sast"])
        self.assertTrue(categories["cross_stack"])

    def test_backend_change_runs_backend_cross_stack_and_profile_boot(self) -> None:
        categories = self.classify("backend/src/main/java/example/Service.java")
        self.assertTrue(categories["backend"])
        self.assertTrue(categories["backend_sast"])
        self.assertTrue(categories["cross_stack"])
        self.assertTrue(categories["profile_boot"])
        self.assertFalse(categories["frontend"])
        self.assertFalse(categories["ocr"])

    def test_backend_test_change_does_not_boot_deployment_profiles(self) -> None:
        categories = self.classify("backend/src/test/java/example/ServiceTest.java")
        self.assertTrue(categories["backend"])
        self.assertTrue(categories["backend_sast"])
        self.assertTrue(categories["cross_stack"])
        self.assertFalse(categories["profile_boot"])

    def test_migration_change_adds_the_forward_only_guard(self) -> None:
        categories = self.classify(
            "backend/src/main/resources/db/migration/tenant/V131__example.sql"
        )
        self.assertTrue(categories["backend"])
        self.assertTrue(categories["backend_sast"])
        self.assertTrue(categories["migrations"])
        self.assertTrue(categories["profile_boot"])

    def test_frontend_dependency_change_adds_audit(self) -> None:
        categories = self.classify("frontend/package.json", "frontend/app/page.tsx")
        self.assertTrue(categories["frontend"])
        self.assertTrue(categories["frontend_sast"])
        self.assertTrue(categories["cross_stack"])
        self.assertTrue(categories["frontend_audit"])
        self.assertFalse(categories["backend"])

    def test_ocr_dependencies_add_the_ocr_audit_only(self) -> None:
        categories = self.classify("ocr/requirements.lock")
        self.assertTrue(categories["ocr"])
        self.assertTrue(categories["ocr_audit"])
        self.assertFalse(categories["backend"])
        self.assertFalse(categories["backend_sast"])
        self.assertFalse(categories["frontend_sast"])
        self.assertFalse(categories["cross_stack"])

    def test_backup_change_does_not_boot_the_application(self) -> None:
        categories = self.classify("deploy/backup/backup.sh")
        self.assertTrue(categories["backup"])
        self.assertTrue(categories["action_pins"])

    def test_support_bundle_change_runs_its_own_offline_suite(self) -> None:
        categories = self.classify("deploy/support-bundle/collect.sh")
        self.assertTrue(categories["support_bundle"])
        self.assertFalse(categories["backup"])
        self.assertFalse(categories["profile_boot"])
        self.assertFalse(categories["compose"])
        self.assertFalse(categories["profile_boot"])

    def test_deployment_change_validates_compose_and_profiles(self) -> None:
        categories = self.classify("deploy/docker-compose.yml")
        self.assertTrue(categories["compose"])
        self.assertTrue(categories["profile_boot"])
        self.assertTrue(categories["action_pins"])
        self.assertFalse(categories["backend"])

    def test_caddyfile_change_adds_the_edge_header_regression(self) -> None:
        categories = self.classify("deploy/Caddyfile")
        self.assertTrue(categories["compose"])
        self.assertTrue(categories["profile_boot"])
        self.assertTrue(categories["action_pins"])

    def test_deployment_documentation_runs_security_regressions(self) -> None:
        categories = self.classify("deploy/backup/README.md")
        self.assertTrue(categories["action_pins"])
        self.assertFalse(categories["backup"])

    def test_network_runbooks_run_security_regressions(self) -> None:
        for path in ("docs/DEPLOYMENT.md", "docs/UPGRADING.md"):
            with self.subTest(path=path):
                categories = self.classify(path)
                self.assertTrue(categories["action_pins"])
                self.assertFalse(categories["compose"])

    def test_ci_policy_change_forces_every_category(self) -> None:
        categories = self.classify(".github/workflows/ci.yml")
        self.assertTrue(all(categories.values()))

    def test_unknown_path_fails_safe_to_every_category(self) -> None:
        categories = self.classify("benchmark/verify_report.py")
        self.assertTrue(all(categories.values()))

    def test_trusted_events_always_run_every_category(self) -> None:
        for event_name in ("push", "merge_group", "schedule", "workflow_dispatch"):
            with self.subTest(event_name=event_name):
                categories = self.classify(event_name=event_name)
                self.assertTrue(all(categories.values()))


if __name__ == "__main__":
    unittest.main()
