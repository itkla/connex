import argparse
import hashlib
import ipaddress
import json
import math
import os
import platform
import re
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise urllib.error.HTTPError(request.full_url, code, "Benchmark redirects are disabled", headers, file_pointer)


def benchmark_opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects)


BENCHMARK_OPENER = benchmark_opener()
CANONICAL_MANIFEST_SHA256 = "a641d9af0c946a03753606b88924fc9ac5ed0c58a2678ee9e960adc07fd84d87"
CANONICAL_FONT_SHA256 = "68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5"
CANONICAL_FIXTURES_SHA256 = "bfff98a022ded013b42d2313f75c2ec6e5fc7632c1926adea6274ca0172899e5"
CANONICAL_IMAGE_NAMES = {
    "backend": "ghcr.io/itkla/connex-backend",
    "frontend": "ghcr.io/itkla/connex-frontend",
    "ocr": "ghcr.io/itkla/connex-ocr",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", type=Path, default=Path(__file__).with_name("generated"))
    parser.add_argument("--report", type=Path)
    parser.add_argument(
        "--requests-per-minute",
        type=positive_integer,
        default=positive_integer(os.environ.get("CONNEX_BENCHMARK_REQUESTS_PER_MINUTE", "3")),
    )
    arguments = parser.parse_args()
    manifest_path = Path(__file__).with_name("manifest.json")
    manifest = canonical_manifest(manifest_path)
    configuration = environment()
    source_revision = repository_revision()
    runtime = runtime_provenance()
    outcomes: list[dict[str, object]] = []
    last_started: float | None = None
    interval = 60 / arguments.requests_per_minute
    for case in manifest["cases"]:
        if last_started is not None:
            time.sleep(max(0, interval - (time.monotonic() - last_started)))
        last_started = time.monotonic()
        outcomes.append(run_case(case, arguments.images, configuration))
    report = summarize(
        outcomes,
        provenance(
            manifest_path,
            arguments.images,
            manifest,
            configuration,
            arguments.requests_per_minute,
            source_revision,
            runtime,
        ),
    )
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if arguments.report is not None:
        arguments.report.write_text(rendered + "\n", encoding="utf-8")
    gates = report["gates"]
    return 0 if all(gate["passed"] for gate in gates.values()) else 1


def environment() -> dict[str, str]:
    names = {
        "base_url": "CONNEX_BENCHMARK_BASE_URL",
        "session_cookie": "CONNEX_BENCHMARK_SESSION_COOKIE",
        "csrf_token": "CONNEX_BENCHMARK_CSRF_TOKEN",
        "csrf_header": "CONNEX_BENCHMARK_CSRF_HEADER",
        "workspace_id": "CONNEX_BENCHMARK_WORKSPACE_ID",
    }
    values: dict[str, str] = {}
    for key, name in names.items():
        value = os.environ.get(name, "").strip()
        if not value:
            raise ValueError(f"{name} is required")
        values[key] = value
    values["base_url"] = validated_base_url(values["base_url"])
    if values["csrf_header"] not in {"X-CSRF-TOKEN", "X-XSRF-TOKEN"}:
        raise ValueError("CONNEX_BENCHMARK_CSRF_HEADER is invalid")
    return values


def positive_integer(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def validated_base_url(value: str) -> str:
    if any(character.isspace() or ord(character) < 32 for character in value):
        raise ValueError("CONNEX_BENCHMARK_BASE_URL must not contain whitespace or control characters")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in {"http", "https"} or parsed.hostname is None:
        raise ValueError("CONNEX_BENCHMARK_BASE_URL must be an absolute HTTP or HTTPS URL")
    if parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment:
        raise ValueError("CONNEX_BENCHMARK_BASE_URL must not contain credentials, a query, or a fragment")
    try:
        parsed.port
    except ValueError as exception:
        raise ValueError("CONNEX_BENCHMARK_BASE_URL contains an invalid port") from exception
    if parsed.scheme == "http" and not loopback_host(parsed.hostname):
        raise ValueError("CONNEX_BENCHMARK_BASE_URL requires HTTPS unless it targets loopback")
    return value.rstrip("/")


def loopback_host(hostname: str) -> bool:
    if hostname.casefold() == "localhost":
        return True
    try:
        return ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        return False


def validated_source_revision(value: str) -> str:
    if re.fullmatch(r"[0-9a-fA-F]{40}", value) is None:
        raise ValueError("CONNEX_BENCHMARK_SOURCE_REVISION must be a full 40-character commit SHA")
    return value.lower()


def validated_image_reference(value: str, name: str) -> str:
    digest = value.rsplit("@", maxsplit=1)[-1]
    if re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest) is None:
        raise ValueError(f"{name} must identify an immutable sha256 image digest")
    return value


def canonical_manifest(path: Path) -> dict[str, object]:
    if file_sha256(path) != CANONICAL_MANIFEST_SHA256:
        raise ValueError("Canonical benchmark manifest hash does not match the reviewed suite")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("Canonical benchmark manifest must be an object")
    cases = manifest.get("cases")
    if manifest.get("schemaVersion") != 1 or not isinstance(cases, list) or len(cases) != 40:
        raise ValueError("Canonical benchmark manifest must contain exactly 40 version-one cases")
    case_ids = [case.get("id") for case in cases if isinstance(case, dict)]
    if len(case_ids) != 40 or any(not isinstance(case_id, str) for case_id in case_ids):
        raise ValueError("Every canonical benchmark case must have a string id")
    if len(set(case_ids)) != 40:
        raise ValueError("Canonical benchmark case ids must be unique")
    return manifest


def repository_revision() -> str:
    repository = Path(__file__).parents[2]
    revision = command_output(["git", "rev-parse", "HEAD"], repository)
    for arguments in (
        ["git", "diff", "--quiet", "--", "ocr/benchmark", "ocr/requirements.lock"],
        ["git", "diff", "--cached", "--quiet", "--", "ocr/benchmark", "ocr/requirements.lock"],
    ):
        result = subprocess.run(arguments, cwd=repository, check=False)
        if result.returncode != 0:
            raise ValueError("Benchmark sources and dependency lock must match the checked-out commit")
    return validated_source_revision(revision)


def runtime_provenance() -> dict[str, object]:
    containers: dict[str, object] = {}
    for component in ("backend", "frontend", "ocr"):
        variable = f"CONNEX_BENCHMARK_{component.upper()}_CONTAINER"
        container_id = os.environ.get(variable, "").strip()
        if re.fullmatch(r"[0-9a-fA-F]{12,64}", container_id) is None:
            raise ValueError(f"{variable} must be a Docker container id")
        containers[component] = inspect_runtime_container(component, container_id)
    return {
        "containers": containers,
        "host": {
            "avx": require_host_avx(),
            "machine": platform.machine(),
            "platform": platform.platform(),
            "python": sys.version.split()[0],
            "dockerServer": command_output(
                ["docker", "version", "--format", "{{.Server.Version}}"],
                Path(__file__).parents[2],
            ),
        },
    }


def require_host_avx() -> bool:
    processors = Path("/proc/cpuinfo").read_text(encoding="utf-8").strip().split("\n\n")
    flag_sets = []
    for processor in processors:
        for line in processor.splitlines():
            name, separator, value = line.partition(":")
            if separator and name.strip() in {"flags", "Features"}:
                flag_sets.append(set(value.split()))
                break
    if not flag_sets or any("avx" not in flags for flags in flag_sets):
        raise ValueError("Benchmark qualification requires AVX on every visible processor")
    return True


def inspect_runtime_container(component: str, container_id: str) -> dict[str, object]:
    raw = command_output(
        ["docker", "inspect", "--type", "container", container_id],
        Path(__file__).parents[2],
    )
    decoded = json.loads(raw)
    if not isinstance(decoded, list) or len(decoded) != 1 or not isinstance(decoded[0], dict):
        raise ValueError(f"Could not inspect the {component} benchmark container")
    inspection = decoded[0]
    config = inspection.get("Config")
    state = inspection.get("State")
    host_config = inspection.get("HostConfig")
    network_settings = inspection.get("NetworkSettings")
    if not all(isinstance(value, dict) for value in (config, state, host_config, network_settings)):
        raise ValueError(f"The {component} benchmark container inspection is incomplete")
    image_reference = config.get("Image")
    expected_prefix = CANONICAL_IMAGE_NAMES[component] + "@"
    if not isinstance(image_reference, str) or not image_reference.startswith(expected_prefix):
        raise ValueError(f"The {component} benchmark container uses an unexpected image repository")
    validated_image_reference(image_reference, component)
    image_id = inspection.get("Image")
    if not isinstance(image_id, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", image_id) is None:
        raise ValueError(f"The {component} benchmark container image id is invalid")
    if state.get("Running") is not True:
        raise ValueError(f"The {component} benchmark container is not running")
    health = state.get("Health")
    if component == "ocr" and (not isinstance(health, dict) or health.get("Status") != "healthy"):
        raise ValueError("The OCR benchmark container is not healthy")
    if component == "ocr":
        ports = network_settings.get("Ports")
        if ports is not None and not isinstance(ports, dict):
            raise ValueError("The OCR benchmark container port inspection is invalid")
        published_ports = [] if ports is None else [value for value in ports.values() if value is not None]
        cap_drop = host_config.get("CapDrop")
        if (
            host_config.get("Memory") != 2_147_483_648
            or host_config.get("MemorySwap") != 2_147_483_648
            or host_config.get("NanoCpus") != 2_000_000_000
            or host_config.get("PidsLimit") != 128
            or host_config.get("ReadonlyRootfs") is not True
            or not isinstance(cap_drop, list)
            or "ALL" not in cap_drop
            or published_ports
        ):
            raise ValueError("The OCR benchmark container does not match the qualified resource and isolation profile")
    return {
        "containerId": container_id.lower(),
        "imageReference": image_reference,
        "imageId": image_id,
    }


def command_output(arguments: list[str], working_directory: Path) -> str:
    result = subprocess.run(
        arguments,
        cwd=working_directory,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return result.stdout.strip()


def run_case(case: dict[str, object], images: Path, configuration: dict[str, str]) -> dict[str, object]:
    image = (images / f"{case['id']}.jpg").read_bytes()
    boundary = "connex-benchmark-" + uuid.uuid4().hex
    body = multipart(boundary, image, f"{case['id']}.jpg")
    request = urllib.request.Request(
        configuration["base_url"].rstrip("/") + "/api/business-cards/scan",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Cookie": configuration["session_cookie"],
            configuration["csrf_header"]: configuration["csrf_token"],
            "X-Workspace-Id": configuration["workspace_id"],
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with BENCHMARK_OPENER.open(request, timeout=30) as response:
            payload = json.load(response)
            status = response.status
    except urllib.error.HTTPError as exception:
        payload = {"error": exception.read().decode("utf-8", errors="replace")[:512]}
        status = exception.code
    latency = time.perf_counter() - started
    expected = case["fields"]
    assert isinstance(expected, dict)
    scores = {
        field: status == 200 and equal(field, expected[field], candidate(payload, field))
        for field in ("name", "email", "phone", "title", "company")
    }
    return {
        "id": case["id"],
        "language": case["language"],
        "layout": case["layout"],
        "condition": case["condition"],
        "status": status,
        "latencySeconds": round(latency, 4),
        "correct": scores,
    }


def multipart(boundary: str, image: bytes, file_name: str) -> bytes:
    prefix = (
        f"--{boundary}\r\n"
        f"Content-Disposition: form-data; name=\"image\"; filename=\"{file_name}\"\r\n"
        "Content-Type: image/jpeg\r\n\r\n"
    ).encode("ascii")
    suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
    return prefix + image + suffix


def candidate(payload: object, name: str) -> object:
    if not isinstance(payload, dict):
        return None
    container = payload.get("company") if name == "company" else payload.get("fields", {})
    if not isinstance(container, dict):
        return None
    value = container if name == "company" else container.get(name)
    return value.get("value") if isinstance(value, dict) else None


def equal(field: str, expected: object, actual: object) -> bool:
    if not isinstance(expected, str) or not isinstance(actual, str):
        return False
    if field == "phone":
        return "".join(character for character in expected if character.isdigit()) == "".join(
            character for character in actual if character.isdigit()
        )
    return normalize(expected) == normalize(actual)


def normalize(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def provenance(
    manifest_path: Path,
    images: Path,
    manifest: dict[str, object],
    configuration: dict[str, str],
    requests_per_minute: int,
    source_revision: str,
    runtime: dict[str, object],
) -> dict[str, object]:
    cases = manifest.get("cases")
    if not isinstance(cases, list):
        raise ValueError("Benchmark manifest cases must be a list")
    fixture_digest = hashlib.sha256()
    for case in sorted(cases, key=lambda item: str(item.get("id")) if isinstance(item, dict) else ""):
        if not isinstance(case, dict) or not isinstance(case.get("id"), str):
            raise ValueError("Every benchmark case must have a string id")
        case_id = case["id"]
        fixture_digest.update(case_id.encode("utf-8"))
        fixture_digest.update(b"\0")
        fixture_digest.update((images / f"{case_id}.jpg").read_bytes())
    if fixture_digest.hexdigest() != CANONICAL_FIXTURES_SHA256:
        raise ValueError("Benchmark fixtures do not match the reviewed canonical set")
    requirements_lock = Path(__file__).parents[1] / "requirements.lock"
    fixture_metadata = validated_fixture_set(images, cases)
    return {
        "sourceRevision": source_revision,
        "runtime": runtime,
        "baseUrl": configuration["base_url"],
        "requestsPerMinute": requests_per_minute,
        "manifestSha256": file_sha256(manifest_path),
        "fixturesSha256": fixture_digest.hexdigest(),
        "fixtureMetadataSha256": file_sha256(images / "fixtures.json"),
        "fixtureGeneratorSha256": fixture_metadata["generatorSha256"],
        "fixtureFontSha256": fixture_metadata["fontSha256"],
        "ocrRequirementsLockSha256": file_sha256(requirements_lock),
    }


def validated_fixture_set(images: Path, cases: list[object]) -> dict[str, object]:
    metadata_path = images / "fixtures.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if not isinstance(metadata, dict) or metadata.get("schemaVersion") != 1:
        raise ValueError("Benchmark fixture metadata is invalid")
    generator_path = Path(__file__).with_name("generate_cards.py")
    expected = {
        "manifestSha256": CANONICAL_MANIFEST_SHA256,
        "generatorSha256": file_sha256(generator_path),
        "fontSha256": CANONICAL_FONT_SHA256,
        "fixturesSha256": CANONICAL_FIXTURES_SHA256,
    }
    for name, value in expected.items():
        if metadata.get(name) != value:
            raise ValueError(f"Benchmark fixture metadata {name} does not match the canonical input")
    listed = metadata.get("cases")
    if not isinstance(listed, list) or len(listed) != 40:
        raise ValueError("Benchmark fixture metadata must describe exactly 40 cases")
    canonical_ids = [case.get("id") for case in cases if isinstance(case, dict)]
    listed_ids = [item.get("id") for item in listed if isinstance(item, dict)]
    if listed_ids != canonical_ids:
        raise ValueError("Benchmark fixture order and ids do not match the canonical suite")
    expected_files = {"fixtures.json"}
    for item in listed:
        if not isinstance(item, dict):
            raise ValueError("Benchmark fixture metadata entry is invalid")
        case_id = item.get("id")
        sha256 = item.get("sha256")
        size = item.get("size")
        if not isinstance(case_id, str) or re.fullmatch(r"[0-9a-f]{64}", str(sha256)) is None:
            raise ValueError("Benchmark fixture metadata entry is invalid")
        image_path = images / f"{case_id}.jpg"
        if not isinstance(size, int) or size <= 0 or image_path.stat().st_size != size:
            raise ValueError(f"Benchmark fixture size does not match for {case_id}")
        if file_sha256(image_path) != sha256:
            raise ValueError(f"Benchmark fixture hash does not match for {case_id}")
        expected_files.add(image_path.name)
    actual_files = {path.name for path in images.iterdir() if path.is_file()}
    if actual_files != expected_files:
        raise ValueError("Benchmark fixture directory contains missing or unexpected files")
    return metadata


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def summarize(outcomes: list[dict[str, object]], benchmark_provenance: dict[str, object] | None = None) -> dict[str, object]:
    total = len(outcomes)
    accuracy = {
        field: sum(1 for outcome in outcomes if outcome["correct"][field]) / total
        for field in ("name", "email", "phone", "title", "company")
    }
    latencies = sorted(float(outcome["latencySeconds"]) for outcome in outcomes)
    p95 = latencies[max(0, math.ceil(len(latencies) * 0.95) - 1)]
    report = {
        "caseCount": total,
        "accuracy": {key: round(value, 4) for key, value in accuracy.items()},
        "p95LatencySeconds": round(p95, 4),
        "gates": {
            "email": {"threshold": 0.95, "actual": round(accuracy["email"], 4), "passed": accuracy["email"] >= 0.95},
            "phone": {"threshold": 0.95, "actual": round(accuracy["phone"], 4), "passed": accuracy["phone"] >= 0.95},
            "name": {"threshold": 0.85, "actual": round(accuracy["name"], 4), "passed": accuracy["name"] >= 0.85},
            "title": {"threshold": 0.8, "actual": round(accuracy["title"], 4), "passed": accuracy["title"] >= 0.8},
            "company": {"threshold": 0.8, "actual": round(accuracy["company"], 4), "passed": accuracy["company"] >= 0.8},
            "p95Latency": {"thresholdSeconds": 8, "actualSeconds": round(p95, 4), "passed": p95 <= 8},
        },
        "cases": outcomes,
    }
    if benchmark_provenance is not None:
        report["provenance"] = benchmark_provenance
    return report


if __name__ == "__main__":
    raise SystemExit(main())
