import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from clamav_service.config import ServiceConfig
from clamav_service.startup import StartupFailure
from clamav_service.supervisor import verify_signatures

from tests.test_signatures import write_container


VALID_TOKEN = "0123456789abcdef0123456789abcdef"


def service_config(database_directory: Path, signature_source: str) -> ServiceConfig:
    environment = {
        "CONNEX_CLAMAV_SERVICE_TOKEN": VALID_TOKEN,
        "CONNEX_CLAMAV_SIGNATURE_SOURCE": signature_source,
        "CONNEX_CLAMAV_DATABASE_DIRECTORY": str(database_directory),
    }
    with patch.dict(os.environ, environment, clear=True):
        return ServiceConfig.from_environment()


def fresh_database(directory: Path) -> None:
    now = int(time.time())
    write_container(directory, "daily.cvd", now - 60)
    write_container(directory, "main.cvd", now - 400 * 86_400)


class VerifySignaturesTest(unittest.TestCase):
    """Pins the air-gapped escape hatch that makes the 30-day hard block defensible.

    Uploads block permanently once the baked set expires, with no override, so an operator must be
    able to transfer a newer database in. That path only exists when the deployment actually mounts
    one at the sidecar's database path; declaring the source without the mount used to keep serving
    the expired baked contents while the transferred files sat unused on the host.
    """

    def test_the_volume_source_refuses_an_unmounted_database_directory(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            fresh_database(directory)
            config = service_config(directory, "volume")

            with self.assertRaises(StartupFailure) as raised:
                verify_signatures(config)

            self.assertEqual("signature_volume_not_mounted", raised.exception.reason)

    def test_the_volume_source_accepts_a_mounted_database_directory(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            fresh_database(directory)
            config = service_config(directory, "volume")

            with patch("clamav_service.supervisor.os.path.ismount", return_value=True):
                verify_signatures(config)

    def test_the_baked_source_does_not_require_a_mount(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            fresh_database(directory)

            verify_signatures(service_config(directory, "baked"))

    def test_an_expired_database_still_fails_before_the_mount_is_considered(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            now = int(time.time())
            write_container(directory, "daily.cvd", now - 40 * 86_400)
            write_container(directory, "main.cvd", now - 400 * 86_400)
            config = service_config(directory, "baked")

            with self.assertRaises(StartupFailure) as raised:
                verify_signatures(config)

            self.assertEqual("signature_database_expired", raised.exception.reason)


if __name__ == "__main__":
    unittest.main()
