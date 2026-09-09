"""Synthetic edge export regressions; no real client or account data."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


sanitizer = module("sanitize_events")
headers = module("public_headers")
RULE = "a" * 32


class EvidenceTest(unittest.TestCase):
    def test_realistic_event_and_unknown_fields_are_removed(self):
        event = {"datetime": "2026-09-08T01:24:51Z", "clientRequestHTTPHost": "preview.connexcrm.jp",
                 "clientRequestHTTPMethodName": "POST", "action": "block", "source": "rateLimit",
                 "ruleId": RULE, "clientIP": "192.0.2.17", "rayName": "synthetic-ray",
                 "clientRequestPath": "/api/delivery/webhooks/secret-fixture",
                 "clientRequestQuery": "email=person@example.invalid", "cookies": "session=secret-fixture",
                 "Authorization": "Bearer secret-fixture", "SAMLResponse": "secret-fixture",
                 "unknownFutureField": {"password": "secret-fixture"}}
        result = sanitizer.sanitize([event, event], {RULE: "CF-RL-01-AUTH-ASSERT"})
        encoded = json.dumps(result)
        for forbidden in ["192.0.2", "secret-fixture", "synthetic-ray", "example.invalid", "unknownFutureField"]:
            self.assertNotIn(forbidden, encoded)
        self.assertEqual(result['aggregates'][0]['count'], 2)
        self.assertEqual(result['aggregates'][0]['utc_hour'], '2026-09-08T01:00:00Z')

    def test_unknown_values_and_wrong_types_redact(self):
        event = dict.fromkeys(['datetime', 'clientRequestHTTPHost', 'clientRequestHTTPMethodName',
                               'action', 'source', 'ruleId'], 'secret-fixture')
        result = sanitizer.sanitize([event, {k: {} for k in event}], {})
        self.assertEqual(result['aggregates'][0]['count'], 2)
        self.assertNotIn('secret-fixture', json.dumps(result))
        self.assertEqual(set(result['aggregates'][0].values()), {'REDACTED', 2})

    def test_reject_invalid_envelope_and_mapping(self):
        for value in [{"errors": ["secret"]}, [None], "secret"]:
            with self.assertRaises(ValueError):
                sanitizer.sanitize(value, {})
        with self.assertRaises(ValueError):
            sanitizer.sanitize([], {RULE: 'person@example.invalid'})
        with self.assertRaises(ValueError):
            sanitizer.sanitize([], {RULE: 'CF-MANAGED-BEARER-SECRET'})

    def test_deterministic(self):
        events = [{'action': 'block'}, {'action': 'allow'}]
        self.assertEqual(sanitizer.sanitize(events, {}), sanitizer.sanitize(events[::-1], {}))

    def test_cli_error_does_not_echo_input(self):
        result = subprocess.run([sys.executable, str(ROOT / 'sanitize_events.py'), '/dev/null'],
                                input='secret-fixture', text=True, capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, '')
        self.assertEqual(result.stderr, 'ERROR: rejected input; no event data emitted\n')

    def test_hsts_strict_contract(self):
        self.assertTrue(headers.hsts_valid(['max-age=31536000; includeSubDomains']))
        for values in [[], ['max-age=0'], ['max-age=31536000'],
                       ['max-age=31536000; includeSubDomains; preload'],
                       ['max-age=31536000; includeSubDomains'] * 2,
                       ['max-age=31536000; includeSubDomains; unknown']]:
            self.assertFalse(headers.hsts_valid(values))


if __name__ == '__main__':
    unittest.main(verbosity=2)
