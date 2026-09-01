import json
import os
import subprocess
import sys
import time
from pathlib import Path

from .signatures import read_container_header


FRESHCLAM_BINARY = "/usr/bin/freshclam"
SIGTOOL_BINARY = "/usr/bin/sigtool"
FRESHCLAM_CONFIG = "/etc/clamav/freshclam.conf"
DATABASE_DIRECTORY = Path("/var/lib/clamav")
MANIFEST_PATH = DATABASE_DIRECTORY / "connex-signature-manifest.json"

REQUIRED_CONTAINERS = ("main.cvd", "daily.cvd", "bytecode.cvd")
MAX_ATTEMPTS = 5
BACKOFF_SECONDS = 20
FRESHCLAM_TIMEOUT_SECONDS = 900
SIGTOOL_TIMEOUT_SECONDS = 300
VERIFICATION_MARKER = "Verification OK"


class PrefetchError(RuntimeError):
    pass


def main() -> int:
    try:
        download()
        manifest = verify()
    except PrefetchError as error:
        print(f"ClamAV signature prefetch failed: {error}", file=sys.stderr, flush=True)
        return 1
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(manifest, sort_keys=True), flush=True)
    return 0


def download() -> None:
    """Fetches the baseline signature set once, at image build time.

    A bounded retry is not politeness: database.clamav.net rate-limits shared CI egress, and a
    transient 429 must not be allowed to produce an image that ships without signatures. The
    build fails rather than degrading, because there is no safe partial outcome here.
    """
    DATABASE_DIRECTORY.mkdir(parents=True, exist_ok=True)
    last_error = ""
    for attempt in range(1, MAX_ATTEMPTS + 1):
        completed = subprocess.run(
            [FRESHCLAM_BINARY, "--config-file", FRESHCLAM_CONFIG, "--stdout"],
            capture_output=True,
            text=True,
            timeout=FRESHCLAM_TIMEOUT_SECONDS,
            check=False,
        )
        if completed.returncode == 0 and _containers_present():
            return
        last_error = (completed.stdout or "") + (completed.stderr or "")
        if attempt < MAX_ATTEMPTS:
            time.sleep(BACKOFF_SECONDS * attempt)
    raise PrefetchError(f"freshclam did not produce a complete signature set: {last_error[-500:]}")


def verify() -> dict[str, object]:
    """Proves every baked container carries a valid ClamAV digital signature.

    Signature databases are the one artifact class where pinning a SHA-256 is the wrong integrity
    mechanism: daily.cvd is republished several times a day, so a pinned digest would break the
    build within hours and pressure whoever hits it into removing the check. CVD containers are
    RSA-signed by the publisher and sigtool verifies that signature, which is both stronger and
    stable across republication.
    """
    manifest: dict[str, object] = {"containers": {}}
    for name in REQUIRED_CONTAINERS:
        path = DATABASE_DIRECTORY / name
        if not path.is_file():
            raise PrefetchError(f"missing signature container: {name}")
        completed = subprocess.run(
            [SIGTOOL_BINARY, "--info", str(path)],
            capture_output=True,
            text=True,
            timeout=SIGTOOL_TIMEOUT_SECONDS,
            check=False,
        )
        if completed.returncode != 0 or VERIFICATION_MARKER not in completed.stdout:
            raise PrefetchError(f"signature container failed verification: {name}")
        header = read_container_header(path)
        if header is None:
            raise PrefetchError(f"signature container header is unreadable: {name}")
        build_epoch, version = header
        manifest["containers"][name] = {
            "version": version,
            "build_epoch": build_epoch,
            "size": path.stat().st_size,
        }
    manifest["baked_at_epoch"] = int(time.time())
    manifest["clamav_version"] = _clamav_version()
    return manifest


def _containers_present() -> bool:
    return all((DATABASE_DIRECTORY / name).is_file() for name in REQUIRED_CONTAINERS)


def _clamav_version() -> str:
    completed = subprocess.run(
        [SIGTOOL_BINARY, "--version"],
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    return completed.stdout.strip() or "unknown"


if __name__ == "__main__":
    raise SystemExit(main())
