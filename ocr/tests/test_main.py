import unittest
from unittest.mock import patch

from ocr_service.__main__ import (
    exception_type_chain,
    initialization_reason,
    report_initialization_failure,
)
from ocr_service.startup import StartupFailure


class MainTest(unittest.TestCase):
    def test_initialization_diagnostic_reports_only_exception_types(self) -> None:
        try:
            try:
                raise OSError("private startup detail")
            except OSError as cause:
                raise RuntimeError("private wrapper detail") from cause
        except RuntimeError as exception:
            rendered = exception_type_chain(exception)

        self.assertEqual("builtins.RuntimeError <- builtins.OSError", rendered)
        self.assertNotIn("private", rendered)

    def test_initialization_reason_uses_safe_specific_codes(self) -> None:
        self.assertEqual("avx_unavailable", initialization_reason(StartupFailure("avx_unavailable")))
        self.assertEqual(
            "runtime_dependency_unavailable",
            initialization_reason(ModuleNotFoundError("private module name")),
        )
        self.assertEqual(
            "invalid_configuration",
            initialization_reason(ValueError("private setting"), configuration=True),
        )
        self.assertEqual(
            "engine_initialization_failed",
            initialization_reason(RuntimeError("private native failure")),
        )

    def test_initialization_diagnostic_excludes_exception_messages(self) -> None:
        with patch("ocr_service.__main__.print") as rendered:
            report_initialization_failure(RuntimeError("private native failure"))

        message = rendered.call_args.args[0]
        self.assertIn("reason=engine_initialization_failed", message)
        self.assertIn("exception_types=builtins.RuntimeError", message)
        self.assertNotIn("private", message)


if __name__ == "__main__":
    unittest.main()
