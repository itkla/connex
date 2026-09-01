import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from clamav_service.config import (
    MAX_SCAN_SIZE_BYTES,
    STREAM_MAX_LENGTH_BYTES,
    ServiceConfig,
    required_scratch_bytes,
    verify_scratch_capacity,
)
from clamav_service.startup import StartupFailure


VALID_TOKEN = "0123456789abcdef0123456789abcdef"


def environment(**overrides: str) -> dict[str, str]:
    base = {"CONNEX_CLAMAV_SERVICE_TOKEN": VALID_TOKEN}
    base.update(overrides)
    return base


class TokenTest(unittest.TestCase):
    def test_a_valid_token_is_accepted(self) -> None:
        with patch.dict(os.environ, environment(), clear=True):
            self.assertEqual(ServiceConfig.from_environment().service_token, VALID_TOKEN)

    def test_a_short_or_injected_token_fails_closed(self) -> None:
        for token in ("", "short", "a" * 31, VALID_TOKEN + "\n", VALID_TOKEN + "\r"):
            with self.subTest(token=token):
                with patch.dict(os.environ, {"CONNEX_CLAMAV_SERVICE_TOKEN": token}, clear=True):
                    with self.assertRaises(StartupFailure) as raised:
                        ServiceConfig.from_environment()
                    self.assertEqual(raised.exception.reason, "invalid_configuration")


class BoundsTest(unittest.TestCase):
    def test_the_scan_ceiling_cannot_exceed_the_daemon_stream_limit(self) -> None:
        with patch.dict(
            os.environ,
            environment(CONNEX_CLAMAV_MAX_SCAN_BYTES=str(STREAM_MAX_LENGTH_BYTES + 1)),
            clear=True,
        ):
            with self.assertRaises(StartupFailure):
                ServiceConfig.from_environment()

    def test_the_default_scan_ceiling_matches_the_backend_upload_ceiling(self) -> None:
        with patch.dict(os.environ, environment(), clear=True):
            self.assertEqual(ServiceConfig.from_environment().max_scan_bytes, 26_214_400)

    def test_a_warn_age_above_the_block_age_is_refused(self) -> None:
        with patch.dict(
            os.environ,
            environment(
                CONNEX_CLAMAV_SIGNATURE_WARN_AGE_SECONDS="2000000",
                CONNEX_CLAMAV_SIGNATURE_MAX_AGE_SECONDS="1000000",
            ),
            clear=True,
        ):
            with self.assertRaises(StartupFailure):
                ServiceConfig.from_environment()

    def test_the_block_age_cannot_be_raised_past_thirty_days(self) -> None:
        with patch.dict(
            os.environ,
            environment(CONNEX_CLAMAV_SIGNATURE_MAX_AGE_SECONDS="2592001"),
            clear=True,
        ):
            with self.assertRaises(StartupFailure):
                ServiceConfig.from_environment()

    def test_an_unknown_signature_source_is_refused(self) -> None:
        with patch.dict(os.environ, environment(CONNEX_CLAMAV_SIGNATURE_SOURCE="freshclam"), clear=True):
            with self.assertRaises(StartupFailure):
                ServiceConfig.from_environment()

    def test_a_non_numeric_bound_is_refused(self) -> None:
        with patch.dict(os.environ, environment(CONNEX_CLAMAV_MAX_CONCURRENT_SCANS="lots"), clear=True):
            with self.assertRaises(StartupFailure):
                ServiceConfig.from_environment()


class ScratchCapacityTest(unittest.TestCase):
    def test_the_requirement_is_derived_from_the_daemon_limits(self) -> None:
        self.assertEqual(
            required_scratch_bytes(2),
            2 * (STREAM_MAX_LENGTH_BYTES + MAX_SCAN_SIZE_BYTES),
        )

    def test_an_undersized_mount_fails_startup(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            with patch.dict(
                os.environ,
                environment(CONNEX_CLAMAV_SCRATCH_DIRECTORY=str(Path(raw) / "scan")),
                clear=True,
            ):
                config = ServiceConfig.from_environment()
            statistics = os.statvfs(raw)
            undersized = statistics.f_frsize * statistics.f_blocks < required_scratch_bytes(
                config.max_concurrent_scans
            )
            if undersized:
                with self.assertRaises(StartupFailure) as raised:
                    verify_scratch_capacity(config)
                self.assertEqual(raised.exception.reason, "scan_scratch_undersized")
            else:
                verify_scratch_capacity(config)

    def test_an_unwritable_mount_fails_startup(self) -> None:
        with patch.dict(
            os.environ,
            environment(CONNEX_CLAMAV_SCRATCH_DIRECTORY="/proc/connex-not-writable"),
            clear=True,
        ):
            config = ServiceConfig.from_environment()
        with self.assertRaises(StartupFailure) as raised:
            verify_scratch_capacity(config)
        self.assertEqual(raised.exception.reason, "scan_scratch_unavailable")


if __name__ == "__main__":
    unittest.main()
