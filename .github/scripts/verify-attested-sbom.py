#!/usr/bin/env python3

import argparse
import base64
import json
import re
from pathlib import Path


def decoded_envelopes(path: Path) -> list[dict[str, object]]:
    raw = path.read_text(encoding="utf-8").strip()
    if not raw:
        raise ValueError("Verified attestation output is empty")
    try:
        parsed = json.loads(raw)
        values = parsed if isinstance(parsed, list) else [parsed]
    except json.JSONDecodeError:
        values = [json.loads(line) for line in raw.splitlines() if line.strip()]
    if not values or any(not isinstance(value, dict) for value in values):
        raise ValueError("Verified attestation output is invalid")
    return values


def verified_statements(path: Path) -> list[dict[str, object]]:
    statements: list[dict[str, object]] = []
    for envelope in decoded_envelopes(path):
        payload = envelope.get("payload")
        if not isinstance(payload, str):
            raise ValueError("Verified attestation envelope has no payload")
        try:
            decoded = base64.b64decode(payload, validate=True)
            statement = json.loads(decoded)
        except (ValueError, json.JSONDecodeError) as exception:
            raise ValueError("Verified attestation payload is invalid") from exception
        if not isinstance(statement, dict):
            raise ValueError("Verified attestation statement is invalid")
        statements.append(statement)
    return statements


def verify_attested_sbom(
    attestation_path: Path,
    sbom_path: Path,
    image: str,
    digest: str,
) -> None:
    if re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None:
        raise ValueError("Manifest image digest is invalid")
    sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
    if not isinstance(sbom, dict):
        raise ValueError("Manifest SBOM must be an object")
    expected_subject = {"name": image, "digest": {"sha256": digest.removeprefix("sha256:")}}
    statements = verified_statements(attestation_path)
    if not statements:
        raise ValueError("No verified SBOM attestation was returned")
    for statement in statements:
        subjects = statement.get("subject")
        if subjects != [expected_subject]:
            raise ValueError("Verified SBOM attestation subject does not match the manifest image")
        if statement.get("predicate") != sbom:
            raise ValueError("Verified SBOM attestation predicate does not match the manifest SBOM")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("attestation", type=Path)
    parser.add_argument("sbom", type=Path)
    parser.add_argument("image")
    parser.add_argument("digest")
    arguments = parser.parse_args()
    verify_attested_sbom(arguments.attestation, arguments.sbom, arguments.image, arguments.digest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
