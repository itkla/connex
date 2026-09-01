import time
from dataclasses import dataclass
from pathlib import Path


_HEADER_BYTES = 512
_HEADER_PREFIX = "ClamAV-VDB:"
_BUILD_EPOCH_FIELD = 8
_VERSION_FIELD = 2
_REQUIRED_STEMS = ("main", "daily")
_FRESHNESS_STEM = "daily"


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
    """Reports how far behind the signature set is, measured from the daily container.

    Every required container must be present, but only ``daily`` carries freshness. ``main`` is a
    consolidated base set that upstream republishes rarely -- it is routinely months or years old
    on a perfectly current install, and ``bytecode`` likewise. Treating the oldest container as the
    freshness signal would put a freshly built image instantly past the 30-day ceiling and refuse
    every upload, so age comes from ``daily`` alone while the others are checked for presence.
    """
    reference = time.time() if now is None else now
    headers: dict[str, tuple[int, str]] = {}
    for stem in _REQUIRED_STEMS:
        header = _newest_container(database_directory, stem)
        if header is None:
            return SignatureState(None, None, parse_daemon_version(daemon_version))
        headers[stem] = header
    build_epoch, version = headers[_FRESHNESS_STEM]
    age = max(0, int(reference - build_epoch))
    return SignatureState(age, version, parse_daemon_version(daemon_version))


def _newest_container(database_directory: Path, stem: str) -> tuple[int, str] | None:
    best: tuple[int, str] | None = None
    for suffix in (".cvd", ".cld"):
        header = read_container_header(database_directory / f"{stem}{suffix}")
        if header is None:
            continue
        if best is None or header[0] > best[0]:
            best = header
    return best
