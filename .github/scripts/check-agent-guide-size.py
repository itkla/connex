#!/usr/bin/env python3

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT_LIMIT = 10 * 1024
PACKAGE_LIMIT = 20 * 1024
INHERITED_LIMIT = 30 * 1024

ROOT_GUIDE = Path("AGENTS.md")


def tracked_agent_guides() -> tuple[Path, ...]:
    result = subprocess.run(
        ["git", "ls-files", "-z", "--", "AGENTS.md", ":(glob)**/AGENTS.md"],
        check=True,
        capture_output=True,
    )
    guides = {
        Path(raw_path)
        for raw_path in result.stdout.decode("utf-8").split("\0")
        if raw_path
    }
    if ROOT_GUIDE not in guides:
        raise FileNotFoundError(f"required root agent guide is missing: {ROOT_GUIDE}")
    return tuple(sorted(guides))


def byte_size(path: Path) -> int:
    if not path.is_file():
        raise FileNotFoundError(f"tracked agent guide is missing from the checkout: {path}")
    return len(path.read_bytes())


def inherited_chain(guide: Path, guides: frozenset[Path]) -> tuple[Path, ...]:
    ancestors: list[Path] = []
    parent = guide.parent
    while parent != Path("."):
        candidate = parent / "AGENTS.md"
        if candidate != guide and candidate in guides:
            ancestors.append(candidate)
        parent = parent.parent
    return (ROOT_GUIDE, *reversed(ancestors), guide)


def format_size(value: int) -> str:
    return f"{value} bytes ({value / 1024:.1f} KiB)"


def main() -> int:
    failures: list[str] = []

    try:
        guide_paths = tracked_agent_guides()
        sizes = {guide: byte_size(guide) for guide in guide_paths}
    except (FileNotFoundError, subprocess.CalledProcessError, UnicodeDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    root_size = sizes[ROOT_GUIDE]
    print(f"{ROOT_GUIDE}: {format_size(root_size)} / {format_size(ROOT_LIMIT)}")
    if root_size > ROOT_LIMIT:
        failures.append(
            f"{ROOT_GUIDE} exceeds the root guide budget by {root_size - ROOT_LIMIT} bytes"
        )

    guide_set = frozenset(guide_paths)
    package_guides = tuple(guide for guide in guide_paths if guide != ROOT_GUIDE)
    if not package_guides:
        failures.append("no tracked package agent guides were discovered")

    for package_guide in package_guides:
        package_size = sizes[package_guide]
        chain = inherited_chain(package_guide, guide_set)
        inherited_size = sum(sizes[path] for path in chain)
        chain_label = " + ".join(str(path) for path in chain)
        print(
            f"{package_guide}: {format_size(package_size)} / "
            f"{format_size(PACKAGE_LIMIT)}; inherited ({chain_label}): "
            f"{format_size(inherited_size)} / {format_size(INHERITED_LIMIT)}"
        )

        if package_size > PACKAGE_LIMIT:
            failures.append(
                f"{package_guide} exceeds the package guide budget by "
                f"{package_size - PACKAGE_LIMIT} bytes"
            )
        if inherited_size > INHERITED_LIMIT:
            failures.append(
                f"{chain_label} exceed the inherited context budget by "
                f"{inherited_size - INHERITED_LIMIT} bytes"
            )

    if failures:
        print("\nAgent guide context budget failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        print(
            "Move subsystem detail into a focused document under docs/ and link it from the guide.",
            file=sys.stderr,
        )
        return 1

    print(f"Agent guide context budgets pass for {len(guide_paths)} tracked guide(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
