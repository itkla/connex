# Deterministic volume seeder

The backend seeder creates deterministic, tenant-scoped CRM fixtures through the same MyBatis
insert statements used by the application. It is intended for CI end-to-end tests, performance
work, and upgrade drills. It does not call entity services, rule-trigger publishers, notification
publishers, or audit publishers, so a run does not create per-row automation or notification
traffic.

## One-shot contract

Run the seeder only against a new or empty disposable schema. Organization and workspace slugs,
usernames, and emails are deterministic and globally unique. Rerunning the same fixture against an
already-seeded schema therefore fails on those unique values by design. Drop and recreate the
disposable schema, or select a new schema name, before another run.

Each workspace commits in its own transaction. If a multi-workspace run fails after an earlier
workspace committed, discard and recreate the schema rather than trying to resume it.

## Profiles and deterministic inputs

- `small` creates 50 contacts, 10 companies, 20 deals, 200 activities, 50 notes, 30 tasks, and 10
  external-URL attachment rows per workspace.
- `volume` creates 5,000 contacts, 1,000 companies, 2,000 deals, 20,000 activities, 5,000 notes,
  3,000 tasks, and 1,000 external-URL attachment rows per workspace.

Both profiles also create one organization and workspace, five users and memberships, two
pipelines with ten total stages, twelve tags, employment history, deal-contact links, deal stage
history, and tag links. English and Japanese names, kana-form near-duplicates, ownerless records,
missing contact fields, open and closed deals, and interactions spread across the prior 18 months
exercise realistic application paths.

The inputs are:

- `-PseederProfile=small|volume`, default `small`
- `-PseederSeed=<long>`, default `853`
- `-PseederWorkspaces=<1..100>`, default `1`
- `-PseederAnchorDate=YYYY-MM-DD`, default the current UTC date resolved once at run start
- `-PseederAllowRemoteHost=true|false`, default `false`

Every workspace receives a stable derived sub-seed. Entity fields are independently derived from
that sub-seed and their logical row index, so generation order changes do not shift unrelated
values. An explicit anchor date is recommended for reproducible CI evidence. Auto-increment IDs and
the natural `CURRENT_TIMESTAMP` values on control-plane, pipeline, attachment, and employment-row
metadata are not deterministic inputs; business fields, explicit interaction/employment/history
dates, and the controlled contact/company/deal/note/task creation dates are.

All seeded users share one precomputed BCrypt hash. The corresponding local-only plaintext is
`seeder-password`. The encoder is never called during a run.

## Local invocation

From `backend/`, use the throwaway MySQL service on port 3313:

```bash
CONNEX_DB_URL='jdbc:mysql://127.0.0.1:3313/connex_seeder?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&sslMode=DISABLED' \
CONNEX_DB_USERNAME=connexuser \
CONNEX_DB_PASSWORD=connexpass \
bash gradlew seedData \
  -PseederProfile=small \
  -PseederSeed=853 \
  -PseederWorkspaces=1 \
  -PseederAnchorDate=2026-01-15
```

The task activates the isolated non-web `seeder` profile, disables bootstrap, mail, schedulers,
async processing, and realtime infrastructure, runs Flyway, seeds the requested workspaces, logs
row-count summaries, and closes the application context.

## CI invocation

The backend CI job already exports `CONNEX_DB_URL`, `CONNEX_DB_USERNAME`, and
`CONNEX_DB_PASSWORD` for its MySQL service. Against a freshly created CI schema, run:

```bash
bash gradlew seedData \
  -PseederProfile=small \
  -PseederSeed=853 \
  -PseederWorkspaces=1 \
  -PseederAnchorDate=2026-01-15 \
  --no-daemon
```

No alternate test-database configuration is required.

## Production guard

The guard executes before Flyway's first write. It first asserts the whole invocation contract and
refuses unless **all** of the following hold, so that setting `connex.seeder.enabled=true` alone can
never arm fixture writing on a serving deployment:

- the `seeder` Spring profile is active;
- `connex.maintenance.mode` is `seeder`;
- `spring.main.web-application-type` is `none` — only a one-shot non-web process may seed; and
- `connex.tenancy.routing.mode` is `single-database`.

It then refuses the target itself:

- a database named `connex_pub`, case-insensitively, in the configured URL or in the effective
  connection catalog/schema;
- a JDBC URL containing a decoded, case- and punctuation-normalized target selector, routing
  override, or pre-metadata executable hook — this includes direct database/host/port selectors;
  `url` / `jdbcUrl`; DNS SRV, protocol, named-pipe path, socket-factory, SOCKS-proxy, host-priority
  and affinity settings; `propertiesTransform`; `sessionVariables`; connection, query, and
  exception interceptors; custom authentication handlers/plugins; logger/profiler handlers; and
  Connector/J cache or load-balancer factories;
