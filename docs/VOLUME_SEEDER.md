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

The `Frontend — unit & e2e` job in `.github/workflows/ci.yml` runs the seeder against the fresh
`connex_seed_e2e` schema its MySQL service creates per run, **after** building the war and
**before** booting the backend. The guard permits only a non-web one-shot process, so the seeder
cannot run inside the serving app, and the dedicated catalog prefix satisfies the one-shot
contract:

```bash
bash gradlew seedData \
  -PseederProfile=small \
  -PseederSeed=853 \
  -PseederWorkspaces=1 \
  -PseederAnchorDate=2026-01-15 \
  --no-daemon \
  | tee ../frontend/test/e2e/.artifacts/seeder.log
```

No alternate test-database configuration is required: the job's existing `CONNEX_DB_URL`,
`CONNEX_DB_USERNAME`, and `CONNEX_DB_PASSWORD` already point at the disposable CI schema. The
tee'd log is how e2e specs learn the seeded usernames — see
[FRONTEND_TESTING.md](FRONTEND_TESTING.md#seeded-identities-deterministic-volume-seeder).

## Production guard

Connex disables Spring JNDI property-source discovery through its classpath startup policy before
packaged or external-WAR startup creates an environment. The `seedData` task launches
`SeederApplication` on the production runtime classpath, excluding Spring DevTools and its late
home-directory property overrides. The launcher sets the application type to non-web before Spring
creates an environment. The first guard is a ConfigData-aware environment post-processor. It runs
after Spring
Boot loads `application.yml` and `application-seeder.yml`, immediately before the
database-transport post-processor, and before any datasource, Hikari pool, Flyway or MyBatis object,
JDBC driver, JNDI lookup, or operator-named class can be initialized. Any active `seeder` profile,
`connex.seeder.enabled=true`, or `connex.maintenance.mode=seeder` activates this boundary. Once
activated it refuses unless **all** of the following hold:

- `seeder` is the only active Spring profile, so `seeder,dev` and `seeder,test` cannot bypass the
  normal transport checks;
- the supplied Spring application was already configured as non-web before property binding, so
  the executable `BackendApplication` and external-WAR `ServletInitializer` paths cannot seed;
- `connex.seeder.enabled` is `true`, `connex.maintenance.mode` is `seeder`, and the
  defense-in-depth `spring.main.web-application-type` property is `none`;
- `connex.tenancy.routing.mode` is `single-database`;
- `connex.deployment.profile` is unset;
- `connex.object-storage.legacy-migration.mode` is `off`; and
- the required `spring.datasource.url` is a safely parseable simple
  `jdbc:mysql://host[:port]/catalog` target.

The early boundary applies one closed configuration policy:

- `hikaricp.configurationFile` must be absent as a raw JVM system property, including a blank value;
- `connexdb` and `connex_pub` are protected case-insensitively, remote hosts require
  `-PseederAllowRemoteHost=true`, implicit local admission requires a numeric IPv4 or IPv6 loopback
  address, textual `localhost` is refused, host comparison is case-insensitive, only a missing
  authority port normalizes to `3306`, and database/catalog comparison is case-sensitive;
- the target catalog must use the dedicated `connex_seed` or `cnx_seeder_` prefix;
- alternate Connector/J URL forms and every query/map routing selector are refused, including
  `address`, `databaseName`, `dbname`, `dnsSrv`, host, port, protocol, named-pipe path,
  `serverName`, `type`, `url`/`jdbcUrl`, and `useConfigs`;
- JDBC URL parameters, Hikari data-source properties, and Flyway JDBC properties use a closed
  reviewed allowlist. Unknown properties and executable SQL, transform, interceptor, class, plugin,
  provider, authentication callback, socket-factory, logger/profiler, cache-factory,
  high-availability/load-balance, session-variable, and X DevAPI hooks fail closed. Compatibility
  aliases such as `parseInfoCacheFactory` and `namedPipePath` are covered. `connectionAttributes`,
  `clientCertificateKeyStoreUrl`, and `trustCertificateKeyStoreUrl` are explicitly refused,
  together with certificate-store passwords, in JDBC query, Hikari map, and Flyway map forms,
  including case, encoded, bracketed, and relaxed spellings. Remote TLS may use only JVM/system
  trust material, never per-connection keystore URL hooks;
- every relaxed Hikari JDBC URL alias, including `jdbc_url` and environment-style spellings, must
  agree with the baseline target. The whole relaxed `spring.flyway` namespace is closed: operator
  input is accepted only for the already-validated dynamic `url`, `driver-class-name`,
  `jdbc-properties`, `default-schema`, and `schemas` channels. A Flyway URL must agree with the
  baseline target, a driver override must be exactly `com.mysql.cj.jdbc.Driver`, Connector/J map
  entries must pass the closed allowlist, the default schema must remain scalar, and every schema
  must name the exact target catalog. Separate Flyway credentials and every other operator Flyway
  property are refused, including same-value overrides, relaxed aliases, indexed or descendant
  forms, removed or deprecated names, and unknown future names;
- datasource type, JNDI, XA, data-source-class, credentials-provider, exception-override,
  registry/object-hook, catalog/schema, and connection-test-query channels are refused. Driver
  overrides are absent or exactly `com.mysql.cj.jdbc.Driver`;
- `spring.autoconfigure.exclude`, including relaxed and indexed forms, must be absent so Flyway
  auto-configuration cannot be removed from the seeder lifecycle;
- Hikari connection initialization is absent or exactly `SET time_zone = '+00:00'` after outer
  whitespace is removed;
- the whole relaxed `spring.sql.init` namespace contains only the repository-owned effective
  `mode=never` pin and optional `classpath:seeder-sql-init-canary.sql` data location. Operator
  schema/data locations, indexed or aliased descendants, credentials, platform, encoding,
  separators, error handling, overrides, and unknown descendants are refused;
- the whole relaxed `mybatis` namespace contains only the repository-owned effective mapper
  location `classpath:mappers/*.xml`, type-alias package `ooo.klae.connex.backend.beans`, and
  underscore-to-camel-case mapping. External, wildcard, comma-separated, indexed, configuration
  file/property/variable/database-id, type-handler/alias/scripting class, operator override, alias,
  and unknown descendant channels are refused;
- `application-seeder.yml` is the required effective source for the current Spring Boot 4.1 and
  Flyway 12 migration semantics: `enabled=true`, `baseline-on-migrate=true`, `baseline-version=0`,
  `clean-disabled=true`, `skip-executing-migrations=false`, `target=latest`,
  `table=flyway_schema_history`, `skip-default-resolvers=false`,
  `skip-default-callbacks=false`, exactly `classpath:db/migration`, empty callback locations, empty
  init SQL, an absent closed placeholder namespace whose bound default is empty,
  `placeholder-replacement=false`, `sql-migration-prefix=V`,
  `repeatable-sql-migration-prefix=R`, `sql-migration-separator=__`, exactly `.sql` migration
  suffixes, `validate-on-migrate=true`, `validate-migration-naming=true`, empty ignored migration
  patterns, `out-of-order=false`, and `fail-on-missing-locations=true`. The boundary refuses an
  operator occurrence of any of these properties before datasource, metadata, or migration
  activity.

The existing database-transport post-processor remains the transport authority: exact loopback
seeder plaintext with `sslMode=DISABLED` is allowed, while remote seeding still requires
`sslMode=VERIFY_CA` or `sslMode=VERIFY_IDENTITY`.

The dedicated launcher installs a highest-precedence context initializer that repeats the complete
configuration policy after every environment post-processor and before any bean or
operator-configured initializer can be constructed. The runtime `SeederGuard` then checks the
application and actual Flyway datasource metadata URL and the server result of `SELECT DATABASE()`
against the same validated host, port, and exact catalog. A shared
datasource is opened only once during one guard pass. `SeederFlywayMigrationStrategy` runs this
check before migration, and the startup singleton and seed runner retain their later checks. The
runner also requires a Flyway bean and refuses fixture writes unless Flyway reports no pending
migrations and its current version is the latest resolved version.

### What the guard does not protect against

The dedicated catalog prefix is an admission marker, not proof that a local schema is disposable.
Always pass a newly named throwaway schema.

Repository-controlled Java `Callback`, `JavaMigration`, and Flyway customizer beans remain trusted
code. The effective-catalog check is not authoritative for a later session change performed inside
repository migration or callback code, so a `USE` statement or equivalent redirect there is a
security defect. External migration and callback locations cannot be configured for seeder runs.

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
