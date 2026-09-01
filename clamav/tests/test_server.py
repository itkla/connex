import json
import os
import socket
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from clamav_service.clamd import ClamdUnavailable, ScanResult
from clamav_service.config import ServiceConfig
from clamav_service.server import create_server


VALID_TOKEN = "connex-clamav-test-token-not-a-secret"


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def write_container(directory: Path, name: str, build_epoch: int, version: str = "27000") -> None:
    header = (
        "ClamAV-VDB:31 Aug 2026 09-00 -0000:"
        f"{version}:2000000:90:"
        "0123456789abcdef0123456789abcdef:"
        "dsigplaceholder:builder:"
        f"{build_epoch}"
    )
    (directory / name).write_bytes(header.encode("ascii").ljust(512, b" "))


class FakeClamd:
    def __init__(self, result: ScanResult | None = None, alive: bool = True) -> None:
        self.result = result or ScanResult("clean", None, None)
        self.alive = alive
        self.failure: ClamdUnavailable | None = None
        self.scans = 0
        self.gate: threading.Event | None = None

    def ping(self) -> bool:
        return self.alive

    def version(self) -> str | None:
        return "ClamAV 1.0.7/27890/Mon Aug 31 09:12:34 2026" if self.alive else None

    def scan(self, content: bytes, deadline: float) -> ScanResult:
        self.scans += 1
        if self.gate is not None:
            self.gate.wait(timeout=5)
        if self.failure is not None:
            raise self.failure
        return self.result


