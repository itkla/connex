#!/usr/bin/env python3
"""Replay snapshotted CodeQL dismissals onto regenerated alert identities.

Restoring repository-relative result paths (#1244) regenerates every CodeQL alert under a new
identity, and GitHub does not carry a dismissal across identities. This script matches each alert
that is open on the default branch to the dismissal recorded for the same finding in a snapshot
taken before the path change, keyed on (rule id, path minus its `backend/` or `frontend/` prefix,
start line, start column), and re-applies the snapshot's reason and comment verbatim.

Exit codes: 0 every open alert matched (and, with --apply, every PATCH succeeded); 1 an open alert
had no snapshotted dismissal or a PATCH failed; 2 malformed input or a comment over the API cap.
"""

from __future__ import annotations

import argparse
import gzip
import json
import subprocess
import sys
from pathlib import Path
from typing import NamedTuple


DEFAULT_REPOSITORY = "itkla/connex"
DISMISSAL_COMMENT_CAP = 280
VALID_DISMISSAL_REASONS = {"false positive", "won't fix", "used in tests"}
STRIPPED_PREFIXES = ("backend/", "frontend/")

MatchKey = tuple[str, str, int, int]


class Site(NamedTuple):
    number: int
    rule_id: str
    path: str
    start_line: int
    start_column: int

    @property
    def key(self) -> MatchKey:
        return (self.rule_id, strip_prefix(self.path), self.start_line, self.start_column)


class Dismissal(NamedTuple):
    site: Site
    reason: str
    comment: str
    dismissed_at: str


class Replay(NamedTuple):
    snapshot: Dismissal
    target: Site


def strip_prefix(path: str) -> str:
    for prefix in STRIPPED_PREFIXES:
        if path.startswith(prefix):
            return path[len(prefix):]
    return path


def load_alerts(path: Path) -> list[dict[str, object]]:
    if path.name.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            document = json.load(handle)
    else:
        document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, list):
        raise ValueError(f"{path}: the alert document must be a JSON array")
    alerts: list[dict[str, object]] = []
    for index, element in enumerate(document):
        if isinstance(element, dict):
            alerts.append(element)
        elif isinstance(element, list):
            for alert_index, alert in enumerate(element):
                if not isinstance(alert, dict):
                    raise ValueError(
                        f"{path}: alert {alert_index + 1} on page {index + 1} must be a JSON object"
                    )
                alerts.append(alert)
        else:
            raise ValueError(f"{path}: element {index + 1} must be an alert or a page of alerts")
    return alerts


def site_of(alert: dict[str, object]) -> Site:
    number = alert.get("number")
    rule = alert.get("rule")
    instance = alert.get("most_recent_instance")
    if not isinstance(number, int) or number <= 0:
        raise ValueError("an alert has an invalid number")
    if not isinstance(rule, dict) or not isinstance(rule.get("id"), str) or not rule["id"]:
        raise ValueError(f"alert {number} has an invalid rule id")
    if not isinstance(instance, dict) or not isinstance(instance.get("location"), dict):
        raise ValueError(f"alert {number} has no most recent instance location")
    location = instance["location"]
    path = location.get("path")
    start_line = location.get("start_line")
    start_column = location.get("start_column")
    if not isinstance(path, str) or not path:
        raise ValueError(f"alert {number} has an invalid location path")
    if not isinstance(start_line, int) or not isinstance(start_column, int):
        raise ValueError(f"alert {number} has an invalid location line or column")
    return Site(number, rule["id"], path, start_line, start_column)


def snapshot_dismissals(alerts: list[dict[str, object]]) -> dict[MatchKey, Dismissal]:
    dismissals: dict[MatchKey, Dismissal] = {}
    for alert in alerts:
        if alert.get("state") != "dismissed":
            continue
        site = site_of(alert)
        reason = alert.get("dismissed_reason")
        comment = alert.get("dismissed_comment")
        dismissed_at = alert.get("dismissed_at")
        if reason not in VALID_DISMISSAL_REASONS:
            raise ValueError(f"snapshot alert {site.number} has an unknown dismissal reason: {reason!r}")
        if not isinstance(comment, str) or not comment:
            raise ValueError(f"snapshot alert {site.number} has no dismissal comment")
        if not isinstance(dismissed_at, str) or not dismissed_at:
            raise ValueError(f"snapshot alert {site.number} has no dismissal timestamp")
        candidate = Dismissal(site, reason, comment, dismissed_at)
        incumbent = dismissals.get(site.key)
        if incumbent is None or (candidate.dismissed_at, candidate.site.number) > (
            incumbent.dismissed_at,
            incumbent.site.number,
        ):
            dismissals[site.key] = candidate
    return dismissals


