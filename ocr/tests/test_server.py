import http.client
import json
import socket
import threading
import time
import unittest
import urllib.error
import urllib.request

from ocr_service.config import ServiceConfig
from ocr_service.engine import ImageRejected
from ocr_service.server import create_server


class FakeEngine:
    def __init__(self, ready: bool = True) -> None:
        self._ready = ready

    @property
    def ready(self) -> bool:
        return self._ready

    def recognize(self, content: bytes, content_type: str) -> list[dict[str, object]]:
        return [{"text": "Ada Lovelace", "confidence": 0.99, "box": [1, 2, 3, 4]}]


class BlockingEngine(FakeEngine):
    def __init__(self) -> None:
        super().__init__()
        self.started = threading.Event()
        self.release = threading.Event()

    def recognize(self, content: bytes, content_type: str) -> list[dict[str, object]]:
        self.started.set()
        if not self.release.wait(5):
            raise RuntimeError("test timeout")
        return super().recognize(content, content_type)


class FailingEngine(FakeEngine):
    def recognize(self, content: bytes, content_type: str) -> list[dict[str, object]]:
        raise RuntimeError("native worker failed")


class RejectingEngine(FakeEngine):
    def recognize(self, content: bytes, content_type: str) -> list[dict[str, object]]:
        raise ImageRejected(422, "OCR result contains too many lines")


class ServerTest(unittest.TestCase):
    token = "test-service-token-0000000000000000"

    def setUp(self) -> None:
        self.engine = FakeEngine()
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token=self.token,
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=2,
        )
        self.server = create_server(config, self.engine)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def test_health_reports_engine_readiness(self) -> None:
        with urllib.request.urlopen(self.base_url + "/health", timeout=2) as response:
            self.assertEqual(200, response.status)
            self.assertEqual(
                {"ready": True, "active": False, "generation": None},
                json.load(response),
            )

    def test_ready_requires_the_service_token(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(self.base_url + "/ready", timeout=2)
        self.assertEqual(401, raised.exception.code)
        request = urllib.request.Request(
            self.base_url + "/ready",
            headers={"Authorization": "Bearer " + self.token},
        )

        with urllib.request.urlopen(request, timeout=2) as response:
            self.assertEqual({"ready": True}, json.load(response))

    def test_ocr_requires_bearer_token(self) -> None:
        request = urllib.request.Request(
            self.base_url + "/v1/ocr",
            data=b"image",
            headers={"Content-Type": "image/jpeg"},
            method="POST",
        )

        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=2)

        self.assertEqual(401, raised.exception.code)

    def test_ocr_returns_only_structured_lines(self) -> None:
        request = self._request(b"image")

        with urllib.request.urlopen(request, timeout=2) as response:
            self.assertEqual(200, response.status)
            self.assertEqual({"lines": [
                {"text": "Ada Lovelace", "confidence": 0.99, "box": [1, 2, 3, 4]}
            ]}, json.load(response))

    def test_rejects_oversized_body_before_inference(self) -> None:
        request = self._request(b"x" * 129)

        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=2)

        self.assertEqual(413, raised.exception.code)

    def _request(self, content: bytes) -> urllib.request.Request:
        return urllib.request.Request(
            self.base_url + "/v1/ocr",
            data=content,
            headers={
                "Authorization": "Bearer " + self.token,
                "Content-Type": "image/jpeg",
            },
            method="POST",
        )


class HeaderTimeoutTest(unittest.TestCase):
    def test_closes_connection_when_headers_arrive_too_slowly(self) -> None:
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token="test-service-token-0000000000000000",
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=0.1,
        )
        server = create_server(config, FakeEngine())
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        try:
            with socket.create_connection(("127.0.0.1", server.server_port), timeout=1) as connection:
                connection.settimeout(1)
                connection.sendall(b"POST /v1/ocr HTTP/1.1\r\nHost: localhost\r\nAuthorization:")
                self.assertEqual(b"", connection.recv(1))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)


