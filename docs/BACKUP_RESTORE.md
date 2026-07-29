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
- **RTO (recovery time objective): about 5 minutes** — a point-in-time restore measured at 295
  seconds in the shipped drill, against a seeded database of 107 tables and roughly 65,000 rows
  (20.5 MB): 5,000 contacts, 1,000 companies, 2,000 deals, 20,000 activities, 5,000 notes, 3,000
  tasks and 1,000 attachment rows. That figure covers dump restore plus binlog replay and excludes
  the time an operator spends deciding and starting the restore. Taking the daily full backup is a
  separate 9 minutes on the same data, most of it the `RESTORE_VERIFY` scratch restore, and does not
  count against RTO. Your time scales with database size and hardware — measure your own RTO in your
  first drill and re-measure whenever your data volume grows materially.

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
| `tests/run-tests.sh` | Offline regression tests for the selection, coverage, and retention logic |

All scripts log single-line structured events (`ts=… level=… event=…`) to stdout — journald
captures them under the unit name — and exit nonzero with a documented per-class exit code on any
failure, so a `systemctl --failed` or an `OnFailure=` hook surfaces broken backups. **A backup
run that did not end with a `*_summary` event carrying `status=success exit_code=0` is not a
backup** (the full run logs `event=backup_summary status=success`).

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

   Schema selection defaults to "every schema the server has, minus the system ones".
   `CONNEX_BACKUP_SCHEMA_INCLUDE` is an **exclusive allowlist**: leave it empty unless you mean
   to restrict the backup, because once it is set, a schema missing from it is dropped from the
   backup (the run still succeeds and reports `event=schema_selection schema_count=…`). To keep a
   schema of your own that happens to be named `connex_verify_*` — the shape the restore-verify
   scratch schemas use, which are skipped with `reason=restore_verify_scratch` — name it in
   `CONNEX_BACKUP_SCHEMA_SCRATCH_OVERRIDE`, which lifts only that prefix rule and leaves the rest
   of the backup scope alone.

3. Verify the first run end-to-end:

   ```bash
   sudo systemctl start connex-backup.service
   journalctl -u connex-backup.service -n 20
   ```

   Confirm `event=backup_summary status=success`, then confirm the timers are active:
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
- **No binlog, no PITR.** The archive script fails closed if `log_bin` is off, `binlog_format` is
  not `ROW`, auto-purge is disabled, or the server's own `binlog_expire_logs_seconds` exceeds
  retention minus one day (29 days at the 30-day cap) — so server-side binlogs can never outlive
  the legal ceiling either. Set the `db` service's `binlog_expire_logs_seconds` to `2505600`
  (29 days) or less. The shipped Compose deployment has binlogs enabled by default. Configuration
  is not evidence, so each archive run also *observes* the oldest binlog the server still holds
  (`event=binlog_server_retention`), warns when it crosses the 29-day prune threshold, and fails
  the run if it ever reaches 30 days — purge it with `PURGE BINARY LOGS BEFORE …` and fix the
  server setting.
- **A hole in the binlog chain is written down, not papered over.** Two situations leave the
  recoverable window with a piece missing: a closed binlog that is already past the retention
  ceiling the first time the archive sees it (a long archive outage, or a first run against an old
  server), and a cursor file the server has purged since the last run. Both fail the run
  (`event=binlog_coverage_gap`, `reason=last_closed_file_missing`) — but only after the run records
  what it found. When the cursor is gone the run still archives every closed log the server does
  still hold, starting from the oldest, and re-bases the cursor onto the newest of them, so the
  archive is neither stuck forever on a file that is never coming back nor throwing away logs that
  are sitting there and fetchable. The hole it appends to `binlog/coverage-gap` therefore spans
  only what is genuinely missing — from the previously published coverage to the creation of the
  oldest log still on the server — so a full backup taken after the hole opened stays restorable.
  That file is append-only: no later archive run rewrites it and the pruner never deletes it.
  A PITR whose replay window overlaps a recorded hole is refused
  (`reason=archive_coverage_gap`); the remedy is the one the failure logs — take a new full backup
  to re-base the point-in-time coordinate, which moves the dump past the hole.
  A run that hit a hole also refuses to advance published coverage, since it cannot prove it can
  replay through the hole: it republishes the coverage of the last clean run, and the next clean
  run (one timer interval later) moves it forward again. A *first-ever* run that hits a hole has no
  earlier coverage to republish, so it publishes `coverage_through_epoch 0` and PITR is refused
  with `reason=target_not_archived` until that next run — expected, fail-closed, and transient.