- any non-loopback or ambiguous JDBC host unless `-PseederAllowRemoteHost=true` is explicit;
- a relaxed-bound `spring.datasource.hikari.jdbc-url` or `spring.flyway.url` override that names a
  different host, port, or database than the required `spring.datasource.url` baseline, which
  would otherwise open or migrate one database while seeding another — hosts compare
  case-insensitively, an implicit port matches `3306`, database/catalog names compare with exact
  case, and `localhost` and `127.0.0.1` remain different servers;
- a scalar `spring.flyway.default-schema` or any entry in the comma-separated/indexed
  `spring.flyway.schemas` collection naming anything other than that exact target database — on
  MySQL a Flyway schema is a catalog, so a divergent entry migrates a database the seeder never
  writes;
- any of those target, routing, transform, SQL, interceptor, plugin, handler, or factory properties
  in `spring.datasource.hikari.data-source-properties` or `spring.flyway.jdbc-properties`,
  including relaxed-case and punctuation forms; these maps are checked before a connection because
  `dbname` combined with `createDatabaseIfNotExist=true` can create its selected catalog, while
  transforms and executable hooks can rewrite routing or run code before JDBC metadata is visible;
- any `spring.flyway.init-sqls` statement at all, because Flyway installs it on every one of its
  own connections before it detects the current schema, so a `USE other_db` there would migrate a
  catalog no configured property names;
- any nonblank relaxed-bound `spring.datasource.hikari.connection-init-sql` value other than the
  exact project default `SET time_zone = '+00:00'` after stripping leading and trailing
  whitespace; this allowlist is enforced before any datasource is opened because Connector/J can
  leave `Connection.getCatalog()` and `Connection.getSchema()` stale after an initializer changes
  the server session database;
- any nonblank relaxed-bound `spring.datasource.hikari.connection-test-query` value, because
  Hikari executes that SQL while establishing its first connection and a `USE other_db` validation
  query can produce the same stale JDBC catalog before the guard inspects metadata;
- any application or Flyway datasource whose effective metadata URL does not name the baseline
  host, port, and exact database, or whose current catalog/schema is not that exact database,
  which catches redirects that JDBC metadata reports, including a stray `@FlywayDataSource` bean,
  rather than relying only on configured properties; and
- every deployment whose authoritative `connex.deployment.profile` is explicitly configured
  (`saas`, `silo`, or `on-prem`) — production editions are never seedable.

The required baseline, relaxed-bound URL overrides, unsafe URL-query and driver-map properties,
schema and init settings, Hikari connection initializer, and Hikari validation query are checked
before opening a connection, then the application and actual Flyway datasource metadata are checked
again. A shared datasource is opened only once during one guard pass. Remote operation still
requires the normal verified MySQL TLS posture; the override does not permit plaintext remote
transport.

### What the guard does not protect against

The guard stops accidental and remote targets, not a determined operator. It cannot distinguish a
disposable loopback schema from a *local* database that matters, so `bash gradlew seedData` with a
normal `backend/.env` loaded will happily seed your own development database. Always pass an
explicit throwaway schema. Likewise, `connex.deployment.profile` is optional during soft launch, so
the deployment-profile refusal only fires where an operator set it; the non-web and loopback
requirements are the load-bearing ones.

The effective-catalog check reads JDBC metadata at guard time, but it is not authoritative for
session changes that the driver does not reflect there. The pre-connection Hikari initializer
allowlist and validation-query denial close the known configuration gaps. The guard still cannot
see a redirect that happens later inside the migration run itself: a `USE other_db` statement in a
migration script or in a Flyway callback still moves the session after the guard has passed.
Migration scripts are repository content rather than operator configuration, so treat a `USE` in
one as the defect it is.

Seeded accounts are org owners whose password is the published constant below. Treat any schema the
seeder has touched as compromised for authentication purposes and never expose it.

## Migration timing evidence

On every seeder startup, the guarded Flyway strategy reads the applied history after migration and
logs `installed_rank`, `version`, `description`, `execution_time_ms`, and `success` for each entry,
followed by `total_ms`. These lines are the fresh-schema migration-duration evidence referenced by
[UPGRADING.md](UPGRADING.md).

To capture the evidence for standing up the volume profile, point the local command at a newly
named empty schema such as `connex_seeder_volume_853`, change
`-PseederProfile=volume`, keep an explicit anchor date, and retain the Gradle log. The migration
report measures empty-schema Flyway execution; the subsequent volume seed establishes the
realistic populated dataset used for separate upgrade-drill measurements.
