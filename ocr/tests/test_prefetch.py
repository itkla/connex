import hashlib
import http.client
import io
import tarfile
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

from ocr_service.models import MODEL_NAMES
from ocr_service.prefetch import (
    MODEL_ARTIFACTS,
    ModelArtifact,
    _download,
    _extract,
    _fetch,
    _validate_members,
)


class DownloadResponse(io.BytesIO):
    def __init__(self, content: bytes, url: str, content_length: str | None = None) -> None:
        super().__init__(content)
        self._url = url
        self.headers = {} if content_length is None else {"Content-Length": content_length}

    def geturl(self) -> str:
        return self._url


class TruncatedResponse(DownloadResponse):
    def read(self, size: int = -1) -> bytes:
        raise http.client.IncompleteRead(b"partial")


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
        artifact = ModelArtifact(
            name="test-model", size=len(content), sha256=hashlib.sha256(content).hexdigest()
        )
        cases = (
            ("length", artifact.url, len(content) + 1, artifact.sha256, "length changed"),
            ("hash", artifact.url, len(content), "0" * 64, "integrity check failed"),
            ("host", "https://example.test/model.tar", len(content), artifact.sha256,
             "left the trusted host"),
        )
        for name, url, length, checksum, error in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                response = DownloadResponse(content, url, str(length))
                pinned = ModelArtifact(name=artifact.name, size=artifact.size, sha256=checksum)
                with patch("ocr_service.prefetch._MODEL_OPENER.open", return_value=response):
                    with self.assertRaisesRegex(RuntimeError, error):
                        _download(pinned, Path(temporary) / "model.tar")


class ModelFetchRetryTest(unittest.TestCase):
    content = b"pinned model"
    artifact = ModelArtifact(
        name="test-model",
        size=len(content),
        sha256="3e81645fd76fce1e1888a9258bfa81df8fd9cb8fb2ac1d1607d44fee4927b921",
    )

    def test_transport_failures_retry_and_leave_no_partial_archive(self) -> None:
        attempts = [
            urllib.error.URLError(OSError(101, "Network is unreachable")),
            TruncatedResponse(self.content, self.artifact.url, str(len(self.content))),
            DownloadResponse(self.content, self.artifact.url, str(len(self.content))),
        ]
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "model.tar"
            with self._opener(attempts) as opener:
                _fetch(self.artifact, destination)

            self.assertEqual(3, opener.call_count)
            self.assertEqual(self.content, destination.read_bytes())

    def test_retries_stay_bounded(self) -> None:
        failure = urllib.error.URLError(OSError(101, "Network is unreachable"))
        with tempfile.TemporaryDirectory() as temporary:
            with self._opener([failure] * 8) as opener:
                with self.assertRaises(urllib.error.URLError):
                    _fetch(self.artifact, Path(temporary) / "model.tar")

            self.assertEqual(4, opener.call_count)

    def test_verification_failures_are_never_retried(self) -> None:
        cases = {
            "length": DownloadResponse(
                self.content, self.artifact.url, str(len(self.content) + 1)
            ),
            "host": DownloadResponse(
                self.content, "https://example.test/model.tar", str(len(self.content))
            ),
            "hash": DownloadResponse(
                b"t" * len(self.content), self.artifact.url, str(len(self.content))
            ),
        }
        for name, response in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                good = DownloadResponse(
                    self.content, self.artifact.url, str(len(self.content))
                )
                with self._opener([response, good]) as opener:
                    with self.assertRaises(RuntimeError):
                        _fetch(self.artifact, Path(temporary) / "model.tar")

                self.assertEqual(1, opener.call_count)

    def test_a_body_that_ends_early_is_retried_and_still_verified(self) -> None:
        short = DownloadResponse(
            self.content[:4], self.artifact.url, str(len(self.content))
        )
        good = DownloadResponse(self.content, self.artifact.url, str(len(self.content)))
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "model.tar"
            with self._opener([short, good]) as opener:
                _fetch(self.artifact, destination)

            self.assertEqual(2, opener.call_count)
            self.assertEqual(self.content, destination.read_bytes())

    def test_a_body_that_never_completes_fails_closed(self) -> None:
        attempts = [
            DownloadResponse(self.content[:4], self.artifact.url, str(len(self.content)))
            for _ in range(4)
        ]
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "model.tar"
            with self._opener(attempts) as opener:
                with self.assertRaises(OSError):
                    _fetch(self.artifact, destination)

            self.assertEqual(4, opener.call_count)
            self.assertFalse(destination.exists())

    def test_client_errors_are_not_retried_but_server_errors_are(self) -> None:
        cases = ((404, 1), (429, 2), (503, 2))
        for status, expected in cases:
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                failure = urllib.error.HTTPError(
                    self.artifact.url, status, "rejected", {}, None
                )
                good = DownloadResponse(
                    self.content, self.artifact.url, str(len(self.content))
                )
                destination = Path(temporary) / "model.tar"
                with self._opener([failure, good]) as opener:
                    if expected == 1:
                        with self.assertRaises(urllib.error.HTTPError):
                            _fetch(self.artifact, destination)
                    else:
                        _fetch(self.artifact, destination)

                self.assertEqual(expected, opener.call_count)

    def _opener(self, attempts: list[object]):
        patcher = patch("ocr_service.prefetch._MODEL_OPENER.open", side_effect=attempts)
        self.addCleanup(patch.stopall)
        patch("ocr_service.prefetch.time.sleep").start()
        return patcher


if __name__ == "__main__":
    unittest.main()
