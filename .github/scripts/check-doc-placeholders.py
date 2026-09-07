#!/usr/bin/env python3
"""Refuse unfilled `<UPPER-CASE>` placeholders in the SAST compliance documents.

The incident record in `docs/STATIC_ANALYSIS.md` and the third-generation section of
`docs/SAST_TRIAGE_LOG.md` are written before the numbers they cite exist (issue, alert twin,
snapshot date, replay output, canary pull request) and completed by the operator once they do. An
auditor reads the document, not the code, so a literal `<TRACKING-ISSUE>` reaching `main` is a
compliance record that points at nothing. This check runs in the `action-pins` job and fails the
pull request until every placeholder is filled (#1244).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


GUARDED_DOCUMENTS = (
    Path("docs/STATIC_ANALYSIS.md"),
    Path("docs/SAST_TRIAGE_LOG.md"),
)
PLACEHOLDER = re.compile(r"<[A-Z][A-Z0-9-]*>")


def unfilled_placeholders(root: Path, documents: tuple[Path, ...] = GUARDED_DOCUMENTS) -> list[str]:
    found: list[str] = []
    for document in documents:
        path = root / document
        if not path.is_file():
            found.append(f"{document}: guarded document is missing")
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for match in PLACEHOLDER.finditer(line):
                found.append(f"{document}:{line_number}: {match.group(0)}")
    return found


def main() -> int:
    found = unfilled_placeholders(Path.cwd())
    if not found:
        print(f"No unfilled placeholders in {', '.join(str(document) for document in GUARDED_DOCUMENTS)}")
        return 0
    for entry in found:
        print(f"::error::unfilled placeholder {entry}", file=sys.stderr)
    print(f"{len(found)} unfilled placeholder(s); fill every value before merging", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
