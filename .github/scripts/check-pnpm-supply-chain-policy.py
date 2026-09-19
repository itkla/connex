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
when a setting that relaxes the policy appears, or when an exclusion is not a single exact version
with the required comment directly above it.

The files are read line by line instead of through a YAML parser because the comments are the
evidence being checked. Any top-level construct the reader does not recognise fails closed.
"""

from __future__ import annotations

import re
import sys
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
RELAXING_SETTINGS = ("trustLockfile", "trustPolicyIgnoreAfter")
RELEASE_AGE_EXCLUSIONS = "minimumReleaseAgeExclude"
TRUST_EXCLUSIONS = "trustPolicyExclude"
EXCLUSION_LISTS = (RELEASE_AGE_EXCLUSIONS, TRUST_EXCLUSIONS)

TOP_LEVEL_KEY = re.compile(r"^([A-Za-z][A-Za-z0-9]*):(?:\s+(.*))?$")
SEQUENCE_ENTRY = re.compile(r"^\s+-\s+(\S.*)$")
TRAILING_COMMENT = re.compile(r"\s+#.*$")
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


def setting_value(raw: str | None) -> str:
    return TRAILING_COMMENT.sub("", raw or "").strip()


def unquoted(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value


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


def workspace_violations(workspace: Path, text: str) -> list[str]:
    found: list[str] = []
    settings: dict[str, list[tuple[int, str]]] = {}
    exclusions: list[Exclusion] = []
    open_list: str | None = None
    annotation: list[str] = []

    for line_number, raw_line in enumerate(text.splitlines(), 1):
        line = raw_line.rstrip()
        stripped = line.strip()
        if not stripped:
            annotation = []
            continue
        if stripped.startswith("#"):
            if open_list is not None:
                annotation.append(stripped.lstrip("#").strip())
            continue
        if not line[0].isspace():
            open_list = None
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
            if key in EXCLUSION_LISTS:
                if value:
                    found.append(
                        f"{workspace}:{line_number}: {key} must be a block sequence with one commented "
                        "entry per line"
                    )
                else:
                    open_list = key
            continue
        if open_list is None:
            continue
        entry = SEQUENCE_ENTRY.match(line)
        if entry is None:
            found.append(f"{workspace}:{line_number}: unrecognised line in {open_list}: {stripped!r}")
        else:
            exclusions.append(
                Exclusion(open_list, line_number, unquoted(setting_value(entry.group(1))), " ".join(annotation))
            )
        annotation = []

    for key, expected in REQUIRED_SETTINGS.items():
        occurrences = settings.get(key, [])
        if not occurrences:
            found.append(f"{workspace}: `{key}: {expected}` is missing")
        for line_number, value in occurrences:
            if value != expected:
                found.append(f"{workspace}:{line_number}: {key} is {value!r}; the policy requires {expected!r}")
    for key in RELAXING_SETTINGS:
        for line_number, _ in settings.get(key, []):
            found.append(f"{workspace}:{line_number}: {key} relaxes the policy and is not allowed")
    for key, occurrences in settings.items():
        for line_number, _ in occurrences[1:]:
            found.append(f"{workspace}:{line_number}: {key} is set more than once")
    for exclusion in exclusions:
        found.extend(exclusion_violations(workspace, exclusion))
    return found


def policy_violations(root: Path, workspaces: tuple[Path, ...] = WORKSPACE_FILES) -> list[str]:
    found: list[str] = []
    for workspace in workspaces:
        path = root / workspace
        if not path.is_file():
            found.append(f"{workspace}: pnpm workspace file is missing")
            continue
        found.extend(workspace_violations(workspace, path.read_text(encoding="utf-8")))
    return found


def main() -> int:
    found = policy_violations(Path.cwd())
    if not found:
        print(f"pnpm supply-chain policy holds in {', '.join(str(path) for path in WORKSPACE_FILES)}")
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
