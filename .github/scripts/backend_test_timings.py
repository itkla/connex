#!/usr/bin/env python3
"""Write the recorded per-class durations that fill the backend database CI shards.

Usage: backend_test_timings.py JUNIT_DIR [JUNIT_DIR ...] > backend/gradle/db-test-timings.tsv

Each JUNIT_DIR is a downloaded `backend-test-reports-db-N` artifact (or any directory holding
Gradle JUnit XML). Nested classes are folded into their outer class, because the partition assigns
outer classes. The output is sorted by class name so a refresh diff shows only changed durations.
"""
import re
import sys
import xml.etree.ElementTree as ET
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

HEADER = (
    "# Recorded seconds per DB-backed backend test class; build.gradle fills the dbTestShard tasks\n"
    "# with them (longest first). Classes missing here are placed by a stable name hash.\n"
    "# Regenerate from a green main run: gh run download <run-id> -n backend-test-reports-db-1 ...\n"
    "#   python3 .github/scripts/backend_test_timings.py <artifact dirs> > backend/gradle/db-test-timings.tsv\n"
)
CLASS_NAME = re.compile(r"[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+")


def collect(directories: list[Path]) -> dict[str, Decimal]:
    seconds: dict[str, Decimal] = {}
    reports = sorted(report for directory in directories for report in directory.rglob("TEST-*.xml"))
    if not reports:
        raise SystemExit("no JUnit XML reports found under the given directories")
    for report in reports:
        suite = ET.parse(report).getroot()
        name = (suite.get("name") or "").split("$", 1)[0]
        if not CLASS_NAME.fullmatch(name):
            raise SystemExit(f"{report}: unexpected test suite name {suite.get('name')!r}")
        seconds[name] = seconds.get(name, Decimal(0)) + Decimal(suite.get("time") or "0")
    return seconds


def render(seconds: dict[str, Decimal]) -> str:
    lines = [
        f"{name}\t{value.quantize(Decimal('0.1'), rounding=ROUND_HALF_UP)}"
        for name, value in sorted(seconds.items())
    ]
    return HEADER + "\n".join(lines) + "\n"


def main(arguments: list[str]) -> int:
    if not arguments:
        raise SystemExit(__doc__)
    sys.stdout.write(render(collect([Path(argument) for argument in arguments])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
