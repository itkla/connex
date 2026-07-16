import sys
from typing import Literal


StartupReason = Literal[
    "unsupported_cpu_architecture",
    "cpu_capabilities_unreadable",
    "avx_unavailable",
    "models_unavailable",
    "runtime_dependency_unavailable",
    "invalid_configuration",
    "engine_initialization_failed",
    "server_initialization_failed",
    "worker_launch_failed",
]

StartupComponent = Literal["worker", "supervisor"]
StartupPhase = Literal["configuration", "engine", "server", "worker_launch"]


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
    chain = exception_chain(exception)
    for current in chain:
        if isinstance(current, StartupFailure):
            return current.reason
    if any(isinstance(current, ModuleNotFoundError) for current in chain):
        return "runtime_dependency_unavailable"
    fallbacks: dict[StartupPhase, StartupReason] = {
        "configuration": "invalid_configuration",
        "engine": "engine_initialization_failed",
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
        "OCR startup failed: "
        f"component={component}; reason={startup_reason(exception, phase)}; "
        f"exception_types={exception_type_chain(exception)}",
        file=sys.stderr,
    )
