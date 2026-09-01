import sys
from typing import Literal


StartupReason = Literal[
    "invalid_configuration",
    "scan_scratch_unavailable",
    "scan_scratch_undersized",
    "signature_database_unavailable",
    "signature_database_unreadable",
    "signature_database_expired",
    "daemon_binary_unavailable",
    "daemon_launch_failed",
    "daemon_never_became_ready",
    "server_initialization_failed",
    "worker_launch_failed",
]

StartupComponent = Literal["worker", "supervisor"]
StartupPhase = Literal["configuration", "signatures", "daemon", "server", "worker_launch"]

ScanReason = Literal[
    "daemon_unreachable",
    "daemon_protocol_violation",
    "daemon_timeout",
    "stream_limit_exceeded",
    "scan_limits_exceeded",
    "encrypted_container",
    "macro_container",
    "daemon_error",
    "signatures_expired",
]


class StartupFailure(RuntimeError):
    def __init__(self, reason: StartupReason) -> None:
        super().__init__(reason)
        self.reason = reason


def exception_chain(exception: BaseException) -> list[BaseException]:
    chain: list[BaseException] = []
    seen: set[int] = set()
    current: BaseException | None = exception
    while current is not None and len(chain) < 8 and id(current) not in seen:
        chain.append(current)
        seen.add(id(current))
        current = current.__cause__ if current.__cause__ is not None else current.__context__
    return chain


def exception_type_chain(exception: BaseException) -> str:
    return " <- ".join(
        f"{type(current).__module__}.{type(current).__qualname__}"
        for current in exception_chain(exception)
    )


def startup_reason(exception: BaseException, phase: StartupPhase) -> StartupReason:
    for current in exception_chain(exception):
        if isinstance(current, StartupFailure):
            return current.reason
    fallbacks: dict[StartupPhase, StartupReason] = {
        "configuration": "invalid_configuration",
        "signatures": "signature_database_unavailable",
        "daemon": "daemon_launch_failed",
        "server": "server_initialization_failed",
        "worker_launch": "worker_launch_failed",
    }
    return fallbacks[phase]


def report_startup_failure(
    component: StartupComponent,
    phase: StartupPhase,
    exception: BaseException,
) -> None:
    print(
        "ClamAV startup failed: "
        f"component={component}; reason={startup_reason(exception, phase)}; "
        f"exception_types={exception_type_chain(exception)}",
        file=sys.stderr,
        flush=True,
    )


def report_scan_failure(reason: ScanReason, exception: BaseException | None = None) -> None:
    types = exception_type_chain(exception) if exception is not None else "none"
    print(
        f"ClamAV scan failed: reason={reason}; exception_types={types}",
        file=sys.stderr,
        flush=True,
    )
