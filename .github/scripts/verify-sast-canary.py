#!/usr/bin/env python3
"""Verify one CodeQL gate canary proof run against its collected evidence.

The canary pull request carries one deliberately vulnerable fixture per analysed language and is
never merged. This script reads the evidence the proof workflow collects from a real Security run
on that pull request and asserts, fail-closed, that the gate actually blocked it: both SAST jobs
failed at the gate step rather than at the CodeQL tooling, `Security — required` failed, both
fixtures produced a Critical alert on the merge ref, GitHub attributed both to the pull request,
each analysis carries exactly one result more than the base commit's analysis of the same category,
branch protection still requires the three contexts, and the pull request is not mergeable.

Exit codes: 0 every assertion held; 1 an assertion failed; 2 the evidence was malformed.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import NamedTuple


CLASSIFY_JOB = "Classify security impact"
REQUIRED_JOB = "Security — required"
GATE_STEP = "Block Critical, High, or error-severity alerts"
REQUIRED_CONTEXTS = (REQUIRED_JOB, "Backend SAST (CodeQL)", "Frontend SAST (CodeQL)")
ANALYSE_STEPS = {
    "Backend SAST (CodeQL)": "Analyze backend with CodeQL",
    "Frontend SAST (CodeQL)": "Analyze frontend with CodeQL",
}
ADMIN_ENFORCEMENT_LEVEL = "everyone"


class Fixture(NamedTuple):
    category: str
    rule_id: str
    path: str


FIXTURES = (
    Fixture(
        "/language:java-kotlin",
        "java/command-line-injection",
        "backend/src/test/java/ooo/klae/connex/backend/codeqlfixture/IntentionalCommandInjectionFixture.java",
    ),
    Fixture(
        "/language:javascript-typescript",
        "js/command-line-injection",
        "frontend/test/fixtures/codeql/intentional-command-injection.mjs",
    ),
)


class Report(NamedTuple):
    failures: list[str]
    warnings: list[str]
    lines: list[str]


def read_json(path: Path) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{path}: {error}") from error


def require_mapping(value: object, description: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError(f"{description} must be a JSON object")
    return value


def require_sequence(value: object, description: str) -> list[object]:
    if not isinstance(value, list):
        raise ValueError(f"{description} must be a JSON array")
    return value


def flatten_alerts(document: object, description: str) -> list[dict[str, object]]:
    alerts: list[dict[str, object]] = []
    for index, element in enumerate(require_sequence(document, description)):
        if isinstance(element, dict):
            alerts.append(element)
            continue
        for page_index, alert in enumerate(
            require_sequence(element, f"{description} page {index + 1}")
        ):
            alerts.append(
                require_mapping(alert, f"{description} alert {page_index + 1} on page {index + 1}")
            )
    return alerts


def job_by_name(document: object) -> dict[str, dict[str, object]]:
    jobs = require_sequence(
        require_mapping(document, "the jobs response").get("jobs"), "the jobs list"
    )
    by_name: dict[str, dict[str, object]] = {}
    for index, job in enumerate(jobs):
        entry = require_mapping(job, f"job {index + 1}")
        name = entry.get("name")
        if not isinstance(name, str) or not name:
            raise ValueError(f"job {index + 1} has no name")
        by_name[name] = entry
    return by_name


def step_conclusion(job: dict[str, object], step_name: str) -> str | None:
    for step in require_sequence(job.get("steps", []), f"the steps of {job.get('name')!r}"):
        entry = require_mapping(step, "a step")
        if entry.get("name") == step_name:
            conclusion = entry.get("conclusion")
            return conclusion if isinstance(conclusion, str) else None
    return None


def check_jobs(document: object) -> Report:
    jobs = job_by_name(document)
    failures: list[str] = []
    lines = ["| Job | Conclusion | Analyze step | Gate step |", "| --- | --- | --- | --- |"]

    for job_name, analyse_step in ANALYSE_STEPS.items():
        job = jobs.get(job_name)
        if job is None:
            failures.append(f"the run has no {job_name!r} job")
            lines.append(f"| {job_name} | *absent* | *absent* | *absent* |")
            continue
        conclusion = job.get("conclusion")
        analyse = step_conclusion(job, analyse_step)
        gate = step_conclusion(job, GATE_STEP)
        lines.append(f"| {job_name} | {conclusion} | {analyse} | {gate} |")
        if analyse != "success":
            failures.append(
                f"{job_name!r} did not complete {analyse_step!r} (conclusion {analyse!r}): "
                "the run proves a tool failure, not the gate"
            )
        if gate != "failure":
            failures.append(f"{job_name!r} step {GATE_STEP!r} concluded {gate!r}, not 'failure'")
        if conclusion != "failure":
            failures.append(f"{job_name!r} concluded {conclusion!r}, not 'failure'")

    for job_name, expected in ((CLASSIFY_JOB, "success"), (REQUIRED_JOB, "failure")):
        job = jobs.get(job_name)
        conclusion = job.get("conclusion") if job is not None else None
        lines.append(f"| {job_name} | {conclusion} | — | — |")
        if conclusion != expected:
            failures.append(f"{job_name!r} concluded {conclusion!r}, not {expected!r}")

    return Report(failures, [], lines)


def alert_site(alert: dict[str, object]) -> tuple[str, str, str, str | None]:
    number = alert.get("number")
    rule = require_mapping(alert.get("rule"), f"the rule of alert {number}")
    instance = require_mapping(
        alert.get("most_recent_instance"), f"the most recent instance of alert {number}"
    )
    location = require_mapping(instance.get("location"), f"the location of alert {number}")
    rule_id = rule.get("id")
    path = location.get("path")
    category = instance.get("category")
    severity = rule.get("security_severity_level")
    if not isinstance(rule_id, str) or not isinstance(path, str) or not isinstance(category, str):
        raise ValueError(f"alert {number} has an invalid rule id, path or category")
    return rule_id, path, category, severity if isinstance(severity, str) else None


def check_alerts(ref_document: object, pr_document: object) -> Report:
    ref_alerts = flatten_alerts(ref_document, "the merge-ref alerts")
    pr_alerts = flatten_alerts(pr_document, "the pull-request alerts")
    attributed: set[int] = set()
    for alert in pr_alerts:
        number = alert.get("number")
        if not isinstance(number, int):
            raise ValueError("a pull-request alert has an invalid number")
        attributed.add(number)

    failures: list[str] = []
    lines = ["| Fixture | Rule | Alert | Severity | Attributed to the pull request |", "| --- | --- | --- | --- | --- |"]
    for fixture in FIXTURES:
        matches = []
        for alert in ref_alerts:
            rule_id, path, category, severity = alert_site(alert)
            if rule_id == fixture.rule_id and path == fixture.path and category == fixture.category:
                if alert.get("state") != "open":
                    raise ValueError(
                        f"alert {alert.get('number')} was not open despite the API filter"
                    )
                matches.append((alert.get("number"), severity))
        if not matches:
            failures.append(
                f"no open {fixture.rule_id} alert at {fixture.path} on the merge ref "
                f"for category {fixture.category}"
            )
            lines.append(f"| `{fixture.path}` | `{fixture.rule_id}` | *absent* | — | no |")
            continue
        number, severity = matches[0]
        attributed_here = number in attributed
        lines.append(
            f"| `{fixture.path}` | `{fixture.rule_id}` | #{number} | {severity} | "
            f"{'yes' if attributed_here else 'no'} |"
        )
        if severity != "critical":
            failures.append(
                f"alert #{number} ({fixture.rule_id}) has security severity {severity!r}, "
                "not 'critical'"
            )
        if not attributed_here:
            failures.append(
                f"alert #{number} ({fixture.rule_id}) is not attributed to the canary pull "
                "request, so result paths are not repository-relative"
            )
    return Report(failures, [], lines)


def latest_analysis(
    document: object, description: str, category: str, commit_sha: str
) -> dict[str, object] | None:
    for index, analysis in enumerate(require_sequence(document, description)):
        entry = require_mapping(analysis, f"{description} entry {index + 1}")
        if entry.get("category") != category:
            continue
        if entry.get("commit_sha") != commit_sha:
            continue
        if not isinstance(entry.get("results_count"), int):
            raise ValueError(f"{description} entry {index + 1} has an invalid results count")
        return entry
    return None


def check_analyses(
    ref_document: object, main_document: object, merge_commit: object
) -> Report:
    commit = require_mapping(merge_commit, "the merge commit")
    merge_sha = commit.get("sha")
    parents = require_sequence(commit.get("parents"), "the merge commit parents")
    if not isinstance(merge_sha, str) or not merge_sha:
        raise ValueError("the merge commit has no sha")
    if not parents:
        raise ValueError("the merge commit has no parents")
    base_sha = require_mapping(parents[0], "the first merge commit parent").get("sha")
    if not isinstance(base_sha, str) or not base_sha:
        raise ValueError("the first merge commit parent has no sha")

    failures: list[str] = []
    lines = ["| Category | Base commit | Merge ref | Expected |", "| --- | --- | --- | --- |"]
    for fixture in FIXTURES:
        ref_analysis = latest_analysis(
            ref_document, "the merge-ref analyses", fixture.category, merge_sha
        )
        main_analysis = latest_analysis(
            main_document, "the main analyses", fixture.category, base_sha
        )
        if ref_analysis is None:
            failures.append(
                f"no {fixture.category} analysis of the merge commit {merge_sha[:9]} on the "
                "canary merge ref; the evidence is stale"
            )
        if main_analysis is None:
            failures.append(
                f"no {fixture.category} analysis of the base commit {base_sha[:9]} on main; "
                "the count invariant cannot be measured"
            )
        if ref_analysis is None or main_analysis is None:
            lines.append(f"| {fixture.category} | — | — | — |")
            continue
        expected = main_analysis["results_count"] + 1
        lines.append(
            f"| {fixture.category} | {main_analysis['results_count']} "
            f"| {ref_analysis['results_count']} | {expected} |"
        )
        if ref_analysis["results_count"] != expected:
            failures.append(
                f"{fixture.category} stored {ref_analysis['results_count']} result(s) on the "
                f"merge ref but {main_analysis['results_count']} on the base commit; the canary "
                "must add exactly one, so results are still being pruned or the branch is stale"
            )
    return Report(failures, [], lines)


def check_protection(document: object, require_admin_enforcement: bool) -> Report:
    branch = require_mapping(document, "the branch response")
    protection = require_mapping(branch.get("protection"), "the branch protection block")
    checks = require_mapping(
        protection.get("required_status_checks"), "the required status checks block"
    )
    contexts = [
        context
        for context in require_sequence(checks.get("contexts"), "the required contexts")
        if isinstance(context, str)
    ]
    enforcement = checks.get("enforcement_level")

    failures: list[str] = []
    warnings: list[str] = []
    missing = [context for context in REQUIRED_CONTEXTS if context not in contexts]
    for context in missing:
        failures.append(f"branch protection no longer requires the {context!r} context")
    if enforcement != ADMIN_ENFORCEMENT_LEVEL:
        message = (
            f"branch protection enforcement level is {enforcement!r}, not "
            f"{ADMIN_ENFORCEMENT_LEVEL!r}: an administrator can merge past the failed checks"
        )
        if require_admin_enforcement:
            failures.append(message)
        else:
            warnings.append(message)
    lines = [
        "| Property | Value |",
        "| --- | --- |",
        f"| Required contexts present | {len(REQUIRED_CONTEXTS) - len(missing)}/{len(REQUIRED_CONTEXTS)} |",
        f"| Enforcement level | {enforcement} |",
    ]
    return Report(failures, warnings, lines)


def check_pull_request(document: object, canary_number: int) -> Report:
    pull_request = require_mapping(document, "the pull request")
    if pull_request.get("number") != canary_number:
        raise ValueError(
            f"the pull request evidence is for #{pull_request.get('number')}, "
            f"not the canary #{canary_number}"
        )
    commits = require_sequence(
        require_mapping(pull_request.get("commits"), "the pull request commits").get("nodes"),
        "the pull request commit nodes",
    )
    if not commits:
        raise ValueError("the pull request has no commits")
    commit = require_mapping(
        require_mapping(commits[-1], "the last pull request commit").get("commit"),
        "the last pull request commit object",
    )
    rollup = commit.get("statusCheckRollup")
    nodes: list[object] = []
    if isinstance(rollup, dict):
        contexts = require_mapping(rollup.get("contexts"), "the status check contexts")
        nodes = require_sequence(contexts.get("nodes"), "the status check nodes")
    conclusions: dict[str, object] = {}
    for node in nodes:
        entry = require_mapping(node, "a status check node")
        name = entry.get("name")
        if isinstance(name, str):
            conclusions[name] = entry.get("conclusion")

    failures: list[str] = []
    merge_state = pull_request.get("mergeStateStatus")
    if merge_state != "BLOCKED":
        failures.append(f"the canary pull request is {merge_state!r}, not 'BLOCKED'")
    if pull_request.get("isDraft") is not False:
        failures.append(
            "the canary pull request is a draft, so its merge state does not prove the gate"
        )
    lines = ["| Check run | Conclusion |", "| --- | --- |"]
    for context in REQUIRED_CONTEXTS:
        conclusion = conclusions.get(context)
        lines.append(f"| {context} | {conclusion} |")
        if conclusion != "FAILURE":
            failures.append(
                f"the {context!r} check run on the canary concluded {conclusion!r}, not 'FAILURE'"
            )
    lines.append(f"| Merge state | {merge_state} |")
    return Report(failures, [], lines)


def write_summary(path: Path, sections: list[tuple[str, list[str]]]) -> None:
    body = ["## CodeQL gate canary proof", ""]
    for title, lines in sections:
        body.append(f"### {title}")
        body.append("")
        body.extend(lines)
        body.append("")
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(body) + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify a CodeQL gate canary proof run against its collected evidence"
    )
    parser.add_argument("--jobs", required=True, type=Path)
    parser.add_argument("--ref-alerts", required=True, type=Path)
    parser.add_argument("--pr-alerts", required=True, type=Path)
    parser.add_argument("--ref-analyses", required=True, type=Path)
    parser.add_argument("--main-analyses", required=True, type=Path)
    parser.add_argument("--merge-commit", required=True, type=Path)
    parser.add_argument("--branch", required=True, type=Path)
    parser.add_argument("--pull-request", required=True, type=Path)
    parser.add_argument("--canary-number", required=True, type=int)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--require-admin-enforcement", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        merge_commit = read_json(args.merge_commit)
        reports = [
            ("Jobs", check_jobs(read_json(args.jobs))),
            (
                "Alerts",
                check_alerts(read_json(args.ref_alerts), read_json(args.pr_alerts)),
            ),
            (
                "Analysis counts",
                check_analyses(
                    read_json(args.ref_analyses), read_json(args.main_analyses), merge_commit
                ),
            ),
            (
                "Branch protection",
                check_protection(read_json(args.branch), args.require_admin_enforcement),
            ),
            (
                "Mergeability",
                check_pull_request(read_json(args.pull_request), args.canary_number),
            ),
        ]
    except ValueError as error:
        print(f"::error::canary proof evidence was invalid: {error}", file=sys.stderr)
        return 2

    merge_sha = require_mapping(merge_commit, "the merge commit")["sha"]
    sections = [
        ("Run", [f"Canary pull request #{args.canary_number}, merge commit `{merge_sha}`."]),
    ]
    failures: list[str] = []
    warnings: list[str] = []
    for title, report in reports:
        sections.append((title, report.lines))
        failures.extend(report.failures)
        warnings.extend(report.warnings)

    verdict = ["The gate blocked the canary." if not failures else "The proof failed."]
    verdict.extend(f"- WARNING: {warning}" for warning in warnings)
    verdict.extend(f"- FAILED: {failure}" for failure in failures)
    sections.append(("Verdict", verdict))
    write_summary(args.summary, sections)

    for warning in warnings:
        print(f"::warning::{warning}")
    for failure in failures:
        print(f"::error title=CodeQL gate canary proof::{failure}", file=sys.stderr)
    if failures:
        print(
            f"CodeQL gate canary proof failed: {len(failures)} assertion(s)", file=sys.stderr
        )
        return 1
    print(
        f"CodeQL gate canary proof passed for pull request #{args.canary_number} "
        f"at merge commit {merge_sha[:9]}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
