#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


BLOCKING_SECURITY_SEVERITIES = {"critical", "high"}
VALID_SECURITY_SEVERITIES = BLOCKING_SECURITY_SEVERITIES | {"medium", "low", None}
VALID_SEVERITIES = {"error", "warning", "note"}


class BlindAnalysisError(ValueError):
    """The analysis the gate would rely on is missing or stored nothing to gate on."""


def load_alerts(path: Path) -> list[dict[str, object]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, list):
        raise ValueError("the paginated response must be a JSON array")

    alerts: list[dict[str, object]] = []
    for page_index, page in enumerate(document):
        if not isinstance(page, list):
            raise ValueError(f"page {page_index + 1} must be a JSON array")
        for alert_index, alert in enumerate(page):
            if not isinstance(alert, dict):
                raise ValueError(
                    f"alert {alert_index + 1} on page {page_index + 1} must be a JSON object"
                )
            alerts.append(alert)
    return alerts


def load_analyses(path: Path) -> list[dict[str, object]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, list):
        raise ValueError("the analyses response must be a JSON array")

    analyses: list[dict[str, object]] = []
    for index, analysis in enumerate(document):
        if not isinstance(analysis, dict):
            raise ValueError(f"analysis {index + 1} must be a JSON object")
        analyses.append(analysis)
    return analyses


def alert_fields(
    alert: dict[str, object],
) -> tuple[int, str, str, str | None, str, str, str, str]:
    number = alert.get("number")
    state = alert.get("state")
    url = alert.get("html_url")
    rule = alert.get("rule")
    most_recent_instance = alert.get("most_recent_instance")
    if not isinstance(number, int) or number <= 0:
        raise ValueError("an alert has an invalid number")
    if state != "open":
        raise ValueError(f"alert {number} was not open despite the API filter")
    if not isinstance(url, str) or not url.startswith("https://github.com/"):
        raise ValueError(f"alert {number} has an invalid GitHub URL")
    if not isinstance(rule, dict):
        raise ValueError(f"alert {number} has no rule object")
    if not isinstance(most_recent_instance, dict):
        raise ValueError(f"alert {number} has no most recent instance")

    rule_id = rule.get("id")
    severity = rule.get("severity")
    security_severity = rule.get("security_severity_level")
    category = most_recent_instance.get("category")
    instance_ref = most_recent_instance.get("ref")
    instance_state = most_recent_instance.get("state")
    if not isinstance(rule_id, str) or not rule_id:
        raise ValueError(f"alert {number} has an invalid rule id")
    if severity not in VALID_SEVERITIES:
        raise ValueError(f"alert {number} has an unknown severity: {severity!r}")
    if security_severity not in VALID_SECURITY_SEVERITIES:
        raise ValueError(
            f"alert {number} has an unknown security severity: {security_severity!r}"
        )
    if not isinstance(category, str) or not category:
        raise ValueError(f"alert {number} has an invalid analysis category")
    if not isinstance(instance_ref, str) or not instance_ref:
        raise ValueError(f"alert {number} has an invalid instance ref")
    if instance_state != "open":
        raise ValueError(
            f"alert {number} has a most recent instance that is not open: {instance_state!r}"
        )
    return number, rule_id, severity, security_severity, state, url, category, instance_ref


def require_ref(number: int, instance_ref: str, expected_ref: str, role: str) -> None:
    if instance_ref != expected_ref:
        raise ValueError(
            f"alert {number} was analysed on {instance_ref}, not the {role} {expected_ref}"
        )


def baseline_numbers(alerts: list[dict[str, object]], baseline_ref: str) -> set[int]:
    if not baseline_ref:
        raise ValueError("the baseline ref must not be empty")
    numbers: set[int] = set()
    for alert in alerts:
        number, _, _, _, _, _, _, instance_ref = alert_fields(alert)
        require_ref(number, instance_ref, baseline_ref, "baseline ref")
        numbers.add(number)
    return numbers


def newest_analysis(
    analyses: list[dict[str, object]], category: str, commit_sha: str | None
) -> tuple[str, int] | None:
    for index, analysis in enumerate(analyses):
        if analysis.get("category") != category:
            continue
        sha = analysis.get("commit_sha")
        if not isinstance(sha, str) or not sha:
            raise ValueError(f"analysis {index + 1} has an invalid commit sha")
        if commit_sha is not None and sha != commit_sha:
            continue
        results = analysis.get("results_count")
        if not isinstance(results, int) or results < 0:
            raise ValueError(f"analysis {index + 1} has an invalid results count")
        return sha, results
    return None


