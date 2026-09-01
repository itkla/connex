import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

from .clamd import ClamdClient
from .config import ServiceConfig, verify_scratch_capacity
from . import signatures
from .server import create_server
from .startup import StartupFailure, report_startup_failure


CLAMD_BINARY = "/usr/sbin/clamd"
CLAMD_CONFIG = "/etc/clamav/clamd.conf"

_PROBE_INTERVAL_SECONDS = 1.0
_MONITOR_INTERVAL_SECONDS = 2.0
_STABLE_UPTIME_SECONDS = 120.0
_INITIAL_BACKOFF_SECONDS = 2.0
_MAX_BACKOFF_SECONDS = 60.0
_SHUTDOWN_GRACE_SECONDS = 10.0


class DaemonSupervisor:
    def __init__(self, config: ServiceConfig) -> None:
        self._config = config
        self._client = ClamdClient(config.socket_path, timeout_seconds=10.0)
        self._process: subprocess.Popen[bytes] | None = None
        self._stopping = threading.Event()
        self._backoff_seconds = _INITIAL_BACKOFF_SECONDS

    @property
    def client(self) -> ClamdClient:
        return self._client

    def start(self) -> None:
        self._config.socket_path.parent.mkdir(parents=True, exist_ok=True)
        self._config.socket_path.unlink(missing_ok=True)
        try:
            self._process = subprocess.Popen(
                [CLAMD_BINARY, "--config-file", CLAMD_CONFIG, "--foreground"],
                stdout=sys.stderr.fileno(),
                stderr=sys.stderr.fileno(),
                stdin=subprocess.DEVNULL,
                close_fds=True,
            )
        except OSError as exception:
            raise StartupFailure("daemon_binary_unavailable") from exception

    def await_ready(self, timeout_seconds: float) -> None:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            if self._process is not None and self._process.poll() is not None:
                raise StartupFailure("daemon_launch_failed")
            if self._client.ping():
                return
            time.sleep(_PROBE_INTERVAL_SECONDS)
        raise StartupFailure("daemon_never_became_ready")

    def monitor(self) -> None:
        readiness_reached_at = time.monotonic()
        while not self._stopping.is_set():
            time.sleep(_MONITOR_INTERVAL_SECONDS)
            if self._stopping.is_set() or self._process is None:
                return
            if self._process.poll() is None:
                if time.monotonic() - readiness_reached_at > _STABLE_UPTIME_SECONDS:
                    self._backoff_seconds = _INITIAL_BACKOFF_SECONDS
                continue
            if self._stopping.wait(self._backoff_seconds):
                return
            self._backoff_seconds = min(self._backoff_seconds * 2, _MAX_BACKOFF_SECONDS)
            try:
                self.start()
                self.await_ready(self._config.startup_timeout_seconds)
            except StartupFailure as exception:
                report_startup_failure("supervisor", "daemon", exception)
                os._exit(1)
            readiness_reached_at = time.monotonic()

    def stop(self) -> None:
        self._stopping.set()
        process = self._process
        if process is None or process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=_SHUTDOWN_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            process.kill()


def verify_signatures(config: ServiceConfig) -> None:
    """Refuses to start against a signature set that is missing, unreadable, or past the ceiling.

    Startup is the one moment where an operator is guaranteed to be looking. A deployment that
    boots happily and then answers 503 to every upload is far harder to diagnose than one that
    refuses to boot with an allowlisted reason code.

    ``volume`` additionally requires the database path to be a real mount and the transferred set
    to be complete. Declaring the operator-managed source without applying
    deploy/docker-compose.signatures.yml would otherwise keep scanning against the baked image
    contents and hard-block every upload the moment they reach the 30-day ceiling, with the
    transferred files sitting unused on the host; a partial copy would reach readiness with
    silently reduced coverage.

    ``os.path.ismount`` establishes that something is mounted at the database path, not that it is
    the intended read-only host bind. It is a guard against the half-applied deployment, not a
    proof of provenance: mount the operator directory as a bind, never as a named volume, because
    Docker seeds a fresh named volume from the image's own baked database and that would satisfy
    this check while still serving the baked set.
    """
    if not config.database_directory.is_dir():
        raise StartupFailure("signature_database_unavailable")
    if config.signature_source == "volume":
        if not os.path.ismount(config.database_directory):
            raise StartupFailure("signature_volume_not_mounted")
        if signatures.missing_containers(config.database_directory):
            raise StartupFailure("signature_database_unreadable")
    state = signatures.inspect(config.database_directory, None)
    if state.age_seconds is None:
        raise StartupFailure("signature_database_unreadable")
    if state.expired(config.signature_max_age_seconds):
        raise StartupFailure("signature_database_expired")


def main() -> int:
    try:
        config = ServiceConfig.from_environment()
        verify_scratch_capacity(config)
    except Exception as exception:
        report_startup_failure("supervisor", "configuration", exception)
        return 1
    try:
        verify_signatures(config)
    except Exception as exception:
        report_startup_failure("supervisor", "signatures", exception)
        return 1

    supervisor = DaemonSupervisor(config)
    try:
        supervisor.start()
        supervisor.await_ready(config.startup_timeout_seconds)
    except Exception as exception:
        report_startup_failure("supervisor", "daemon", exception)
        supervisor.stop()
        return 1

    try:
        server = create_server(config, supervisor.client)
    except Exception as exception:
        report_startup_failure("supervisor", "server", exception)
        supervisor.stop()
        return 1

    def shutdown(signum: int, frame: object) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    monitor = threading.Thread(target=supervisor.monitor, daemon=True)
    monitor.start()
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        server.server_close()
        supervisor.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
