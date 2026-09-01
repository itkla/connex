import hmac
import json
import socket
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .clamd import ClamdClient, ClamdUnavailable
from .config import ServiceConfig
from . import signatures
from .startup import report_scan_failure


_STATE_CACHE_SECONDS = 5.0
_PROBE_TIMEOUT_SECONDS = 5.0


class ScannerServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        address: tuple[str, int],
        handler: type[BaseHTTPRequestHandler],
        config: ServiceConfig,
        client: ClamdClient,
    ) -> None:
        super().__init__(address, handler)
        self.config = config
        self.client = client
        self.scans = threading.BoundedSemaphore(config.max_concurrent_scans)
        self.request_handlers = threading.BoundedSemaphore(config.max_request_handlers)
        self._state_lock = threading.Lock()
        self._state_expires_at = 0.0
        self._state: dict[str, object] = {
            "ready": False,
            "signature_age_seconds": None,
            "seconds_until_block": 0,
            "database_version": None,
            "degraded": True,
        }

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(self.config.request_timeout_seconds)
        return connection, address

    def process_request(self, request: socket.socket, client_address: tuple[str, int]) -> None:
        if not self.request_handlers.acquire(blocking=False):
            try:
                request.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\n"
                    b"Content-Length: 0\r\n"
                    b"Connection: close\r\n\r\n"
                )
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.request_handlers.release()
            raise

    def process_request_thread(self, request: socket.socket, client_address: tuple[str, int]) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.request_handlers.release()

    def state(self) -> dict[str, object]:
        now = time.monotonic()
        with self._state_lock:
            if now < self._state_expires_at:
                return dict(self._state)
        refreshed = self._probe()
        with self._state_lock:
            self._state = refreshed
            self._state_expires_at = time.monotonic() + _STATE_CACHE_SECONDS
        return dict(refreshed)

    def _probe(self) -> dict[str, object]:
        alive = self.client.ping()
        daemon_version = self.client.version() if alive else None
        state = signatures.inspect(self.config.database_directory, daemon_version)
        expired = state.expired(self.config.signature_max_age_seconds)
        return {
            "ready": alive and not expired,
            "signature_age_seconds": state.age_seconds,
            "seconds_until_block": state.seconds_until_expiry(self.config.signature_max_age_seconds),
            "database_version": state.database_version,
            "daemon_database_version": state.daemon_version,
            "degraded": (not alive) or state.stale(self.config.signature_warn_age_seconds),
            "signature_source": self.config.signature_source,
        }

    def invalidate_state(self) -> None:
        with self._state_lock:
            self._state_expires_at = 0.0


def create_server(config: ServiceConfig, client: ClamdClient) -> ScannerServer:
    return ScannerServer((config.host, config.port), ScannerRequestHandler, config, client)


class ScannerRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server: ScannerServer

    def setup(self) -> None:
        super().setup()
        self._request_expired = threading.Event()
        self._request_deadline = time.monotonic() + self.server.config.request_timeout_seconds
        self._request_timer = threading.Timer(
            self.server.config.request_timeout_seconds,
            self._expire_request,
        )
        self._request_timer.daemon = True
        self._request_timer.start()

    def finish(self) -> None:
        self._request_timer.cancel()
        super().finish()

    def _expire_request(self) -> None:
        self._request_expired.set()
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            return

    def do_GET(self) -> None:
        if self.path == "/health":
            self._respond(HTTPStatus.OK, self.server.state())
            return
        if self.path != "/ready":
            self._respond(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if not self._authorized():
            self._respond(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
            return
        state = self.server.state()
        self._respond(HTTPStatus.OK, {
            "ready": state["ready"],
            "degraded": state["degraded"],
            "signature_age_seconds": state["signature_age_seconds"],
            "seconds_until_block": state["seconds_until_block"],
            "database_version": state["database_version"],
        })

    def do_POST(self) -> None:
        if self.path != "/v1/scan":
            self._respond(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if not self._authorized():
            self._respond(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
            return
        content_length = self._content_length()
        if content_length is None:
            return
        state = self.server.state()
        if not state["ready"]:
            self._respond(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Scanner unavailable"})
            return
        content = self._read_body(content_length)
        if content is None:
            return
        if not self.server.scans.acquire(blocking=False):
            self._respond(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Scanner busy"})
            return
        try:
            deadline = min(
                self._request_deadline,
                time.monotonic() + self.server.config.scan_timeout_seconds,
            )
            try:
                result = self.server.client.scan(content, deadline)
            except ClamdUnavailable as exception:
                report_scan_failure(exception.reason, exception)
                self.server.invalidate_state()
                self._respond(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Scanner unavailable"})
                return
        finally:
            self.server.scans.release()
        self._respond(HTTPStatus.OK, {
            "verdict": result.verdict,
            "signature": result.signature,
            "reason": result.reason,
            "database_version": state["database_version"],
            "signature_age_seconds": state["signature_age_seconds"],
            "degraded": state["degraded"],
        })

    def _read_body(self, content_length: int) -> bytes | None:
        remaining = content_length
        chunks: list[bytes] = []
        while remaining > 0:
            try:
                chunk = self.rfile.read(min(remaining, 65_536))
            except (OSError, TimeoutError):
                return None
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        if self._request_expired.is_set():
            return None
        content = b"".join(chunks)
        if len(content) != content_length:
            self._respond(HTTPStatus.BAD_REQUEST, {"error": "Incomplete request body"})
            return None
        return content

    def _authorized(self) -> bool:
        authorization = self.headers.get("Authorization", "")
        expected = "Bearer " + self.server.config.service_token
        return hmac.compare_digest(authorization, expected)

    def _content_length(self) -> int | None:
        raw = self.headers.get("Content-Length")
        if raw is None:
            self._respond(HTTPStatus.LENGTH_REQUIRED, {"error": "Content-Length required"})
            return None
        try:
            value = int(raw)
        except ValueError:
            self._respond(HTTPStatus.BAD_REQUEST, {"error": "Invalid Content-Length"})
            return None
        if value <= 0:
            self._respond(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": "Body is empty"})
            return None
        if value > self.server.config.max_scan_bytes:
            self._respond(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "Body is too large"})
            return None
        return value

    def _respond(self, status: int, payload: dict[str, object]) -> None:
        content = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(content)
        except OSError:
            return
        finally:
            self.close_connection = True

    def log_message(self, format: str, *args: object) -> None:
        return
