import io
import unittest

from ocr_service.config import ServiceConfig
from ocr_service.engine import PaddleEngine, _extract_lines


class Result:
    def __init__(self, payload: object) -> None:
        self.json = payload


class ArrayLike:
    def __init__(self, values: list[object]) -> None:
        self.values = values

    def tolist(self) -> list[object]:
        return self.values


class EngineResultTest(unittest.TestCase):
    def test_extracts_bounded_lines_from_paddle_result(self) -> None:
        results = [Result({"res": {
            "rec_texts": [" Ada   Lovelace ", "ada@example.test"],
            "rec_scores": [0.98, 0.96],
            "rec_boxes": [[10, 20, 210, 60], [10, 70, 240, 95]],
        }})]

        self.assertEqual([
            {"text": "Ada Lovelace", "confidence": 0.98, "box": [10, 20, 210, 60]},
            {"text": "ada@example.test", "confidence": 0.96, "box": [10, 70, 240, 95]},
        ], _extract_lines(results))

    def test_rejects_misaligned_result_arrays(self) -> None:
        results = [Result({
            "rec_texts": ["Ada Lovelace"],
            "rec_scores": [],
            "rec_boxes": [[10, 20, 210, 60]],
        })]

        with self.assertRaises(RuntimeError):
            _extract_lines(results)

    def test_rejects_invalid_coordinates(self) -> None:
        results = [Result({
            "rec_texts": ["Ada Lovelace"],
            "rec_scores": [0.98],
            "rec_boxes": [[210, 20, 10, 60]],
        })]

        with self.assertRaises(RuntimeError):
            _extract_lines(results)

    def test_accepts_array_like_numeric_outputs(self) -> None:
        results = [Result({
            "rec_texts": ["Ada Lovelace"],
            "rec_scores": ArrayLike([0.98]),
            "rec_boxes": ArrayLike([[10, 20, 210, 60]]),
        })]

        self.assertEqual([
            {"text": "Ada Lovelace", "confidence": 0.98, "box": [10, 20, 210, 60]}
        ], _extract_lines(results))


class ImageOrientationTest(unittest.TestCase):
    def test_applies_exif_orientation_before_dimension_bounds(self) -> None:
        from PIL import Image

        image = Image.new("RGB", (40, 20), "white")
        exif = Image.Exif()
        exif[274] = 6
        content = io.BytesIO()
        image.save(content, format="JPEG", exif=exif)
        config = ServiceConfig(
            host="127.0.0.1",
            port=8090,
            service_token="test-service-token-0000000000000000",
            max_image_bytes=1024,
            max_width=25,
            max_height=50,
            max_pixels=2_000,
            request_timeout_seconds=2,
        )
        engine = PaddleEngine.__new__(PaddleEngine)
        engine._config = config

        decoded = engine._decode(content.getvalue(), "image/jpeg")

        self.assertEqual((40, 20, 3), decoded.shape)


if __name__ == "__main__":
    unittest.main()
