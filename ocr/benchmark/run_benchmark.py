import argparse
import json
import math
import os
import time
import unicodedata
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("manifest.json"))
    parser.add_argument("--images", type=Path, default=Path(__file__).with_name("generated"))
    parser.add_argument("--report", type=Path)
    arguments = parser.parse_args()
    manifest = json.loads(arguments.manifest.read_text(encoding="utf-8"))
    configuration = environment()
    outcomes = [run_case(case, arguments.images, configuration) for case in manifest["cases"]]
    report = summarize(outcomes)
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
        "workspace_id": "CONNEX_BENCHMARK_WORKSPACE_ID",
    }
    values: dict[str, str] = {}
    for key, name in names.items():
        value = os.environ.get(name, "").strip()
        if not value:
            raise ValueError(f"{name} is required")
        values[key] = value
    return values


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
            "X-XSRF-TOKEN": configuration["csrf_token"],
            "X-Workspace-Id": configuration["workspace_id"],
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
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


def summarize(outcomes: list[dict[str, object]]) -> dict[str, object]:
    total = len(outcomes)
    accuracy = {
        field: sum(1 for outcome in outcomes if outcome["correct"][field]) / total
        for field in ("name", "email", "phone", "title", "company")
    }
    latencies = sorted(float(outcome["latencySeconds"]) for outcome in outcomes)
    p95 = latencies[max(0, math.ceil(len(latencies) * 0.95) - 1)]
    return {
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


if __name__ == "__main__":
    raise SystemExit(main())
