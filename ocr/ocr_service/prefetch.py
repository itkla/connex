from .config import ServiceConfig
from .engine import PaddleEngine


def main() -> None:
    config = ServiceConfig(
        host="127.0.0.1",
        port=8090,
        service_token="prefetch-token-not-used-000000000000",
        max_image_bytes=8_388_608,
        max_width=8_192,
        max_height=8_192,
        max_pixels=24_000_000,
        request_timeout_seconds=20.0,
    )
    PaddleEngine(config, allow_download=True)


if __name__ == "__main__":
    main()
