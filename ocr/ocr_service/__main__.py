import signal
import sys
import threading

from .config import ServiceConfig
from .engine import PaddleEngine
from .server import create_server


def main() -> int:
    try:
        config = ServiceConfig.from_environment()
        engine = PaddleEngine(config)
    except Exception as exception:
        print(
            f"OCR service initialization failed: {type(exception).__name__}: {exception}",
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
