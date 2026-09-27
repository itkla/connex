"""Offline command-boundary tests using a synthetic curl executable."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class CommandTest(unittest.TestCase):
    def test_sanitizer_cli_failure_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            mapping = Path(directory) / 'map.txt'
            mapping.write_text('{}')
            for raw in ['secret-fixture', '{"errors":["secret-fixture"]}', '[null]', 'x' * (32 * 1024 * 1024 + 1)]:
                result = subprocess.run([sys.executable, str(ROOT / 'sanitize_events.py'), str(mapping)],
                                        input=raw, text=True, capture_output=True)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, '')
                self.assertEqual(result.stderr, 'ERROR: rejected input; no event data emitted\n')

    def command(self, script, mode):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            curl = base / 'curl'
            curl.write_text('''#!/usr/bin/env python3
import json, os, sys
assert sys.argv[1] == '--disable'
mode = os.environ['FIXTURE_MODE']
url = sys.argv[-1]
if mode == 'transport':
    sys.exit(22)
if mode == 'api-error':
    print(json.dumps({'success': False, 'errors': ['secret-fixture']}))
elif mode == 'pagination':
    print(json.dumps({'success': True, 'result': [], 'result_info': {'total_pages': 2}}))
elif url.endswith('/graphql'):
    events = [{'action': 'block', 'source': 'rateLimit', 'ruleId': 'a' * 32,
               'datetime': '2026-09-08T01:01:00Z', 'clientRequestHTTPHost': 'preview.connexcrm.jp',
               'clientRequestHTTPMethodName': 'POST', 'future': 'secret-fixture'}]
    if mode == 'cap':
        events *= 10000
    print(json.dumps({'data': {'viewer': {'zones': [{'firewallEventsAdaptive': events}]}}}))
else:
    result = [{'id': 'a' * 32}] if url.endswith('/rulesets') else {'enabled': True}
    print(json.dumps({'success': True, 'result': result}))
''')
            curl.chmod(0o700)
            mapping = base / 'map.txt'
            mapping.write_text(json.dumps({'a' * 32: 'CF-RL-01-AUTH-ASSERT'}))
            env = dict(os.environ, PATH=str(base) + os.pathsep + os.environ['PATH'],
                       CLOUDFLARE_API_TOKEN='synthetic-token', CLOUDFLARE_ZONE_ID='b' * 32,
                       TMPDIR=directory, FIXTURE_MODE=mode)
            args = [str(base / 'output')] if script == 'export-zone.sh' else [
                '2026-09-08T01:00:00Z', '2026-09-08T01:10:00Z', str(mapping)]
            result = subprocess.run(['bash', str(ROOT / script), *args], env=env,
                                    text=True, capture_output=True)
            files = sorted(p.name for p in (base / 'output').glob('*'))
            self.assertNotIn('secret-fixture', result.stdout + result.stderr)
            self.assertNotIn('synthetic-token', result.stdout + result.stderr)
            return result, files

    def test_export_success(self):
        result, files = self.command('export-zone.sh', 'ok')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(files, ['COMPLETE.txt', 'bots.private.txt', 'ruleset-' + 'a' * 32 + '.private.txt',
                                 'rulesets.private.txt', 'settings.private.txt', 'zone.private.txt'])

    def test_export_failures_have_no_completion_marker(self):
        for mode in ['transport', 'api-error', 'pagination']:
            result, files = self.command('export-zone.sh', mode)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('COMPLETE.txt', files)

    def test_capture_sanitizes(self):
        result, _ = self.command('capture-events.sh', 'ok')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['aggregates'][0]['rule'], 'CF-RL-01-AUTH-ASSERT')

    def test_capture_failures_emit_no_evidence(self):
        for mode in ['transport', 'api-error', 'cap']:
            result, _ = self.command('capture-events.sh', mode)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main(verbosity=2)
