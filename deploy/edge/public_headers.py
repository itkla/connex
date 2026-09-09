"""Bounded curl probes of representative public response classes, never WAF proof."""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HOST = "preview.connexcrm.jp"


def hsts_valid(values):
    if len(values) != 1:
        return False
    directives = [part.strip().lower() for part in values[0].split(";")]
    return sorted(directives) == ["includesubdomains", "max-age=31536000"]


def fetch(path, scheme="https"):
    with tempfile.TemporaryDirectory() as directory:
        headers = Path(directory) / "headers"
        body = Path(directory) / "body"
        subprocess.run(["curl", "--disable", "--silent", "--show-error", "--proto", "=http,https",
                        "--connect-timeout", "10", "--max-time", "30", "--max-filesize", "2097152",
                        "--dump-header", str(headers), "--output", str(body),
                        f"{scheme}://{HOST}{path}"], check=True, stderr=subprocess.DEVNULL)
        blocks = re.split(r"\r?\n\r?\n", headers.read_text())
        block = [b for b in blocks if b.startswith("HTTP/")][-1]
        lines = block.splitlines()
        status = int(lines[0].split()[1])
        fields = {}
        for line in lines[1:]:
            key, separator, value = line.partition(":")
            if separator:
                fields.setdefault(key.lower(), []).append(value.strip())
        return status, fields, body.read_text(errors="replace")


def main():
    failed = False
    print(f"host={HOST} policy=max-age=31536000;includeSubDomains;no-preload")
    login = fetch("/auth/login")
    assets = sorted(set(re.findall(r'/_next/static/[A-Za-z0-9_./-]+\.css', login[2])))
    cases = [("app", "/auth/login", {200}), ("root", "/", {200, 301, 302, 307, 308}),
             ("api-bare", "/api", {200, 401, 403, 404}),
             ("api", "/api/version", {200}),
             ("https-redirect", "/auth/login/", {301, 302, 307, 308}),
             ("app-error", "/edge-evidence-nonexistent", {404}),
             ("api-error", "/api/edge-evidence-nonexistent", {401, 403, 404}),
             ("static-error", "/_next/static/edge-evidence-nonexistent.css", {404})]
    if assets:
        cases.insert(4, ("static", assets[0], {200}))
    else:
        print("static FAIL no same-origin CSS discovered")
        failed = True
    for label, path, expected in cases:
        status, headers, _ = login if label == "app" else fetch(path)
        values = headers.get("strict-transport-security", [])
        valid = hsts_valid(values) and status in expected
        failed |= not valid
        print(f"{label} status={status} hsts_count={len(values)} "
              f"max_age_31536000={str(len(values) == 1 and 'max-age=31536000' in values[0].lower()).lower()} "
              f"policy={'PASS' if hsts_valid(values) else 'FAIL'} result={'PASS' if valid else 'FAIL'}")
        if label == "app":
            print(f"edge_observation cloudflare_server={headers.get('server') == ['cloudflare']} "
                  f"cf_ray_present={'cf-ray' in headers} "
                  f"cache_dynamic={headers.get('cf-cache-status') == ['DYNAMIC']} waf_proven=false")
    status, headers, _ = fetch("/auth/login", "http")
    redirect = status == 308 and headers.get("location") == [f"https://{HOST}/auth/login"]
    print(f"http-redirect status={status} same_host_https_308={'PASS' if redirect else 'FAIL'}")
    failed |= not redirect
    print("coverage=representative GET responses; edge-generated challenges/5xx and authenticated flows UNVERIFIED")
    print(f"RESULT={'FAIL' if failed else 'PASS'}")
    return int(failed)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, IndexError, subprocess.SubprocessError):
        print("RESULT=ERROR transport or malformed response; no response content retained")
        sys.exit(2)
