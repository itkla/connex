import argparse
import json
from pathlib import Path

from benchmark.run_benchmark import canonical_manifest, verify_qualification_report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("source_revision")
    arguments = parser.parse_args()
    manifest_path = Path(__file__).with_name("manifest.json")
    manifest = canonical_manifest(manifest_path)
    report = json.loads(arguments.report.read_text(encoding="utf-8"))
    verify_qualification_report(report, manifest, arguments.source_revision)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
