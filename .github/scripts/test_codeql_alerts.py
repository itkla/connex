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
        "most_recent_instance": {"category": category},
    }


class CodeqlAlertCheckerTest(unittest.TestCase):
    def write_pages(self, pages: object) -> Path:
        temporary = tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False)
        with temporary:
            json.dump(pages, temporary)
        path = Path(temporary.name)
        self.addCleanup(path.unlink, missing_ok=True)
        return path

    def run_checker(self, path: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(path), FRONTEND_CATEGORY],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_empty_paginated_response_passes(self) -> None:
        alerts = CHECKER.load_alerts(self.write_pages([[]]))
        self.assertEqual([], CHECKER.blocking_alerts(alerts, FRONTEND_CATEGORY))

    def test_medium_and_low_non_error_alerts_do_not_block(self) -> None:
        alerts = CHECKER.load_alerts(
            self.write_pages(
                [[alert(1, "warning", "medium")], [alert(2, "note", "low")]]
            )
        )
        self.assertEqual([], CHECKER.blocking_alerts(alerts, FRONTEND_CATEGORY))

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
            CHECKER.blocking_alerts(alerts, FRONTEND_CATEGORY),
        )

    def test_alerts_from_another_analysis_category_do_not_block(self) -> None:
        alerts = CHECKER.load_alerts(
            self.write_pages([[alert(4, "error", "critical", category=BACKEND_CATEGORY)]])
        )
        self.assertEqual([], CHECKER.blocking_alerts(alerts, FRONTEND_CATEGORY))

    def test_malformed_or_non_open_results_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "page 1"):
            CHECKER.load_alerts(self.write_pages([{"not": "a page"}]))
        with self.assertRaisesRegex(ValueError, "was not open"):
            CHECKER.blocking_alerts(
                [alert(5, "error", "high", state="dismissed")], FRONTEND_CATEGORY
            )
        with self.assertRaisesRegex(ValueError, "unknown security severity"):
            CHECKER.blocking_alerts(
                [alert(6, "warning", "future")], FRONTEND_CATEGORY
            )
        with self.assertRaisesRegex(ValueError, "analysis category"):
            invalid_category = alert(7, "warning", "low")
            invalid_category["most_recent_instance"] = {"category": ""}
            CHECKER.blocking_alerts([invalid_category], FRONTEND_CATEGORY)

    def test_cli_exit_codes_are_fail_closed(self) -> None:
        passing = self.run_checker(self.write_pages([[]]))
        blocking = self.run_checker(
            self.write_pages([[alert(8, "warning", "critical")]])
        )
        malformed = self.run_checker(self.write_pages({"not": "paginated"}))

        self.assertEqual(0, passing.returncode, passing.stderr)
        self.assertEqual(1, blocking.returncode, blocking.stderr)
        self.assertEqual(2, malformed.returncode, malformed.stderr)


if __name__ == "__main__":
    unittest.main()
