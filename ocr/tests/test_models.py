import tempfile
import unittest
from pathlib import Path

from ocr_service.models import model_directory_ready, models_ready


class ModelReadinessTest(unittest.TestCase):
    def test_requires_every_nonempty_inference_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "inference.json").write_text("{}", encoding="utf-8")
            (directory / "inference.pdiparams").write_bytes(b"parameters")
            (directory / "inference.yml").write_text("model: test", encoding="utf-8")

            self.assertTrue(model_directory_ready(directory))

            (directory / "inference.yml").write_bytes(b"")
            self.assertFalse(model_directory_ready(directory))

    def test_requires_all_configured_model_directories(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = {"first": root / "first", "second": root / "second"}
            for path in paths.values():
                path.mkdir()
                (path / "inference.json").write_text("{}", encoding="utf-8")
                (path / "inference.pdiparams").write_bytes(b"parameters")
                (path / "inference.yml").write_text("model: test", encoding="utf-8")

            self.assertTrue(models_ready(paths))

            (paths["second"] / "inference.json").unlink()
            self.assertFalse(models_ready(paths))


if __name__ == "__main__":
    unittest.main()
