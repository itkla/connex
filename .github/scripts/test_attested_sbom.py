import base64
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify-attested-sbom.py")
SPEC = importlib.util.spec_from_file_location("verify_attested_sbom", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the SBOM attestation verifier")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class AttestedSbomTest(unittest.TestCase):
    def paths(self, root: Path, predicate: dict[str, object]) -> tuple[Path, Path]:
        image = "ghcr.io/itkla/connex-ocr"
        digest = "a" * 64
        statement = {
            "subject": [{"name": image, "digest": {"sha256": digest}}],
            "predicate": predicate,
        }
        envelope = {
            "payload": base64.b64encode(json.dumps(statement).encode()).decode(),
        }
        attestation = root / "attestation.json"
        sbom = root / "sbom.json"
        attestation.write_text(json.dumps(envelope), encoding="utf-8")
        sbom.write_text(json.dumps({"spdxVersion": "SPDX-2.3"}), encoding="utf-8")
        return attestation, sbom

    def test_accepts_the_exact_manifest_sbom_predicate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            attestation, sbom = self.paths(Path(temporary), {"spdxVersion": "SPDX-2.3"})
            VERIFIER.verify_attested_sbom(
                attestation,
                sbom,
                "ghcr.io/itkla/connex-ocr",
                "sha256:" + "a" * 64,
            )

    def test_rejects_a_different_verified_predicate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            attestation, sbom = self.paths(Path(temporary), {"spdxVersion": "SPDX-2.2"})
            with self.assertRaisesRegex(ValueError, "predicate"):
                VERIFIER.verify_attested_sbom(
                    attestation,
                    sbom,
                    "ghcr.io/itkla/connex-ocr",
                    "sha256:" + "a" * 64,
                )

    def test_rejects_a_different_subject_or_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            attestation, sbom = self.paths(Path(temporary), {"spdxVersion": "SPDX-2.3"})
            for image, digest in (
                ("ghcr.io/itkla/connex-frontend", "sha256:" + "a" * 64),
                ("ghcr.io/itkla/connex-ocr", "sha256:" + "b" * 64),
            ):
                with self.subTest(image=image, digest=digest):
                    with self.assertRaisesRegex(ValueError, "subject"):
                        VERIFIER.verify_attested_sbom(attestation, sbom, image, digest)


if __name__ == "__main__":
    unittest.main()
