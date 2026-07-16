import os
import unittest
from unittest.mock import patch

from ocr_service.config import ServiceConfig


class ServiceConfigTest(unittest.TestCase):
    def test_rejects_non_finite_request_timeout(self) -> None:
        for raw in ("nan", "inf", "-inf"):
            with self.subTest(raw=raw), patch.dict(
                os.environ,
                {
                    "CONNEX_OCR_SERVICE_TOKEN": "x" * 32,
                    "CONNEX_OCR_REQUEST_TIMEOUT_SECONDS": raw,
                },
                clear=True,
            ):
                with self.assertRaisesRegex(ValueError, "must be between"):
                    ServiceConfig.from_environment()

    def test_rejects_an_unbounded_handler_configuration(self) -> None:
        with patch.dict(
            os.environ,
            {
                "CONNEX_OCR_SERVICE_TOKEN": "x" * 32,
                "CONNEX_OCR_MAX_REQUEST_HANDLERS": "65",
            },
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "must be between"):
                ServiceConfig.from_environment()


if __name__ == "__main__":
    unittest.main()
