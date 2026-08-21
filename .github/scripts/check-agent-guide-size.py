#!/usr/bin/env python3

from __future__ import annotations

import sys
from pathlib import Path

ROOT_LIMIT = 10 * 1024
PACKAGE_LIMIT = 20 * 1024
INHERITED_LIMIT = 30 * 1024

ROOT_GUIDE = Path("AGENTS.md")
PACKAGE_GUIDES = (
    Path("frontend/AGENTS.md"),
    Path("backend/AGENTS.md"),
    Path("ocr/AGENTS.md"),
)


def byte_size(path: Path) -> int:
    if not path.is_file():
        raise FileNotFoundError(f"required agent guide is missing: {path}")
    return len(path.read_bytes())


def format_size(value: int) -> str:
    return f"{value} bytes ({value / 1024:.1f} KiB)"


def main() -> int:
    failures: list[str] = []

    try:
        root_size = byte_size(ROOT_GUIDE)
    except FileNotFoundError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(f"{ROOT_GUIDE}: {format_size(root_size)} / {format_size(ROOT_LIMIT)}")
    if root_size > ROOT_LIMIT:
        failures.append(
            f"{ROOT_GUIDE} exceeds the root guide budget by {root_size - ROOT_LIMIT} bytes"
        )

    for package_guide in PACKAGE_GUIDES:
        try:
            package_size = byte_size(package_guide)
        except FileNotFoundError as error:
            failures.append(str(error))
            continue

        inherited_size = root_size + package_size
        print(
            f"{package_guide}: {format_size(package_size)} / "
            f"{format_size(PACKAGE_LIMIT)}; inherited: "
            f"{format_size(inherited_size)} / {format_size(INHERITED_LIMIT)}"
        )

        if package_size > PACKAGE_LIMIT:
            failures.append(
                f"{package_guide} exceeds the package guide budget by "
                f"{package_size - PACKAGE_LIMIT} bytes"
            )
        if inherited_size > INHERITED_LIMIT:
            failures.append(
                f"{ROOT_GUIDE} + {package_guide} exceed the inherited context budget by "
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

    print("Agent guide context budgets pass.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
