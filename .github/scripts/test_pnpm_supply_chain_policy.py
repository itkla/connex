"""Regression tests for the pnpm supply-chain policy guard (#835)."""

import hashlib
import importlib.util
import json
import os
import shutil
import stat
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
READ_PACKAGE_PNPMFILE = (
    "function readPackage(pkg) {\n"
    "    return pkg;\n"
    "}\n"
    "\n"
    "module.exports = { hooks: { readPackage } };\n"
)
LOCKFILE = "lockfileVersion: '9.0'\n"
REVIEWED_PNPMFILE = REPOSITORY.joinpath("frontend", ".pnpmfile.cjs").read_bytes()
MANIFEST = {"name": "project", "version": "0.0.0", "private": True, "packageManager": "pnpm@11.9.0"}
RESOLVED_POLICY = {
    "@jsr:registry": "https://npm.jsr.io/",
    "json": True,
    "minimumReleaseAge": 1440,
    "minimumReleaseAgeIgnoreMissingTime": False,
    "minimumReleaseAgeStrict": True,
    "registry": "https://registry.npmjs.org/",
    "trustPolicy": "no-downgrade",
    "userAgent": "pnpm/11.9.0 npm/? node/v22.20.0 linux x64",
}
PNPM_STUB = """#!{python}
import json
import sys
from pathlib import Path

if sys.argv[1:] != ["config", "list", "--json"]:
    sys.exit(f"unexpected pnpm arguments {{sys.argv[1:]}}")
responses = json.loads(Path(__file__).with_name("responses.json").read_text(encoding="utf-8"))
response = responses[str(Path.cwd().resolve())]
sys.stdout.write(response["stdout"])
sys.exit(response["exit"])
"""


def track(root: Path) -> None:
    for command in (["git", "init", "--quiet"], ["git", "add", "--all"]):
        subprocess.run(command, cwd=root, check=True, capture_output=True)


