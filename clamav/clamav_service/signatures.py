import time
from dataclasses import dataclass
from pathlib import Path


_HEADER_BYTES = 512
_HEADER_PREFIX = "ClamAV-VDB:"
_BUILD_EPOCH_FIELD = 8
_VERSION_FIELD = 2
_AGE_SENSITIVE_STEMS = ("daily", "main")


@dataclass(frozen=True)
class SignatureState:
    """Freshness of the signature set clamd is actually running against.

    ``age_seconds`` is ``None`` only when no readable database was found at all, which every
    caller must treat as at least as bad as expired -- never as fresh.
    """

    age_seconds: int | None
    database_version: str | None
    daemon_version: str | None

    def expired(self, max_age_seconds: int) -> bool:
        return self.age_seconds is None or self.age_seconds > max_age_seconds

    def stale(self, warn_age_seconds: int) -> bool:
        return self.age_seconds is None or self.age_seconds > warn_age_seconds

    def seconds_until_expiry(self, max_age_seconds: int) -> int:
        if self.age_seconds is None:
            return 0
        return max(0, max_age_seconds - self.age_seconds)


def read_container_header(path: Path) -> tuple[int, str] | None:
    try:
        with path.open("rb") as handle:
            header = handle.read(_HEADER_BYTES)
    except OSError:
        return None
    try:
        decoded = header.decode("ascii", errors="strict")
    except UnicodeDecodeError:
        return None
    if not decoded.startswith(_HEADER_PREFIX):
        return None
    fields = decoded.split(":")
    if len(fields) <= _BUILD_EPOCH_FIELD:
        return None
    try:
        build_epoch = int(fields[_BUILD_EPOCH_FIELD].strip())
    except ValueError:
        return None
    if build_epoch <= 0:
        return None
    return build_epoch, fields[_VERSION_FIELD].strip()


def parse_daemon_version(raw: str | None) -> str | None:
    if not raw:
        return None
    parts = raw.strip().split("/")
    if len(parts) < 2:
        return None
    candidate = parts[1].strip()
    return candidate if candidate.isdigit() else None


def inspect(database_directory: Path, daemon_version: str | None, now: float | None = None) -> SignatureState:
    """Reports the age of the oldest age-sensitive signature container on disk.

    The OLDEST is deliberate. A fresh ``daily`` sitting next to a ``main`` from two years ago
    still leaves the deployment behind on the base signature set, and reporting the newest file
    would let one recently-touched container mask that.
    """
    reference = time.time() if now is None else now
    oldest_age: int | None = None
    newest_version: str | None = None
    for stem in _AGE_SENSITIVE_STEMS:
        header = _newest_container(database_directory, stem)
        if header is None:
            return SignatureState(None, None, parse_daemon_version(daemon_version))
        build_epoch, version = header
        age = max(0, int(reference - build_epoch))
        oldest_age = age if oldest_age is None else max(oldest_age, age)
        if stem == "daily":
            newest_version = version
    return SignatureState(oldest_age, newest_version, parse_daemon_version(daemon_version))


def _newest_container(database_directory: Path, stem: str) -> tuple[int, str] | None:
    best: tuple[int, str] | None = None
    for suffix in (".cvd", ".cld"):
        header = read_container_header(database_directory / f"{stem}{suffix}")
        if header is None:
            continue
        if best is None or header[0] > best[0]:
            best = header
    return best
