import signal
import sys
import threading

from .config import ServiceConfig
from .engine import PaddleEngine
from .server import create_server
from .startup import StartupFailure, StartupReason


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


def initialization_reason(exception: BaseException, configuration: bool = False) -> StartupReason:
    chain = exception_chain(exception)
    for current in chain:
        if isinstance(current, StartupFailure):
            return current.reason
    if any(isinstance(current, ModuleNotFoundError) for current in chain):
        return "runtime_dependency_unavailable"
    if configuration:
        return "invalid_configuration"
    return "engine_initialization_failed"


def report_initialization_failure(exception: BaseException, configuration: bool = False) -> None:
    reason = initialization_reason(exception, configuration)
    print(
        f"OCR service initialization failed: reason={reason}; exception_types={exception_type_chain(exception)}",
        file=sys.stderr,
    )


def main() -> int:
    try:
        config = ServiceConfig.from_environment()
    except Exception as exception:
        report_initialization_failure(exception, configuration=True)
        return 1
    try:
        engine = PaddleEngine(config)
    except Exception as exception:
        report_initialization_failure(exception)
        return 1
    server = create_server(config, engine)

    def shutdown(signum: int, frame: object) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
