# Backup & restore

How a Connex deployment operator backs up the database, and how to get it back — whole or to a
point in time. This is **operator-run tooling shipped in the release deploy bundle**
(`deploy/backup/`): it runs on your host, against your database, on your schedule. Connex (the
vendor) has no access to your deployment and cannot run or verify your backups for you.

Applies to the standard Compose deployment ([DEPLOYMENT.md](DEPLOYMENT.md)) where MySQL runs as
the `db` service, and to any deployment whose MySQL is reachable over TCP. Object storage
(`/var/lib/connex/objects`) is a separate volume — include it in your backup media alongside these
database backups (see [DEPLOYMENT.md](DEPLOYMENT.md) and the upgrade runbook in
[UPGRADING.md](UPGRADING.md)); the tooling here covers the database.

## Policy

- **30-day rolling retention + point-in-time recovery (PITR).** Daily full logical dumps
  (per-schema `mysqldump`) plus continuous binary-log archiving, pruned automatically so that
  **nothing — dumps, archived binlogs, or server-side binlogs — is retained beyond 30 days.**
  The pruner deletes at 29 days so a daily timer can never let an artifact cross the 30-day
  wall-clock line.
- The 30-day cap is deliberate: it is reconcilable with the Connex DPA's 30-day post-termination
  deletion clause ("including from routine backups on their normal cycle"). Do **not** raise
  `CONNEX_BACKUP_RETENTION_DAYS` above 30 on a deployment holding customer personal data unless
  your own data-protection commitments allow it; the tooling validates `1..30` and treats 30 as a
  hard cap.
