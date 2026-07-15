import tempfile
import unittest
from pathlib import Path

from ocr_service.platform_support import _all_processors_support_avx, require_supported_cpu


class PlatformSupportTest(unittest.TestCase):
    def test_accepts_avx_on_every_processor(self) -> None:
        cpuinfo = "processor: 0\nflags: sse4_2 avx avx2\nprocessor: 1\nflags: sse4_2 avx\n"

        self.assertTrue(_all_processors_support_avx(cpuinfo))

    def test_rejects_missing_or_inconsistent_avx_flags(self) -> None:
        self.assertFalse(_all_processors_support_avx("processor: 0\nmodel name: test\n"))
        self.assertFalse(_all_processors_support_avx(
            "processor: 0\nflags: sse4_2 avx\nprocessor: 1\nflags: sse4_2\n"
        ))

    def test_preflight_fails_before_paddle_import_on_unsupported_x86(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cpuinfo = Path(temporary) / "cpuinfo"
            cpuinfo.write_text("processor: 0\nflags: sse4_2\n", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "requires AVX"):
                require_supported_cpu("x86_64", cpuinfo)

    def test_preflight_rejects_other_architectures(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "x86-64"):
            require_supported_cpu("aarch64", Path("/missing/cpuinfo"))


if __name__ == "__main__":
    unittest.main()
