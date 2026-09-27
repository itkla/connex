"""Reduce Cloudflare event arrays to fixed-vocabulary hourly counts; unknown data never passes."""
import collections
import datetime
import json
import re
import sys

HOSTS = {"preview.connexcrm.jp", "connexcrm.jp"}
METHODS = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT"}
ACTIONS = {"block", "challenge", "managed_challenge", "jschallenge", "skip", "log", "allow"}
SOURCES = {"waf", "firewallRules", "rateLimit", "botFight", "botManagement", "l7ddos", "securityLevel"}
LABELS = {"CF-RL-01-AUTH-ASSERT", "CF-RL-02-ACCOUNT-LIFECYCLE", "CF-RL-03-AI",
          "CF-RL-04-UPLOADS", "CF-RL-05-API-VOLUME", "CF-CUSTOM-01-METHODS",
          "CF-EX-01-WEBSOCKET", "CF-EX-02-TOKEN-CALLBACKS", "CF-EX-03-SAML",
          "CF-EX-04-UPLOADS", "CF-MANAGED", "CF-OWASP"}


def choose(value, allowed):
    return value if isinstance(value, str) and value in allowed else "REDACTED"


def sanitize(events, rule_map):
    if not isinstance(events, list) or len(events) > 100000:
        raise ValueError("invalid events")
    if not isinstance(rule_map, dict) or any(
        not isinstance(k, str) or not re.fullmatch(r"[a-f0-9]{32}", k)
        or not isinstance(v, str) or v not in LABELS for k, v in rule_map.items()
    ):
        raise ValueError("invalid mapping")
    counts = collections.Counter()
    for event in events:
        if not isinstance(event, dict):
            raise ValueError("invalid event")
        stamp = event.get("datetime")
        bucket = "REDACTED"
        if isinstance(stamp, str) and re.fullmatch(r"20\d\d-\d\d-\d\dT\d\d:\d\d:\d\dZ", stamp):
            try:
                bucket = datetime.datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").strftime("%Y-%m-%dT%H:00:00Z")
            except ValueError:
                pass
        rule = event.get("ruleId")
        counts[(bucket, choose(event.get("clientRequestHTTPHost"), HOSTS),
                choose(event.get("clientRequestHTTPMethodName"), METHODS),
                choose(event.get("action"), ACTIONS), choose(event.get("source"), SOURCES),
                rule_map.get(rule, "REDACTED") if isinstance(rule, str) else "REDACTED")] += 1
    keys = ("utc_hour", "hostname", "method", "action", "service", "rule")
    return {"schema": 1, "semantics": "observed rows, possibly sampled; not total requests",
            "aggregates": [dict(zip(keys, group), count=count) for group, count in sorted(counts.items())]}


def main():
    try:
        with open(sys.argv[1]) as mapping:
            rule_map = json.load(mapping)
        raw = sys.stdin.buffer.read(32 * 1024 * 1024 + 1)
        if len(raw) > 32 * 1024 * 1024:
            raise ValueError("too large")
        result = sanitize(json.loads(raw), rule_map)
        print(json.dumps(result, indent=2, sort_keys=True))
    except (ValueError, TypeError, OSError, IndexError, RecursionError):
        print("ERROR: rejected input; no event data emitted", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
