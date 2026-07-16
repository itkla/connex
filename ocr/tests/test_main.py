import unittest
from unittest.mock import Mock, patch

from ocr_service.__main__ import main
from ocr_service.startup import (
    exception_type_chain,
    report_inference_failure,
    report_startup_failure,
    startup_reason,
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
        self.assertEqual("avx_unavailable", startup_reason(StartupFailure("avx_unavailable"), "engine"))
        self.assertEqual(
            "runtime_dependency_unavailable",
            startup_reason(ModuleNotFoundError("private module name"), "engine"),
        )
        self.assertEqual(
            "invalid_configuration",
            startup_reason(ValueError("private setting"), "configuration"),
        )
        self.assertEqual(
            "engine_initialization_failed",
            startup_reason(RuntimeError("private native failure"), "engine"),
        )

    def test_initialization_diagnostic_excludes_exception_messages(self) -> None:
        with patch("ocr_service.startup.print") as rendered:
            report_startup_failure("worker", "engine", RuntimeError("private native failure"))

        message = rendered.call_args.args[0]
        self.assertIn("component=worker", message)
        self.assertIn("reason=engine_initialization_failed", message)
        self.assertIn("exception_types=builtins.RuntimeError", message)
        self.assertNotIn("private", message)

    def test_inference_diagnostic_excludes_exception_messages(self) -> None:
        with patch("ocr_service.startup.print") as rendered:
            report_inference_failure(RuntimeError("private recognized card text"))

        message = rendered.call_args.args[0]
        self.assertEqual(
            "OCR inference failed: exception_types=builtins.RuntimeError",
            message,
        )
        self.assertNotIn("private", message)

    def test_server_bind_failure_uses_safe_startup_diagnostic(self) -> None:
        with (
            patch("ocr_service.__main__.ServiceConfig.from_environment", return_value=Mock()),
            patch("ocr_service.__main__.PaddleEngine", return_value=Mock()),
            patch("ocr_service.__main__.create_server", side_effect=OSError("private bind detail")),
            patch("ocr_service.startup.print") as rendered,
        ):
            self.assertEqual(1, main())

        message = rendered.call_args.args[0]
        self.assertIn("component=worker", message)
        self.assertIn("reason=server_initialization_failed", message)
        self.assertIn("exception_types=builtins.OSError", message)
        self.assertNotIn("private", message)


if __name__ == "__main__":
    unittest.main()