- **Backups self-verify by default.** `CONNEX_BACKUP_RESTORE_VERIFY=true` restores each fresh dump
  into a throwaway scratch schema and compares base-table counts before the run is marked
  `COMPLETE`, so a gzip-valid but semantically incomplete dump cannot pass as good. Point the
  verify profile at a non-production host if the extra load matters, but do not disable it lightly.
- **PITR refuses to leak onto other schemas.** Before any replay, the restore decodes the binlog
  window and refuses (fail-closed) if it references any schema other than `--source-schema` — an
  allowlist, so an unknown schema name is a refusal, not a pass — or contains account/global/
  database-level statements (`GRANT`, `CREATE USER`, `SET GLOBAL`, `CREATE DATABASE`, …) that a
  schema rewrite cannot contain, or a qualified reference split across lines that the rewriter
  cannot safely retarget. It also decodes the window a second time through the exact filter the
  replay uses and compares complete Query events as a hash multiset, preserving event boundaries
  and duplicate occurrences. Statements the replay would drop while still naming the source
  schema — an `ALTER TABLE src.foo …` issued under a different default database — are a refusal
  (`reason=qualified_statement_without_matching_default_database`), because they would otherwise go
  missing from an apparently successful restore. A window in which the source schema was merely
  idle drops plenty of unrelated text and is not a refusal.
  `--force-overwrite` downgrades this refusal to a logged warning for the
  rare case where you have vetted the window yourself. The default configuration replays into the
  **same server** the backup came from; the run logs `event=pitr_replay_target_shared` to say so.
  Point `CONNEX_BACKUP_RESTORE_DB_*` at a separate host when you can — a restore server that is
  not production is the only guard that cannot be defeated by a parser gap.
- **A silent empty replay is a failure, not a success.** The replay counts the row events the
  archived window holds for the source schema and the row events actually applied to the target.
  If the window provably contains events and none were applied, PITR fails
  (`reason=zero_events_applied`) instead of reporting a dump-time restore as a point-in-time one.
  Both counts appear on `event=pitr_completed` / `event=pitr_summary`.
- **Scratch and sidecar restores do not enter the binary log.** Restore-verify schemas, and any
  restore/PITR into a schema other than the source, run with `SET SESSION sql_log_bin = 0`, so a
  daily self-verify does not write a second full copy of your database into the binlogs (and into
  every later PITR window). This needs `BINLOG_ADMIN` (or `SUPER`) on the verify/restore account:
  the backup fails closed if the verify account lacks it, while a restore falls back to logged
  mode with a warning rather than blocking a recovery.
- **Pruning is resilient.** A single corrupt or half-written artifact is skipped and logged
  (`event=prune_skipped`), never aborting the whole prune — one bad file can't silently freeze
  retention. The prune timer runs twice daily and a failed run retries every 15 minutes, so a
  transient failure still clears well inside the one-day margin under the legal ceiling.
- **Interrupted binlog publication is recovered before orphan pruning.** The raw bytes, checksum,
  and metadata are synced under pending names before final atomic renames. Archive and prune runs
  validate and finish any complete pending triplet, including the legacy crash state with final raw
  bytes and pending sidecars. A genuinely metadata-less raw file that is not part of a recoverable
  publication is still quarantined after the 24-hour failed-run grace
  (`reason=orphaned_binlog_without_metadata`). That clock honours the ceiling: the file's mtime is
  the local fetch time, so a catch-up archive run would otherwise keep month-old events for another
  29 days. "Archived binlog" is decided by the archive's own naming — the file
  prefix recorded in `binlog/archive-state`, or in the metadata sidecars if that file is gone —
  *and* the binary-log magic bytes. Anything else in the binlog root — an operator's own file, a
  future sidecar, anything at all when neither source can tell the pruner what the archive's files
  are called — keeps its advisory `event=prune_skipped` warning and is never deleted.
- **The newest complete dump is never pruned.** Retention deletes by age, but the single newest
  complete run is exempt: a deployment whose backups have been failing for a month keeps its last
  good dump rather than ending up with nothing. If your data-protection commitments require the
  30-day ceiling to win even over that, delete the backup root by hand when you decommission the
  deployment — the tooling will not leave you with zero recoverable backups on its own.
- **Disk-space guard.** The full-dump script refuses to start below a configurable free-space
  floor rather than producing a truncated dump.
- **Retention versus PITR depth.** Pruning at 29 days means the oldest reachable PITR moment
  moves forward daily. That is the intended consequence of the 30-day deletion commitment.