class ServerTestCase(unittest.TestCase):
    def build(self, client: FakeClamd, age_seconds: int = 3_600, **overrides: str):
        self.directory = TemporaryDirectory()
        database = Path(self.directory.name)
        import time

        write_container(database, "daily.cvd", int(time.time()) - age_seconds)
        write_container(database, "main.cvd", int(time.time()) - age_seconds)
        environment = {
            "CONNEX_CLAMAV_SERVICE_TOKEN": VALID_TOKEN,
            "CONNEX_CLAMAV_PORT": str(free_port()),
            "CONNEX_CLAMAV_HOST": "127.0.0.1",
            "CONNEX_CLAMAV_DATABASE_DIRECTORY": str(database),
            "CONNEX_CLAMAV_MAX_SCAN_BYTES": "1024",
        }
        environment.update(overrides)
        with patch.dict(os.environ, environment, clear=True):
            config = ServiceConfig.from_environment()
        server = create_server(config, client)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        host, port = server.server_address[0], server.server_address[1]
        return server, f"http://{host}:{port}"

    def call(self, base: str, method: str, path: str, body: bytes | None = None, token: str | None = VALID_TOKEN):
        headers = {}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(base + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            return error.code, error.read()


class AuthorizationTest(ServerTestCase):
    def test_health_is_unauthenticated_but_scanning_is_not(self) -> None:
        _, base = self.build(FakeClamd())
        status, payload = self.call(base, "GET", "/health", token=None)
        self.assertEqual(status, 200)
        self.assertTrue(payload["ready"])

        self.assertEqual(self.call(base, "GET", "/ready", token=None)[0], 401)
        self.assertEqual(self.call(base, "POST", "/v1/scan", b"body", token=None)[0], 401)
        self.assertEqual(self.call(base, "POST", "/v1/scan", b"body", token="wrong-token-value-padding-32ch")[0], 401)

    def test_no_other_route_exists(self) -> None:
        _, base = self.build(FakeClamd())
        self.assertEqual(self.call(base, "GET", "/v1/scan")[0], 404)
        self.assertEqual(self.call(base, "POST", "/scan")[0], 404)
        self.assertEqual(self.call(base, "POST", "/health", b"x")[0], 404)


class VerdictTest(ServerTestCase):
    def test_a_clean_verdict_is_reported(self) -> None:
        _, base = self.build(FakeClamd(ScanResult("clean", None, None)))
        status, payload = self.call(base, "POST", "/v1/scan", b"harmless")
        self.assertEqual(status, 200)
        self.assertEqual(payload["verdict"], "clean")

    def test_a_detection_is_reported_with_its_signature(self) -> None:
        _, base = self.build(FakeClamd(ScanResult("infected", "Win.Test.EICAR_HDB-1", None)))
        status, payload = self.call(base, "POST", "/v1/scan", b"probe")
        self.assertEqual(status, 200)
        self.assertEqual(payload["verdict"], "infected")
        self.assertEqual(payload["signature"], "Win.Test.EICAR_HDB-1")

    def test_a_limit_hit_is_reported_unscannable(self) -> None:
        _, base = self.build(
            FakeClamd(ScanResult("unscannable", "Heuristics.Limits.Exceeded.MaxScanSize", "scan_limits_exceeded"))
        )
        status, payload = self.call(base, "POST", "/v1/scan", b"probe")
        self.assertEqual(payload["verdict"], "unscannable")
        self.assertEqual(payload["reason"], "scan_limits_exceeded")


class FailClosedTest(ServerTestCase):
    def test_a_dead_daemon_is_never_reported_ready_or_clean(self) -> None:
        _, base = self.build(FakeClamd(alive=False))
        status, payload = self.call(base, "GET", "/health", token=None)
        self.assertFalse(payload["ready"])
        self.assertTrue(payload["degraded"])
        self.assertEqual(self.call(base, "POST", "/v1/scan", b"probe")[0], 503)

    def test_a_daemon_failure_mid_scan_is_a_503_not_a_verdict(self) -> None:
        client = FakeClamd()
        client.failure = ClamdUnavailable("daemon_timeout")
        _, base = self.build(client)
        self.assertEqual(self.call(base, "POST", "/v1/scan", b"probe")[0], 503)

    def test_expired_signatures_refuse_to_scan(self) -> None:
        _, base = self.build(FakeClamd(), age_seconds=2_592_001)
        status, payload = self.call(base, "GET", "/health", token=None)
        self.assertFalse(payload["ready"])
        self.assertEqual(payload["seconds_until_block"], 0)
        self.assertEqual(self.call(base, "POST", "/v1/scan", b"probe")[0], 503)

    def test_stale_signatures_still_scan_but_report_degraded(self) -> None:
        _, base = self.build(FakeClamd(), age_seconds=1_209_600)
        status, payload = self.call(base, "GET", "/health", token=None)
        self.assertTrue(payload["ready"])
        self.assertTrue(payload["degraded"])
        self.assertGreater(payload["seconds_until_block"], 0)
        self.assertEqual(self.call(base, "POST", "/v1/scan", b"probe")[0], 200)

    def test_an_oversized_body_is_refused_rather_than_admitted(self) -> None:
        client = FakeClamd()
        _, base = self.build(client)
        status, _ = self.call(base, "POST", "/v1/scan", b"x" * 2048)
        self.assertEqual(status, 413)
        self.assertEqual(client.scans, 0)

    def test_an_empty_body_is_refused(self) -> None:
        client = FakeClamd()
        _, base = self.build(client)
        self.assertEqual(self.call(base, "POST", "/v1/scan", b"")[0], 422)
        self.assertEqual(client.scans, 0)


class ConcurrencyTest(ServerTestCase):
    def test_overlapping_scans_are_shed_rather_than_queued(self) -> None:
        client = FakeClamd()
        client.gate = threading.Event()
        _, base = self.build(client, CONNEX_CLAMAV_MAX_CONCURRENT_SCANS="1")
        statuses: list[int] = []

        def call_once() -> None:
            statuses.append(self.call(base, "POST", "/v1/scan", b"probe")[0])

        first = threading.Thread(target=call_once)
        first.start()
        for _ in range(200):
            if client.scans >= 1:
                break
            threading.Event().wait(0.01)
        second = threading.Thread(target=call_once)
        second.start()
        second.join(timeout=10)
        client.gate.set()
        first.join(timeout=10)
        self.assertIn(429, statuses)


if __name__ == "__main__":
    unittest.main()
