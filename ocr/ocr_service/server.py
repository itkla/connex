import hmac
import json
import os
import socket
import threading
from collections.abc import Callable
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from .config import ServiceConfig
from .engine import ImageRejected, OcrEngine


class OcrServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        address: tuple[str, int],
        handler: type[BaseHTTPRequestHandler],
        config: ServiceConfig,
        engine: OcrEngine,
        fatal_timeout: Callable[[], None] | None = None,
    ) -> None:
        super().__init__(address, handler)
        self.config = config
        self.engine = engine
        self.invocation = threading.BoundedSemaphore(1)
        self.request_handlers = threading.BoundedSemaphore(config.max_request_handlers)
        self._fatal_timeout = fatal_timeout or _terminate_process
        self._deadline_lock = threading.Lock()
        self._deadline_generation = 0
        self._active_generation: int | None = None
        self._timed_out = False

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

    @property
    def ready(self) -> bool:
        with self._deadline_lock:
            timed_out = self._timed_out
        return self.engine.ready and not timed_out

    @property
    def inference_active(self) -> bool:
        with self._deadline_lock:
            return self._active_generation is not None

    @property
    def inference_generation(self) -> int | None:
        with self._deadline_lock:
            return self._active_generation

    def begin_inference(self) -> tuple[int, threading.Timer]:
        with self._deadline_lock:
            self._deadline_generation += 1
            generation = self._deadline_generation
            self._active_generation = generation
        timer = threading.Timer(
            self.config.request_timeout_seconds,
            self._inference_timed_out,
            args=(generation,),
        )
        timer.daemon = True
        timer.start()
        return generation, timer

    def finish_inference(self, generation: int, timer: threading.Timer) -> None:
        with self._deadline_lock:
            if self._active_generation == generation:
                self._active_generation = None
        timer.cancel()

    def _inference_timed_out(self, generation: int) -> None:
        with self._deadline_lock:
            if self._active_generation != generation:
                return
            self._timed_out = True
        self._fatal_timeout()

    def fail_inference(self) -> None:
        with self._deadline_lock:
            self._timed_out = True
        threading.Thread(target=self._fatal_timeout, daemon=True).start()


def _terminate_process() -> None:
    os._exit(1)


def create_server(
    config: ServiceConfig,
    engine: OcrEngine,
    fatal_timeout: Callable[[], None] | None = None,
) -> OcrServer:
    return OcrServer(
        (config.host, config.port),
        OcrRequestHandler,
        config,
        engine,
        fatal_timeout,
    )


class OcrRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server: OcrServer

    def setup(self) -> None:
        super().setup()
        self._request_expired = threading.Event()
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
            self._respond(HTTPStatus.OK, {
                "ready": self.server.ready,
                "active": self.server.inference_active,
                "generation": self.server.inference_generation,
            })
            return
        if self.path != "/ready":
            self._respond(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if not self._authorized():
            self._respond(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
            return
        self._respond(HTTPStatus.OK, {"ready": self.server.ready})

    def do_POST(self) -> None:
        if self.path != "/v1/ocr":
            self._respond(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if not self._authorized():
            self._respond(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
            return
        content_length = self._content_length()
        if content_length is None:
            return
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type not in {"image/jpeg", "image/png", "image/webp"}:
            self._respond(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "Unsupported image media type"})
            return
        if not self.server.ready:
            self._respond(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "OCR unavailable"})
            return
        content = self._read_body(content_length)
        if content is None:
            return
        if not self.server.ready:
            self._respond(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "OCR unavailable"})
            return
        if not self.server.invocation.acquire(blocking=False):
            self._respond(HTTPStatus.TOO_MANY_REQUESTS, {"error": "OCR busy"})
            return
        try:
            generation, timer = self.server.begin_inference()
            try:
                try:
                    lines = self.server.engine.recognize(content, content_type)
                except ImageRejected as exception:
                    self._respond(exception.status, {"error": exception.message})
                    return
                except Exception:
                    self.server.fail_inference()
                    self._respond(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "OCR unavailable"})
                    return
            finally:
                self.server.finish_inference(generation, timer)
            self._respond(HTTPStatus.OK, {"lines": lines})
        finally:
            self.server.invocation.release()

    def _read_body(self, content_length: int) -> bytes | None:
        try:
            content = self.rfile.read(content_length)
        except (OSError, TimeoutError):
            return None
        if self._request_expired.is_set():
            return None
        if len(content) != content_length:
            self._respond(HTTPStatus.BAD_REQUEST, {"error": "Incomplete request body"})
            return None
        self._request_timer.cancel()
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
            self._respond(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": "Image is empty"})
            return None
        if value > self.server.config.max_image_bytes:
            self._respond(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "Image is too large"})
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
