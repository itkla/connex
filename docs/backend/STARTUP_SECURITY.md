# Backend Startup and Seeder Security Contract

This document records the fail-closed startup posture for normal web deployment, external WAR launch, seeder execution, and maintenance modes. Operator commands/sequencing live in `docs/DEPLOYMENT.md`.

Read this before changing environment post-processors, application launchers, datasource/Flyway/MyBatis configuration, JNDI handling, maintenance modes, or readiness publication.

## Pre-context enforcement

Security-sensitive posture is validated before application infrastructure and before an embedded server can bind.

- Spring JNDI property-source discovery remains disabled through the classpath policy before packaged or external-WAR startup creates an environment. Datasource JNDI is not a supported configuration channel.
- Seeder startup uses the dedicated `SeederApplication`, which sets `WebApplicationType.NONE` before environment creation.
- ConfigData-aware environment post-processors run in the established order immediately before application infrastructure: deployment-profile posture, seeder configuration, then database transport.
- The deployment-profile processor retains Binder-based rule evaluation so forbidden posture is rejected before context creation.
- These guards execute before datasource/Hikari/Flyway/MyBatis/JDBC/JNDI/operator object construction and before any embedded web server is created.

Do not move these checks into an `ApplicationRunner`, bean, or later startup phase.

## Seeder activation

Any genuine seeder signal requires both the pre-binding non-web application type and the defense-in-depth `spring.main.web-application-type=none` property. Normal `BackendApplication` and external-WAR `ServletInitializer` paths refuse seeder posture.

Activated seeder posture requires the exact repository-owned combination, including:

- `seeder` as the only profile;
- explicit seeder enablement and exact maintenance mode;
- single-database routing;
- no deployment profile;
- legacy-upload migration disabled;
- the approved simple MySQL baseline target.

Do not broaden aliases or accept near-equivalent mixed profiles/modes.

## Closed configuration surface

Seeder configuration is closed, not an arbitrary Spring/Hikari/Flyway launcher.

Reject operator-controlled executable/class/object channels and alternate datasource mechanisms, including raw Hikari configuration files, JNDI/XA/data-source classes, alternate/routing JDBC forms, application sources, initializers, listeners, and arbitrary schema/catalog/test-query hooks.

The relaxed `spring.flyway`, `spring.sql.init`, and `mybatis` namespaces remain closed except for the explicitly allowed dynamic Flyway target channels that have already passed database-target validation. Separate Flyway credentials and unreviewed/deprecated/future properties are refused even when their value appears harmless or matches a repository default.

Connector/J query/map options use a reviewed closed allowlist with explicit compatibility aliases, matched case-insensitively without Spring punctuation-relaxed expansion. Certificate-store URLs/passwords and connection-attribute injection remain refused. Remote TLS uses approved JVM/system trust material rather than per-connection keystore URL hooks.

Add a Connector/J option only after reviewing it as inert TLS/timeout/encoding/timezone/performance configuration and extending characterization tests. Do not replace the allowlist with a blacklist.

## Repository-owned migration semantics

`application-seeder.yml` remains the effective source for migration behavior. Preserve the reviewed pins for:

- migration enabled and baseline-on-migrate at version 0;
- clean disabled and migration execution not skipped;
- target latest and the canonical history table;
- default resolvers/callbacks and exactly the repository migration location;
- no operator init SQL/callback location/placeholder namespace;
- placeholder replacement off;
- canonical version/repeatable prefixes, separator, and SQL suffix;
- validation/migration-name validation on;
- out-of-order off and missing locations fatal;
- repository-owned SQL initializer/MyBatis posture.

Repository Java Flyway callbacks, migrations, and customizers remain allowed. Operator overrides/aliases/indexed descendants/executable class channels remain refused, including same-value overrides, so the startup contract cannot silently widen as dependencies evolve.

## Maintenance and readiness

Maintenance modes default off and require their exact centralized application-type/profile/mode pairing. Legacy-upload migration additionally follows `docs/backend/OBJECT_STORAGE.md` and `docs/DEPLOYMENT.md`.

Normal startup performs fail-closed guards such as legacy public-upload reference detection without turning startup into an implicit data migration.

Deployment readiness is published only after all required `ApplicationRunner` checks complete. Production orchestration waits for backend health before replacing dependent services; do not publish readiness early merely to reduce startup time.

## Review checklist

- Forbidden posture is rejected before context/server binding.
- JNDI/alternate datasource/executable class channels remain closed.
- Seeder can run only through exact non-web posture.
- Flyway/MyBatis/SQL-init behavior remains repository-owned.
- Connector/J options remain a reviewed allowlist.
- No same-value/alias/descendant override bypasses pins.
- Maintenance modes default off and cannot activate in normal web startup.
- Readiness follows all startup guards/runners.
- Characterization and startup-context tests cover packaged and external-WAR paths where applicable.
