#!/usr/bin/env python3
"""Verify one CodeQL gate canary proof run against its collected evidence.

The canary pull request carries one deliberately vulnerable fixture per analysed language and is
never merged. This script reads the evidence the proof workflow collects from a real Security run
on that pull request and asserts, fail-closed, that the gate actually blocked it: the run and every
job belong to the exact re-run attempt of the canary's current head, both SAST jobs failed at the
gate step rather than at the CodeQL tooling and recorded a `Blocking CodeQL alert` verdict naming
each fixture's alert (and no blind, invalid or unsupported gate diagnostic), `Security — required`
failed, both fixtures produced a Critical alert on the merge ref at the analysed merge commit,
GitHub attributed both to the pull request, each analysis was created by that attempt and carries
exactly one result more than the base commit's analysis of the same category, branch protection
still requires the three contexts, and the pull request is not mergeable.

A failed gate step alone proves nothing: a failed `gh api` fetch, a blind analysis and an invalid
alert response fail it exactly as a blocking verdict does, and fixture alerts and analyses from
earlier attempts persist on the reused merge commit. Only the attempt's own verdict annotations and
analyses timestamped inside its SAST jobs distinguish a fresh rejection from stale evidence.

Exit codes: 0 every assertion held; 1 an assertion failed; 2 the evidence was malformed.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
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
SAST_JOB_CATEGORIES = {
    "Backend SAST (CodeQL)": "/language:java-kotlin",
    "Frontend SAST (CodeQL)": "/language:javascript-typescript",
}
ADMIN_ENFORCEMENT_LEVEL = "everyone"
BLOCKING_TITLE = "Blocking CodeQL alert #{number}"
UNGATED_DIAGNOSTICS = (
    "CodeQL analysis cannot be gated",
    "CodeQL alert response was invalid",
    "Unsupported CodeQL gate event",
    "fork pull requests are not gated",
)


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


def parse_timestamp(value: object, description: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{description} has no timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{description} has an invalid timestamp {value!r}") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{description} timestamp {value!r} has no time zone")
    return parsed


def require_sha(value: object, description: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{description} has no commit sha")
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


class RunBinding(NamedTuple):
    run_id: int
    attempt: int
    head_sha: str
    rerun_started_at: datetime


def pull_request_head(document: object) -> str:
    pull_request = require_mapping(document, "the pull request")
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
    return require_sha(commit.get("oid"), "the last pull request commit")


def check_run(
    run_document: object,
    jobs_document: object,
    merge_commit: object,
    pull_request: object,
    binding: RunBinding,
    canary_number: int,
) -> Report:
    run = require_mapping(run_document, "the run attempt")
    run_id = run.get("id")
    run_attempt = run.get("run_attempt")
    if not isinstance(run_id, int) or not isinstance(run_attempt, int):
        raise ValueError("the run attempt has an invalid id or attempt number")
    run_head = require_sha(run.get("head_sha"), "the run attempt")
    run_started = parse_timestamp(run.get("run_started_at"), "the run attempt start")
    pull_numbers = []
    for index, entry in enumerate(
        require_sequence(run.get("pull_requests"), "the run's pull requests")
    ):
        pull_numbers.append(require_mapping(entry, f"run pull request {index + 1}").get("number"))
    pr_head = pull_request_head(pull_request)
    commit = require_mapping(merge_commit, "the merge commit")
    parents = require_sequence(commit.get("parents"), "the merge commit parents")
    merged_head = (
        require_mapping(parents[1], "the second merge commit parent").get("sha")
        if len(parents) > 1
        else None
    )

    failures: list[str] = []
    if run_id != binding.run_id:
        failures.append(f"the run evidence is for run {run_id}, not the re-run {binding.run_id}")
    if run_attempt != binding.attempt:
        failures.append(
            f"the run evidence is for attempt {run_attempt}, not the re-run attempt {binding.attempt}"
        )
    if run.get("event") != "pull_request":
        failures.append(f"the run was triggered by {run.get('event')!r}, not 'pull_request'")
    if canary_number not in pull_numbers:
        failures.append(f"the run is not associated with the canary pull request #{canary_number}")
    if binding.head_sha != pr_head:
        failures.append(
            f"the canary head moved during the proof: re-ran {binding.head_sha[:9]}, "
            f"the pull request is now at {pr_head[:9]}"
        )
    if run_head != binding.head_sha:
        failures.append(
            f"the run is at {run_head[:9]}, not the canary head {binding.head_sha[:9]}"
        )
    if merged_head != binding.head_sha:
        failures.append(
            f"the analysed merge commit does not merge the canary head {binding.head_sha[:9]} "
            f"(second parent {merged_head!r})"
        )
    if run_started < binding.rerun_started_at:
        failures.append(
            f"the run attempt started at {run.get('run_started_at')}, before the proof requested "
            "the re-run, so it is not the re-run attempt"
        )

    jobs = require_sequence(
        require_mapping(jobs_document, "the jobs response").get("jobs"), "the jobs list"
    )
    for index, job in enumerate(jobs):
        entry = require_mapping(job, f"job {index + 1}")
        name = entry.get("name")
        if entry.get("run_id") != binding.run_id or entry.get("run_attempt") != binding.attempt:
            failures.append(
                f"job {name!r} belongs to run {entry.get('run_id')} attempt "
                f"{entry.get('run_attempt')}, not run {binding.run_id} attempt {binding.attempt}"
            )
        if entry.get("head_sha") != binding.head_sha:
            failures.append(
                f"job {name!r} ran at {entry.get('head_sha')!r}, not the canary head "
                f"{binding.head_sha[:9]}"
            )
    lines = [
        "| Property | Value |",
        "| --- | --- |",
        f"| Run | {run_id} |",
        f"| Attempt | {run_attempt} (expected {binding.attempt}) |",
        f"| Event | {run.get('event')} |",
        f"| Head | `{run_head}` (pull request head `{pr_head}`) |",
        f"| Re-run requested at | {binding.rerun_started_at.isoformat()} |",
        f"| Attempt started at | {run.get('run_started_at')} |",
    ]
    return Report(failures, [], lines)


def check_jobs(
    document: object, rerun_started_at: datetime
) -> tuple[Report, dict[str, tuple[datetime, datetime]]]:
    jobs = job_by_name(document)
    failures: list[str] = []
    windows: dict[str, tuple[datetime, datetime]] = {}
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
        started = parse_timestamp(job.get("started_at"), f"the start of {job_name!r}")
        completed = parse_timestamp(job.get("completed_at"), f"the completion of {job_name!r}")
        windows[SAST_JOB_CATEGORIES[job_name]] = (max(started, rerun_started_at), completed)

    for job_name, expected in ((CLASSIFY_JOB, "success"), (REQUIRED_JOB, "failure")):
        job = jobs.get(job_name)
        conclusion = job.get("conclusion") if job is not None else None
        lines.append(f"| {job_name} | {conclusion} | — | — |")
        if conclusion != expected:
            failures.append(f"{job_name!r} concluded {conclusion!r}, not {expected!r}")

    return Report(failures, [], lines), windows


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


def check_alerts(
    ref_document: object, pr_document: object, merge_sha: str, ref: str
) -> tuple[Report, dict[str, int]]:
    ref_alerts = flatten_alerts(ref_document, "the merge-ref alerts")
    pr_alerts = flatten_alerts(pr_document, "the pull-request alerts")
    attributed: set[int] = set()
    for alert in pr_alerts:
        number = alert.get("number")
        if not isinstance(number, int):
            raise ValueError("a pull-request alert has an invalid number")
        attributed.add(number)

    failures: list[str] = []
    fixture_alerts: dict[str, int] = {}
    lines = ["| Fixture | Rule | Alert | Severity | Attributed to the pull request |", "| --- | --- | --- | --- | --- |"]
    for fixture in FIXTURES:
        matches = []
        elsewhere = []
        for alert in ref_alerts:
            rule_id, path, category, severity = alert_site(alert)
            if rule_id == fixture.rule_id and path == fixture.path and category == fixture.category:
                if alert.get("state") != "open":
                    raise ValueError(
                        f"alert {alert.get('number')} was not open despite the API filter"
                    )
                number = alert.get("number")
                if not isinstance(number, int):
                    raise ValueError(f"a {fixture.rule_id} alert has an invalid number")
                instance = require_mapping(
                    alert.get("most_recent_instance"), f"the most recent instance of alert {number}"
                )
                if instance.get("commit_sha") == merge_sha and instance.get("ref") == ref:
                    matches.append((number, severity))
                else:
                    elsewhere.append(
                        f"#{number} at {instance.get('commit_sha')!r} on {instance.get('ref')!r}"
                    )
        if not matches:
            detail = f" (only {', '.join(elsewhere)})" if elsewhere else ""
            failures.append(
                f"no open {fixture.rule_id} alert at {fixture.path} on the merge ref "
                f"for category {fixture.category} at the analysed merge commit {merge_sha[:9]}"
                f"{detail}"
            )
            lines.append(f"| `{fixture.path}` | `{fixture.rule_id}` | *absent* | — | no |")
            continue
        number, severity = matches[0]
        fixture_alerts[fixture.category] = number
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
    return Report(failures, [], lines), fixture_alerts


def check_verdicts(
    annotations: dict[str, object], fixture_alerts: dict[str, int]
) -> Report:
    failures: list[str] = []
    lines = ["| Job | Fixture alert | Blocking verdict | Ungated diagnostic |", "| --- | --- | --- | --- |"]
    for job_name, category in SAST_JOB_CATEGORIES.items():
        entries = flatten_alerts(annotations[job_name], f"the {job_name!r} annotations")
        titles: list[str] = []
        ungated: list[str] = []
        for index, entry in enumerate(entries):
            title = entry.get("title")
            message = entry.get("message")
            if not isinstance(title, (str, type(None))) or not isinstance(message, (str, type(None))):
                raise ValueError(
                    f"{job_name!r} annotation {index + 1} has an invalid title or message"
                )
            text = f"{title or ''}\n{message or ''}"
            if entry.get("annotation_level") == "failure" and title:
                titles.append(title)
            for marker in UNGATED_DIAGNOSTICS:
                if marker in text:
                    ungated.append(marker)
        number = fixture_alerts.get(category)
        expected = BLOCKING_TITLE.format(number=number) if number is not None else None
        blocked = expected is not None and expected in titles
        lines.append(
            f"| {job_name} | {'#' + str(number) if number is not None else '*absent*'} | "
            f"{'yes' if blocked else 'no'} | {', '.join(ungated) or 'none'} |"
        )
        if expected is None:
            failures.append(
                f"{job_name!r} cannot be tied to a blocking verdict: its fixture alert is absent"
            )
        elif not blocked:
            failures.append(
                f"{job_name!r} recorded no {expected!r} annotation, so its failed gate step is "
                "not a blocking verdict on the fixture"
            )
        for marker in ungated:
            failures.append(
                f"{job_name!r} reported {marker!r}: the gate failed without gating the analysis"
            )
    return Report(failures, [], lines)


def analysis_of(
    document: object,
    description: str,
    category: str,
    commit_sha: str,
    window: tuple[datetime, datetime] | None,
) -> tuple[dict[str, object] | None, str | None]:
    candidates: list[tuple[datetime, dict[str, object]]] = []
    errored = 0
    for index, analysis in enumerate(require_sequence(document, description)):
        entry = require_mapping(analysis, f"{description} entry {index + 1}")
        if entry.get("category") != category or entry.get("commit_sha") != commit_sha:
            continue
        error = entry.get("error")
        if not isinstance(error, (str, type(None))):
            raise ValueError(f"{description} entry {index + 1} has an invalid error field")
        if error:
            errored += 1
            continue
        if not isinstance(entry.get("results_count"), int):
            raise ValueError(f"{description} entry {index + 1} has an invalid results count")
        created = parse_timestamp(entry.get("created_at"), f"{description} entry {index + 1}")
        candidates.append((created, entry))
    skipped = f"{errored} errored analysis(es) skipped" if errored else None
    if not candidates:
        return None, skipped
    created, newest = max(candidates, key=lambda candidate: candidate[0])
    if window is not None and not window[0] <= created <= window[1]:
        return None, (
            f"the newest successful analysis was created at {newest.get('created_at')}, outside "
            f"the re-run's SAST job ({window[0].isoformat()} to {window[1].isoformat()})"
            + (f"; {skipped}" if skipped else "")
        )
    return newest, skipped


def check_analyses(
    ref_document: object,
    main_document: object,
    merge_commit: object,
    windows: dict[str, tuple[datetime, datetime]],
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
    lines = ["| Category | Base commit | Merge ref | Expected | Created |", "| --- | --- | --- | --- | --- |"]
    for fixture in FIXTURES:
        window = windows.get(fixture.category)
        ref_analysis, ref_note = (
            analysis_of(ref_document, "the merge-ref analyses", fixture.category, merge_sha, window)
            if window is not None
            else (None, "its SAST job is absent")
        )
        main_analysis, _ = analysis_of(
            main_document, "the main analyses", fixture.category, base_sha, None
        )
        if ref_analysis is None:
            failures.append(
                f"no {fixture.category} analysis of the merge commit {merge_sha[:9]} created by the "
                f"re-run attempt on the canary merge ref; the evidence is stale"
                + (f" ({ref_note})" if ref_note else "")
            )
        if main_analysis is None:
            failures.append(
                f"no {fixture.category} analysis of the base commit {base_sha[:9]} is among the "
                "fetched main analyses, so the canary has aged past the proof's page budget: rebase "
                "canary/sast-gate-proof onto main and force-push so the proof compares against a "
                "recent base"
            )
        if ref_analysis is None or main_analysis is None:
            lines.append(f"| {fixture.category} | — | — | — | — |")
            continue
        expected = main_analysis["results_count"] + 1
        lines.append(
            f"| {fixture.category} | {main_analysis['results_count']} "
            f"| {ref_analysis['results_count']} | {expected} | {ref_analysis.get('created_at')} |"
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
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--attempt", required=True, type=int)
    parser.add_argument("--rerun-started-at", required=True)
    parser.add_argument("--expected-head-sha", required=True)
    parser.add_argument("--jobs", required=True, type=Path)
    parser.add_argument("--backend-annotations", required=True, type=Path)
    parser.add_argument("--frontend-annotations", required=True, type=Path)
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
        binding = RunBinding(
            args.run_id,
            args.attempt,
            require_sha(args.expected_head_sha, "the expected canary head"),
            parse_timestamp(args.rerun_started_at, "the re-run request"),
        )
        merge_commit = read_json(args.merge_commit)
        merge_sha = require_sha(
            require_mapping(merge_commit, "the merge commit").get("sha"), "the merge commit"
        )
        pull_request = read_json(args.pull_request)
        jobs = read_json(args.jobs)
        jobs_report, windows = check_jobs(jobs, binding.rerun_started_at)
        alerts_report, fixture_alerts = check_alerts(
            read_json(args.ref_alerts),
            read_json(args.pr_alerts),
            merge_sha,
            f"refs/pull/{args.canary_number}/merge",
        )
        annotations = {
            "Backend SAST (CodeQL)": read_json(args.backend_annotations),
            "Frontend SAST (CodeQL)": read_json(args.frontend_annotations),
        }
        reports = [
            (
                "Run binding",
                check_run(
                    read_json(args.run),
                    jobs,
                    merge_commit,
                    pull_request,
                    binding,
                    args.canary_number,
                ),
            ),
            ("Jobs", jobs_report),
            ("Alerts", alerts_report),
            ("Gate verdicts", check_verdicts(annotations, fixture_alerts)),
            (
                "Analysis counts",
                check_analyses(
                    read_json(args.ref_analyses),
                    read_json(args.main_analyses),
                    merge_commit,
                    windows,
                ),
            ),
            (
                "Branch protection",
                check_protection(read_json(args.branch), args.require_admin_enforcement),
            ),
            (
                "Mergeability",
                check_pull_request(pull_request, args.canary_number),
            ),
        ]
    except ValueError as error:
        print(f"::error::canary proof evidence was invalid: {error}", file=sys.stderr)
        return 2

    sections = [
        (
            "Run",
            [
                f"Canary pull request #{args.canary_number}, run {args.run_id} attempt "
                f"{args.attempt}, head `{args.expected_head_sha}`, merge commit `{merge_sha}`."
            ],
        ),
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
