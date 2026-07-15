import hashlib
import shutil
import tarfile
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

from .models import MODEL_NAMES, REQUIRED_MODEL_FILES, model_directory_ready, model_root


_MODEL_HOST = "paddle-model-ecology.bj.bcebos.com"
_MODEL_BASE_URL = f"https://{_MODEL_HOST}/paddlex/official_inference_model/paddle3.0.0"
_MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
_MAX_EXTRACTED_BYTES = 128 * 1024 * 1024
_MAX_ARCHIVE_MEMBERS = 64


@dataclass(frozen=True)
class ModelArtifact:
    name: str
    size: int
    sha256: str

    @property
    def url(self) -> str:
        return f"{_MODEL_BASE_URL}/{self.name}_infer.tar"


MODEL_ARTIFACTS = (
    ModelArtifact(
        name="PP-LCNet_x1_0_doc_ori",
        size=6_881_280,
        sha256="282337df5c41f7cdf8dacd5acf71fddfdc10218399f4b318463c17f4eae96c97",
    ),
    ModelArtifact(
        name="PP-LCNet_x0_25_textline_ori",
        size=1_095_680,
        sha256="268d9aea61461c3d4a5a32752e5b920e8af2ee0a002362f6ba5cd39638fa2c3a",
    ),
    ModelArtifact(
        name="PP-OCRv6_small_det",
        size=10_055_680,
        sha256="bfb7c1e59f0faa6b540ebdca93aea3f4b1f2477805b389fbee117820d68fe9f5",
    ),
    ModelArtifact(
        name="PP-OCRv6_small_rec",
        size=21_442_560,
        sha256="da460f968ce9f88325ac3a34fa302077d6e9b0dcefb16ba3137cd7796f879d06",
    ),
)


def main() -> None:
    if {artifact.name for artifact in MODEL_ARTIFACTS} != set(MODEL_NAMES):
        raise RuntimeError("Pinned model artifacts do not match the runtime model set")
    root = model_root()
    root.mkdir(parents=True, exist_ok=True)
    for artifact in MODEL_ARTIFACTS:
        _install(artifact, root)


def _install(artifact: ModelArtifact, root: Path) -> None:
    destination = root / artifact.name
    if destination.exists():
        raise RuntimeError(f"Model directory already exists: {artifact.name}")
    with tempfile.TemporaryDirectory(prefix=".prefetch-", dir=root) as temporary:
        temporary_root = Path(temporary)
        archive_path = temporary_root / f"{artifact.name}.tar"
        _download(artifact, archive_path)
        extracted_root = temporary_root / "extracted"
        extracted_root.mkdir()
        source = _extract(artifact, archive_path, extracted_root)
        if not model_directory_ready(source):
            raise RuntimeError(f"Model archive is incomplete: {artifact.name}")
        source.rename(destination)


def _download(artifact: ModelArtifact, destination: Path) -> None:
    if artifact.size <= 0 or artifact.size > _MAX_ARCHIVE_BYTES:
        raise RuntimeError(f"Model archive size is invalid: {artifact.name}")
    request = urllib.request.Request(
        artifact.url,
        headers={"User-Agent": "Connex-OCR-model-prefetch/1"},
    )
    digest = hashlib.sha256()
    received = 0
    with urllib.request.urlopen(request, timeout=60) as response:
        final_url = urlparse(response.geturl())
        if final_url.scheme != "https" or final_url.hostname != _MODEL_HOST:
            raise RuntimeError(f"Model download left the trusted host: {artifact.name}")
        content_length = response.headers.get("Content-Length")
        if content_length is not None and content_length != str(artifact.size):
            raise RuntimeError(f"Model archive length changed: {artifact.name}")
        with destination.open("xb") as output:
            while chunk := response.read(1024 * 1024):
                received += len(chunk)
                if received > artifact.size:
                    raise RuntimeError(f"Model archive exceeds its pinned size: {artifact.name}")
                digest.update(chunk)
                output.write(chunk)
    if received != artifact.size or digest.hexdigest() != artifact.sha256:
        raise RuntimeError(f"Model archive integrity check failed: {artifact.name}")


def _extract(artifact: ModelArtifact, archive_path: Path, destination: Path) -> Path:
    expected_root = f"{artifact.name}_infer"
    expected_files = {f"{expected_root}/{filename}" for filename in REQUIRED_MODEL_FILES}
    with tarfile.open(archive_path, mode="r:") as archive:
        members = archive.getmembers()
        _validate_members(expected_root, members)
        for member in members:
            relative = PurePosixPath(member.name)
            if relative.as_posix() not in expected_files:
                continue
            target = destination.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                raise RuntimeError(f"Model archive entry is unreadable: {artifact.name}")
            with source, target.open("xb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
    return destination / expected_root


def _validate_members(expected_root: str, members: list[tarfile.TarInfo]) -> None:
    if not members or len(members) > _MAX_ARCHIVE_MEMBERS:
        raise RuntimeError("Model archive contains an invalid number of entries")
    names: set[str] = set()
    total_size = 0
    for member in members:
        relative = PurePosixPath(member.name)
        if (
            relative.is_absolute()
            or not relative.parts
            or relative.parts[0] != expected_root
            or any(part in {"", ".", ".."} for part in relative.parts)
            or not (member.isdir() or member.isfile())
            or member.size < 0
        ):
            raise RuntimeError("Model archive contains an unsafe entry")
        normalized = relative.as_posix().rstrip("/")
        if normalized in names:
            raise RuntimeError("Model archive contains duplicate entries")
        names.add(normalized)
        total_size += member.size
        if total_size > _MAX_EXTRACTED_BYTES:
            raise RuntimeError("Model archive expands beyond its allowed size")
    expected_files = {f"{expected_root}/{filename}" for filename in REQUIRED_MODEL_FILES}
    if not expected_files.issubset(names):
        raise RuntimeError("Model archive is missing required inference files")


if __name__ == "__main__":
    main()
