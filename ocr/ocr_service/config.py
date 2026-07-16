import math
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class ServiceConfig:
    host: str
    port: int
    service_token: str
    max_image_bytes: int
    max_width: int
    max_height: int
    max_pixels: int
    request_timeout_seconds: float
    max_request_handlers: int = 8

    @classmethod
    def from_environment(cls) -> "ServiceConfig":
        token = os.environ.get("CONNEX_OCR_SERVICE_TOKEN", "")
        if len(token) < 32 or "\r" in token or "\n" in token:
            raise ValueError("CONNEX_OCR_SERVICE_TOKEN must contain at least 32 safe characters")
        return cls(
            host=os.environ.get("CONNEX_OCR_HOST", "0.0.0.0"),
            port=_integer("CONNEX_OCR_PORT", 8090, 1, 65_535),
            service_token=token,
            max_image_bytes=_integer("CONNEX_OCR_MAX_IMAGE_BYTES", 8_388_608, 1, 100_000_000),
            max_width=_integer("CONNEX_OCR_MAX_WIDTH", 8_192, 1, 65_535),
            max_height=_integer("CONNEX_OCR_MAX_HEIGHT", 8_192, 1, 65_535),
            max_pixels=_integer("CONNEX_OCR_MAX_PIXELS", 24_000_000, 1, 250_000_000),
            request_timeout_seconds=_number("CONNEX_OCR_REQUEST_TIMEOUT_SECONDS", 12.0, 1.0, 120.0),
            max_request_handlers=_integer("CONNEX_OCR_MAX_REQUEST_HANDLERS", 8, 2, 64),
        )


def _integer(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.environ.get(name, str(default))
    try:
        value = int(raw)
    except ValueError as exception:
        raise ValueError(f"{name} must be an integer") from exception
    if not math.isfinite(value) or value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _number(name: str, default: float, minimum: float, maximum: float) -> float:
    raw = os.environ.get(name, str(default))
    try:
        value = float(raw)
    except ValueError as exception:
        raise ValueError(f"{name} must be numeric") from exception
    if not math.isfinite(value) or value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value