- **RPO (recovery point objective): 15 minutes** — the binlog-archive timer interval. Each archive
  run rotates the active binlog (`FLUSH BINARY LOGS`) and fetches every closed binlog, so at most
  the last timer interval of writes is at risk if the database host is lost entirely. (If only the
  database *process* is lost, the server's own binlogs usually close the gap to seconds.)
- **RTO (recovery time objective): FILL_FROM_DRILL** measured in the shipped restore drill on a
  seeded database (FILL_SIZE); your time scales with database size and hardware. Measure your own
  RTO in your first drill and re-measure whenever your data volume grows materially.

## What ships

Everything lives in `deploy/backup/` inside the release deploy bundle:

| Piece | Purpose |
|---|---|
| `backup.env.example` | Every knob, documented; copy to `/etc/connex-backup/backup.env` |
| `connex-backup-full.sh` | Daily per-schema full dump + integrity check |
| `connex-binlog-archive.sh` | 15-minute binlog rotation + archive (the PITR feed / RPO) |
| `connex-backup-prune.sh` | 30-day retention enforcement + stale-backup alarm |
| `connex-restore-full.sh` | Full restore into a fresh schema, with production guards |
| `connex-restore-pitr.sh` | Restore to a timestamp (full dump + binlog replay) |
| `shims/` | Docker client wrappers — no MySQL client tools needed on the host |
| `systemd/` + `install.sh` | Timers and a one-command root installer |

All scripts log single-line structured events (`ts=… level=… event=…`) to stdout — journald
captures them under the unit name — and exit nonzero with a documented per-class exit code on any
failure, so a `systemctl --failed` or an `OnFailure=` hook surfaces broken backups. **A backup
run that did not end with `event=summary status=ok` and exit 0 is not a backup.**

## Install (operator, once)

1. Unpack the release deploy bundle; from the deployment directory:

   ```bash
   sudo deploy/backup/install.sh
   ```

   This installs the scripts, systemd units, and timers (daily full dump, 15-minute binlog
   archive, daily prune), and creates `/etc/connex-backup/backup.env` (mode 0600) from the example
   on first run.

2. Edit `/etc/connex-backup/backup.env`: point it at your `db` container and
   credentials file, and set the backup root (a filesystem with room for ~35 daily dumps plus
   binlogs). Credentials go in a mode-0600 MySQL defaults file — never on a command line.

   Dumps and binlog archiving work with the pinned `db` image alone — the official MySQL images
   ship no `mysqlbinlog`, so binlog archiving copies closed binlogs at the file level
   (byte-identical). **PITR replay does need a real `mysqlbinlog`**: either install the MySQL
   community client tools on the restore host, or set `CONNEX_BACKUP_DOCKER_BINLOG_IMAGE` to a
   client-tools image that ships it (verified example: `percona/percona-server:8.4`). Do this at
   install time and confirm the replay tool answers (`/usr/local/lib/connex-backup/shims/mysqlbinlog
   --version`) — discover a missing replay tool during a drill, not during a disaster. Air-gapped deployments must mirror that image alongside
   the release images.

3. Verify the first run end-to-end:

   ```bash
   sudo systemctl start connex-backup.service
   journalctl -u connex-backup.service -n 20
   ```

   Confirm `event=summary status=ok`, then confirm the timers are active:
   `systemctl list-timers 'connex-*'`.

The backup root holds plaintext logical dumps of your database. Keep it on an encrypted volume,
owned by root with mode 0700 (the installer sets this), and replicate it off-host — a backup on
the same disk as the database protects you from nothing but `DROP TABLE`.

## Runbook: full restore

Restores the newest complete dump into a **fresh schema** — use this to verify backups, to stand
up a copy for inspection, or as step one of disaster recovery.

```bash
sudo connex-restore-full.sh latest --target-schema connexdb_restore_20260725
```

- The tool refuses to touch a schema named like production (`connex_pub`, `connexdb`, …,
  configurable denylist) **or any existing non-empty schema**. Both guards are absolute unless you
  pass `--force-overwrite` — which exists precisely for the real disaster-recovery moment when you
  *do* mean to overwrite production. Type it deliberately.
- Integrity (checksums, completeness marker) is verified before the target is touched.
- The summary line reports per-table row counts and wall-clock time — record both in your drill log.

To recover production in place: quiesce writers (stop ingress + frontend + backend, keep `db` up —
see the upgrade runbook in [UPGRADING.md](UPGRADING.md)), restore with `--force-overwrite` onto
the production schema, verify counts, then restart services and re-open ingress.

## Runbook: point-in-time restore (PITR)

Recovers to a chosen moment — e.g. right before a bad migration, a bulk delete, or a fat-fingered
import. Requires the binlog-archive timer to have been running.

```bash
sudo connex-restore-pitr.sh \
  --target-time '2026-07-25 14:59:00' \
  --source-schema connexdb \
  --target-schema connexdb_pitr
```

What it does: picks the newest complete full dump taken **before** the target time, restores it
into the target schema, then replays the archived binlogs from the dump's recorded coordinates up
to `--target-time` (UTC), rewriting events into the target schema. The same production guards and
`--force-overwrite` semantics apply as for full restore.

After it completes, inspect the restored schema, then either point the application at it or dump
it and restore over production (guards + `--force-overwrite`, writers quiesced). PITR can only
reach back as far as retention: with 30-day retention you can restore to any moment in roughly the
last 29 days.

## Drill cadence (recommended)

An unrehearsed backup is a hope, not a backup. Recommended operator cadence:

- **Quarterly, minimum** — and after any MySQL upgrade, schema-topology change (new schema), or
  backup-host change: run a PITR drill — restore to a timestamp between two real backups on a
  scratch schema, verify row counts/markers, record wall-clock times (your RTO) in your ops log.
- **Monthly** — restore-verify the newest full dump into a scratch schema
  (`connex-restore-full.sh latest --target-schema …drill…`) and compare table counts.
- **Weekly or via monitoring** — check `systemctl list-timers 'connex-*'` and the journal for
  `event=stale_backup` alarms from the pruner (it fires when the newest complete dump is older
  than 26 hours).

The vendor-side reference drill that produced the published RTO above is recorded in issue
[#853](https://github.com/itkla/connex/issues/853).

## Failure modes worth knowing

- **Partial dumps cannot masquerade as backups.** A run directory only counts (for restore and
  for the stale-backup alarm) once every schema dumped, compressed, and checksum-verified; failed
  runs are marked `FAILED` and pruned after a grace period.
- **No binlog, no PITR.** The archive script fails closed if `log_bin` is off or the server's own
  binlog expiry exceeds the 30-day cap. The shipped Compose deployment has binlogs enabled by
  default.
- **Disk-space guard.** The full-dump script refuses to start below a configurable free-space
  floor rather than producing a truncated dump.
- **Retention versus PITR depth.** Pruning at 29 days means the oldest reachable PITR moment
  moves forward daily. That is the intended consequence of the 30-day deletion commitment.
