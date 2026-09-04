"""Regression tests for the CodeQL dismissal replay.

The replay runs once, against live alert state, with `--apply` issuing real dismissals. These tests
pin the matching key, the fail-closed exits, and — through a recording `gh` stub on PATH — that a
dry run never mutates anything and an apply issues exactly one PATCH per match with the snapshot's
reason and comment untouched.
"""

import importlib.util
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest


SCRIPT = pathlib.Path(__file__).with_name("replay-codeql-dismissals.py")
SPEC = importlib.util.spec_from_file_location("replay_codeql_dismissals", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the CodeQL dismissal replay")
REPLAY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPLAY)

RULE = "java/csrf-unprotected-request-type"
COMMENT = "False positive: read-only handler. Owner Hunter Nakagawa. Expiry 2027-02-14, re-review 2027-01-14. #1296"

GH_STUB = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    printf '%s\\n' "$*" >> "$GH_LOG"
    if [ "${GH_PATCH_FAILS:-0}" = "1" ]; then exit 1; fi
    exit 0
    """
)


def alert(
    number: int,
    path: str,
    line: int = 27,
    column: int = 5,
    state: str = "open",
    rule: str = RULE,
    reason: str | None = None,
    comment: str | None = None,
    dismissed_at: str | None = None,
) -> dict[str, object]:
    return {
        "number": number,
        "state": state,
        "rule": {"id": rule},
        "dismissed_reason": reason,
        "dismissed_comment": comment,
        "dismissed_at": dismissed_at,
        "most_recent_instance": {
            "ref": "refs/heads/main",
            "location": {"path": path, "start_line": line, "start_column": column},
        },
    }


def dismissed(number: int, path: str, **overrides: object) -> dict[str, object]:
    fields: dict[str, object] = {
        "state": "dismissed",
        "reason": "false positive",
        "comment": COMMENT,
        "dismissed_at": "2026-09-02T07:50:00Z",
    }
    fields.update(overrides)
    return alert(number, path, **fields)


class ReplayPlanTest(unittest.TestCase):
    def test_matches_regenerated_alerts_across_both_stripped_prefixes(self) -> None:
        dismissals = REPLAY.snapshot_dismissals(
            [
                dismissed(113, "src/main/java/x/AiOrganizationBudgetController.java"),
                dismissed(2, "test/e2e/global.setup.ts", rule="js/insecure-randomness", reason="used in tests"),
            ]
        )
        targets = REPLAY.open_sites(
            [
                alert(160, "backend/src/main/java/x/AiOrganizationBudgetController.java"),
                alert(161, "frontend/test/e2e/global.setup.ts", rule="js/insecure-randomness"),
            ]
        )

        replays, unmatched = REPLAY.plan(dismissals, targets)

        self.assertEqual([], unmatched)
        self.assertEqual([(113, 160), (2, 161)], [(r.snapshot.site.number, r.target.number) for r in replays])
        self.assertEqual("used in tests", replays[1].snapshot.reason)

    def test_line_or_column_mismatch_is_unmatched(self) -> None:
        dismissals = REPLAY.snapshot_dismissals([dismissed(113, "src/main/java/x/A.java", line=27, column=5)])
        targets = REPLAY.open_sites(
            [
                alert(160, "backend/src/main/java/x/A.java", line=28, column=5),
                alert(161, "backend/src/main/java/x/A.java", line=27, column=9),
            ]
        )

        replays, unmatched = REPLAY.plan(dismissals, targets)

        self.assertEqual([], replays)
        self.assertEqual([160, 161], [site.number for site in unmatched])

    def test_a_different_rule_at_the_same_site_is_unmatched(self) -> None:
        dismissals = REPLAY.snapshot_dismissals([dismissed(113, "src/main/java/x/A.java")])
        targets = REPLAY.open_sites([alert(160, "backend/src/main/java/x/A.java", rule="java/log-injection")])

        _, unmatched = REPLAY.plan(dismissals, targets)

        self.assertEqual([160], [site.number for site in unmatched])

    def test_the_latest_dismissal_wins_when_old_and_new_roots_share_a_site(self) -> None:
        dismissals = REPLAY.snapshot_dismissals(
            [
                dismissed(76, "backend/src/main/java/x/NativeConnectController.java", dismissed_at="2026-08-15T10:00:00Z", comment="old"),
                dismissed(130, "src/main/java/x/NativeConnectController.java", dismissed_at="2026-09-02T07:50:00Z"),
            ]
        )

        self.assertEqual(1, len(dismissals))
        self.assertEqual(130, next(iter(dismissals.values())).site.number)

    def test_snapshot_entries_that_are_not_dismissed_never_match(self) -> None:
        dismissals = REPLAY.snapshot_dismissals(
            [alert(34, "backend/src/main/java/x/A.java", state="fixed"), alert(153, "src/main/java/x/A.java", state="open")]
        )

        self.assertEqual({}, dismissals)

    def test_accepts_flat_and_paginated_documents(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            flat = pathlib.Path(tmp) / "flat.json"
            paged = pathlib.Path(tmp) / "paged.json"
            flat.write_text(json.dumps([alert(1, "a"), alert(2, "b")]))
            paged.write_text(json.dumps([[alert(1, "a")], [alert(2, "b")]]))

            self.assertEqual([1, 2], [a["number"] for a in REPLAY.load_alerts(flat)])
            self.assertEqual([1, 2], [a["number"] for a in REPLAY.load_alerts(paged)])

    def test_malformed_input_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "not open"):
            REPLAY.open_sites([alert(5, "a", state="dismissed")])
        with self.assertRaisesRegex(ValueError, "unknown dismissal reason"):
            REPLAY.snapshot_dismissals([dismissed(6, "a", reason="because")])
        with self.assertRaisesRegex(ValueError, "no dismissal comment"):
            REPLAY.snapshot_dismissals([dismissed(7, "a", comment="")])
        with self.assertRaisesRegex(ValueError, "line or column"):
            broken = alert(8, "a")
            broken["most_recent_instance"]["location"]["start_column"] = None
            REPLAY.open_sites([broken])

    def test_a_comment_over_the_api_cap_is_refused_before_any_patch(self) -> None:
        dismissals = REPLAY.snapshot_dismissals([dismissed(113, "src/x.java", comment="x" * 281)])
        targets = REPLAY.open_sites([alert(160, "backend/src/x.java")])

        with self.assertRaisesRegex(ValueError, "281-character"):
            REPLAY.plan(dismissals, targets)


class ReplayCliTest(unittest.TestCase):
    def run_replay(
        self, snapshot: object, open_alerts: object, *flags: str, gh_env: dict[str, str] | None = None
    ) -> tuple[subprocess.CompletedProcess[str], list[str]]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            gh = root / "gh"
            gh.write_text(GH_STUB)
            gh.chmod(gh.stat().st_mode | stat.S_IEXEC)
            log = root / "gh.log"
            log.touch()
            snapshot_path = root / "snapshot.json"
            open_path = root / "open.json"
            snapshot_path.write_text(json.dumps(snapshot))
            open_path.write_text(json.dumps(open_alerts))
            env = {
                **os.environ,
                "PATH": f"{root}:{os.environ['PATH']}",
                "GH_LOG": str(log),
                **(gh_env or {}),
            }
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--snapshot", str(snapshot_path), "--open", str(open_path), *flags],
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
            return result, log.read_text().splitlines()

    def test_dry_run_prints_the_mapping_and_issues_no_patch(self) -> None:
        result, calls = self.run_replay(
            [dismissed(113, "src/main/java/x/A.java")],
            [[alert(160, "backend/src/main/java/x/A.java")]],
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)
        self.assertIn("| #113 | #160 | `java/csrf-unprotected-request-type` |", result.stdout)
        self.assertIn("would dismiss #160 as false positive (from #113)", result.stdout)

    def test_apply_issues_one_patch_per_match_with_reason_and_comment_preserved(self) -> None:
        wont_fix = "Accepted: OAuth state is the CSRF defence. Owner Hunter Nakagawa. Expiry 2027-02-14, re-review 2027-01-14. #1296"
        result, calls = self.run_replay(
            [
                dismissed(113, "src/main/java/x/A.java"),
                dismissed(134, "src/main/java/x/ProviderConnectionController.java", line=47, reason="won't fix", comment=wont_fix),
            ],
            [[alert(160, "backend/src/main/java/x/A.java"), alert(161, "backend/src/main/java/x/ProviderConnectionController.java", line=47)]],
            "--apply",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))
        self.assertEqual(
            "api --method PATCH -H Accept: application/vnd.github+json repos/itkla/connex/code-scanning/alerts/160 "
            f"-f state=dismissed -f dismissed_reason=false positive -f dismissed_comment={COMMENT}",
            calls[0],
        )
        self.assertEqual(
            "api --method PATCH -H Accept: application/vnd.github+json repos/itkla/connex/code-scanning/alerts/161 "
            f"-f state=dismissed -f dismissed_reason=won't fix -f dismissed_comment={wont_fix}",
            calls[1],
        )
        self.assertIn("dismissed #160 as false positive (from #113)", result.stdout)

    def test_unmatched_open_alerts_exit_one_after_listing_them(self) -> None:
        result, calls = self.run_replay(
            [dismissed(113, "src/main/java/x/A.java")],
            [[alert(160, "backend/src/main/java/x/A.java"), alert(170, "backend/src/main/java/x/ReportController.java", line=126)]],
            "--apply",
        )

        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(1, len(calls), "the matched alert is still replayed")
        self.assertIn("Unmatched open CodeQL alert #170", result.stderr)
        self.assertIn("ReportController.java:126:5", result.stderr)

    def test_a_failed_patch_exits_one(self) -> None:
        result, calls = self.run_replay(
            [dismissed(113, "src/main/java/x/A.java")],
            [[alert(160, "backend/src/main/java/x/A.java")]],
            "--apply",
            gh_env={"GH_PATCH_FAILS": "1"},
        )

        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(1, len(calls))
        self.assertIn("PATCH for alert #160 failed", result.stderr)

    def test_over_cap_comment_and_malformed_input_exit_two_without_patching(self) -> None:
        over_cap, calls = self.run_replay(
            [dismissed(113, "src/main/java/x/A.java", comment="x" * 281)],
            [[alert(160, "backend/src/main/java/x/A.java")]],
            "--apply",
        )
        malformed, more_calls = self.run_replay({"not": "an array"}, [[]], "--apply")

        self.assertEqual(2, over_cap.returncode, over_cap.stderr)
        self.assertEqual(2, malformed.returncode, malformed.stderr)
        self.assertEqual([], calls)
        self.assertEqual([], more_calls)

    def test_nothing_open_exits_zero(self) -> None:
        result, calls = self.run_replay([dismissed(113, "src/main/java/x/A.java")], [[]])

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
