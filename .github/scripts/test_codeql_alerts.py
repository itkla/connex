import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-codeql-alerts.py")
BACKEND_CATEGORY = "/language:java-kotlin"
FRONTEND_CATEGORY = "/language:javascript-typescript"
MERGE_REF = "refs/pull/9/merge"
MAIN_REF = "refs/heads/main"
MERGE_SHA = "1111111111111111111111111111111111111111"
MAIN_SHA = "2222222222222222222222222222222222222222"
SPEC = importlib.util.spec_from_file_location("check_codeql_alerts", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the CodeQL alert checker")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


def alert(
    number: int,
    severity: str,
    security_severity: str | None,
    state: str = "open",
    category: str = FRONTEND_CATEGORY,
    ref: str = MERGE_REF,
    instance_state: str = "open",
    path: str = "frontend/app/page.tsx",
) -> dict[str, object]:
    return {
        "number": number,
        "state": state,
        "html_url": f"https://github.com/itkla/connex/security/code-scanning/{number}",
        "rule": {
            "id": f"rule/{number}",
            "severity": severity,
            "security_severity_level": security_severity,
        },
        "most_recent_instance": {
            "category": category,
            "ref": ref,
            "state": instance_state,
            "location": {"path": path, "start_line": 1, "start_column": 1},
        },
    }


def analysis(category: str, commit_sha: str, results_count: int) -> dict[str, object]:
    return {"category": category, "commit_sha": commit_sha, "results_count": results_count}


class CodeqlAlertCheckerTest(unittest.TestCase):
    def write_pages(self, pages: object) -> Path:
        temporary = tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False)
        with temporary:
            json.dump(pages, temporary)
        path = Path(temporary.name)
        self.addCleanup(path.unlink, missing_ok=True)
        return path

    def run_checker(
        self, path: Path, *arguments: str, analyses: Path | None = None, commit: str = MERGE_SHA
    ) -> subprocess.CompletedProcess[str]:
        if analyses is None:
            analyses = self.write_pages([analysis(FRONTEND_CATEGORY, commit, 8)])
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                str(path),
                FRONTEND_CATEGORY,
                "--analyses",
                str(analyses),
                "--commit",
                commit,
                *arguments,
            ],
            check=False,
            capture_output=True,
            text=True,
        )

    def blocking(
        self, alerts: list[dict[str, object]], baseline: set[int] | None = None
    ) -> list[tuple[int, str, str, str]]:
        blocking, _ = CHECKER.blocking_alerts(alerts, FRONTEND_CATEGORY, MERGE_REF, baseline)
        return blocking

    def test_empty_paginated_response_passes(self) -> None:
        alerts = CHECKER.load_alerts(self.write_pages([[]]))
        self.assertEqual([], self.blocking(alerts))

    def test_medium_and_low_non_error_alerts_do_not_block(self) -> None:
        alerts = CHECKER.load_alerts(
            self.write_pages(
                [[alert(1, "warning", "medium")], [alert(2, "note", "low")]]
            )
        )
        self.assertEqual([], self.blocking(alerts))

    def test_critical_high_and_generic_error_alerts_block(self) -> None:
        alerts = CHECKER.load_alerts(
            self.write_pages(
                [
                    [
                        alert(1, "warning", "critical"),
                        alert(2, "warning", "high"),
                        alert(3, "error", None),
                    ]
                ]
            )
        )
        self.assertEqual(
            [
                (1, "rule/1", "critical", alerts[0]["html_url"]),
                (2, "rule/2", "high", alerts[1]["html_url"]),
                (3, "rule/3", "error", alerts[2]["html_url"]),
            ],
            self.blocking(alerts),
        )

    def test_alerts_from_another_analysis_category_do_not_block(self) -> None:
        alerts = CHECKER.load_alerts(
            self.write_pages([[alert(4, "error", "critical", category=BACKEND_CATEGORY)]])
        )
        self.assertEqual([], self.blocking(alerts))

    def test_alert_outside_the_pull_request_diff_blocks_when_absent_from_main(self) -> None:
        """A finding whose location is outside the diff still blocks.

        The gate compares the full open set on the analysed ref with the open set on the base
        ref, so it never depends on GitHub's diff attribution; the only way an alert is ignored is
        by being open on the base ref under the same number.
        """
        sink = alert(200, "warning", "high", path="backend/src/main/java/x/Sink.java")
        blocking, pre_existing = CHECKER.blocking_alerts(
            [sink], FRONTEND_CATEGORY, MERGE_REF, set()
        )
        self.assertEqual([(200, "rule/200", "high", sink["html_url"])], blocking)
        self.assertEqual(0, pre_existing)

    def test_alert_open_on_main_does_not_block(self) -> None:
        alerts = [alert(153, "warning", "high"), alert(154, "warning", "high")]
        baseline = CHECKER.baseline_numbers(
            [alert(153, "warning", "high", ref=MAIN_REF)], MAIN_REF
        )
        blocking, pre_existing = CHECKER.blocking_alerts(
            alerts, FRONTEND_CATEGORY, MERGE_REF, baseline
        )
        self.assertEqual([154], [number for number, _, _, _ in blocking])
        self.assertEqual(1, pre_existing)

    def test_without_baseline_every_blocking_alert_blocks(self) -> None:
        alerts = [alert(153, "warning", "high", ref=MAIN_REF)]
        blocking, _ = CHECKER.blocking_alerts(alerts, FRONTEND_CATEGORY, MAIN_REF, None)
        self.assertEqual([153], [number for number, _, _, _ in blocking])

    def test_instance_ref_mismatch_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "analysed ref"):
            self.blocking([alert(5, "warning", "low", ref=MAIN_REF)])

    def test_baseline_ref_mismatch_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "baseline ref"):
            CHECKER.baseline_numbers([alert(5, "warning", "low", ref=MERGE_REF)], MAIN_REF)

    def test_instance_state_not_open_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "not open"):
            self.blocking([alert(5, "warning", "high", instance_state="fixed")])
        with self.assertRaisesRegex(ValueError, "not open"):
            CHECKER.baseline_numbers(
                [alert(5, "warning", "high", ref=MAIN_REF, instance_state="dismissed")], MAIN_REF
            )

    def test_an_empty_analysis_beside_a_populated_base_ref_fails_closed(self) -> None:
        """A processed upload that stored zero results is refused, not passed.

        From 2026-08-26 every pull-request analysis was processed successfully with
        `results_count: 0`, so `wait-for-processing` saw nothing wrong and the empty alert set
        read as a pass. The base ref's newest analysis of the same category is the reference: if
        it stored results and the analysed commit stored none, results were pruned before upload
        or the upload was empty, and an empty alert set proves nothing.
        """
        with self.assertRaisesRegex(CHECKER.BlindAnalysisError, "stored 0 results while"):
            CHECKER.require_live_analysis(
                [analysis(FRONTEND_CATEGORY, MERGE_SHA, 0)],
                FRONTEND_CATEGORY,
                MERGE_SHA,
                MERGE_REF,
                [
                    analysis(BACKEND_CATEGORY, MAIN_SHA, 58),
                    analysis(FRONTEND_CATEGORY, MAIN_SHA, 8),
                ],
                MAIN_REF,
            )

    def test_an_empty_analysis_passes_when_the_base_ref_stored_nothing_either(self) -> None:
        both_empty = CHECKER.require_live_analysis(
            [analysis(FRONTEND_CATEGORY, MERGE_SHA, 0)],
            FRONTEND_CATEGORY,
            MERGE_SHA,
            MERGE_REF,
            [analysis(FRONTEND_CATEGORY, MAIN_SHA, 0)],
            MAIN_REF,
        )
        self.assertIn("stored 0 result(s) against 0 on refs/heads/main", both_empty)
        no_reference = CHECKER.require_live_analysis(
            [analysis(FRONTEND_CATEGORY, MERGE_SHA, 0)],
            FRONTEND_CATEGORY,
            MERGE_SHA,
            MERGE_REF,
            [analysis(BACKEND_CATEGORY, MAIN_SHA, 58)],
            MAIN_REF,
        )
        self.assertIn("has no /language:javascript-typescript analysis to compare", no_reference)
        without_baseline = CHECKER.require_live_analysis(
            [analysis(FRONTEND_CATEGORY, MAIN_SHA, 0)], FRONTEND_CATEGORY, MAIN_SHA, MAIN_REF, None, None
        )
        self.assertEqual(
            "/language:javascript-typescript analysis of 222222222 on refs/heads/main stored 0 result(s)",
            without_baseline,
        )

    def test_the_analysed_commit_must_have_an_analysis_in_the_category(self) -> None:
        with self.assertRaisesRegex(CHECKER.BlindAnalysisError, "no /language:javascript-typescript analysis of 111111111"):
            CHECKER.require_live_analysis(
                [analysis(FRONTEND_CATEGORY, MAIN_SHA, 8), analysis(BACKEND_CATEGORY, MERGE_SHA, 58)],
                FRONTEND_CATEGORY,
                MERGE_SHA,
                MERGE_REF,
                None,
                None,
            )
        with self.assertRaisesRegex(ValueError, "invalid results count"):
            CHECKER.require_live_analysis(
                [{"category": FRONTEND_CATEGORY, "commit_sha": MERGE_SHA, "results_count": "8"}],
                FRONTEND_CATEGORY,
                MERGE_SHA,
                MERGE_REF,
                None,
                None,
            )

    def test_the_newest_analysis_of_the_category_is_the_reference(self) -> None:
        reference = CHECKER.newest_analysis(
            [
                analysis(BACKEND_CATEGORY, MAIN_SHA, 58),
                analysis(FRONTEND_CATEGORY, MAIN_SHA, 8),
                analysis(FRONTEND_CATEGORY, "3" * 40, 9),
            ],
            FRONTEND_CATEGORY,
            None,
        )
        self.assertEqual((MAIN_SHA, 8), reference)

    def test_malformed_or_non_open_results_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "page 1"):
            CHECKER.load_alerts(self.write_pages([{"not": "a page"}]))
        with self.assertRaisesRegex(ValueError, "was not open"):
            self.blocking([alert(5, "error", "high", state="dismissed")])
        with self.assertRaisesRegex(ValueError, "unknown security severity"):
            self.blocking([alert(6, "warning", "future")])
        with self.assertRaisesRegex(ValueError, "analysis category"):
            invalid_category = alert(7, "warning", "low")
            invalid_category["most_recent_instance"]["category"] = ""
            self.blocking([invalid_category])
        with self.assertRaisesRegex(ValueError, "instance ref"):
            invalid_ref = alert(8, "warning", "low")
            invalid_ref["most_recent_instance"]["ref"] = ""
            self.blocking([invalid_ref])

    def test_cli_exit_codes_are_fail_closed(self) -> None:
        empty = self.write_pages([[]])
        new_high = self.write_pages([[alert(8, "warning", "critical")]])
        main_high = self.write_pages([[alert(8, "warning", "critical", ref=MAIN_REF)]])
        main_other = self.write_pages([[alert(9, "warning", "critical", ref=MAIN_REF)]])
        main_analyses = self.write_pages(
            [analysis(BACKEND_CATEGORY, MAIN_SHA, 58), analysis(FRONTEND_CATEGORY, MAIN_SHA, 8)]
        )
        blind_analyses = self.write_pages([analysis(FRONTEND_CATEGORY, MERGE_SHA, 0)])
        baseline = ("--baseline-analyses", str(main_analyses))

        passing = self.run_checker(empty, "--ref", MERGE_REF)
        blocking = self.run_checker(new_high, "--ref", MERGE_REF)
        blocking_with_baseline = self.run_checker(
            new_high, "--ref", MERGE_REF, "--baseline", str(main_other), "--baseline-ref", MAIN_REF, *baseline
        )
        pre_existing = self.run_checker(
            new_high, "--ref", MERGE_REF, "--baseline", str(main_high), "--baseline-ref", MAIN_REF, *baseline
        )
        main_gate = self.run_checker(main_high, "--ref", MAIN_REF, commit=MAIN_SHA)
        malformed = self.run_checker(self.write_pages({"not": "paginated"}), "--ref", MERGE_REF)
        wrong_ref = self.run_checker(new_high, "--ref", MAIN_REF)
        missing_ref = self.run_checker(empty)
        missing_baseline_ref = self.run_checker(
            new_high, "--ref", MERGE_REF, "--baseline", str(main_high), *baseline
        )
        missing_baseline_analyses = self.run_checker(
            new_high, "--ref", MERGE_REF, "--baseline", str(main_high), "--baseline-ref", MAIN_REF
        )
        blind = self.run_checker(
            empty, "--ref", MERGE_REF, "--baseline", str(empty), "--baseline-ref", MAIN_REF, *baseline,
            analyses=blind_analyses,
        )
        unanalysed = self.run_checker(empty, "--ref", MERGE_REF, analyses=self.write_pages([]))
        missing_analyses = subprocess.run(
            [sys.executable, str(SCRIPT), str(empty), FRONTEND_CATEGORY, "--ref", MERGE_REF],
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, passing.returncode, passing.stderr)
        self.assertIn("stored 8 result(s)", passing.stdout)
        self.assertEqual(1, blocking.returncode, blocking.stderr)
        self.assertEqual(1, blocking_with_baseline.returncode, blocking_with_baseline.stderr)
        self.assertEqual(0, pre_existing.returncode, pre_existing.stderr)
        self.assertIn("1 pre-existing alert(s) on refs/heads/main ignored", pre_existing.stdout)
        self.assertIn("against 8 on refs/heads/main", pre_existing.stdout)
        self.assertEqual(1, main_gate.returncode, main_gate.stderr)
        self.assertEqual(2, malformed.returncode, malformed.stderr)
        self.assertEqual(2, wrong_ref.returncode, wrong_ref.stderr)
        self.assertEqual(2, missing_ref.returncode, missing_ref.stderr)
        self.assertEqual(2, missing_baseline_ref.returncode, missing_baseline_ref.stderr)
        self.assertEqual(2, missing_baseline_analyses.returncode, missing_baseline_analyses.stderr)
        self.assertEqual(2, blind.returncode, blind.stdout)
        self.assertIn("CodeQL analysis cannot be gated", blind.stderr)
        self.assertIn("stored 0 results while the newest on refs/heads/main", blind.stderr)
        self.assertEqual(2, unanalysed.returncode, unanalysed.stdout)
        self.assertIn("no /language:javascript-typescript analysis of 111111111", unanalysed.stderr)
        self.assertEqual(2, missing_analyses.returncode, missing_analyses.stdout)

if __name__ == "__main__":
    unittest.main()
