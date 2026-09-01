import argparse
import json
from pathlib import Path

from benchmark.run_benchmark import canonical_manifest, verify_qualification_report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("source_revision")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--requests-per-minute", required=True, type=int)
    parser.add_argument("--backend-image-reference", required=True)
    parser.add_argument("--frontend-image-reference", required=True)
    parser.add_argument("--ocr-image-reference", required=True)
    parser.add_argument("--clamav-image-reference", required=True)
    arguments = parser.parse_args()
    manifest_path = Path(__file__).with_name("manifest.json")
    manifest = canonical_manifest(manifest_path)
    report = json.loads(arguments.report.read_text(encoding="utf-8"))
    verify_qualification_report(
        report,
        manifest,
        arguments.source_revision,
        arguments.base_url,
        arguments.requests_per_minute,
        {
            "backend": arguments.backend_image_reference,
            "frontend": arguments.frontend_image_reference,
            "ocr": arguments.ocr_image_reference,
            "clamav": arguments.clamav_image_reference,
        },
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
