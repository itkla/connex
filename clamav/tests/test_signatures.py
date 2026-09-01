import tempfile
import unittest
from pathlib import Path

from clamav_service import signatures


def write_container(directory: Path, name: str, build_epoch: int, version: str = "27000") -> None:
    header = (
        "ClamAV-VDB:31 Aug 2026 09-00 -0000:"
        f"{version}:2000000:90:"
        "0123456789abcdef0123456789abcdef:"
        "dsigplaceholder:builder:"
        f"{build_epoch}"
    )
    (directory / name).write_bytes(header.encode("ascii").ljust(512, b" "))


class InspectTest(unittest.TestCase):
    def test_reports_the_oldest_age_sensitive_container(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            write_container(directory, "daily.cvd", 1_000_000)
            write_container(directory, "main.cvd", 900_000)
            state = signatures.inspect(directory, None, now=1_000_500)
            self.assertEqual(state.age_seconds, 100_500)

    def test_a_missing_container_is_reported_as_unknown_not_fresh(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            write_container(directory, "daily.cvd", 1_000_000)
            state = signatures.inspect(directory, None, now=1_000_100)
            self.assertIsNone(state.age_seconds)
            self.assertTrue(state.expired(2_592_000))
            self.assertTrue(state.stale(604_800))
            self.assertEqual(state.seconds_until_expiry(2_592_000), 0)

    def test_an_unreadable_header_is_reported_as_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "daily.cvd").write_bytes(b"not a signature container")
            write_container(directory, "main.cvd", 1_000_000)
            self.assertIsNone(signatures.inspect(directory, None).age_seconds)

    def test_a_cld_container_is_accepted_alongside_a_cvd(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            write_container(directory, "daily.cld", 1_000_400)
            write_container(directory, "main.cvd", 1_000_000)
            state = signatures.inspect(directory, None, now=1_000_600)
            self.assertEqual(state.age_seconds, 600)

    def test_the_newest_container_wins_within_one_stem(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            write_container(directory, "daily.cvd", 1_000_000)
            write_container(directory, "daily.cld", 1_000_500)
            write_container(directory, "main.cvd", 1_000_500)
            state = signatures.inspect(directory, None, now=1_000_600)
            self.assertEqual(state.age_seconds, 100)


class FreshnessThresholdTest(unittest.TestCase):
    def test_grading_between_warn_and_block(self) -> None:
        state = signatures.SignatureState(age_seconds=1_000_000, database_version="27000", daemon_version=None)
        self.assertTrue(state.stale(604_800))
        self.assertFalse(state.expired(2_592_000))
        self.assertEqual(state.seconds_until_expiry(2_592_000), 1_592_000)

    def test_past_the_ceiling_is_expired(self) -> None:
        state = signatures.SignatureState(age_seconds=2_592_001, database_version=None, daemon_version=None)
        self.assertTrue(state.expired(2_592_000))
        self.assertEqual(state.seconds_until_expiry(2_592_000), 0)


class DaemonVersionTest(unittest.TestCase):
    def test_extracts_the_database_version(self) -> None:
        self.assertEqual(
            signatures.parse_daemon_version("ClamAV 1.0.7/27890/Mon Aug 31 09:12:34 2026"),
            "27890",
        )

    def test_rejects_unparseable_versions(self) -> None:
        for raw in (None, "", "ClamAV 1.0.7", "ClamAV 1.0.7/notanumber/date"):
            with self.subTest(raw=raw):
                self.assertIsNone(signatures.parse_daemon_version(raw))


if __name__ == "__main__":
    unittest.main()
