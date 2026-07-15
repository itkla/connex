import json
import unittest
from collections import Counter
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