class ConcurrencyTest(unittest.TestCase):
    def test_rejects_overlapping_inference(self) -> None:
        token = "test-service-token-0000000000000000"
        engine = BlockingEngine()
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token=token,
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=2,
        )
        server = create_server(config, engine)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        url = f"http://127.0.0.1:{server.server_port}/v1/ocr"
        headers = {"Authorization": "Bearer " + token, "Content-Type": "image/jpeg"}
        first_status: list[int] = []

        def first_request() -> None:
            request = urllib.request.Request(url, data=b"first", headers=headers, method="POST")
            with urllib.request.urlopen(request, timeout=5) as response:
                first_status.append(response.status)

        first_thread = threading.Thread(target=first_request)
        first_thread.start()
        self.assertTrue(engine.started.wait(2))
        second = urllib.request.Request(url, data=b"second", headers=headers, method="POST")

        try:
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(second, timeout=2)
            self.assertEqual(429, raised.exception.code)
        finally:
            engine.release.set()
            first_thread.join(5)
            server.shutdown()
            server.server_close()
            server_thread.join(2)

        self.assertEqual([200], first_status)

    def test_partial_body_does_not_hold_the_inference_slot(self) -> None:
        token = "test-service-token-0000000000000000"
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token=token,
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=1,
        )
        server = create_server(config, FakeEngine())
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        partial = socket.create_connection(("127.0.0.1", server.server_port), timeout=1)
        partial.sendall(
            b"POST /v1/ocr HTTP/1.1\r\n"
            b"Host: localhost\r\n"
            b"Authorization: Bearer " + token.encode() + b"\r\n"
            b"Content-Type: image/jpeg\r\n"
            b"Content-Length: 5\r\n\r\nx"
        )

        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/v1/ocr",
                data=b"image",
                headers={
                    "Authorization": "Bearer " + token,
                    "Content-Type": "image/jpeg",
                },
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(200, response.status)
        finally:
            partial.close()
            server.shutdown()
            server.server_close()
            server_thread.join(2)


class HandlerLimitTest(unittest.TestCase):
    def test_rejects_connections_above_the_handler_limit(self) -> None:
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token="test-service-token-0000000000000000",
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=1,
            max_request_handlers=2,
        )
        server = create_server(config, FakeEngine())
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        blocked = [
            socket.create_connection(("127.0.0.1", server.server_port), timeout=1)
            for _ in range(2)
        ]
        for connection in blocked:
            connection.sendall(b"GET /health HTTP/1.1\r\nHost: localhost\r\nX-Slow:")
        time.sleep(0.05)

        try:
            with socket.create_connection(("127.0.0.1", server.server_port), timeout=1) as overflow:
                overflow.settimeout(1)
                overflow.sendall(b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
                self.assertIn(b"503 Service Unavailable", overflow.recv(256))
        finally:
            for connection in blocked:
                connection.close()
            server.shutdown()
            server.server_close()
            server_thread.join(2)


class InferenceDeadlineTest(unittest.TestCase):
    def test_marks_unready_and_invokes_fatal_handler_after_deadline(self) -> None:
        token = "test-service-token-0000000000000000"
        engine = BlockingEngine()
        fatal_timeout = threading.Event()
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token=token,
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=0.25,
        )
        server = create_server(config, engine, fatal_timeout.set)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        request = urllib.request.Request(
            base_url + "/v1/ocr",
            data=b"image",
            headers={
                "Authorization": "Bearer " + token,
                "Content-Type": "image/jpeg",
            },
            method="POST",
        )
        request_failed = threading.Event()

        def invoke() -> None:
            try:
                with urllib.request.urlopen(request, timeout=5) as response:
                    response.read()
            except (OSError, http.client.HTTPException):
                request_failed.set()

        request_thread = threading.Thread(target=invoke)
        request_thread.start()
        self.assertTrue(engine.started.wait(2))

        try:
            self.assertTrue(fatal_timeout.wait(2))
            with urllib.request.urlopen(base_url + "/health", timeout=2) as response:
                self.assertEqual(
                    {"ready": False, "active": True, "generation": 1},
                    json.load(response),
                )
        finally:
            engine.release.set()
            request_thread.join(5)
            server.shutdown()
            server.server_close()
            server_thread.join(2)

        self.assertTrue(request_failed.is_set())

    def test_body_upload_and_inference_share_one_deadline(self) -> None:
        token = "test-service-token-0000000000000000"
        engine = BlockingEngine()
        fatal_timeout = threading.Event()
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token=token,
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=0.4,
        )
        server = create_server(config, engine, fatal_timeout.set)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        connection = socket.create_connection(("127.0.0.1", server.server_port), timeout=1)
        connection.sendall(
            b"POST /v1/ocr HTTP/1.1\r\n"
            b"Host: localhost\r\n"
            b"Authorization: Bearer " + token.encode() + b"\r\n"
            b"Content-Type: image/jpeg\r\n"
            b"Content-Length: 5\r\n\r\nx"
        )
        time.sleep(0.25)
        inference_started_at = time.monotonic()
        connection.sendall(b"mage")

        try:
            self.assertTrue(engine.started.wait(1))
            self.assertTrue(fatal_timeout.wait(0.35))
            self.assertLess(time.monotonic() - inference_started_at, 0.35)
        finally:
            engine.release.set()
            connection.close()
            server.shutdown()
            server.server_close()
            server_thread.join(2)