def open_sites(alerts: list[dict[str, object]]) -> list[Site]:
    sites: list[Site] = []
    for alert in alerts:
        site = site_of(alert)
        if alert.get("state") != "open":
            raise ValueError(f"alert {site.number} was not open despite the API filter")
        sites.append(site)
    return sites


def plan(
    dismissals: dict[MatchKey, Dismissal], targets: list[Site]
) -> tuple[list[Replay], list[Site]]:
    replays: list[Replay] = []
    unmatched: list[Site] = []
    for target in sorted(targets, key=lambda site: site.number):
        dismissal = dismissals.get(target.key)
        if dismissal is None:
            unmatched.append(target)
            continue
        if len(dismissal.comment) > DISMISSAL_COMMENT_CAP:
            raise ValueError(
                f"snapshot alert {dismissal.site.number} has a {len(dismissal.comment)}-character "
                f"comment; the API cap is {DISMISSAL_COMMENT_CAP}"
            )
        replays.append(Replay(dismissal, target))
    return replays, unmatched


def mapping_table(replays: list[Replay]) -> str:
    lines = [
        "| Snapshot alert | New alert | Rule | Site | Reason |",
        "| --- | --- | --- | --- | --- |",
    ]
    for replay in replays:
        site = replay.target
        lines.append(
            f"| #{replay.snapshot.site.number} | #{site.number} | `{site.rule_id}` "
            f"| `{site.path}:{site.start_line}` | {replay.snapshot.reason} |"
        )
    return "\n".join(lines)


def patch_dismissal(repository: str, replay: Replay) -> None:
    subprocess.run(
        [
            "gh",
            "api",
            "--method",
            "PATCH",
            "-H",
            "Accept: application/vnd.github+json",
            f"repos/{repository}/code-scanning/alerts/{replay.target.number}",
            "-f",
            "state=dismissed",
            "-f",
            f"dismissed_reason={replay.snapshot.reason}",
            "-f",
            f"dismissed_comment={replay.snapshot.comment}",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Replay snapshotted CodeQL dismissals onto regenerated alert identities"
    )
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--open", required=True, type=Path)
    parser.add_argument("--repo", default=DEFAULT_REPOSITORY)
    parser.add_argument("--apply", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        dismissals = snapshot_dismissals(load_alerts(args.snapshot))
        targets = open_sites(load_alerts(args.open))
        replays, unmatched = plan(dismissals, targets)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        print(f"::error::CodeQL dismissal replay input was invalid: {error}", file=sys.stderr)
        return 2

    mode = "apply" if args.apply else "dry run"
    print(
        f"CodeQL dismissal replay ({mode}): {len(dismissals)} snapshotted dismissal(s), "
        f"{len(targets)} open alert(s), {len(replays)} match(es), {len(unmatched)} unmatched"
    )
    print()
    print(mapping_table(replays))

    failures = 0
    for replay in replays:
        if args.apply:
            try:
                patch_dismissal(args.repo, replay)
            except (OSError, subprocess.CalledProcessError) as error:
                failures += 1
                print(f"::error::PATCH for alert #{replay.target.number} failed: {error}", file=sys.stderr)
                continue
            print(f"dismissed #{replay.target.number} as {replay.snapshot.reason} (from #{replay.snapshot.site.number})")
        else:
            print(f"would dismiss #{replay.target.number} as {replay.snapshot.reason} (from #{replay.snapshot.site.number})")

    for site in unmatched:
        print(
            f"::error title=Unmatched open CodeQL alert #{site.number}::{site.rule_id} at "
            f"{site.path}:{site.start_line}:{site.start_column} has no snapshotted dismissal and needs triage",
            file=sys.stderr,
        )
    if unmatched or failures:
        print(
            f"CodeQL dismissal replay incomplete: {len(unmatched)} unmatched, {failures} failed PATCH(es)",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
