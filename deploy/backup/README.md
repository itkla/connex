# Connex on-prem database backups

This package gives a customer-operated Compose deployment daily compressed logical dumps, 15-minute MySQL binary-log archiving for point-in-time recovery, automated retention pruning, and guarded full/PITR restore commands.

It covers the MySQL database only. A complete recovery set must also protect the `object_data` volume or configured S3-compatible storage and the exact deployment bundle. See [`docs/BACKUP_RESTORE.md`](../../docs/BACKUP_RESTORE.md) for the full runbook and drill procedure.

## Quickstart

From the extracted release bundle:

```bash
sudo ./deploy/backup/install.sh
sudoedit /etc/connex-backup/backup.env
sudoedit /etc/connex-backup/source.cnf
sudo chmod 0600 /etc/connex-backup/backup.env /etc/connex-backup/source.cnf
sudo ./deploy/backup/install.sh
```

The first invocation creates the mode-0600 configuration and exits 64 until the TLS and PITR
prerequisites below are configured. The installer validates those settings before replacing
programs or enabling timers; correct any migration diagnostic and rerun it.

Set `CONNEX_BACKUP_DB_CONTAINER` to the running container for the Compose `db` service:

```bash
cd deploy
docker inspect --format '{{.Name}}' "$(docker compose ps -q db)"
```

The default `exec` client mode runs `mysql` and `mysqldump` inside that container, so the configured database endpoint is `localhost:3306`. Use a dedicated administrative backup account. A defaults file contains its secret without exposing it through `/proc`:

```ini
[client]
password=REPLACE_WITH_OPERATOR_SECRET
```

Create separate mode-0600 `verify.cnf` and `restore.cnf` files when those targets use different credentials. Remote source, verification, restore, and native remote-binlog connections require identity-verified TLS. Put the trusted CA path in each effective client defaults file, using a certificate whose identity matches the configured DB host:

```ini
[client]
password=REPLACE_WITH_OPERATOR_SECRET
ssl-mode=VERIFY_IDENTITY
ssl-ca=/etc/connex-backup/mysql-ca.pem
```

Every client invocation enforces `--ssl-mode=VERIFY_IDENTITY` after operator options, overriding weaker defaults. The MySQL handshake validates trust and hostname before queries/imports can transfer data; dumps additionally run a verified `SELECT 1` preflight before starting `mysqldump`. An incompatible client, plaintext-only server, untrusted CA, or mismatched identity fails closed. In `exec` mode, certificate paths must exist inside the DB container; only the credential defaults file is streamed there. In `run` mode, mount CA material read-only at its configured path.

For an explicitly local connection only, set the applicable `CONNEX_BACKUP_SOURCE_ALLOW_LOOPBACK_PLAINTEXT`, `CONNEX_BACKUP_VERIFY_ALLOW_LOOPBACK_PLAINTEXT`, or `CONNEX_BACKUP_RESTORE_ALLOW_LOOPBACK_PLAINTEXT` to `true`. These independent exceptions require the literal host `localhost`, `127.0.0.1`, or `::1`; they never allow remote/private network addresses or the Compose host `db`. Default `exec` mode connects to loopback inside the database container, so operators who use plaintext there must opt in for each applicable profile. The example leaves all exceptions disabled.

Before any plaintext connection, the actual client must successfully report its effective defaults
with `--print-defaults`. DNS SRV endpoint overrides (including loose and abbreviated options) are
refused in defaults and arguments because they can bypass the configured loopback host. Captured
defaults are never logged. Clients that cannot expose their defaults fail closed.

Daily dumps and binary-log archiving work with the bundled `mysql:8.4.10` database image alone. Archive stream mode lists closed logs through MySQL, then copies their immutable bytes from `/var/lib/mysql` with `docker exec ... cat`. Each copy must match the server-reported size and binary-log magic before it is published. Raw bytes, checksum, and metadata are synced before final atomic renames; archive and prune runs recover a valid interrupted publication instead of discarding its only raw copy. The paired stat command records filesystem birth and close times; stream mode fails closed when the database filesystem cannot report a positive birth time because legal-age pruning would otherwise be ambiguous.

PITR replay additionally requires a real `mysqlbinlog` executable. Set `CONNEX_BACKUP_DOCKER_BINLOG_IMAGE` to an independently approved `IMAGE@sha256:<64 lowercase hex digits>` reference for the supplied shim, or set `MYSQLBINLOG` to a native Oracle-compatible client. The release owner must review the client image and record its immutable registry digest before deployment; a mutable tag alone is refused before Docker is invoked, including for `--version`. Docker verifies downloaded content against that digest. A digest binds content to the approved artifact; it does not establish publisher trust by itself. Verify this prerequisite during installation:

```bash
sudo CONNEX_BACKUP_ENV_FILE=/etc/connex-backup/backup.env \
  /usr/local/lib/connex-backup/shims/mysqlbinlog --version
```

Air-gapped operators must mirror the configured client-tools image alongside the release images before a disaster. The official MySQL server images do not contain `mysqlbinlog`.

