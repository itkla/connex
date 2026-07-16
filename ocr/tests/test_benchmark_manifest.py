import hashlib
import json
import os
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request
from unittest.mock import Mock, patch

from benchmark.run_benchmark import (
    RejectRedirects,
    benchmark_opener,
    canonical_manifest,
    candidate,
    inspect_runtime_container,
    provenance,
    require_host_avx,
    repository_revision,
    summarize,
    validated_base_url,
    validated_image_reference,
    validated_source_revision,
    verify_qualification_report,
)


class BenchmarkManifestTest(unittest.TestCase):
    def qualification_outcomes(self) -> tuple[dict[str, object], list[dict[str, object]]]:
        manifest = json.loads(
            (Path(__file__).parents[1] / "benchmark" / "manifest.json").read_text(encoding="utf-8")
        )
        outcomes = [
            {
                "id": case["id"],
                "language": case["language"],
                "layout": case["layout"],
                "condition": case["condition"],
                "status": 200,
                "latencySeconds": 1.0,
                "correct": {
                    "name": True,
                    "email": True,
                    "phone": True,
                    "title": True,
                    "company": True,
                },
            }
            for case in manifest["cases"]
        ]
        return manifest, outcomes

    def test_manifest_has_required_coverage_and_synthetic_contacts(self) -> None:
        manifest = json.loads(
            (Path(__file__).parents[1] / "benchmark" / "manifest.json").read_text(encoding="utf-8")
        )
        cases = manifest["cases"]

        self.assertEqual(40, len(cases))
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        self.assertGreaterEqual(Counter(case["language"] for case in cases), Counter({"en": 12, "ja": 12, "mixed": 12}))
        self.assertEqual({"clean", "glare", "rotation", "perspective", "low_light"}, {case["condition"] for case in cases})
        self.assertGreaterEqual(len({case["layout"] for case in cases}), 4)
        for case in cases:
            fields = case["fields"]
            self.assertTrue(fields["name"])
            self.assertTrue(fields["email"].endswith("@example.test"))
            self.assertRegex(fields["phone"], r"^\+\d{11}$")

    def test_scoring_includes_title_and_company_candidates(self) -> None:
        payload = {
            "fields": {
                "title": {"value": "Principal Engineer"},
            },
            "company": {"value": "Analytical Labs"},
        }
        outcome = {
            "latencySeconds": 1.0,
            "correct": {
                "name": True,
                "email": True,
                "phone": True,
                "title": True,
                "company": True,
            },
        }

        self.assertEqual("Principal Engineer", candidate(payload, "title"))
        self.assertEqual("Analytical Labs", candidate(payload, "company"))
        report = summarize([outcome])
        self.assertEqual(1.0, report["accuracy"]["title"])
        self.assertEqual(1.0, report["accuracy"]["company"])
        self.assertTrue(report["gates"]["title"]["passed"])
        self.assertTrue(report["gates"]["company"]["passed"])

    def test_qualification_recomputes_every_gate_from_exact_raw_cases(self) -> None:
        manifest, outcomes = self.qualification_outcomes()
        provenance_value = {"sourceRevision": "a" * 40}
        report = summarize(outcomes, provenance_value)

        verify_qualification_report(report, manifest, "a" * 40)

        outcomes[0]["correct"]["email"] = False
        with self.assertRaisesRegex(ValueError, "raw case outcomes"):
            verify_qualification_report(report, manifest, "a" * 40)

    def test_qualification_rejects_missing_or_duplicate_cases(self) -> None:
        manifest, outcomes = self.qualification_outcomes()
        provenance_value = {"sourceRevision": "a" * 40}
        report = summarize(outcomes, provenance_value)
        report["cases"][1]["id"] = report["cases"][0]["id"]

        with self.assertRaisesRegex(ValueError, "order and ids"):
            verify_qualification_report(report, manifest, "a" * 40)

        with self.assertRaisesRegex(ValueError, "exactly 40"):
            verify_qualification_report({"cases": []}, manifest, "a" * 40)

    def test_authenticated_benchmark_rejects_redirects(self) -> None:
        request = Request(
            "https://connex.example/api/business-cards/scan",
            headers={"Cookie": "JSESSIONID=secret", "X-XSRF-TOKEN": "secret"},
        )

        with self.assertRaises(HTTPError) as raised:
            RejectRedirects().redirect_request(
                request,
                None,
                307,
                "Temporary Redirect",
                {},
                "https://attacker.example/collect",
            )

        self.assertEqual(307, raised.exception.code)
        self.assertEqual(request.full_url, raised.exception.url)

    def test_authenticated_benchmark_ignores_environment_proxies(self) -> None:
        with patch.dict(
            os.environ,
            {"HTTP_PROXY": "http://proxy.example:8080", "HTTPS_PROXY": "http://proxy.example:8080"},
        ):
            opener = benchmark_opener()

        proxy_handlers = [handler for handler in opener.handlers if isinstance(handler, ProxyHandler)]
        self.assertTrue(all(handler.proxies == {} for handler in proxy_handlers))

    def test_base_url_requires_https_or_loopback(self) -> None:
        self.assertEqual("https://connex.example", validated_base_url("https://connex.example/"))
        self.assertEqual("http://localhost:8080", validated_base_url("http://localhost:8080"))
        self.assertEqual("http://127.0.0.1:8080", validated_base_url("http://127.0.0.1:8080"))
        self.assertEqual("http://[::1]:8080", validated_base_url("http://[::1]:8080"))
        for unsafe in (
            "http://connex.example",
            "https://user:secret@connex.example",
            "https://connex.example?token=secret",
            "https://connex.example#fragment",
            "file:///tmp/connex",
        ):
            with self.subTest(unsafe=unsafe), self.assertRaises(ValueError):
                validated_base_url(unsafe)

    def test_provenance_binds_report_to_exact_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_path = root / "manifest.json"
            images = root / "images"
            images.mkdir()
            cases = [{"id": f"case-{index:02d}"} for index in range(40)]
            manifest_path.write_text(json.dumps({"cases": cases}), encoding="utf-8")
            fixture_entries = []
            for case in cases:
                image_path = images / f"{case['id']}.jpg"
                image_path.write_bytes(case["id"].encode("utf-8"))
                fixture_entries.append({
                    "id": case["id"],
                    "sha256": hashlib.sha256(image_path.read_bytes()).hexdigest(),
                    "size": image_path.stat().st_size,
                })
            generator = Path(__file__).parents[1] / "benchmark" / "generate_cards.py"
            (images / "fixtures.json").write_text(json.dumps({
                "schemaVersion": 1,
                "manifestSha256": "a641d9af0c946a03753606b88924fc9ac5ed0c58a2678ee9e960adc07fd84d87",
                "generatorSha256": hashlib.sha256(generator.read_bytes()).hexdigest(),
                "fontSha256": "68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5",
                "fixturesSha256": "placeholder",
                "cases": fixture_entries,
            }), encoding="utf-8")
            configuration = {
                "base_url": "https://connex.example",
            }
            runtime = {"containers": {"backend": {"imageReference": "backend@sha256:" + "b" * 64}}}

            fixture_digest = hashlib.sha256()
            for case in sorted(cases, key=lambda item: item["id"]):
                fixture_digest.update(case["id"].encode("utf-8"))
                fixture_digest.update(b"\0")
                fixture_digest.update((images / f"{case['id']}.jpg").read_bytes())
            expected_fixtures = fixture_digest.hexdigest()
            metadata = json.loads((images / "fixtures.json").read_text(encoding="utf-8"))
            metadata["fixturesSha256"] = expected_fixtures
            (images / "fixtures.json").write_text(json.dumps(metadata), encoding="utf-8")
            with patch(
                "benchmark.run_benchmark.CANONICAL_FIXTURES_SHA256",
                expected_fixtures,
            ):
                result = provenance(
                    manifest_path,
                    images,
                    json.loads(manifest_path.read_text()),
                    configuration,
                    3,
                    "a" * 40,
                    runtime,
                )

        self.assertEqual("a" * 40, result["sourceRevision"])
        self.assertEqual(runtime, result["runtime"])
        self.assertEqual(64, len(result["manifestSha256"]))
        self.assertEqual(64, len(result["fixturesSha256"]))
        self.assertEqual(64, len(result["ocrRequirementsLockSha256"]))

    def test_runtime_rejects_noncanonical_manifest(self) -> None:
        canonical = Path(__file__).parents[1] / "benchmark" / "manifest.json"
        self.assertEqual(40, len(canonical_manifest(canonical)["cases"]))
        with tempfile.TemporaryDirectory() as temporary:
            custom = Path(temporary) / "manifest.json"
            custom.write_text('{"schemaVersion":1,"cases":[{"id":"only"}]}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "manifest hash"):
                canonical_manifest(custom)

    def test_source_revision_rejects_dirty_benchmark_inputs(self) -> None:
        dirty = Mock(returncode=1)
        with (
            patch("benchmark.run_benchmark.command_output", return_value="a" * 40),
            patch("benchmark.run_benchmark.subprocess.run", return_value=dirty),
        ):
            with self.assertRaisesRegex(ValueError, "must match"):
                repository_revision()

    def test_runtime_inspection_requires_exact_ocr_limits(self) -> None:
        inspection = [{
            "Config": {
                "Image": "ghcr.io/itkla/connex-ocr@sha256:" + "b" * 64,
                "User": "10001",
                "ExposedPorts": {"8090/tcp": {}},
            },
            "State": {"Running": True, "Health": {"Status": "healthy"}},
            "HostConfig": {
                "Memory": 2_147_483_648,
                "MemorySwap": 2_147_483_648,
                "NanoCpus": 2_000_000_000,
                "PidsLimit": 128,
                "ReadonlyRootfs": True,
                "CapDrop": ["ALL"],
                "Privileged": False,
                "SecurityOpt": ["no-new-privileges:true"],
                "Devices": [],
                "DeviceRequests": None,
                "Binds": None,
                "Mounts": None,
                "Tmpfs": {"/tmp": "rw,noexec,nosuid,nodev,size=67108864"},
                "NetworkMode": "connex_ocr_internal",
            },
            "Mounts": [],
            "NetworkSettings": {
                "Ports": {"8090/tcp": None},
                "Networks": {"connex_ocr_internal": {}},
            },
            "Image": "sha256:" + "c" * 64,
        }]
        with patch("benchmark.run_benchmark.command_output", return_value=json.dumps(inspection)):
            result = inspect_runtime_container("ocr", "d" * 12)
        self.assertEqual("ghcr.io/itkla/connex-ocr@sha256:" + "b" * 64, result["imageReference"])

        inspection[0]["HostConfig"]["Memory"] = 1
        with patch("benchmark.run_benchmark.command_output", return_value=json.dumps(inspection)):
            with self.assertRaisesRegex(ValueError, "resource"):
                inspect_runtime_container("ocr", "d" * 12)

        inspection[0]["HostConfig"]["Memory"] = 2_147_483_648
        inspection[0]["HostConfig"]["Privileged"] = True
        with patch("benchmark.run_benchmark.command_output", return_value=json.dumps(inspection)):
            with self.assertRaisesRegex(ValueError, "resource"):
                inspect_runtime_container("ocr", "d" * 12)

    def test_host_qualification_requires_avx_on_every_processor(self) -> None:
        with patch(
            "benchmark.run_benchmark.Path.read_text",
            return_value="processor: 0\nflags: sse avx\n\nprocessor: 1\nflags: sse avx\n",
        ):
            self.assertTrue(require_host_avx())
        with patch(
            "benchmark.run_benchmark.Path.read_text",
            return_value="processor: 0\nflags: sse avx\n\nprocessor: 1\nflags: sse\n",
        ):
            with self.assertRaisesRegex(ValueError, "AVX"):
                require_host_avx()

    def test_provenance_identifiers_must_be_immutable(self) -> None:
        self.assertEqual("a" * 40, validated_source_revision("A" * 40))
        self.assertEqual(
            "backend@sha256:" + "b" * 64,
            validated_image_reference("backend@sha256:" + "b" * 64, "BACKEND_IMAGE"),
        )
        for value in ("main", "a" * 39, "g" * 40):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validated_source_revision(value)
        for value in ("backend:latest", "backend@sha256:short", "backend@sha512:" + "b" * 64):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validated_image_reference(value, "BACKEND_IMAGE")


if __name__ == "__main__":
    unittest.main()
