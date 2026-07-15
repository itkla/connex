import json
import unittest
from collections import Counter
from pathlib import Path

from benchmark.run_benchmark import candidate, summarize


class BenchmarkManifestTest(unittest.TestCase):
    def test_manifest_has_required_coverage_and_synthetic_contacts(self) -> None:
        manifest = json.loads(
            (Path(__file__).parents[1] / "benchmark" / "manifest.json").read_text(encoding="utf-8")
        )
        cases = manifest["cases"]

        self.assertGreaterEqual(len(cases), 40)
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        self.assertGreaterEqual(Counter(case["language"] for case in cases), Counter({"en": 12, "ja": 12, "mixed": 12}))
        self.assertEqual({"clean", "glare", "rotation", "perspective", "low_light"}, {case["condition"] for case in cases})
        self.assertGreaterEqual(len({case["layout"] for case in cases}), 4)
        for case in cases:
            fields = case["fields"]
            self.assertTrue(fields["name"])
            self.assertTrue(fields["email"].endswith("@example.test"))
            self.assertRegex(fields["phone"], r"^\+\d{11}$")

    def test_scoring_includes_title_and_company_candidates(self) -> None:
        payload = {
            "fields": {
                "title": {"value": "Principal Engineer"},
            },
            "company": {"value": "Analytical Labs"},
        }
        outcome = {
            "latencySeconds": 1.0,
            "correct": {
                "name": True,
                "email": True,
                "phone": True,
                "title": True,
                "company": True,
            },
        }

        self.assertEqual("Principal Engineer", candidate(payload, "title"))
        self.assertEqual("Analytical Labs", candidate(payload, "company"))
        report = summarize([outcome])
        self.assertEqual(1.0, report["accuracy"]["title"])
        self.assertEqual(1.0, report["accuracy"]["company"])
        self.assertTrue(report["gates"]["title"]["passed"])
        self.assertTrue(report["gates"]["company"]["passed"])


if __name__ == "__main__":
    unittest.main()
