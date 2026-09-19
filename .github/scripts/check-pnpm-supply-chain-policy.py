#!/usr/bin/env python3
"""Hold every pnpm workspace to the #835 supply-chain resolution policy.

The owner decided on 2026-09-19 that `frontend/`, `frontend/emails/`, and `landing/` refuse versions
younger than one day, refuse versions without a registry publish time, and refuse trust downgrades,
and that a release-age exclusion is admitted only for an exact version the owner approved, recorded
beside the setting with the advisory, the approver, and the removal condition.

pnpm 11 writes uncommented `minimumReleaseAgeExclude` entries itself: its interactive "Add to
minimumReleaseAgeExclude ... and proceed with the install?" prompt and `pnpm audit --fix` both append
exact versions with no annotation, and the frozen-lockfile re-check then accepts them. This check runs
in the `frontend-audit` job and fails the pull request when a policy setting is missing or changed,
when a top-level setting outside the allowlist appears, or when an exclusion is not a single exact
version with the required comment directly above it.

The files are read line by line instead of through a YAML parser because the comments are the
evidence being checked. The reader accepts a narrow subset of YAML: allowlisted plain top-level keys,
one-line values, and block content indented under a key with nothing after its colon. Anything else,
including a plain-scalar continuation line and a control or line-separator character, fails closed.

The workspace file is not pnpm's only configuration source. A `.pnpmfile.cjs` or `.pnpmfile.mjs` beside
it runs its `updateConfig` hook after the file is read and can rewrite every setting, so the check
also fails on a pnpmfile that names any hook or export other than `readPackage`, or that loads another
module where such a hook could hide. That scan is lexical. `--effective` adds a behavioural check: it
asks pnpm itself what it resolves in each workspace (`pnpm config list --json` reports every explicitly
configured setting after the pnpmfile hooks have run) and compares that with the policy and with the
exclusions the reader found.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import unicodedata
from pathlib import Path
from typing import NamedTuple


WORKSPACE_FILES = (
    Path("frontend/pnpm-workspace.yaml"),
    Path("frontend/emails/pnpm-workspace.yaml"),
    Path("landing/pnpm-workspace.yaml"),
)
REQUIRED_SETTINGS = {
    "minimumReleaseAge": "1440",
    "minimumReleaseAgeStrict": "true",
    "minimumReleaseAgeIgnoreMissingTime": "false",
    "trustPolicy": "no-downgrade",
}
RELEASE_AGE_EXCLUSIONS = "minimumReleaseAgeExclude"
TRUST_EXCLUSIONS = "trustPolicyExclude"
EXCLUSION_LISTS = (RELEASE_AGE_EXCLUSIONS, TRUST_EXCLUSIONS)
UNGUARDED_SETTINGS = ("allowBuilds", "overrides")
ALLOWED_SETTINGS = (*UNGUARDED_SETTINGS, *REQUIRED_SETTINGS, *EXCLUSION_LISTS)

EFFECTIVE_SETTINGS: dict[str, object] = {
    "minimumReleaseAge": 1440,
    "minimumReleaseAgeStrict": True,
    "minimumReleaseAgeIgnoreMissingTime": False,
    "trustPolicy": "no-downgrade",
    "registry": "https://registry.npmjs.org/",
}
EFFECTIVELY_UNSET_SETTINGS = (
    "trustLockfile",
    "trustPolicyIgnoreAfter",
    "pnpmfile",
    "globalPnpmfile",
    "configDependencies",
)
PNPM_CONFIG_TIMEOUT_SECONDS = 120

PNPMFILES = (".pnpmfile.cjs", ".pnpmfile.mjs")
PNPMFILE_OTHER_HOOK = re.compile(
    r"\b(?:updateConfig|afterAllResolved|preResolution|importPackage|beforePacking|filterLog"
    r"|finders|resolvers|fetchers)\b"
)
PNPMFILE_MODULE_LOAD = re.compile(r"\b(?:require|import)\b")

LINE_SPLITTING_CATEGORIES = frozenset(("Cc", "Zl", "Zp"))
TOP_LEVEL_KEY = re.compile(r"^([A-Za-z][A-Za-z0-9]*):(?:[ \t]+(.*))?$")
SEQUENCE_ENTRY = re.compile(r"^[ \t]+-[ \t]+(\S.*)$")
TRAILING_COMMENT = re.compile(r"[ \t]+#.*$")
EXACT_VERSION_ENTRY = re.compile(
    r"^(?:@[A-Za-z0-9._~-]+/)?[A-Za-z0-9._~-]+"
    r"@(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)
ADVISORY = re.compile(r"\b(?:GHSA(?:-[0-9a-z]{4}){3}|CVE-[0-9]{4}-[0-9]{4,})\b")
APPROVAL = re.compile(r"\bApproved:\s*[^\s,][^,]*,\s*[0-9]{4}-[0-9]{2}-[0-9]{2}\b")
REMOVAL = re.compile(r"\bremove\b", re.IGNORECASE)


class Exclusion(NamedTuple):
    """One exclusion-list entry with the comment lines directly above it."""

    setting: str
    line_number: int
    value: str
    annotation: str


class WorkspaceReading(NamedTuple):
    """The violations and exclusion entries the line reader found in one workspace file."""

    violations: list[str]
    exclusions: list[Exclusion]


class PnpmConfigurationError(Exception):
    """pnpm could not report the configuration it resolves for a workspace."""


def setting_value(raw: str | None) -> str:
    return TRAILING_COMMENT.sub("", raw or "").strip(" \t")


def unquoted(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value


def line_splitting_character(line: str) -> str | None:
    for character in line:
        if character != "\t" and unicodedata.category(character) in LINE_SPLITTING_CATEGORIES:
            return character
    return None


def exclusion_violations(workspace: Path, exclusion: Exclusion) -> list[str]:
    location = f"{workspace}:{exclusion.line_number}"
    label = f"{exclusion.setting} entry {exclusion.value!r}"
    found: list[str] = []
    if not EXACT_VERSION_ENTRY.match(exclusion.value):
        found.append(
            f"{location}: {label} is not one exact `name@x.y.z` version "
            "(no bare name, wildcard, range, or `||` union)"
        )
    if not exclusion.annotation:
        found.append(f"{location}: {label} has no comment directly above it")
        return found
    if exclusion.setting == RELEASE_AGE_EXCLUSIONS:
        if not ADVISORY.search(exclusion.annotation):
            found.append(f"{location}: {label} comment names no GHSA or CVE advisory")
        if not APPROVAL.search(exclusion.annotation):
            found.append(
                f"{location}: {label} comment records no owner approval (`Approved: <name>, <YYYY-MM-DD>`)"
            )
    if not REMOVAL.search(exclusion.annotation):
        found.append(f"{location}: {label} comment states no removal condition (`Remove ...`)")
    return found


def read_workspace(workspace: Path, text: str) -> WorkspaceReading:
    found: list[str] = []
    settings: dict[str, list[tuple[int, str]]] = {}
    exclusions: list[Exclusion] = []
    open_list: str | None = None
    inline_key: str | None = None
    annotation: list[str] = []

    for line_number, raw_line in enumerate(text.split("\n"), 1):
        character = line_splitting_character(raw_line)
        if character is not None:
            found.append(
                f"{workspace}:{line_number}: control or line-separator character U+{ord(character):04X}; "
                "pnpm and this guard could split the file into different lines"
            )
            continue
        line = raw_line.rstrip(" \t")
        stripped = line.lstrip(" \t")
        if not stripped:
            annotation = []
            continue
        if stripped.startswith("#"):
            if open_list is not None:
                annotation.append(stripped.lstrip("#").strip(" \t"))
            continue
        if line[0] not in " \t":
            open_list = None
            inline_key = None
            annotation = []
            match = TOP_LEVEL_KEY.match(line)
            if match is None:
                found.append(
                    f"{workspace}:{line_number}: unrecognised top-level line {stripped!r}; "
                    "write each setting as plain `key: value`"
                )
                continue
            key, value = match.group(1), setting_value(match.group(2))
            settings.setdefault(key, []).append((line_number, value))
            if key not in ALLOWED_SETTINGS:
                found.append(
                    f"{workspace}:{line_number}: {key} is not a setting this guard allows; pnpm settings "
                    "such as pnpmfile, configDependencies, registry, or trustLockfile can override the "
                    "policy, so admitting one needs a reviewed change to this guard"
                )
            if value:
                inline_key = key
                if key in EXCLUSION_LISTS:
                    found.append(
                        f"{workspace}:{line_number}: {key} must be a block sequence with one commented "
                        "entry per line"
                    )
            elif key in EXCLUSION_LISTS:
                open_list = key
            continue
        if open_list is not None:
            entry = SEQUENCE_ENTRY.match(line)
            if entry is None:
                found.append(f"{workspace}:{line_number}: unrecognised line in {open_list}: {stripped!r}")
            else:
                exclusions.append(
                    Exclusion(open_list, line_number, unquoted(setting_value(entry.group(1))), " ".join(annotation))
                )
            annotation = []
            continue
        if inline_key is not None:
            found.append(
                f"{workspace}:{line_number}: indented line {stripped!r} continues the value of {inline_key}; "
                "pnpm reads it as part of that value"
            )

    for key, expected in REQUIRED_SETTINGS.items():
        occurrences = settings.get(key, [])
        if not occurrences:
            found.append(f"{workspace}: `{key}: {expected}` is missing")
        for line_number, value in occurrences:
            if value != expected:
                found.append(f"{workspace}:{line_number}: {key} is {value!r}; the policy requires {expected!r}")
    for key, occurrences in settings.items():
        for line_number, _ in occurrences[1:]:
            found.append(f"{workspace}:{line_number}: {key} is set more than once")
    for exclusion in exclusions:
        found.extend(exclusion_violations(workspace, exclusion))
    return WorkspaceReading(found, exclusions)


def workspace_violations(workspace: Path, text: str) -> list[str]:
    return read_workspace(workspace, text).violations


def pnpmfile_violations(root: Path, workspace: Path) -> list[str]:
    found: list[str] = []
    for name in PNPMFILES:
        pnpmfile = workspace.parent / name
        path = root / pnpmfile
        if not (path.exists() or path.is_symlink()):
            continue
        if path.is_symlink() or not path.is_file():
            found.append(f"{pnpmfile}: is not a regular file, so this guard cannot read the hooks pnpm loads")
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
            for match in PNPMFILE_OTHER_HOOK.finditer(line):
                found.append(
                    f"{pnpmfile}:{line_number}: names `{match.group(0)}`; a pnpmfile beside a policy workspace "
                    "may define only the `readPackage` hook, because `updateConfig` and the resolution hooks "
                    "can override the policy after the workspace file is read"
                )
            for match in PNPMFILE_MODULE_LOAD.finditer(line):
                found.append(
                    f"{pnpmfile}:{line_number}: loads another module with `{match.group(0)}`; keep the "
                    "pnpmfile self-contained so this guard sees every hook it exports"
                )
    return found


def policy_violations(root: Path, workspaces: tuple[Path, ...] = WORKSPACE_FILES) -> list[str]:
    found: list[str] = []
    for workspace in workspaces:
        path = root / workspace
        if not path.is_file():
            found.append(f"{workspace}: pnpm workspace file is missing")
            continue
        found.extend(workspace_violations(workspace, path.read_text(encoding="utf-8")))
        found.extend(pnpmfile_violations(root, workspace))
    return found


def pnpm_configuration(directory: Path, pnpm: str) -> dict[str, object]:
    command = f"{pnpm} config list --json"
    try:
        completed = subprocess.run(
            [pnpm, "config", "list", "--json"],
            cwd=directory,
            check=False,
            capture_output=True,
            text=True,
            timeout=PNPM_CONFIG_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise PnpmConfigurationError(f"`{command}` could not run: {error}") from error
    if completed.returncode != 0:
        raise PnpmConfigurationError(
            f"`{command}` exited {completed.returncode}: {completed.stderr.strip() or completed.stdout.strip()}"
        )
    try:
        configuration = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise PnpmConfigurationError(f"`{command}` printed no JSON: {error}") from error
    if not isinstance(configuration, dict):
        raise PnpmConfigurationError(f"`{command}` printed {type(configuration).__name__}, not an object")
    return configuration


def same_value(reported: object, expected: object) -> bool:
    return type(reported) is type(expected) and reported == expected


def effective_violations(
    root: Path, workspaces: tuple[Path, ...] = WORKSPACE_FILES, pnpm: str = "pnpm"
) -> list[str]:
    found: list[str] = []
    for workspace in workspaces:
        path = root / workspace
        if not path.is_file():
            found.append(f"{workspace}: pnpm workspace file is missing")
            continue
        reading = read_workspace(workspace, path.read_text(encoding="utf-8"))
        try:
            configuration = pnpm_configuration(path.parent, pnpm)
        except PnpmConfigurationError as error:
            found.append(f"{workspace}: {error}")
            continue
        for key, expected in EFFECTIVE_SETTINGS.items():
            reported = configuration.get(key)
            if not same_value(reported, expected):
                found.append(
                    f"{workspace}: pnpm resolves {key} to {json.dumps(reported)}; "
                    f"the policy requires {json.dumps(expected)}"
                )
        for key in EFFECTIVELY_UNSET_SETTINGS:
            if key in configuration:
                found.append(
                    f"{workspace}: pnpm resolves {key} to {json.dumps(configuration[key])}; "
                    "the policy requires it unset"
                )
        for key in EXCLUSION_LISTS:
            declared = [exclusion.value for exclusion in reading.exclusions if exclusion.setting == key]
            reported = configuration.get(key)
            if reported in (None, []) and not declared:
                continue
            if reported != declared:
                found.append(
                    f"{workspace}: pnpm resolves {key} to {json.dumps(reported)}, but the annotated "
                    f"entries in the file are {json.dumps(declared)}"
                )
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Hold every pnpm workspace to the #835 supply-chain policy")
    parser.add_argument(
        "--effective",
        action="store_true",
        help="compare the configuration pnpm resolves in each workspace with the policy",
    )
    arguments = parser.parse_args(argv)
    root = Path.cwd()
    found = effective_violations(root) if arguments.effective else policy_violations(root)
    if not found:
        verdict = "pnpm resolves the supply-chain policy" if arguments.effective else "pnpm supply-chain policy holds"
        print(f"{verdict} in {', '.join(str(path) for path in WORKSPACE_FILES)}")
        return 0
    for violation in found:
        print(f"::error::{violation}", file=sys.stderr)
    print(
        f"{len(found)} pnpm supply-chain policy violation(s); follow "
        "'Package manager supply-chain policy' in frontend/AGENTS.md",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
