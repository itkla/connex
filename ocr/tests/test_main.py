import unittest

from ocr_service.__main__ import exception_type_chain


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


if __name__ == "__main__":
    unittest.main()