def require_live_analysis(
    analyses: list[dict[str, object]],
    category: str,
    commit_sha: str,
    ref: str,
    baseline_analyses: list[dict[str, object]] | None,
    baseline_ref: str | None,
) -> str:
    if not commit_sha:
        raise ValueError("the analysed commit must not be empty")
    analysed = newest_analysis(analyses, category, commit_sha)
    if analysed is None:
        raise BlindAnalysisError(
            f"no {category} analysis of {commit_sha[:9]} was stored on {ref}"
        )
    _, results = analysed
    summary = f"{category} analysis of {commit_sha[:9]} on {ref} stored {results} result(s)"
    if baseline_analyses is None:
        return summary
    reference = newest_analysis(baseline_analyses, category, None)
    if reference is None:
        return f"{summary}; {baseline_ref} has no {category} analysis to compare against"
    reference_sha, reference_results = reference
    if results == 0 and reference_results > 0:
        raise BlindAnalysisError(
            f"the {category} analysis of {commit_sha[:9]} on {ref} stored 0 results while the "
            f"newest on {baseline_ref} ({reference_sha[:9]}) stored {reference_results}: results "
            "were pruned before upload or the upload was empty, so an empty alert set proves nothing"
        )
    return f"{summary} against {reference_results} on {baseline_ref} ({reference_sha[:9]})"


def blocking_alerts(
    alerts: list[dict[str, object]],
    expected_category: str,
    expected_ref: str,
    baseline: set[int] | None = None,
) -> tuple[list[tuple[int, str, str, str]], int]:
    if not expected_category:
        raise ValueError("the expected analysis category must not be empty")
    if not expected_ref:
        raise ValueError("the analysed ref must not be empty")

    blocking: list[tuple[int, str, str, str]] = []
    pre_existing = 0
    for alert in alerts:
        number, rule_id, severity, security_severity, _, url, category, instance_ref = (
            alert_fields(alert)
        )
        require_ref(number, instance_ref, expected_ref, "analysed ref")
        if category != expected_category:
            continue
        if security_severity in BLOCKING_SECURITY_SEVERITIES or severity == "error":
            if baseline is not None and number in baseline:
                pre_existing += 1
                continue
            effective_severity = security_severity or severity
            blocking.append((number, rule_id, effective_severity, url))
    return blocking, pre_existing


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Fail on CodeQL Critical, High, or error-severity alerts open on the analysed ref "
            "that are not already open on the base ref, and refuse an analysis that stored "
            "nothing to gate on"
        )
    )
    parser.add_argument("alerts", type=Path)
    parser.add_argument("category")
    parser.add_argument("--ref", required=True)
    parser.add_argument("--analyses", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--baseline-ref")
    parser.add_argument("--baseline-analyses", type=Path)
    args = parser.parse_args()
    baseline_flags = (args.baseline, args.baseline_ref, args.baseline_analyses)
    if any(flag is None for flag in baseline_flags) != all(flag is None for flag in baseline_flags):
        parser.error("--baseline, --baseline-ref and --baseline-analyses must be given together")
    return args


def main() -> int:
    args = parse_args()
    try:
        alerts = load_alerts(args.alerts)
        analyses = load_analyses(args.analyses)
        baseline = None
        baseline_analyses = None
        if args.baseline is not None:
            baseline = baseline_numbers(load_alerts(args.baseline), args.baseline_ref)
            baseline_analyses = load_analyses(args.baseline_analyses)
        liveness = require_live_analysis(
            analyses, args.category, args.commit, args.ref, baseline_analyses, args.baseline_ref
        )
        blocking, pre_existing = blocking_alerts(alerts, args.category, args.ref, baseline)
    except BlindAnalysisError as error:
        print(f"::error::CodeQL analysis cannot be gated: {error}", file=sys.stderr)
        return 2
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        print(f"::error::CodeQL alert response was invalid: {error}", file=sys.stderr)
        return 2

    print(liveness)
    if baseline is not None:
        print(
            f"{pre_existing} pre-existing alert(s) on {args.baseline_ref} ignored "
            f"({len(baseline)} open there in total)"
        )
    if not blocking:
        print(
            f"CodeQL alert check passed: {len(alerts)} queried open alert(s) on {args.ref}, "
            f"none in {args.category} at the blocking threshold"
        )
        return 0

    for number, rule_id, severity, url in blocking:
        print(
            f"::error title=Blocking CodeQL alert #{number}::{rule_id} ({severity}) {url}",
            file=sys.stderr,
        )
    print(
        f"CodeQL alert check failed: {len(blocking)} blocking alert(s) on {args.ref}",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
