import re
import socket
import struct
import time
from dataclasses import dataclass
from pathlib import Path

from .startup import ScanReason


SAFE_SIGNATURE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
UNNAMED_SIGNATURE = "unnamed"

LIMITS_EXCEEDED_PREFIX = "Heuristics.Limits.Exceeded"
ENCRYPTED_PREFIX = "Heuristics.Encrypted"
MACRO_PREFIX = "Heuristics.OLE2.ContainsMacros"

_CHUNK_BYTES = 65_536
_MAX_REPLY_BYTES = 4_096


class ClamdUnavailable(RuntimeError):
    def __init__(self, reason: ScanReason) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class ScanResult:
    verdict: str
    signature: str | None
    reason: ScanReason | None


def normalize_signature(raw: str) -> str:
    candidate = raw.strip()
    return candidate if SAFE_SIGNATURE.match(candidate) else UNNAMED_SIGNATURE


def classify_detection(signature: str) -> ScanResult:
    """Maps a clamd detection name onto the sidecar's three-verdict vocabulary.

    A detection that means "this artifact could not be inspected" is deliberately reported as
    unscannable rather than infected. Both outcomes reject the upload, but only one of them
    tells a user their spreadsheet is a virus.
    """
    normalized = normalize_signature(signature)
    if normalized.startswith(LIMITS_EXCEEDED_PREFIX):
        return ScanResult("unscannable", normalized, "scan_limits_exceeded")
    if normalized.startswith(ENCRYPTED_PREFIX):
        return ScanResult("unscannable", normalized, "encrypted_container")
    if normalized.startswith(MACRO_PREFIX):
        return ScanResult("unscannable", normalized, "macro_container")
    return ScanResult("infected", normalized, None)


def parse_reply(reply: str) -> ScanResult:
    """Turns one clamd INSTREAM reply line into a verdict, failing closed on anything unknown.

    There is no branch here that answers "clean" for a reply clamd did not explicitly terminate
    with OK. An unrecognised reply raises, which the HTTP front turns into a 503 and the backend
    turns into a refused upload.
    """
    line = reply.strip().rstrip("\x00").strip()
    if not line:
        raise ClamdUnavailable("daemon_protocol_violation")
    if line.endswith("ERROR"):
        if "size limit exceeded" in line.lower():
            return ScanResult("unscannable", None, "stream_limit_exceeded")
        raise ClamdUnavailable("daemon_error")
    if line.endswith("FOUND"):
        body = line[: -len("FOUND")].strip()
        _, separator, signature = body.partition(":")
        return classify_detection(signature if separator else body)
    if line.endswith("OK"):
        return ScanResult("clean", None, None)
    raise ClamdUnavailable("daemon_protocol_violation")


class ClamdClient:
    def __init__(self, socket_path: Path, timeout_seconds: float) -> None:
        self._socket_path = str(socket_path)
        self._timeout_seconds = timeout_seconds

    def ping(self) -> bool:
        try:
            return self._command(b"zPING\x00", self._timeout_seconds).strip() == "PONG"
        except (ClamdUnavailable, OSError):
            return False

    def version(self) -> str | None:
        try:
            raw = self._command(b"zVERSION\x00", self._timeout_seconds).strip()
        except (ClamdUnavailable, OSError):
            return None
        return raw or None

    def scan(self, content: bytes, deadline: float) -> ScanResult:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ClamdUnavailable("daemon_timeout")
        connection = self._connect(remaining)
        try:
            connection.sendall(b"zINSTREAM\x00")
            truncated = self._send_chunks(connection, content, deadline)
            reply = self._read_reply(connection, deadline)
        except socket.timeout as exception:
            raise ClamdUnavailable("daemon_timeout") from exception
        except OSError as exception:
            raise ClamdUnavailable("daemon_unreachable") from exception
        finally:
            connection.close()
        result = parse_reply(reply)
        if truncated and result.verdict == "clean":
            raise ClamdUnavailable("daemon_protocol_violation")
        return result

    def _send_chunks(self, connection: socket.socket, content: bytes, deadline: float) -> bool:
        offset = 0
        while offset < len(content):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ClamdUnavailable("daemon_timeout")
            connection.settimeout(remaining)
            chunk = content[offset : offset + _CHUNK_BYTES]
            try:
                connection.sendall(struct.pack("!I", len(chunk)) + chunk)
            except BrokenPipeError:
                return True
            except ConnectionResetError:
                return True
            offset += len(chunk)
        try:
            connection.sendall(struct.pack("!I", 0))
        except (BrokenPipeError, ConnectionResetError):
            return True
        return False

    def _read_reply(self, connection: socket.socket, deadline: float) -> str:
        buffer = bytearray()
        while b"\x00" not in buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ClamdUnavailable("daemon_timeout")
            connection.settimeout(remaining)
            chunk = connection.recv(_MAX_REPLY_BYTES)
            if not chunk:
                break
            buffer.extend(chunk)
            if len(buffer) > _MAX_REPLY_BYTES:
                raise ClamdUnavailable("daemon_protocol_violation")
        return buffer.split(b"\x00", 1)[0].decode("utf-8", errors="replace")

    def _command(self, command: bytes, timeout_seconds: float) -> str:
        connection = self._connect(timeout_seconds)
        try:
            connection.sendall(command)
            return self._read_reply(connection, time.monotonic() + timeout_seconds)
        except socket.timeout as exception:
            raise ClamdUnavailable("daemon_timeout") from exception
        except OSError as exception:
            raise ClamdUnavailable("daemon_unreachable") from exception
        finally:
            connection.close()

    def _connect(self, timeout_seconds: float) -> socket.socket:
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(timeout_seconds)
        try:
            connection.connect(self._socket_path)
        except OSError as exception:
            connection.close()
            raise ClamdUnavailable("daemon_unreachable") from exception
        return connection
