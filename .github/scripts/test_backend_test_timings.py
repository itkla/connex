import importlib.util
import re
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("backend_test_timings.py")
TIMINGS_PATH = Path(__file__).parents[2] / "backend" / "gradle" / "db-test-timings.tsv"
SPEC = importlib.util.spec_from_file_location("backend_test_timings", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Unable to load the backend test timings generator")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

GRADLE_LINE = re.compile(r"[A-Za-z_]\w*(\.[A-Za-z_]\w*)+\t\d+(\.\d+)?")


def suite(directory: Path, name: str, seconds: str) -> None:
    file_name = "TEST-" + name.replace("$", "_") + ".xml"
    (directory / file_name).write_text(
        f'<?xml version="1.0"?><testsuite name="{name}" tests="1" time="{seconds}"/>',
        encoding="utf-8",
    )


class BackendTestTimingsTest(unittest.TestCase):
    def test_nested_classes_fold_into_their_outer_class_and_output_is_sorted(self) -> None:
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            suite(Path(first), "a.b.Zeta", "1.25")
            suite(Path(first), "a.b.Alpha", "2.0")
            suite(Path(second), "a.b.Alpha$Nested", "0.5")
            seconds = MODULE.collect([Path(first), Path(second)])
        self.assertEqual({"a.b.Alpha": Decimal("2.5"), "a.b.Zeta": Decimal("1.25")}, seconds)
        body = [line for line in MODULE.render(seconds).splitlines() if not line.startswith("#")]
        self.assertEqual(["a.b.Alpha\t2.5", "a.b.Zeta\t1.3"], body)

    def test_unexpected_suite_names_and_empty_inputs_fail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(SystemExit):
                MODULE.collect([Path(directory)])
            suite(Path(directory), "not a class", "1")
            with self.assertRaises(SystemExit):
                MODULE.collect([Path(directory)])

    def test_committed_timings_match_the_format_build_gradle_accepts(self) -> None:
        names = []
        for line in TIMINGS_PATH.read_text(encoding="utf-8").splitlines():
            if not line or line.startswith("#"):
                continue
            self.assertRegex(line, GRADLE_LINE)
            names.append(line.split("\t", 1)[0])
        self.assertGreater(len(names), 0)
        self.assertEqual(len(names), len(set(names)), "duplicate class entries")
        self.assertEqual(sorted(names), names, "entries must stay sorted for reviewable refreshes")


if __name__ == "__main__":
    unittest.main()