class InferenceFailureTest(unittest.TestCase):
    def test_image_rejection_returns_422_without_restarting_the_process(self) -> None:
        token = "test-service-token-0000000000000000"
        fatal_failure = threading.Event()
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token=token,
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=2,
        )
        server = create_server(config, RejectingEngine(), fatal_failure.set)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        request = urllib.request.Request(
            base_url + "/v1/ocr",
            data=b"image",
            headers={
                "Authorization": "Bearer " + token,
                "Content-Type": "image/jpeg",
            },
            method="POST",
        )

        try:
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(request, timeout=2)
            self.assertEqual(422, raised.exception.code)
            self.assertEqual(
                {"error": "OCR result contains too many lines"},
                json.load(raised.exception),
            )
            self.assertFalse(fatal_failure.wait(0.1))
            with urllib.request.urlopen(base_url + "/health", timeout=2) as response:
                self.assertEqual(
                    {"ready": True, "active": False, "generation": None},
                    json.load(response),
                )
        finally:
            server.shutdown()
            server.server_close()
            server_thread.join(2)

    def test_marks_unready_and_invokes_fatal_handler_on_unexpected_failure(self) -> None:
        token = "test-service-token-0000000000000000"
        fatal_failure = threading.Event()
        config = ServiceConfig(
            host="127.0.0.1",
            port=0,
            service_token=token,
            max_image_bytes=128,
            max_width=100,
            max_height=100,
            max_pixels=10_000,
            request_timeout_seconds=2,
        )
        server = create_server(config, FailingEngine(), fatal_failure.set)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        request = urllib.request.Request(
            base_url + "/v1/ocr",
            data=b"image",
            headers={
                "Authorization": "Bearer " + token,
                "Content-Type": "image/jpeg",
            },
            method="POST",
        )

        try:
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(request, timeout=2)
            self.assertEqual(503, raised.exception.code)
            self.assertTrue(fatal_failure.wait(2))
            with urllib.request.urlopen(base_url + "/health", timeout=2) as response:
                self.assertEqual(
                    {"ready": False, "active": False, "generation": None},
                    json.load(response),
                )
        finally:
            server.shutdown()
            server.server_close()
            server_thread.join(2)


if __name__ == "__main__":
    unittest.main()
