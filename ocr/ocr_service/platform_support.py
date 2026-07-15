import platform
from pathlib import Path


_X86_64_MACHINES = frozenset({"amd64", "x86_64"})


def require_supported_cpu(
    machine: str | None = None,
    cpuinfo_path: Path = Path("/proc/cpuinfo"),
) -> None:
    selected_machine = platform.machine() if machine is None else machine
    if selected_machine.lower() not in _X86_64_MACHINES:
        raise RuntimeError("PaddleOCR requires an x86-64 CPU with AVX support")
    try:
        cpuinfo = cpuinfo_path.read_text(encoding="utf-8")
    except OSError as exception:
        raise RuntimeError("Unable to verify PaddleOCR CPU support") from exception
    if not _all_processors_support_avx(cpuinfo):
        raise RuntimeError("PaddleOCR requires AVX CPU support")


def _all_processors_support_avx(cpuinfo: str) -> bool:
    feature_sets = []
    for line in cpuinfo.splitlines():
        key, separator, value = line.partition(":")
        if separator and key.strip().lower() in {"flags", "features"}:
            feature_sets.append(frozenset(value.lower().split()))
    return bool(feature_sets) and all("avx" in features for features in feature_sets)
