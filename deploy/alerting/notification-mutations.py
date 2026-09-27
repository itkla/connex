#!/usr/bin/env python3
"""Require each disabled security trigger to fail the real notification drill independently."""
import argparse
import ast
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent
TRIGGERS = (
    'ConnexAuthenticationFailureTenantSpike',
    'ConnexAuthenticationFailureGlobalSpike',
    'ConnexPermissionChange',
    'ConnexServerErrorRateSpike',
    'ConnexBackupFailure',
    'ConnexAuditIntegrityAnomaly',
)


def receipts(output):
    result = set()
    for line in output.splitlines():
        if line.startswith('NOTIFICATION '):
            labels = json.loads(line.removeprefix('NOTIFICATION '))['labels']
            result.add((labels['alertname'], labels.get('scope', '')))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prometheus', required=True)
    parser.add_argument('--alertmanager', required=True)
    parser.add_argument('--fixtures', type=Path, default=ROOT / 'fixtures')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = [sys.executable, str(ROOT / 'notification-smoke.py'),
               '--prometheus', args.prometheus, '--alertmanager', args.alertmanager,
               '--fixtures', str(args.fixtures)]
    baseline = subprocess.run(command, capture_output=True, text=True, timeout=360)
    (args.output / 'baseline.log').write_text(baseline.stdout + baseline.stderr)
    assert baseline.returncode == 0, 'Unmodified notification drill failed; see baseline.log'
    expected = receipts(baseline.stdout)
    assert len(expected) == 8, expected
    print('BASELINE PASS 8 firing notifications', flush=True)
    rules = (ROOT / 'security-rules.yml').read_text()
    with tempfile.TemporaryDirectory(prefix='connex-alert-mutations-') as directory:
        for trigger in TRIGGERS:
            pattern = rf'(      - alert: {trigger}\n        expr: )([^\n]+)'
            mutated, count = re.subn(pattern, r'\1(\2) and on() (vector(0) == 1)', rules)
            assert count == 1, (trigger, count)
            rule_file = Path(directory) / 'security-rules.yml'
            rule_file.write_text(mutated)
            result = subprocess.run(command + ['--rules', str(rule_file)],
                                    capture_output=True, text=True, timeout=360)
            output = result.stdout + result.stderr
            (args.output / f'{trigger}.log').write_text(output)
            missing = {identity for identity in expected if identity[0] == trigger}
            matches = re.findall(r'^AssertionError: Missing notifications: (.+)$',
                                 output, re.MULTILINE)
            assert result.returncode != 0 and len(matches) == 1, (trigger, result.returncode)
            assert ast.literal_eval(matches[0]) == missing, (trigger, matches)
            assert receipts(output) == expected - missing, (trigger, receipts(output))
            print(f'MUTATION KILLED {trigger}: exit={result.returncode}; '
                  f'missing={sorted(missing)}; unaffected={len(expected - missing)}', flush=True)
    print('PASS all 6 trigger mutations across all 5 signal families', flush=True)


if __name__ == '__main__':
    main()
