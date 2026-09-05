"""Regression tests for the SAST compliance-document placeholder guard."""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-doc-placeholders.py")
SPEC = importlib.util.spec_from_file_location("check_doc_placeholders", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the placeholder guard")
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


class DocPlaceholderGuardTest(unittest.TestCase):
    def write_documents(self, root: Path, static_analysis: str, triage_log: str) -> None:
        docs = root / "docs"
        docs.mkdir()
        docs.joinpath("STATIC_ANALYSIS.md").write_text(static_analysis, encoding="utf-8")
        docs.joinpath("SAST_TRIAGE_LOG.md").write_text(triage_log, encoding="utf-8")

    def test_every_upper_case_placeholder_is_reported_with_its_location(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_documents(
                root,
                "# Static analysis\n\nblind window (2026-08-26 → <PR-0-MERGE-DATE>)\n| #<CANARY-PR> |\n",
                "Tracking issue #<TRACKING-ISSUE>; twin #<TWIN>\n`<N>` alerts at <YYYY-MM-DD>\n",
            )
            self.assertEqual(
                [
                    "docs/STATIC_ANALYSIS.md:3: <PR-0-MERGE-DATE>",
                    "docs/STATIC_ANALYSIS.md:4: <CANARY-PR>",
                    "docs/SAST_TRIAGE_LOG.md:1: <TRACKING-ISSUE>",
                    "docs/SAST_TRIAGE_LOG.md:1: <TWIN>",
                    "docs/SAST_TRIAGE_LOG.md:2: <N>",
                    "docs/SAST_TRIAGE_LOG.md:2: <YYYY-MM-DD>",
                ],
                GUARD.unfilled_placeholders(root),
            )

    def test_lower_case_notation_and_filled_values_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_documents(
                root,
                "`alerts?pr=<n>` and `refs/pull/<n>/merge`; window (2026-08-26 → 2026-09-06)\n",
                "Tracking issue #1584; `328` alerts; a <br> tag; `Map<String, Object>`\n",
            )
            self.assertEqual([], GUARD.unfilled_placeholders(root))

    def test_a_missing_guarded_document_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            root.joinpath("docs").mkdir()
            root.joinpath("docs", "STATIC_ANALYSIS.md").write_text("clean\n", encoding="utf-8")
            self.assertEqual(
                ["docs/SAST_TRIAGE_LOG.md: guarded document is missing"],
                GUARD.unfilled_placeholders(root),
            )

    def test_cli_exit_codes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_documents(root, "clean\n", "Tracking issue #<TRACKING-ISSUE>\n")
            red = subprocess.run(
                [sys.executable, str(SCRIPT)], cwd=root, check=False, capture_output=True, text=True
            )
            self.assertEqual(1, red.returncode, red.stdout)
            self.assertIn(
                "::error::unfilled placeholder docs/SAST_TRIAGE_LOG.md:1: <TRACKING-ISSUE>", red.stderr
            )

            root.joinpath("docs", "SAST_TRIAGE_LOG.md").write_text("Tracking issue #1584\n", encoding="utf-8")
            green = subprocess.run(
                [sys.executable, str(SCRIPT)], cwd=root, check=False, capture_output=True, text=True
            )
            self.assertEqual(0, green.returncode, green.stderr)
            self.assertIn("No unfilled placeholders", green.stdout)


if __name__ == "__main__":
    unittest.main()
