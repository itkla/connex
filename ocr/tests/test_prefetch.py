import io
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ocr_service.models import MODEL_NAMES
from ocr_service.prefetch import (
    MODEL_ARTIFACTS,
    ModelArtifact,
    _download,
    _extract,
    _validate_members,
)


class DownloadResponse(io.BytesIO):
    def __init__(self, content: bytes, url: str, content_length: str | None = None) -> None:
        super().__init__(content)
        self._url = url
        self.headers = {} if content_length is None else {"Content-Length": content_length}

    def geturl(self) -> str:
        return self._url


class ModelArchiveTest(unittest.TestCase):
    artifact = ModelArtifact(name="test-model", size=1, sha256="0" * 64)

    def test_extracts_only_complete_single_root_model_archives(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive_path = root / "model.tar"
            self._write_archive(archive_path, {
                "test-model_infer/inference.json": b"{}",
                "test-model_infer/inference.pdiparams": b"parameters",
                "test-model_infer/inference.yml": b"model: test",
            })
            destination = root / "destination"
            destination.mkdir()

            extracted = _extract(self.artifact, archive_path, destination)

            self.assertEqual(b"parameters", (extracted / "inference.pdiparams").read_bytes())

    def test_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive_path = root / "model.tar"
            self._write_archive(archive_path, {
                "test-model_infer/inference.json": b"{}",
                "test-model_infer/inference.pdiparams": b"parameters",
                "test-model_infer/inference.yml": b"model: test",
                "test-model_infer/../../outside": b"unsafe",
            })
            destination = root / "destination"
            destination.mkdir()

            with self.assertRaisesRegex(RuntimeError, "unsafe entry"):
                _extract(self.artifact, archive_path, destination)

            self.assertFalse((root / "outside").exists())

    def test_rejects_archives_without_required_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive_path = root / "model.tar"
            self._write_archive(archive_path, {
                "test-model_infer/inference.json": b"{}",
                "test-model_infer/inference.yml": b"model: test",
            })
            destination = root / "destination"
            destination.mkdir()

            with self.assertRaisesRegex(RuntimeError, "missing required"):
                _extract(self.artifact, archive_path, destination)

    def test_rejects_links_duplicate_entries_and_wrong_roots(self) -> None:
        link = tarfile.TarInfo("test-model_infer/link")
        link.type = tarfile.SYMTYPE
        link.linkname = "inference.json"
        duplicate = tarfile.TarInfo("test-model_infer/inference.json")
        wrong_root = tarfile.TarInfo("other-model/inference.json")

        with self.assertRaisesRegex(RuntimeError, "unsafe entry"):
            _validate_members("test-model_infer", [link])
        with self.assertRaisesRegex(RuntimeError, "duplicate entries"):
            _validate_members("test-model_infer", [duplicate, duplicate])
        with self.assertRaisesRegex(RuntimeError, "unsafe entry"):
            _validate_members("test-model_infer", [wrong_root])

    def test_rejects_archives_that_expand_beyond_the_limit(self) -> None:
        member = tarfile.TarInfo("test-model_infer/inference.json")
        member.size = 129 * 1024 * 1024

        with self.assertRaisesRegex(RuntimeError, "expands beyond"):
            _validate_members("test-model_infer", [member])

    def _write_archive(self, path: Path, files: dict[str, bytes]) -> None:
        with tarfile.open(path, mode="w") as archive:
            for name, content in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))


class ModelDownloadTest(unittest.TestCase):
    def test_runtime_models_and_pinned_artifacts_remain_in_sync(self) -> None:
        self.assertEqual(set(MODEL_NAMES), {artifact.name for artifact in MODEL_ARTIFACTS})

    def test_download_accepts_only_the_pinned_content(self) -> None:
        content = b"pinned model"
        artifact = ModelArtifact(
            name="test-model",
            size=len(content),
            sha256="3e81645fd76fce1e1888a9258bfa81df8fd9cb8fb2ac1d1607d44fee4927b921",
        )
        response = DownloadResponse(content, artifact.url, str(len(content)))
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "model.tar"
            with patch("ocr_service.prefetch._MODEL_OPENER.open", return_value=response):
                _download(artifact, destination)

            self.assertEqual(content, destination.read_bytes())

    def test_download_rejects_changed_length_hash_and_host(self) -> None:
        content = b"model"
        artifact = ModelArtifact(name="test-model", size=len(content), sha256="0" * 64)
        cases = (
            DownloadResponse(content, artifact.url, str(len(content) + 1)),
            DownloadResponse(content, artifact.url, str(len(content))),
            DownloadResponse(content, "https://example.test/model.tar", str(len(content))),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index, response in enumerate(cases):
                with patch("ocr_service.prefetch._MODEL_OPENER.open", return_value=response):
                    with self.assertRaises(RuntimeError):
                        _download(artifact, root / f"model-{index}.tar")


if __name__ == "__main__":
    unittest.main()
