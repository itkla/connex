import signal
import threading

from .config import ServiceConfig
from .engine import PaddleEngine
from .server import create_server
from .startup import report_startup_failure


def main() -> int:
    try:
        config = ServiceConfig.from_environment()
    except Exception as exception:
        report_startup_failure("worker", "configuration", exception)
        return 1
    try:
        engine = PaddleEngine(config)
    except Exception as exception:
        report_startup_failure("worker", "engine", exception)
        return 1
    try:
        server = create_server(config, engine)
    except Exception as exception:
        report_startup_failure("worker", "server", exception)
        return 1

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
