import io
import json
import os
import urllib.error
import urllib.request
import zipfile


BASE_URL = "http://127.0.0.1:8091"
TOKEN = os.environ["CONNEX_CLAMAV_SERVICE_TOKEN"]
NESTING_DEPTH = 14


def eicar_bytes() -> bytes:
    """Assembles the EICAR standard anti-malware test file at runtime.

    EICAR is a harmless, published, non-malicious ASCII string that exists precisely so scanners
    can be proven to work without handling real malware. It is assembled from fragments rather
    than written as one literal so that endpoint protection on developer machines and CI runners
    does not quarantine this source file and break the checkout.
    """
    return (
        "X5O!P%@AP[4"
        "\\PZX54(P^)"
        "7CC)7}$EICAR"
        "-STANDARD-ANTIVIRUS-"
        "TEST-FILE!$H+H*"
    ).encode("ascii")


def deeply_nested_archive() -> bytes:
    payload = b"connex recursion probe"
    current = payload
    for depth in range(NESTING_DEPTH):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(f"level{depth}.zip", current)
        current = buffer.getvalue()
    return current


def request(method: str, path: str, body: bytes | None = None, token: str | None = TOKEN):
    headers = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/octet-stream"
    call = urllib.request.Request(BASE_URL + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(call, timeout=120) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"clamav runtime smoke failed: {message}")


def main() -> int:
    status, health = request("GET", "/health", token=None)
    require(status == 200, f"/health returned {status}")
    require(health.get("ready") is True, "sidecar never reported ready")
    require(isinstance(health.get("signature_age_seconds"), int), "signature age is not reported")
    require(health.get("seconds_until_block", 0) > 0, "baked signatures are already past the ceiling")

    status, _ = request("GET", "/ready", token=None)
    require(status == 401, f"unauthenticated /ready returned {status}")

    status, ready = request("GET", "/ready")
    require(status == 200 and ready.get("ready") is True, "authenticated /ready is not ready")

    status, _ = request("POST", "/v1/scan", b"unauthenticated", token=None)
    require(status == 401, f"unauthenticated scan returned {status}")

    status, clean = request("POST", "/v1/scan", b"a harmless plain text document\n")
    require(status == 200, f"clean scan returned {status}")
    require(clean.get("verdict") == "clean", f"clean file reported {clean.get('verdict')}")

    status, infected = request("POST", "/v1/scan", eicar_bytes())
    require(status == 200, f"eicar scan returned {status}")
    require(
        infected.get("verdict") == "infected",
        f"the standard test signature reported {infected.get('verdict')}",
    )
    require(bool(infected.get("signature")), "a detection carried no signature name")

    status, nested = request("POST", "/v1/scan", deeply_nested_archive())
    require(status == 200, f"nested-archive scan returned {status}")
    require(
        nested.get("verdict") == "unscannable",
        "a file that exceeds clamd's recursion limit was not reported unscannable; "
        "AlertExceedsMax is almost certainly not in effect, which silently admits "
        f"unscannable content as clean (verdict={nested.get('verdict')})",
    )

    status, _ = request("POST", "/v1/scan", b"x" * 64)
    require(status == 200, "a small body was rejected")

    status, _ = request("GET", "/v1/scan")
    require(status == 404, "an undeclared route is reachable")

    print("clamav runtime smoke passed", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
