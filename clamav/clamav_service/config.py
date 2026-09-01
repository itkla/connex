import math
import os
from dataclasses import dataclass
from pathlib import Path

from .startup import StartupFailure


STREAM_MAX_LENGTH_BYTES = 33_554_432
MAX_SCAN_SIZE_BYTES = 201_326_592


@dataclass(frozen=True)
class ServiceConfig:
    host: str
    port: int
    service_token: str
    max_scan_bytes: int
    max_concurrent_scans: int
    scan_timeout_seconds: float
    request_timeout_seconds: float
    startup_timeout_seconds: float
    max_request_handlers: int
    signature_warn_age_seconds: int
    signature_max_age_seconds: int
    signature_source: str
    database_directory: Path
    socket_path: Path
    scratch_directory: Path

    @classmethod
    def from_environment(cls) -> "ServiceConfig":
        token = os.environ.get("CONNEX_CLAMAV_SERVICE_TOKEN", "")
        if len(token) < 32 or "\r" in token or "\n" in token:
            raise StartupFailure("invalid_configuration")

        signature_source = os.environ.get("CONNEX_CLAMAV_SIGNATURE_SOURCE", "baked")
        if signature_source not in {"baked", "volume"}:
            raise StartupFailure("invalid_configuration")

        max_concurrent_scans = _integer("CONNEX_CLAMAV_MAX_CONCURRENT_SCANS", 2, 1, 8)
        scan_timeout_seconds = _number("CONNEX_CLAMAV_SCAN_TIMEOUT_SECONDS", 40.0, 5.0, 300.0)
        request_timeout_seconds = _number(
            "CONNEX_CLAMAV_REQUEST_TIMEOUT_SECONDS",
            scan_timeout_seconds + 5.0,
            scan_timeout_seconds,
            600.0,
        )
        warn_age = _integer("CONNEX_CLAMAV_SIGNATURE_WARN_AGE_SECONDS", 604_800, 3_600, 2_592_000)
        max_age = _integer("CONNEX_CLAMAV_SIGNATURE_MAX_AGE_SECONDS", 2_592_000, 86_400, 2_592_000)
        if warn_age > max_age:
            raise StartupFailure("invalid_configuration")

        config = cls(
            host=os.environ.get("CONNEX_CLAMAV_HOST", "0.0.0.0"),
            port=_integer("CONNEX_CLAMAV_PORT", 8091, 1, 65_535),
            service_token=token,
            max_scan_bytes=_integer(
                "CONNEX_CLAMAV_MAX_SCAN_BYTES", 26_214_400, 1, STREAM_MAX_LENGTH_BYTES
            ),
            max_concurrent_scans=max_concurrent_scans,
            scan_timeout_seconds=scan_timeout_seconds,
            request_timeout_seconds=request_timeout_seconds,
            startup_timeout_seconds=_number(
                "CONNEX_CLAMAV_STARTUP_TIMEOUT_SECONDS", 300.0, 30.0, 1_800.0
            ),
            max_request_handlers=_integer("CONNEX_CLAMAV_MAX_REQUEST_HANDLERS", 8, 2, 64),
            signature_warn_age_seconds=warn_age,
            signature_max_age_seconds=max_age,
            signature_source=signature_source,
            database_directory=Path(
                os.environ.get("CONNEX_CLAMAV_DATABASE_DIRECTORY", "/var/lib/clamav")
            ),
            socket_path=Path(
                os.environ.get("CONNEX_CLAMAV_SOCKET_PATH", "/tmp/clamav-run/clamd.sock")
            ),
            scratch_directory=Path(
                os.environ.get("CONNEX_CLAMAV_SCRATCH_DIRECTORY", "/tmp/clamav-scan")
            ),
        )
        return config


def required_scratch_bytes(max_concurrent_scans: int) -> int:
    return max_concurrent_scans * (STREAM_MAX_LENGTH_BYTES + MAX_SCAN_SIZE_BYTES)


def verify_scratch_capacity(config: ServiceConfig) -> None:
    """Refuses to start when the scan scratch mount cannot hold the permitted concurrent scans.

    clamd does not scan an INSTREAM incrementally. It spools the submitted bytes to a temporary
    file and unpacks archive members alongside it, so an undersized tmpfs turns concurrent
    scanning into a stream of ENOSPC errors under exactly the load the control exists for.
    Failing startup makes that a deployment error instead of a silent runtime degradation.
    """
    try:
        config.scratch_directory.mkdir(parents=True, exist_ok=True)
        probe = config.scratch_directory / ".capacity-probe"
        probe.write_bytes(b"")
        probe.unlink()
        statistics = os.statvfs(config.scratch_directory)
    except OSError as exception:
        raise StartupFailure("scan_scratch_unavailable") from exception
    available = statistics.f_frsize * statistics.f_blocks
    if available < required_scratch_bytes(config.max_concurrent_scans):
        raise StartupFailure("scan_scratch_undersized")


def _integer(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.environ.get(name, "").strip() or str(default)
    try:
        value = int(raw)
    except ValueError as exception:
        raise StartupFailure("invalid_configuration") from exception
    if value < minimum or value > maximum:
        raise StartupFailure("invalid_configuration")
    return value


def _number(name: str, default: float, minimum: float, maximum: float) -> float:
    raw = os.environ.get(name, "").strip() or str(default)
    try:
        value = float(raw)
    except ValueError as exception:
        raise StartupFailure("invalid_configuration") from exception
    if not math.isfinite(value) or value < minimum or value > maximum:
        raise StartupFailure("invalid_configuration")
    return value