The replay shim runs a throwaway client-tools container with `--network none`, a read-only container filesystem, no credentials mount, and no extra operator mounts. It mounts only the requested files under the backup root, after checking binary-log magic, read-only at their requested absolute paths. The `--version` check has no host mounts. Output streams to the host; backup sentinels and checksum manifests are never mounted. The shim ignores client defaults and accepts only local decoding options. Use native `mysqlbinlog` for remote archive mode. `CONNEX_BACKUP_DOCKER_CLIENT_MODE=run` retains the same throwaway-container option for `mysql` and `mysqldump`; the default is `exec`. With `CONNEX_BACKUP_DOCKER_NETWORK=auto`, every database-transfer client discovers the physical network currently attached to `CONNEX_BACKUP_DB_CONTAINER` with Compose's logical `db` label. This follows `-p` and `COMPOSE_PROJECT_NAME` overrides without granting access to the edge, application, or OCR networks. When selecting run mode, set `CONNEX_BACKUP_DB_HOST`, `CONNEX_BACKUP_VERIFY_DB_HOST`, and `CONNEX_BACKUP_RESTORE_DB_HOST` to `db`; the default `localhost` values are for exec mode inside the DB container. An explicit custom network remains supported, but the shim refuses a missing network and refuses a network that differs from the running DB container when the target host is `db`. Native clients remain supported through `MYSQL`, `MYSQLDUMP`, and `MYSQLBINLOG`. Docker socket access is root-equivalent; systemd retains `ProtectSystem=strict`, `NoNewPrivileges`, and `PrivateTmp` but intentionally allows `/var/run/docker.sock`.

Rerunning `install.sh` during an upgrade preserves operator settings but migrates any retired
`<project>_default` value to `auto`. Before enabling timers it requires an absolute `ssl-ca` or
`ssl-capath` in each profile defaults file's `[client]` section, or that profile's explicit loopback
exception, and rejects configured mutable PITR image references. These offline checks do not prove
TLS handshakes or decoder availability; verify backups, archives, and a PITR drill after installation.
Any other configured network name is treated as an operator override, validated at runtime, and left
unchanged. The mandatory upgrade sequence is in
[`docs/UPGRADING.md`](../../docs/UPGRADING.md).

## Commands

```bash
sudo /usr/local/lib/connex-backup/connex-backup-full.sh
sudo /usr/local/lib/connex-backup/connex-binlog-archive.sh
sudo /usr/local/lib/connex-backup/connex-backup-prune.sh
sudo /usr/local/lib/connex-backup/connex-restore-full.sh latest \
  --source-schema connexdb --target-schema connex_restore
sudo /usr/local/lib/connex-backup/connex-restore-pitr.sh \
  --target-time '2026-07-25 12:30:00' \
  --source-schema connexdb --target-schema connex_restore
```

`--force-overwrite` deliberately overrides protected-name, target-collision, and non-empty-schema guards. It can destroy the production schema and exists for the final disaster-recovery step.

The default archive run flushes binary logs, making the 15-minute timer the RPO. Disabling flush is rejected unless `CONNEX_BACKUP_BINLOG_NO_FLUSH_ACK=true`; with that acknowledgement, the timer no longer bounds RPO and the operator owns log rotation.

Retention is legally capped at 30 days. Daily pruning starts at `RETENTION_DAYS - 1`, so the default deletes at 29 days and cannot cross 30 days because of timer delay. Server binary logging, automatic purge, row format, and expiry no greater than the configured cap are verified on every archive run.

Native hosts may select `CONNEX_BACKUP_BINLOG_FETCH_MODE=mysqlbinlog` to use `mysqlbinlog --read-from-remote-server --raw`. Stream commands always receive the absolute source binary-log path as their final argument. A native database host can use `CONNEX_BACKUP_BINLOG_STREAM=cat` with `CONNEX_BACKUP_BINLOG_DIR` set to its datadir; custom remote shims may stream one file to stdout under the same contract.

GTID-disabled servers are the normal Connex configuration. GTID-enabled sources can be dumped with `--set-gtid-purged=AUTO`, but optional scratch restore verification may require additional `SET_ANY_DEFINER` or related privileges. Restores are schema-level: `SET @@GLOBAL.GTID_PURGED` and `SET @@SESSION.SQL_LOG_BIN` statements embedded in a dump are stripped during import so restoring one schema never mutates global state on the target server.

## Tests

```bash
deploy/backup/tests/run-tests.sh
```

Offline regression tests for schema selection, the PITR preflight refusals, binlog coverage-gap handling, and retention pruning. They source the real scripts with stubbed server calls against a sandbox backup root, so they need no database, Docker, or root, and they run in CI on every pull request. The sandbox parent defaults to `/var/tmp/connex-backup-tests` and can be moved with `CONNEX_BACKUP_TEST_ROOT`; the harness runs that path through the real `backup_validate_absolute_path` before creating anything, so it cannot live under `/tmp` any more than a production backup root can.

The suite also records Docker arguments and models TLS handshake outcomes to pin fail-closed
dispatch. These offline cases do not substitute for certificate verification against real MySQL
fixtures. On a Docker-enabled release-verification host, the separate isolation drill uses a
trusted, already-cached digest-pinned image containing `sh` as a hostile decoder fixture:

```bash
CONNEX_BACKUP_ISOLATION_IMAGE='IMAGE@sha256:APPROVED_DIGEST' \
  bash deploy/backup/tests/test-binlog-isolation.sh
```

It preserves the real launcher's confinement options while replacing the decoder with a probe
that attempts to read synthetic credentials and overwrite both its input and a backup sentinel.
The drill refuses to pull images. Run remote MySQL transport drills separately for plaintext-only,
untrusted-certificate, hostname-mismatch, and trusted matching-certificate endpoints.

## Exit codes

| Code | Failure class |
|---:|---|
| 64 | Configuration |
| 65 | Lock held |
| 66 | Disk-space guard |
| 67 | Database or server preflight |
| 68 | Logical dump |
| 69 | Artifact integrity |
| 70 | Scratch restore verification |
| 71 | Binary-log archive |
| 72 | Retention pruning |
| 73 | Stale or missing complete backup |
| 74 | Restore safety guard |
| 75 | Full restore |
| 76 | PITR decode or replay |

Every command emits a single structured final summary. Failure summaries are also mirrored to stderr so systemd and external supervisors retain them when stdout is redirected.
