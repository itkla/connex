"""Regression tests for the pnpm supply-chain policy guard (#835)."""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-pnpm-supply-chain-policy.py")
REPOSITORY = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location("check_pnpm_supply_chain_policy", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the pnpm supply-chain policy guard")
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)

WORKSPACE = Path("frontend/pnpm-workspace.yaml")
POLICY = (
    "allowBuilds:\n"
    "  esbuild: true\n"
    "# Supply-chain resolution policy (#835).\n"
    "minimumReleaseAge: 1440\n"
    "minimumReleaseAgeStrict: true\n"
    "minimumReleaseAgeIgnoreMissingTime: false\n"
    "trustPolicy: no-downgrade\n"
)
APPROVED_NEXT = (
    "minimumReleaseAgeExclude:\n"
    "  # GHSA-2222-3333-4444 (Next.js middleware bypass). Approved: Hunter Nakagawa, 2026-09-19.\n"
    "  # Remove once next@16.3.5 is older than one day.\n"
    "  - next@16.3.5\n"
    "  # GHSA-2222-3333-4444 platform binary. Approved: Hunter Nakagawa, 2026-09-19. Remove with next.\n"
    "  - '@next/swc-linux-x64-gnu@16.3.5'\n"
)


class PnpmSupplyChainPolicyGuardTest(unittest.TestCase):
    def violations(self, text: str) -> list[str]:
        return GUARD.workspace_violations(WORKSPACE, text)

    def test_the_committed_workspaces_hold_the_policy(self) -> None:
        self.assertEqual([], GUARD.policy_violations(REPOSITORY))

    def test_an_owner_approved_exact_version_with_its_evidence_passes(self) -> None:
        self.assertEqual([], self.violations(POLICY + APPROVED_NEXT))

    def test_a_missing_or_changed_policy_setting_fails(self) -> None:
        relaxed = (
            POLICY.replace("minimumReleaseAgeStrict: true\n", "minimumReleaseAgeStrict: false\n")
            .replace("minimumReleaseAgeIgnoreMissingTime: false\n", "")
            .replace("minimumReleaseAge: 1440\n", "minimumReleaseAge: 60 # one hour\n")
        )
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:4: minimumReleaseAge is '60'; the policy requires '1440'",
                "frontend/pnpm-workspace.yaml:5: minimumReleaseAgeStrict is 'false'; the policy requires 'true'",
                "frontend/pnpm-workspace.yaml: `minimumReleaseAgeIgnoreMissingTime: false` is missing",
            ],
            self.violations(relaxed),
        )

    def test_settings_that_relax_the_policy_or_repeat_a_key_fail(self) -> None:
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:10: trustPolicy is 'off'; the policy requires 'no-downgrade'",
                "frontend/pnpm-workspace.yaml:8: trustLockfile relaxes the policy and is not allowed",
                "frontend/pnpm-workspace.yaml:9: trustPolicyIgnoreAfter relaxes the policy and is not allowed",
                "frontend/pnpm-workspace.yaml:10: trustPolicy is set more than once",
            ],
            self.violations(POLICY + "trustLockfile: true\ntrustPolicyIgnoreAfter: 1440\ntrustPolicy: off\n"),
        )

    def test_an_unrecognised_top_level_construct_fails_closed(self) -> None:
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:8: unrecognised top-level line \"'trustLockfile': true\"; "
                "write each setting as plain `key: value`",
                "frontend/pnpm-workspace.yaml:9: unrecognised top-level line '---'; "
                "write each setting as plain `key: value`",
            ],
            self.violations(POLICY + "'trustLockfile': true\n---\n"),
        )

    def test_the_uncommented_entry_pnpm_audit_fix_writes_fails(self) -> None:
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:11: minimumReleaseAgeExclude entry 'semver@7.5.2' "
                "has no comment directly above it",
            ],
            self.violations(
                POLICY
                + "overrides:\n  semver@>=7.0.0 <7.5.2: ^7.5.2\nminimumReleaseAgeExclude:\n  - semver@7.5.2\n"
            ),
        )

    def test_a_comment_separated_by_a_blank_line_does_not_annotate_the_entry(self) -> None:
        detached = APPROVED_NEXT.replace("  - next@16.3.5\n", "\n  - next@16.3.5\n")
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:12: minimumReleaseAgeExclude entry 'next@16.3.5' "
                "has no comment directly above it",
            ],
            self.violations(POLICY + detached),
        )

    def test_entries_that_are_not_one_exact_version_fail(self) -> None:
        note = "  # GHSA-2222-3333-4444. Approved: Hunter Nakagawa, 2026-09-19. Remove after 2026-09-20.\n"
        entries = ("next", "@next/*", "next@^16.3.5", "next@16.3.5 || 16.3.6", "next@latest")
        text = POLICY + "minimumReleaseAgeExclude:\n" + "".join(f"{note}  - '{entry}'\n" for entry in entries)
        self.assertEqual(
            [
                f"frontend/pnpm-workspace.yaml:{10 + 2 * index}: minimumReleaseAgeExclude entry {entry!r} "
                "is not one exact `name@x.y.z` version (no bare name, wildcard, range, or `||` union)"
                for index, entry in enumerate(entries)
            ],
            self.violations(text),
        )

    def test_a_release_age_comment_must_name_advisory_approval_and_removal(self) -> None:
        text = POLICY + "minimumReleaseAgeExclude:\n  # Urgent Next.js patch.\n  - next@16.3.5\n"
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:10: minimumReleaseAgeExclude entry 'next@16.3.5' "
                "comment names no GHSA or CVE advisory",
                "frontend/pnpm-workspace.yaml:10: minimumReleaseAgeExclude entry 'next@16.3.5' "
                "comment records no owner approval (`Approved: <name>, <YYYY-MM-DD>`)",
                "frontend/pnpm-workspace.yaml:10: minimumReleaseAgeExclude entry 'next@16.3.5' "
                "comment states no removal condition (`Remove ...`)",
            ],
            self.violations(text),
        )

    def test_a_trust_exclusion_needs_an_exact_version_and_a_removal_condition(self) -> None:
        text = (
            POLICY
            + "trustPolicyExclude:\n"
            + "  # 3.x maintenance release by the same maintainer. Remove once eslint-config-next moves to 4.x.\n"
            + "  - eslint-import-resolver-typescript@3.10.1\n"
            + "  # Legacy-line backport.\n"
            + "  - semver@6.3.1\n"
            + "  - lodash\n"
        )
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:12: trustPolicyExclude entry 'semver@6.3.1' "
                "comment states no removal condition (`Remove ...`)",
                "frontend/pnpm-workspace.yaml:13: trustPolicyExclude entry 'lodash' "
                "is not one exact `name@x.y.z` version (no bare name, wildcard, range, or `||` union)",
                "frontend/pnpm-workspace.yaml:13: trustPolicyExclude entry 'lodash' "
                "has no comment directly above it",
            ],
            self.violations(text),
        )

    def test_an_inline_exclusion_list_fails(self) -> None:
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:8: minimumReleaseAgeExclude must be a block sequence with one "
                "commented entry per line",
            ],
            self.violations(POLICY + "minimumReleaseAgeExclude: [next@16.3.5]\n"),
        )

    def test_cli_fails_on_a_missing_workspace_and_passes_once_all_hold(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for workspace in GUARD.WORKSPACE_FILES[:2]:
                root.joinpath(workspace).parent.mkdir(parents=True, exist_ok=True)
                root.joinpath(workspace).write_text(POLICY, encoding="utf-8")
            red = subprocess.run(
                [sys.executable, str(SCRIPT)], cwd=root, check=False, capture_output=True, text=True
            )
            self.assertEqual(1, red.returncode, red.stdout)
            self.assertIn("::error::landing/pnpm-workspace.yaml: pnpm workspace file is missing", red.stderr)

            root.joinpath("landing").mkdir()
            root.joinpath("landing", "pnpm-workspace.yaml").write_text(POLICY, encoding="utf-8")
            green = subprocess.run(
                [sys.executable, str(SCRIPT)], cwd=root, check=False, capture_output=True, text=True
            )
            self.assertEqual(0, green.returncode, green.stderr)
            self.assertIn("pnpm supply-chain policy holds", green.stdout)


if __name__ == "__main__":
    unittest.main()