def write_manifest(root: Path, directory: Path, manifest: dict[str, object]) -> None:
    root.joinpath(directory).mkdir(parents=True, exist_ok=True)
    root.joinpath(directory, "package.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def write_workspaces(root: Path, texts: dict[Path, str] | None = None) -> None:
    for workspace in GUARD.WORKSPACE_FILES:
        path = root / workspace
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text((texts or {}).get(workspace, POLICY), encoding="utf-8")
        write_manifest(root, workspace.parent, MANIFEST)
    track(root)


def write_pnpm_stub(root: Path, reports: dict[Path, tuple[int, str]]) -> Path:
    stub_directory = root / "stub-bin"
    stub_directory.mkdir(exist_ok=True)
    stub = stub_directory / "pnpm"
    stub.write_text(PNPM_STUB.format(python=sys.executable), encoding="utf-8")
    stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
    responses = {}
    for workspace in GUARD.WORKSPACE_FILES:
        returncode, stdout = reports.get(workspace, (0, json.dumps(RESOLVED_POLICY)))
        responses[str((root / workspace).parent.resolve())] = {"exit": returncode, "stdout": stdout}
    stub_directory.joinpath("responses.json").write_text(json.dumps(responses), encoding="utf-8")
    return stub


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

    def test_a_setting_outside_the_allowlist_fails(self) -> None:
        overriding = (
            "trustLockfile",
            "trustPolicyIgnoreAfter",
            "pnpmfile",
            "globalPnpmfile",
            "registry",
            "configDependencies",
        )
        text = (
            POLICY
            + "trustLockfile: true\ntrustPolicyIgnoreAfter: 1440\npnpmfile: tools/relax.cjs\n"
            + "globalPnpmfile: tools/relax.cjs\nregistry: https://registry.example.invalid/\n"
            + "configDependencies:\n  pnpm-plugin-relax: 1.0.0+sha512-AAAA\n"
        )
        self.assertEqual(
            [
                f"frontend/pnpm-workspace.yaml:{8 + index}: {key} is not a setting this guard allows; pnpm "
                "settings such as pnpmfile, configDependencies, registry, or trustLockfile can override the "
                "policy, so admitting one needs a reviewed change to this guard"
                for index, key in enumerate(overriding)
            ],
            self.violations(text),
        )

    def test_a_repeated_setting_fails(self) -> None:
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:8: trustPolicy is 'off'; the policy requires 'no-downgrade'",
                "frontend/pnpm-workspace.yaml:8: trustPolicy is set more than once",
            ],
            self.violations(POLICY + "trustPolicy: off\n"),
        )

    def test_a_continuation_line_that_rewrites_a_setting_fails(self) -> None:
        continued = (
            POLICY.replace("minimumReleaseAge: 1440\n", "minimumReleaseAge: 1440\n  minutes\n")
            .replace(
                "minimumReleaseAgeIgnoreMissingTime: false\n", "minimumReleaseAgeIgnoreMissingTime: false\n  alarm\n"
            )
            .replace("trustPolicy: no-downgrade\n", "trustPolicy: no-downgrade\n  # still a comment\n  please\n")
        )
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:5: indented line 'minutes' continues the value of "
                "minimumReleaseAge; pnpm reads it as part of that value",
                "frontend/pnpm-workspace.yaml:8: indented line 'alarm' continues the value of "
                "minimumReleaseAgeIgnoreMissingTime; pnpm reads it as part of that value",
                "frontend/pnpm-workspace.yaml:11: indented line 'please' continues the value of "
                "trustPolicy; pnpm reads it as part of that value",
            ],
            self.violations(continued),
        )

    def test_a_character_pnpm_does_not_split_lines_on_fails(self) -> None:
        for character in ("\x0b", "\u2028"):
            hidden = POLICY.replace(
                "minimumReleaseAgeStrict: true\n",
                f"# pnpm reads this line as a comment{character}minimumReleaseAgeStrict: true\n",
            )
            with self.subTest(character=f"U+{ord(character):04X}"):
                self.assertEqual(
                    [
                        f"frontend/pnpm-workspace.yaml:5: control or line-separator character U+{ord(character):04X}; "
                        "pnpm and this guard could split the file into different lines",
                        "frontend/pnpm-workspace.yaml: `minimumReleaseAgeStrict: true` is missing",
                    ],
                    self.violations(hidden),
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

    def test_a_version_union_continued_onto_the_next_line_fails(self) -> None:
        text = (
            POLICY
            + "minimumReleaseAgeExclude:\n"
            + "  # GHSA-2222-3333-4444. Approved: Hunter Nakagawa, 2026-09-19. Remove after 2026-09-20.\n"
            + "  - typescript@7.1.0-dev.20260917.1\n"
            + "    || 7.1.0-dev.20260918.1\n"
        )
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml:11: unrecognised line in minimumReleaseAgeExclude: "
                "'|| 7.1.0-dev.20260918.1'",
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

    def test_the_reviewed_pnpmfile_matching_its_pinned_digest_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            root.joinpath("frontend", ".pnpmfile.cjs").write_bytes(REVIEWED_PNPMFILE)
            self.assertEqual([], GUARD.policy_violations(root))

    def test_an_edit_to_the_reviewed_pnpmfile_fails_even_without_a_suspicious_word(self) -> None:
        edited = REVIEWED_PNPMFILE.replace(b"'^8.5.10'", b"'^8.5.11'")
        self.assertNotEqual(REVIEWED_PNPMFILE, edited)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            root.joinpath("frontend", ".pnpmfile.cjs").write_bytes(edited)
            self.assertEqual(
                [
                    f"frontend/.pnpmfile.cjs: SHA-256 {hashlib.sha256(edited).hexdigest()} is not the reviewed "
                    f"{GUARD.PINNED_PNPMFILES[Path('frontend/.pnpmfile.cjs')]}; its hooks can rewrite the policy "
                    "after the workspace file is read, so every edit needs a reviewed change to the digest in "
                    "this guard",
                ],
                GUARD.policy_violations(root),
            )

    def test_a_pnpmfile_outside_the_pin_fails_and_names_what_the_lexical_scan_saw(self) -> None:
        unpinned = (
            "pnpm loads this pnpmfile, whose hooks can rewrite the policy after the workspace file is read; "
            "only the reviewed frontend/.pnpmfile.cjs is allowed, so adding another needs a reviewed change "
            "to this guard"
        )
        hooked = REVIEWED_PNPMFILE.replace(
            b"{ readPackage }", b"{ readPackage, updateConfig: (config) => ({ ...config, minimumReleaseAge: 0 }) }"
        )
        self.assertNotEqual(REVIEWED_PNPMFILE, hooked)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            root.joinpath("frontend", ".pnpmfile.cjs").write_bytes(hooked)
            root.joinpath("frontend", "emails", ".pnpmfile.mjs").write_text(
                "export const hooks = await import('./tools/hooks.mjs');\n", encoding="utf-8"
            )
            root.joinpath("landing", ".pnpmfile.cjs").write_text(READ_PACKAGE_PNPMFILE, encoding="utf-8")
            root.joinpath("landing", ".pnpmfile.mjs").mkdir()
            self.assertEqual(
                [
                    f"frontend/.pnpmfile.cjs: SHA-256 {hashlib.sha256(hooked).hexdigest()} is not the reviewed "
                    f"{GUARD.PINNED_PNPMFILES[Path('frontend/.pnpmfile.cjs')]}; its hooks can rewrite the policy "
                    "after the workspace file is read, so every edit needs a reviewed change to the digest in "
                    "this guard (lexical hint: line 13 names `updateConfig`)",
                    f"frontend/emails/.pnpmfile.mjs: {unpinned} (lexical hint: line 1 loads another module with "
                    "`import`)",
                    f"landing/.pnpmfile.cjs: {unpinned}",
                    "landing/.pnpmfile.mjs: is not a regular file, so this guard cannot pin the hooks pnpm loads",
                ],
                GUARD.policy_violations(root),
            )

    def test_a_pnpmfile_in_an_uncovered_tracked_project_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            root.joinpath("tools", "codegen").mkdir(parents=True)
            root.joinpath("tools", "codegen", "pnpm-lock.yaml").write_text(LOCKFILE, encoding="utf-8")
            root.joinpath("tools", "codegen", ".pnpmfile.cjs").write_text(READ_PACKAGE_PNPMFILE, encoding="utf-8")
            track(root)
            violations = GUARD.policy_violations(root)
            self.assertEqual(2, len(violations), violations)
            self.assertTrue(violations[1].startswith("tools/codegen/.pnpmfile.cjs: pnpm loads this pnpmfile"))

    def test_a_tracked_pnpm_project_the_guard_does_not_cover_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            root.joinpath("tools", "codegen").mkdir(parents=True)
            root.joinpath("tools", "codegen", "pnpm-lock.yaml").write_text(LOCKFILE, encoding="utf-8")
            root.joinpath("pnpm-workspace.yaml").write_text("packages: []\n", encoding="utf-8")
            write_manifest(root, Path("scripts/release"), {**MANIFEST, "packageManager": "pnpm@10.0.0"})
            write_manifest(root, Path("scripts/npm-only"), {**MANIFEST, "packageManager": "npm@10.9.0"})
            root.joinpath("frontend", "pnpm-lock.yaml").write_text(LOCKFILE, encoding="utf-8")
            track(root)
            uncovered = (
                "is a tracked pnpm project this guard does not cover; add it to PROJECT_DIRECTORIES in a "
                "reviewed change so the policy applies to it"
            )
            self.assertEqual(
                [f".: {uncovered}", f"scripts/release: {uncovered}", f"tools/codegen: {uncovered}"],
                GUARD.policy_violations(root),
            )

    def test_projects_that_git_cannot_list_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            shutil.rmtree(root / ".git")
            violations = GUARD.policy_violations(root)
            self.assertEqual(1, len(violations), violations)
            self.assertTrue(
                violations[0].startswith("cannot discover the pnpm projects: `git ls-files` exited "), violations[0]
            )

    def test_every_project_must_pin_the_reviewed_pnpm_without_a_dev_engines_override(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            downloaded = {"name": "pnpm", "version": "11.1.2", "onFail": "download"}
            write_manifest(root, Path("frontend"), {**MANIFEST, "devEngines": {"packageManager": downloaded}})
            unpinned = {key: value for key, value in MANIFEST.items() if key != "packageManager"}
            write_manifest(root, Path("frontend/emails"), unpinned)
            write_manifest(root, Path("landing"), {**MANIFEST, "packageManager": "pnpm@11.1.2"})
            track(root)
            reason = (
                'the policy requires "pnpm@11.9.0", because pnpm before 11.1.3 skips the lockfile re-check on a '
                "frozen install, so changing the version needs a reviewed change to this guard"
            )
            self.assertEqual(
                [
                    "frontend/package.json: devEngines.packageManager can make pnpm run another version; pin "
                    "pnpm@11.9.0 through packageManager alone",
                    f"frontend/emails/package.json: packageManager is null; {reason}",
                    f'landing/package.json: packageManager is "pnpm@11.1.2"; {reason}',
                ],
                GUARD.policy_violations(root),
            )

    def test_the_resolved_configuration_matching_the_policy_and_the_file_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root, {WORKSPACE: POLICY + APPROVED_NEXT})
            resolved = {
                **RESOLVED_POLICY,
                "minimumReleaseAgeExclude": ["next@16.3.5", "@next/swc-linux-x64-gnu@16.3.5"],
                "trustPolicyExclude": [],
            }
            stub = write_pnpm_stub(root, {WORKSPACE: (0, json.dumps(resolved))})
            self.assertEqual([], GUARD.effective_violations(root, pnpm=str(stub)))

    def test_a_resolved_configuration_that_differs_from_the_policy_fails(self) -> None:
        union = "typescript@7.1.0-dev.20260917.1 || 7.1.0-dev.20260918.1"
        hooked = {
            **RESOLVED_POLICY,
            "minimumReleaseAge": 0,
            "minimumReleaseAgeStrict": 1,
            "trustPolicy": "no-downgrade please",
            "registry": "https://registry.example.invalid/",
            "trustLockfile": True,
            "minimumReleaseAgeExclude": [union],
        }
        landing = {key: value for key, value in RESOLVED_POLICY.items() if key != "minimumReleaseAgeIgnoreMissingTime"}
        landing["trustPolicyExclude"] = ["semver@6.3.1"]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            stub = write_pnpm_stub(
                root,
                {
                    WORKSPACE: (0, json.dumps(hooked)),
                    Path("frontend/emails/pnpm-workspace.yaml"): (1, "ERR_PNPM_CONFIG_IS_UNDEFINED\n"),
                    Path("landing/pnpm-workspace.yaml"): (0, json.dumps(landing)),
                },
            )
            self.assertEqual(
                [
                    "frontend/pnpm-workspace.yaml: pnpm resolves minimumReleaseAge to 0; the policy requires 1440",
                    "frontend/pnpm-workspace.yaml: pnpm resolves minimumReleaseAgeStrict to 1; the policy "
                    "requires true",
                    'frontend/pnpm-workspace.yaml: pnpm resolves trustPolicy to "no-downgrade please"; the policy '
                    'requires "no-downgrade"',
                    'frontend/pnpm-workspace.yaml: pnpm resolves registry to "https://registry.example.invalid/"; '
                    'the policy requires "https://registry.npmjs.org/"',
                    "frontend/pnpm-workspace.yaml: pnpm resolves trustLockfile to true; the policy requires it unset",
                    f'frontend/pnpm-workspace.yaml: pnpm resolves minimumReleaseAgeExclude to ["{union}"], but the '
                    "annotated entries in the file are []",
                    f"frontend/emails/pnpm-workspace.yaml: `{stub} config list --json` exited 1: "
                    "ERR_PNPM_CONFIG_IS_UNDEFINED",
                    "landing/pnpm-workspace.yaml: pnpm resolves minimumReleaseAgeIgnoreMissingTime to null; the "
                    "policy requires false",
                    'landing/pnpm-workspace.yaml: pnpm resolves trustPolicyExclude to ["semver@6.3.1"], but the '
                    "annotated entries in the file are []",
                ],
                GUARD.effective_violations(root, pnpm=str(stub)),
            )

    def effective(self, reports: dict[Path, dict[str, object]]) -> list[str]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            stub = write_pnpm_stub(root, {workspace: (0, json.dumps(report)) for workspace, report in reports.items()})
            return GUARD.effective_violations(root, pnpm=str(stub))

    def test_a_pnpm_other_than_the_pinned_version_fails(self) -> None:
        without_agent = {key: value for key, value in RESOLVED_POLICY.items() if key != "userAgent"}
        self.assertEqual(
            [
                'frontend/pnpm-workspace.yaml: pnpm reports userAgent "pnpm/11.1.2 npm/? node/v22.20.0 linux x64"; '
                "the policy requires pnpm 11.9.0 (`pnpm/11.9.0 ...`)",
                "frontend/emails/pnpm-workspace.yaml: pnpm reports userAgent null; the policy requires pnpm 11.9.0 "
                "(`pnpm/11.9.0 ...`)",
                'landing/pnpm-workspace.yaml: pnpm reports userAgent "pnpm/11.9.0-rc.1 npm/? node/v22.20.0 linux x64"; '
                "the policy requires pnpm 11.9.0 (`pnpm/11.9.0 ...`)",
            ],
            self.effective(
                {
                    WORKSPACE: {**RESOLVED_POLICY, "userAgent": "pnpm/11.1.2 npm/? node/v22.20.0 linux x64"},
                    Path("frontend/emails/pnpm-workspace.yaml"): without_agent,
                    Path("landing/pnpm-workspace.yaml"): {
                        **RESOLVED_POLICY,
                        "userAgent": "pnpm/11.9.0-rc.1 npm/? node/v22.20.0 linux x64",
                    },
                }
            ),
        )

    def test_a_scoped_registry_other_than_the_built_in_jsr_registry_fails(self) -> None:
        self.assertEqual(
            [
                'frontend/pnpm-workspace.yaml: pnpm resolves @acme:registry to "https://registry.example.invalid/"; '
                "only the built-in @jsr:registry https://npm.jsr.io/ may send a scope to another registry",
                'landing/pnpm-workspace.yaml: pnpm resolves @jsr:registry to "https://jsr.example.invalid/"; '
                "only the built-in @jsr:registry https://npm.jsr.io/ may send a scope to another registry",
            ],
            self.effective(
                {
                    WORKSPACE: {**RESOLVED_POLICY, "@acme:registry": "https://registry.example.invalid/"},
                    Path("landing/pnpm-workspace.yaml"): {
                        **RESOLVED_POLICY,
                        "@jsr:registry": "https://jsr.example.invalid/",
                    },
                }
            ),
        )

    def test_disabled_registry_certificate_verification_fails(self) -> None:
        self.assertEqual(
            [
                "frontend/pnpm-workspace.yaml: pnpm resolves strictSsl to false; the policy requires registry TLS "
                "certificates to be verified",
                "landing/pnpm-workspace.yaml: pnpm resolves strict-ssl to false; the policy requires registry TLS "
                "certificates to be verified",
            ],
            self.effective(
                {
                    WORKSPACE: {**RESOLVED_POLICY, "strictSsl": False},
                    Path("frontend/emails/pnpm-workspace.yaml"): {**RESOLVED_POLICY, "strictSsl": True},
                    Path("landing/pnpm-workspace.yaml"): {**RESOLVED_POLICY, "strict-ssl": False},
                }
            ),
        )

    def test_a_proxy_between_pnpm_and_the_registry_fails(self) -> None:
        proxy = "http://proxy.example.invalid:3128/"
        self.assertEqual(
            [
                f'frontend/pnpm-workspace.yaml: pnpm resolves proxy to "{proxy}"; the policy requires no proxy '
                "between pnpm and the registry",
                f'frontend/emails/pnpm-workspace.yaml: pnpm resolves httpsProxy to "{proxy}"; the policy requires '
                "no proxy between pnpm and the registry",
                f'landing/pnpm-workspace.yaml: pnpm resolves https-proxy to "{proxy}"; the policy requires no proxy '
                "between pnpm and the registry",
            ],
            self.effective(
                {
                    WORKSPACE: {**RESOLVED_POLICY, "proxy": proxy},
                    Path("frontend/emails/pnpm-workspace.yaml"): {**RESOLVED_POLICY, "httpsProxy": proxy},
                    Path("landing/pnpm-workspace.yaml"): {**RESOLVED_POLICY, "https-proxy": proxy},
                }
            ),
        )

    def test_unreadable_pnpm_output_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            stub = write_pnpm_stub(
                root,
                {
                    WORKSPACE: (0, "Progress: resolved 1\n"),
                    Path("landing/pnpm-workspace.yaml"): (0, "[]"),
                },
            )
            violations = GUARD.effective_violations(root, pnpm=str(stub))
            self.assertEqual(2, len(violations), violations)
            self.assertTrue(
                violations[0].startswith(
                    f"frontend/pnpm-workspace.yaml: `{stub} config list --json` printed no JSON: "
                ),
                violations[0],
            )
            self.assertEqual(
                f"landing/pnpm-workspace.yaml: `{stub} config list --json` printed list, not an object", violations[1]
            )

    def test_cli_effective_mode_asks_the_pnpm_on_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_workspaces(root)
            stub = write_pnpm_stub(root, {})
            environment = {**os.environ, "PATH": f"{stub.parent}{os.pathsep}{os.environ['PATH']}"}
            green = subprocess.run(
                [sys.executable, str(SCRIPT), "--effective"],
                cwd=root,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, green.returncode, green.stderr)
            self.assertIn("pnpm resolves the supply-chain policy", green.stdout)

            hooked = json.dumps({**RESOLVED_POLICY, "minimumReleaseAge": 0})
            write_pnpm_stub(root, {workspace: (0, hooked) for workspace in GUARD.WORKSPACE_FILES})
            red = subprocess.run(
                [sys.executable, str(SCRIPT), "--effective"],
                cwd=root,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(1, red.returncode, red.stdout)
            self.assertIn(
                "::error::landing/pnpm-workspace.yaml: pnpm resolves minimumReleaseAge to 0; the policy requires 1440",
                red.stderr,
            )

    def test_cli_fails_on_a_missing_workspace_and_passes_once_all_hold(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for workspace in GUARD.WORKSPACE_FILES:
                write_manifest(root, workspace.parent, MANIFEST)
            for workspace in GUARD.WORKSPACE_FILES[:2]:
                root.joinpath(workspace).write_text(POLICY, encoding="utf-8")
            track(root)
            red = subprocess.run(
                [sys.executable, str(SCRIPT)], cwd=root, check=False, capture_output=True, text=True
            )
            self.assertEqual(1, red.returncode, red.stdout)
            self.assertIn("::error::landing/pnpm-workspace.yaml: pnpm workspace file is missing", red.stderr)

            root.joinpath("landing", "pnpm-workspace.yaml").write_text(POLICY, encoding="utf-8")
            green = subprocess.run(
                [sys.executable, str(SCRIPT)], cwd=root, check=False, capture_output=True, text=True
            )
            self.assertEqual(0, green.returncode, green.stderr)
            self.assertIn("pnpm supply-chain policy holds", green.stdout)


if __name__ == "__main__":
    unittest.main()
