# Upgrade & version policy

How Connex versions releases and how deployments — especially customer-operated on-prem, which
upgrades on its own cadence — move between them safely (issue #500, epic #502; delivers the
migration-discipline part of #87 §9 / #102).

> The specific support window below is a **proposed** policy; confirm it before it is published to
> customers.

## Versioning

- **SemVer**, one product version per release, stamped as a **version-locked backend + frontend + OCR
  image set** (see [RELEASE.md](RELEASE.md)). The components are never upgraded independently, even
  when a low-resource deployment explicitly opts out of OCR; the running backend version is at
  `GET /api/version`.
- On-prem and air-gapped installs verify the tag-bound release manifest and pin its exact image
  digests. Registry version and SHA tags are convenience pointers and are never the deployment
  integrity boundary.

## Supported upgrade paths (proposed)

- **Sequential minor upgrades within a major are supported** (e.g. `1.3 → 1.4 → 1.5`). Skipping
  intermediate minors is best-effort; skipping across a **major** requires stepping through the last
  minor of the prior major first.
- Every upgrade is **forward-only**. Downgrades are not supported (migrations are not reversed);
  recover by restoring the pre-upgrade backup taken with the shipped backup tooling
  (`deploy/backup/`, see [BACKUP_RESTORE.md](BACKUP_RESTORE.md)).

## Migration discipline

- **Shipped migrations are immutable.** A migration that has been released is never edited, renamed,
  or deleted — doing so breaks Flyway checksum validation on any database that already applied it.
  Change is always a **new** migration. This is enforced in CI (`ci.yml` → *Migrations — forward-only
  guard*): pull requests and direct `main` pushes may only **add** files under
  `backend/src/main/resources/db/migration/`.
- **Expand-contract with delayed drops.** The destructive half of a change lags the additive half by
  several releases, so a version-skipping customer never meets a `DROP` before their data has moved
  off the old shape:
  - **Add** the new column/table (nullable/backfilled) → deploy.
  - **Backfill** and cut over reads/writes → deploy.
  - **Drop** the old column/table **several releases later**, once no supported version still uses it.
  - Renames follow the same shape: add-new → backfill → drop-old-later, never an in-place rename.
- Migrations are **non-destructive and defensive** wherever possible, and are tested against
  realistic data volumes before release, not just empty schemas. The
  [deterministic volume seeder](VOLUME_SEEDER.md#migration-timing-evidence) logs fresh-schema
  per-migration and total Flyway timing, then establishes the populated fixture used for separate
  upgrade-drill measurements.
- The control/tenant **plane split** (`db/migration/{control,tenant}`) and version monotonicity are
  additionally enforced by the migration arch tests.

## On-prem upgrade runbook

1. **Preflight legacy media before any recreate** — if the installed version predates private object
   storage, or any database row still refers to the old public `attachments`, `contact-pictures`,
   `company-logos`, or `profile-pictures` paths, do not use the generic commands below. First stage
   the writable-layer files from the still-running old frontend and follow
   [Migrating legacy public uploads](DEPLOYMENT.md#migrating-legacy-public-uploads). Pulling an image is
   safe; recreating the old frontend before staging can permanently erase those files.
2. **Quiesce writers** — stop external ingress plus the frontend, backend, and OCR services. Keep the
   database running. Confirm no application or maintenance container remains able to create or delete
   records or objects.
3. **Back up the quiesced set and exact deployment inputs** — snapshot the database (a
   `connex-backup-full.sh` run — see [BACKUP_RESTORE.md](BACKUP_RESTORE.md)), private object
   storage, and any staged legacy media as one recovery point. Download and verify the currently
   deployed release manifest, signature bundle, and exact `connex-<version>-deploy.tar`; preserve the
   extracted bundle without modification. Copy the mode-0600 `deploy/.env` byte-for-byte into the
   recovery set, retain its mode, and record SHA-256 hashes for both the deploy archive and environment
   file. Do not proceed without a verified, restorable data backup plus these exact prior deployment
   inputs; there is no automatic rollback of a migration.
4. **Pin and pull the complete target set** — verify the exact tag-bound `release-manifest.json` as
   documented in [RELEASE.md](RELEASE.md), then set `CONNEX_BACKEND_DIGEST`,
   `CONNEX_FRONTEND_DIGEST`, and `CONNEX_OCR_DIGEST` in the mode-0600 `deploy/.env` to the 64
   lowercase hexadecimal characters after `sha256:` for each image. Run `docker compose pull`.
   Low-resource deployments using the documented OCR opt-out must additionally run
   `docker compose --profile ocr pull ocr` while keeping the persisted `COMPOSE_PROFILES` value
   empty. Require the backend, frontend, and OCR digest to be locally available before starting the
   target version so every deployment stages the complete signed image set.
5. **Refresh the backup tooling and network discovery** — from the target deployment directory,
   rerun the shipped installer before Compose recreates the database. It preserves operator-owned
   settings, migrates a legacy `<project>_default` Docker network value to automatic discovery, and
   installs the matching shims used by scheduled backups and recovery:

   ```bash
   sudo ./backup/install.sh
   ```

   Do not skip this step when backups normally use `exec`: the Docker-backed `mysqlbinlog` recovery
   shim uses the same network discovery. Automatic discovery follows the configured DB container's
   actual Compose `db` network, including a project name selected with `-p` or
   `COMPOSE_PROJECT_NAME`.
6. **Normalize object-volume ownership when required** — the backend runtime identity is permanently
   `10001:10001`. Before the first upgrade from a preview image that used a dynamic UID/GID, run the
   following idempotent preflight while writers remain stopped:

   ```bash
   docker compose run --rm --no-deps --user 0 --entrypoint sh backend -c '
     current="$(stat -c "%u:%g" /var/lib/connex/objects)"
     if [ "$current" != "10001:10001" ]; then
       chown -R 10001:10001 /var/lib/connex/objects
     fi
     chmod 0700 /var/lib/connex/objects
   '
   ```

7. **Start the data plane with ingress closed** — leave Caddy stopped. Start the database, default
   OCR service, and backend first, and require Compose health before touching the old frontend
   container:

   ```bash
   docker compose up -d --wait --wait-timeout 300 db ocr backend
   ```

   For an existing deployment that uses the documented low-resource OCR opt-out, start only
   `db backend`; do not add `--profile ocr` ad hoc because `COMPOSE_PROFILES` is the persistent
   operator contract.

   Flyway and the normal-startup legacy-reference guard finish before backend health becomes ready.
   If legacy references remain, the backend fails and the old frontend container is left intact so
   its writable-layer media can still be staged. After backend health succeeds, start the frontend
   without Caddy:

   ```bash
   docker compose up -d --wait --wait-timeout 300 frontend
   ```

8. **Verify internally before publishing ingress** — use the pinned Caddy image as a one-shot client
   on the private Compose network:

   ```bash
   docker compose run --rm --no-deps --entrypoint wget caddy -qO- \
     http://backend:8080/api/version
   docker compose run --rm --no-deps --entrypoint wget caddy -qO- \
     http://backend:8080/api/capabilities
   docker compose run --rm --no-deps --entrypoint wget caddy -q --spider \
     http://frontend:3000/auth/login
   docker compose ps
   ```

   Require the target version, the expected business-card capabilities, healthy OCR unless the
   deployment uses the explicit low-resource opt-out,
   exact configured image digests, and stable running containers on a second probe. Smoke a private
   media download with an authenticated test session.
9. **Publish ingress last** — only after every internal check passes, start Caddy and run the external
   smoke:

   ```bash
   docker compose up -d --no-deps caddy
   curl -fsS http://<host>/api/version
   ```

   Reopen upstream ingress or writers only after that smoke succeeds.
10. **On pre-ingress failure** — keep Caddy and upstream ingress closed and stop the target application
   containers. Remove the target deployment directory, re-verify and extract the exact prior signed
   deploy archive, restore the prior mode-0600 `.env` byte-for-byte, and confirm both recorded hashes.
   Use that restored Compose bundle when you **restore the complete database, object, and legacy-media
   backup** and start the previous version. Changing only image digest variables is not a rollback
   because Compose structure and non-secret environment may have changed between releases. Restore is
   safe only while no post-backup writes have been admitted. If a failure occurs after ingress reopens,
   quiesce writers and assess the new writes before restoring. Never hand-edit
   `flyway_schema_history` or delete a partially-applied migration.

## CI coverage & follow-ups

The forward-only guard and its modify/delete/rename/type-change regression suite run on every pull
request and `main` push. `FlywayUpgradeIntegrationTest` migrates representative populated V73 media,
quota, deletion-queue, and import state through the current lineage. A full
**restore-a-previous-release image → migrate → smoke** gate remains a follow-up once stable released
version baselines exist.
