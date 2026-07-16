import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-action-pins.py")
SPEC = importlib.util.spec_from_file_location("check_action_pins", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the action pin checker")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class ActionPinCheckerTest(unittest.TestCase):
    def test_scans_workflow_and_local_action_yaml_variants(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workflows = root / ".github" / "workflows"
            actions = root / ".github" / "actions" / "example"
            workflows.mkdir(parents=True)
            actions.mkdir(parents=True)
            workflows.joinpath("ci.yaml").write_text(
                "jobs:\n  test:\n    steps:\n      - uses: 'owner/action@" + "a" * 40 + "'\n",
                encoding="utf-8",
            )
            actions.joinpath("action.yml").write_text(
                "runs:\n  using: composite\n  steps:\n    - uses: owner/unpinned@v1\n",
                encoding="utf-8",
            )

            self.assertEqual(
                [
                    ".github/actions/example/action.yml:document 1:runs.steps.0.uses: owner/unpinned@v1"
                ],
                CHECKER.invalid_pins(root),
            )

    def test_accepts_local_actions_and_full_commit_revisions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workflows = root / ".github" / "workflows"
            workflows.mkdir(parents=True)
            workflows.joinpath("ci.yml").write_text(
                "jobs:\n  test:\n    steps:\n      - uses: ./local\n      - uses: owner/action/path@" + "b" * 40 + "\n",
                encoding="utf-8",
            )

            self.assertEqual([], CHECKER.invalid_pins(root))


if __name__ == "__main__":
    unittest.main()
