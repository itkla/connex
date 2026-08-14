#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


BLOCKING_SECURITY_SEVERITIES = {"critical", "high"}
VALID_SECURITY_SEVERITIES = BLOCKING_SECURITY_SEVERITIES | {"medium", "low", None}
VALID_SEVERITIES = {"error", "warning", "note"}


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


def alert_fields(
    alert: dict[str, object],
) -> tuple[int, str, str, str | None, str, str, str]:
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
    return number, rule_id, severity, security_severity, state, url, category


def blocking_alerts(
    alerts: list[dict[str, object]], expected_category: str
) -> list[tuple[int, str, str, str]]:
    if not expected_category:
        raise ValueError("the expected analysis category must not be empty")

    blocking: list[tuple[int, str, str, str]] = []
    for alert in alerts:
        number, rule_id, severity, security_severity, _, url, category = alert_fields(
            alert
        )
        if category != expected_category:
            continue
        if security_severity in BLOCKING_SECURITY_SEVERITIES or severity == "error":
            effective_severity = security_severity or severity
            blocking.append((number, rule_id, effective_severity, url))
    return blocking


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fail on scoped CodeQL Critical, High, or error-severity alerts"
    )
    parser.add_argument("alerts", type=Path)
    parser.add_argument("category")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        alerts = load_alerts(args.alerts)
        blocking = blocking_alerts(alerts, args.category)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        print(f"::error::CodeQL alert response was invalid: {error}", file=sys.stderr)
        return 2

    if not blocking:
        print(
            f"CodeQL alert check passed: {len(alerts)} queried open alert(s), "
            f"none in {args.category} at the blocking threshold"
        )
        return 0

    for number, rule_id, severity, url in blocking:
        print(
            f"::error title=Blocking CodeQL alert #{number}::{rule_id} ({severity}) {url}",
            file=sys.stderr,
        )
    print(
        f"CodeQL alert check failed: {len(blocking)} blocking alert(s)",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
