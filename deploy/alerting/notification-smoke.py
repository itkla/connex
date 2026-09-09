#!/usr/bin/env python3
"""Exercise unmodified alert rules through real Prometheus and Alertmanager into a local receiver."""
import argparse
import contextlib
import http.server
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
EXPECTED = {
    ('ConnexAuthenticationFailureTenantSpike', 'workspace:7'),
    ('ConnexAuthenticationFailureTenantSpike', 'workspace:42'),
    ('ConnexAuthenticationFailureGlobalSpike', ''),
    ('ConnexPermissionChange', 'workspace:7'),
    ('ConnexPermissionChange', 'workspace:8'),
    ('ConnexServerErrorRateSpike', ''),
    ('ConnexBackupFailure', ''),
    ('ConnexAuditIntegrityAnomaly', 'workspace:7'),
}
SUMMARIES = {
    'ConnexAuthenticationFailureTenantSpike':
        'At least 20 audited authentication failures in one tenant in 5 minutes',
    'ConnexAuthenticationFailureGlobalSpike':
        'At least 100 audited authentication failures globally in 5 minutes',
    'ConnexPermissionChange':
        'A role definition or membership privilege changed within 15 minutes',
    'ConnexServerErrorRateSpike':
        'More than 5 percent HTTP 5xx responses with at least 100 requests in 5 minutes',
    'ConnexBackupFailure':
        'A backup job failed and requires operator acknowledgement',
    'ConnexAuditIntegrityAnomaly':
        'A read audit row has an invalid or unverifiable HMAC',
}


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prometheus', required=True)
    parser.add_argument('--alertmanager', required=True)
    parser.add_argument('--rules', type=Path, default=ROOT / 'security-rules.yml',
                        help='Rule file override for trigger mutation testing')
    parser.add_argument('--fixtures', type=Path, default=ROOT / 'fixtures',
                        help='Metric fixtures; pass backend/build/security-alerts for Java emission evidence')
    args = parser.parse_args()
    fixture_dir = args.fixtures
    baseline = (fixture_dir / 'baseline.prom').read_text()
    triggered = (fixture_dir / 'triggered.prom').read_text()
    print('FIXTURES ' + str(fixture_dir.resolve()), flush=True)
    received = queue.Queue()
    phase = threading.Event()
    prom_port, alert_port = free_port(), free_port()
    with tempfile.TemporaryDirectory(prefix='connex-alert-smoke-') as work_dir:
        work = Path(work_dir)

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                if self.path == '/tenant':
                    fixture = triggered if phase.is_set() else baseline
                    body = '\n'.join(line for line in fixture.splitlines()
                                     if 'authentication_failures_total{scope="unattributed"}' in line) + '\n'
                elif self.path == '/backup':
                    body = (work / 'connex-backup.prom').read_text() if phase.is_set() else 'connex_backup_failed 0\n'
                else:
                    body = triggered.replace('2.0E9', str(int(time.time()))) if phase.is_set() else baseline
                    if not phase.is_set():
                        body = body.replace('scope="workspace:7"} 0.0', 'scope="workspace:7"} 19.0')
                        body = body.replace('scope="unattributed"} 0.0', 'scope="unattributed"} 80.0')
                    success, failure = (370, 30) if phase.is_set() else (190, 10)
                    body += '# TYPE http_server_requests_seconds_count counter\n'
                    body += f'http_server_requests_seconds_count{{status="200",uri="sensitive@example.invalid"}} {success}\n'
                    body += f'http_server_requests_seconds_count{{status="500",uri="sensitive-secret"}} {failure}\n'
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain; version=0.0.4')
                self.end_headers()
                self.wfile.write(body.encode())

            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                received.put(json.loads(body))
                self.send_response(200)
                self.end_headers()

        with http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            port = server.server_port
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            (work / 'webhook-url').write_text(f'http://127.0.0.1:{port}/notify')
            alert_config = (ROOT / 'alertmanager.yml').read_text().replace(
                '/etc/connex-alerting/security-webhook-url', str(work / 'webhook-url'))
            (work / 'alertmanager.yml').write_text(alert_config)
            dedicated = json.loads((ROOT / 'dedicated-tenant-scrape.json').read_text())
            dedicated.update(scheme='http', metrics_path='/tenant',
                             static_configs=[{'targets': [f'127.0.0.1:{port}']}])
            dedicated.pop('authorization')
            (work / 'prometheus.yml').write_text(json.dumps({
                'global': {'scrape_interval': '2s', 'evaluation_interval': '2s'},
                'rule_files': [str(args.rules.resolve())],
                'alerting': {'alertmanagers': [{'static_configs': [{'targets': [f'127.0.0.1:{alert_port}']}]}]},
                'scrape_configs': [
                    {'job_name': 'connex', 'static_configs': [{'targets': [f'127.0.0.1:{port}']}]},
                    {'job_name': 'connex-backup', 'metrics_path': '/backup',
                     'static_configs': [{'targets': [f'127.0.0.1:{port}']}]}, dedicated],
            }))
            processes = []
            try:
                with contextlib.ExitStack() as stack:
                    for name, command in [
                        ('alertmanager', [args.alertmanager, f'--config.file={work}/alertmanager.yml',
                                          f'--storage.path={work}/alertmanager-data', '--cluster.listen-address=',
                                          f'--web.listen-address=127.0.0.1:{alert_port}']),
                        ('prometheus', [args.prometheus, f'--config.file={work}/prometheus.yml',
                                        f'--storage.tsdb.path={work}/prometheus-data',
                                        f'--web.listen-address=127.0.0.1:{prom_port}']),
                    ]:
                        log = stack.enter_context(open(work / f'{name}.log', 'w'))
                        processes.append(subprocess.Popen(command, stdout=log, stderr=log))
                    readiness_deadline = time.monotonic() + 180
                    for monitoring_port in (prom_port, alert_port):
                        while True:
                            assert all(p.poll() is None for p in processes), 'Monitoring process exited'
                            assert time.monotonic() < readiness_deadline, 'Monitoring readiness timed out'
                            try:
                                with urllib.request.urlopen(
                                        f'http://127.0.0.1:{monitoring_port}/-/ready', timeout=2) as response:
                                    if response.status == 200:
                                        break
                            except (urllib.error.URLError, TimeoutError):
                                pass
                            time.sleep(1)
                    print('READY Prometheus and Alertmanager', flush=True)
                    started = time.monotonic()
                    while time.monotonic() - started < 35:
                        assert all(p.poll() is None for p in processes), 'Monitoring process exited'
                        time.sleep(1)
                    with urllib.request.urlopen(f'http://127.0.0.1:{prom_port}/api/v1/alerts', timeout=5) as response:
                        alerts = json.load(response)['data']['alerts']
                    assert not alerts, f'Below-threshold alerts: {alerts}'
                    assert received.empty(), 'Unexpected below-threshold notification'
                    print('NEGATIVE PASS tenant=19 global=99 no new HTTP requests: no notifications', flush=True)
                    env = dict(os.environ, CONNEX_BACKUP_ENV_FILE=str(work / 'absent.env'))
                    backup = subprocess.run(['bash', str(ROOT.parent / 'backup/connex-backup-full.sh')],
                                            env=env, capture_output=True, text=True, timeout=15)
                    assert backup.returncode == 64, f'Expected backup configuration failure, got {backup.returncode}'
                    subprocess.run(['bash', str(ROOT / 'backup-failure.sh')], check=True, timeout=10,
                                   env=dict(os.environ, CONNEX_ALERT_TEXTFILE_DIR=str(work)))
                    print('BACKUP PASS actual backup command exit=64; OnFailure helper invoked locally', flush=True)
                    phase.set()
                    seen = set()
                    deadline = time.monotonic() + 90
                    while seen != EXPECTED and time.monotonic() < deadline:
                        assert all(p.poll() is None for p in processes), 'Monitoring process exited'
                        try:
                            payload = received.get(timeout=1)
                        except queue.Empty:
                            continue
                        assert payload['receiver'] == 'security-oncall'
                        encoded = json.dumps(payload)
                        assert 'sensitive' not in encoded and '@example' not in encoded, 'PII leaked'
                        for alert in payload['alerts']:
                            if alert['status'] != 'firing':
                                continue
                            labels = alert['labels']
                            assert set(labels) <= {'alertname', 'severity', 'scope'}, labels
                            identity = (labels['alertname'], labels.get('scope', ''))
                            assert identity in EXPECTED, identity
                            assert labels['severity'] in {'warning', 'critical'}, labels
                            assert alert['annotations'] == {
                                'summary': SUMMARIES[labels['alertname']]
                            }, alert['annotations']
                            if identity not in seen:
                                print('NOTIFICATION ' + json.dumps({
                                    'receiver': payload['receiver'], 'status': alert['status'],
                                    'labels': labels, 'annotations': alert['annotations'],
                                }, sort_keys=True), flush=True)
                            seen.add(identity)
                    assert seen == EXPECTED, f'Missing notifications: {EXPECTED - seen}'
                    print('PASS 8 firing notifications; all 5 signals; real HTTP receiver; '
                          'PII/secret canaries absent from full payload; '
                          'exact static annotations and bounded labels', flush=True)
            except BaseException:
                for logfile in work.glob('*.log'):
                    print(logfile.name + '\n' + logfile.read_text()[-4000:])
                raise
            finally:
                for process in processes:
                    process.terminate()
                for process in processes:
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                server.shutdown()
                thread.join(timeout=5)


if __name__ == '__main__':
    main()
