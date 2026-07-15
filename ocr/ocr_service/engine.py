import io
import json
import math
import os
from collections.abc import Mapping, Sequence
from numbers import Real
from pathlib import Path
from typing import Protocol

from .config import ServiceConfig


class OcrEngine(Protocol):
    @property
    def ready(self) -> bool:
        ...

    def recognize(self, content: bytes, content_type: str) -> list[dict[str, object]]:
        ...


class ImageRejected(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


class PaddleEngine:
    _FORMATS = {
        "image/jpeg": "JPEG",
        "image/png": "PNG",
        "image/webp": "WEBP",
    }

    def __init__(self, config: ServiceConfig, allow_download: bool = False) -> None:
        from paddleocr import PaddleOCR

        self._config = config
        arguments: dict[str, object] = {
            "doc_orientation_classify_model_name": "PP-LCNet_x1_0_doc_ori",
            "textline_orientation_model_name": "PP-LCNet_x0_25_textline_ori",
            "text_detection_model_name": "PP-OCRv6_small_det",
            "text_recognition_model_name": "PP-OCRv6_small_rec",
            "use_doc_orientation_classify": True,
            "use_doc_unwarping": False,
            "use_textline_orientation": True,
            "device": "cpu",
            "cpu_threads": 2,
        }
        model_paths = _model_paths()
        if not allow_download:
            if any(not path.is_dir() for path in model_paths.values()):
                raise RuntimeError("Pre-fetched OCR models are unavailable")
            arguments.update({argument: str(path) for argument, path in model_paths.items()})
        self._ocr = PaddleOCR(**arguments)
        if allow_download and any(not path.is_dir() for path in model_paths.values()):
            raise RuntimeError("OCR model prefetch did not populate the locked cache")
        self._ready = True

    @property
    def ready(self) -> bool:
        return self._ready

    def recognize(self, content: bytes, content_type: str) -> list[dict[str, object]]:
        image_array = self._decode(content, content_type)
        results = self._ocr.predict(image_array)
        return _extract_lines(results)

    def _decode(self, content: bytes, content_type: str):
        import numpy
        from PIL import Image, ImageOps, UnidentifiedImageError

        expected_format = self._FORMATS.get(content_type)
        if expected_format is None:
            raise ImageRejected(415, "Image must be JPEG, PNG, or WebP")
        try:
            with Image.open(io.BytesIO(content)) as image:
                if image.format != expected_format:
                    raise ImageRejected(415, "Image content does not match its media type")
                if getattr(image, "n_frames", 1) != 1:
                    raise ImageRejected(422, "Animated or multi-frame images are unsupported")
                width, height = image.size
                pixels = width * height
                if (
                    width <= 0
                    or height <= 0
                    or width > max(self._config.max_width, self._config.max_height)
                    or height > max(self._config.max_width, self._config.max_height)
                    or pixels > self._config.max_pixels
                ):
                    raise ImageRejected(422, "Image dimensions exceed supported bounds")
                image.load()
                with ImageOps.exif_transpose(image) as oriented:
                    width, height = oriented.size
                    pixels = width * height
                    if (
                        width <= 0
                        or height <= 0
                        or width > self._config.max_width
                        or height > self._config.max_height
                        or pixels > self._config.max_pixels
                    ):
                        raise ImageRejected(422, "Image dimensions exceed supported bounds")
                    return numpy.asarray(oriented.convert("RGB"))
        except ImageRejected:
            raise
        except (UnidentifiedImageError, OSError, ValueError) as exception:
            raise ImageRejected(422, "Image could not be decoded") from exception


def _extract_lines(results: object) -> list[dict[str, object]]:
    if not isinstance(results, Sequence) or isinstance(results, (str, bytes, bytearray)):
        raise RuntimeError("Unexpected OCR result")
    lines: list[dict[str, object]] = []
    for result in results:
        payload = _result_payload(result)
        texts = payload.get("rec_texts")
        scores = payload.get("rec_scores")
        boxes = payload.get("rec_boxes")
        scores = _plain_sequence(scores)
        boxes = _plain_sequence(boxes)
        if not _parallel_sequences(texts, scores, boxes):
            raise RuntimeError("Unexpected OCR result fields")
        assert isinstance(texts, Sequence)
        assert isinstance(scores, Sequence)
        assert isinstance(boxes, Sequence)
        for text, score, box in zip(texts, scores, boxes, strict=True):
            line = _line(text, score, box)
            if line is not None:
                lines.append(line)
            if len(lines) > 256:
                raise RuntimeError("OCR result contains too many lines")
    return lines


def _model_paths() -> dict[str, Path]:
    root = Path(os.environ.get(
        "CONNEX_OCR_MODEL_ROOT",
        str(Path.home() / ".paddlex" / "official_models"),
    ))
    return {
        "doc_orientation_classify_model_dir": root / "PP-LCNet_x1_0_doc_ori",
        "textline_orientation_model_dir": root / "PP-LCNet_x0_25_textline_ori",
        "text_detection_model_dir": root / "PP-OCRv6_small_det",
        "text_recognition_model_dir": root / "PP-OCRv6_small_rec",
    }


def _result_payload(result: object) -> Mapping[str, object]:
    value = getattr(result, "json", result)
    if callable(value):
        value = value()
    if isinstance(value, str):
        value = json.loads(value)
    if not isinstance(value, Mapping):
        raise RuntimeError("Unexpected OCR result payload")
    nested = value.get("res")
    if isinstance(nested, Mapping):
        return nested
    return value


def _parallel_sequences(*values: object) -> bool:
    if any(not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)) for value in values):
        return False
    lengths = {len(value) for value in values if isinstance(value, Sequence)}
    return len(lengths) == 1


def _plain_sequence(value: object) -> object:
    to_list = getattr(value, "tolist", None)
    return to_list() if callable(to_list) else value


def _line(text: object, score: object, box: object) -> dict[str, object] | None:
    if not isinstance(text, str):
        raise RuntimeError("OCR text is invalid")
    normalized = " ".join(text.split())
    if not normalized:
        return None
    if len(normalized) > 512 or "\x00" in normalized:
        raise RuntimeError("OCR text exceeds bounds")
    if not isinstance(score, Real) or isinstance(score, bool):
        raise RuntimeError("OCR confidence is invalid")
    confidence = float(score)
    if not math.isfinite(confidence) or confidence < 0 or confidence > 1:
        raise RuntimeError("OCR confidence is invalid")
    coordinates = _box(box)
    return {"text": normalized, "confidence": confidence, "box": coordinates}


def _box(value: object) -> list[int]:
    value = _plain_sequence(value)
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)) or len(value) != 4:
        raise RuntimeError("OCR box is invalid")
    coordinates: list[int] = []
    for coordinate in value:
        if not isinstance(coordinate, Real) or isinstance(coordinate, bool):
            raise RuntimeError("OCR box is invalid")
        number = float(coordinate)
        if not math.isfinite(number) or number < 0 or number > 1_000_000:
            raise RuntimeError("OCR box is invalid")
        coordinates.append(round(number))
    if coordinates[2] < coordinates[0] or coordinates[3] < coordinates[1]:
        raise RuntimeError("OCR box is invalid")
    return coordinates
