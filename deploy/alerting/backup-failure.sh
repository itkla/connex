#!/bin/bash
# Invoked by the existing backup units' OnFailure hook; never copy backup logs into alerts.
set -euo pipefail
umask 022
metric_dir="${CONNEX_ALERT_TEXTFILE_DIR:-/var/lib/prometheus/node-exporter}"
metric_tmp="$(mktemp "$metric_dir/.connex-backup.XXXXXX")"
trap 'rm -f "$metric_tmp"' EXIT
printf '# HELP connex_backup_failed Backup failure pending operator acknowledgement\n# TYPE connex_backup_failed gauge\nconnex_backup_failed 1\n' > "$metric_tmp"
chmod 0644 "$metric_tmp"
mv -f "$metric_tmp" "$metric_dir/connex-backup.prom"
