# Upgrade & version policy

How Connex versions releases and how deployments — especially customer-operated on-prem, which
upgrades on its own cadence — move between them safely (issue #500, epic #502; delivers the
migration-discipline part of #87 §9 / #102).

> The specific support window below is a **proposed** policy; confirm it before it is published to
> customers.

## Versioning

- **SemVer**, one product version per release, stamped as a **version-locked backend + frontend image
  pair** (see [RELEASE.md](RELEASE.md)). The two are never upgraded independently; the running
  version is at `GET /api/version`.
- On-prem and air-gapped installs pin an **exact version/digest** — released tags are immutable and
  never moved.

## Supported upgrade paths (proposed)

- **Sequential minor upgrades within a major are supported** (e.g. `1.3 → 1.4 → 1.5`). Skipping
  intermediate minors is best-effort; skipping across a **major** requires stepping through the last
  minor of the prior major first.
- Every upgrade is **forward-only**. Downgrades are not supported (migrations are not reversed);
  recover by restoring the pre-upgrade backup.

## Migration discipline

- **Shipped migrations are immutable.** A migration that has been released is never edited, renamed,
  or deleted — doing so breaks Flyway checksum validation on any database that already applied it.
  Change is always a **new** migration. This is enforced in CI (`ci.yml` → *Migrations — forward-only
  guard*): a pull request may only **add** files under `backend/src/main/resources/db/migration/`.
- **Expand-contract with delayed drops.** The destructive half of a change lags the additive half by
  several releases, so a version-skipping customer never meets a `DROP` before their data has moved
  off the old shape:
  - **Add** the new column/table (nullable/backfilled) → deploy.
  - **Backfill** and cut over reads/writes → deploy.
  - **Drop** the old column/table **several releases later**, once no supported version still uses it.
  - Renames follow the same shape: add-new → backfill → drop-old-later, never an in-place rename.
- Migrations are **non-destructive and defensive** wherever possible, and are tested against
  realistic data volumes before release, not just empty schemas.
- The control/tenant **plane split** (`db/migration/{control,tenant}`) and version monotonicity are
  additionally enforced by the migration arch tests.

## On-prem upgrade runbook

1. **Back up first** — snapshot the database (and object/upload storage). Do not proceed without a
   verified, restorable backup; there is no automatic rollback of a migration.
2. **Pin the target version** — set `CONNEX_VERSION` to the exact release tag in `deploy/.env`.
3. **Pull and apply** — `docker compose pull && docker compose up -d`. Flyway runs the new migrations
   on backend start.
4. **Verify** — `curl -s http://<host>/api/version` shows the new version; smoke the app.
5. **On failure** — stop the stack, **restore the backup**, and pin back to the previous version.
   Never hand-edit `flyway_schema_history` or delete a partially-applied migration; restore instead.

## CI coverage & follow-ups

The forward-only guard (no editing shipped migrations) runs on every pull request. A fuller
**restore-a-previous-release → migrate → smoke** upgrade test becomes meaningful once released
version baselines exist; it is a follow-up on this issue.
