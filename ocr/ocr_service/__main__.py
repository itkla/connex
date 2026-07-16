import signal
import sys
import threading

from .config import ServiceConfig
from .engine import PaddleEngine
from .server import create_server


def exception_type_chain(exception: BaseException) -> str:
    names: list[str] = []
    current: BaseException | None = exception
    while current is not None and len(names) < 8:
        kind = type(current)
        names.append(f"{kind.__module__}.{kind.__qualname__}")
        current = current.__cause__ if current.__cause__ is not None else current.__context__
    return " <- ".join(names)


def main() -> int:
    try:
        config = ServiceConfig.from_environment()
        engine = PaddleEngine(config)
    except Exception as exception:
        print(
            f"OCR service initialization failed: {exception_type_chain(exception)}",
            file=sys.stderr,
        )
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
