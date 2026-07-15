import hmac
import json
import threading
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
    ) -> None:
        super().__init__(address, handler)
        self.config = config
        self.engine = engine
        self.invocation = threading.BoundedSemaphore(1)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(self.config.request_timeout_seconds)
        return connection, address


def create_server(config: ServiceConfig, engine: OcrEngine) -> OcrServer:
    return OcrServer((config.host, config.port), OcrRequestHandler, config, engine)


class OcrRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server: OcrServer

    def do_GET(self) -> None:
        if self.path != "/health":
            self._respond(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        self._respond(HTTPStatus.OK, {"ready": self.server.engine.ready})

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
        if not self.server.engine.ready:
            self._respond(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "OCR unavailable"})
            return
        if not self.server.invocation.acquire(blocking=False):
            self._respond(HTTPStatus.TOO_MANY_REQUESTS, {"error": "OCR busy"})
            return
        try:
            content = self.rfile.read(content_length)
            if len(content) != content_length:
                self._respond(HTTPStatus.BAD_REQUEST, {"error": "Incomplete request body"})
                return
            try:
                lines = self.server.engine.recognize(content, content_type)
            except ImageRejected as exception:
                self._respond(exception.status, {"error": exception.message})
                return
            except Exception:
                self._respond(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "OCR unavailable"})
                return
            self._respond(HTTPStatus.OK, {"lines": lines})
        finally:
            self.server.invocation.release()

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
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(content)
        self.close_connection = True

    def log_message(self, format: str, *args: object) -> None:
        return
