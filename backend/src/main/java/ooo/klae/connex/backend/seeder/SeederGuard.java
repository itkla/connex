package ooo.klae.connex.backend.seeder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.config.DeploymentProperties;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/**
 * Refuses unsafe seeder targets before Flyway or fixture writers may mutate them.
 *
 * <p>The guard asserts the whole invocation contract, not just the target: a seeder run
 * must be an explicitly activated, non-web, one-shot process in seeder maintenance mode.
 * Enabling {@code connex.seeder.enabled} alone therefore cannot arm fixture writing on a
 * serving deployment.
 *
 * <p>Configured URLs are checked before opening a JDBC connection because MySQL's
 * {@code createDatabaseIfNotExist=true} option can create a catalog during connection
 * establishment. Effective datasource metadata is then checked again.
 *
 * <p>Every configured URL must resolve to the same {@code host:port/database}, every configured
 * Flyway schema must name that agreed database, driver configuration cannot override its target or
 * install executable pre-metadata hooks, Flyway session init statements are refused, and the
 * Hikari connection initializer is restricted to the project's UTC statement before any
 * datasource is opened. Every effective connection must then report the agreed database. A
 * redirect performed at run time by a migration script or a Flyway callback is outside the guard.
 */
@Component
@RequiredArgsConstructor
public class SeederGuard {

    private static final String PRODUCTION_DATABASE = "connex_pub";
    private static final String SEEDER = "seeder";
    private static final int DEFAULT_MYSQL_PORT = 3306;
    private static final String DATASOURCE_URL_PROPERTY = "spring.datasource.url";
    private static final String HIKARI_JDBC_URL_PROPERTY = "spring.datasource.hikari.jdbc-url";
    private static final String HIKARI_CONNECTION_INIT_SQL_PROPERTY =
        "spring.datasource.hikari.connection-init-sql";
    private static final String HIKARI_CONNECTION_TEST_QUERY_PROPERTY =
        "spring.datasource.hikari.connection-test-query";
    private static final String HIKARI_DATA_SOURCE_PROPERTIES_PROPERTY =
        "spring.datasource.hikari.data-source-properties";
    private static final String PROJECT_HIKARI_CONNECTION_INIT_SQL =
        "SET time_zone = '+00:00'";
    private static final String FLYWAY_URL_PROPERTY = "spring.flyway.url";
    private static final String FLYWAY_DEFAULT_SCHEMA_PROPERTY = "spring.flyway.default-schema";
    private static final String FLYWAY_SCHEMAS_PROPERTY = "spring.flyway.schemas";
    private static final String FLYWAY_INIT_SQL_PROPERTY = "spring.flyway.init-sqls";
    private static final String FLYWAY_JDBC_PROPERTIES_PROPERTY =
        "spring.flyway.jdbc-properties";
    private static final Set<String> TARGET_SELECTING_DRIVER_PROPERTY_KEYS =
        Set.of(
            "address",
            "database",
            "databasename",
            "dbname",
            "dnssrv",
            "host",
            "hostname",
            "jdbcurl",
            "path",
            "port",
            "portnumber",
            "priority",
            "protocol",
            "serveraffinityorder",
            "servername",
            "socksproxyhost",
            "socksproxyport",
            "socksproxyremotedns",
            "type",
            "url"
        );
    private static final Set<String> PRE_METADATA_EXECUTABLE_DRIVER_PROPERTY_KEYS =
        Set.of(
            "authenticationopenidconnectcallbackhandler",
            "authenticationplugins",
            "authenticationwebauthncallbackhandler",
            "connectionlifecycleinterceptors",
            "defaultauthenticationplugin",
            "exceptioninterceptors",
            "haloadbalancestrategy",
            "loadbalanceexceptionchecker",
            "logger",
            "profilereventhandler",
            "propertiestransform",
            "queryinfocachefactory",
            "queryinterceptors",
            "serverconfigcachefactory",
            "sessionvariables",
            "socketfactory"
        );
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
        "localhost",
        "127.0.0.1",
        "::1",
        "0:0:0:0:0:0:0:1"
    );

    private final Environment environment;
    private final DeploymentProperties deploymentProperties;
    private final SeederProperties properties;
    private final DataSource dataSource;

    /**
     * Verifies configured and effective application datasource targets.
     */
    public void verify() {
        verify(dataSource);
    }

    /**
     * Verifies configured targets plus every supplied effective datasource.
     *
     * @param additionalDataSources Flyway or other effective datasources to revalidate
     */
    public synchronized void verify(DataSource... additionalDataSources) {
        verifyInvocationContract();
        if (deploymentProperties.isConfigured()) {
            throw new IllegalStateException(
                "Seeder refused: explicitly configured deployment profile "
                    + deploymentProperties.getProfile()
                    + " is never seedable");
        }

        JdbcTarget configuredTarget = verifyConfiguredUrls();
        verifyNoUnsafePreconnectionDriverProperties();
        verifyHikariConnectionInitSql();
        verifyNoHikariConnectionTestQuery();
        verifyConfiguredSchemas(configuredTarget);
        verifyNoFlywayInitSql();

        Set<DataSource> effectiveDataSources =
            Collections.newSetFromMap(new IdentityHashMap<>());
        effectiveDataSources.add(dataSource);
        if (additionalDataSources != null) {
            Collections.addAll(effectiveDataSources, additionalDataSources);
        }
        for (DataSource effectiveDataSource : effectiveDataSources) {
            verifyMetadata(effectiveDataSource, configuredTarget);
        }
    }

    private void verifyInvocationContract() {
        if (!environment.acceptsProfiles(Profiles.of(SEEDER))) {
            throw new IllegalStateException(
                "Seeder refused: the seeder Spring profile is not active");
        }
        if (!SEEDER.equals(normalized(environment.getProperty("connex.maintenance.mode")))) {
            throw new IllegalStateException(
                "Seeder refused: connex.maintenance.mode must be seeder");
        }
        if (!"none".equals(normalized(environment.getProperty("spring.main.web-application-type")))) {
            throw new IllegalStateException(
                "Seeder refused: only a non-web one-shot process may seed");
        }
        String routingMode = environment.getProperty(
            "connex.tenancy.routing.mode",
            TenantRoutingProperties.MODE_SINGLE_DATABASE
        );
        if (!TenantRoutingProperties.MODE_SINGLE_DATABASE.equals(normalized(routingMode))) {
            throw new IllegalStateException(
                "Seeder refused: only connex.tenancy.routing.mode=single-database is seedable");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    static void verifyJdbcUrl(String url, boolean allowRemoteHost) {
        verifiedTarget(url, allowRemoteHost);
    }

    private static JdbcTarget verifiedTarget(String url, boolean allowRemoteHost) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Seeder refused: JDBC URL is blank");
        }

        JdbcTarget target = parse(url.strip());
        if (PRODUCTION_DATABASE.equalsIgnoreCase(target.database())) {
            throw new IllegalStateException(
                "Seeder refused: database connex_pub is a protected production target");
        }
        if (!LOOPBACK_HOSTS.contains(target.host().toLowerCase(Locale.ROOT)) && !allowRemoteHost) {
            throw new IllegalStateException(
                "Seeder refused: non-loopback database host "
                    + target.host()
                    + " requires connex.seeder.allow-remote-host=true");
        }
        return target;
    }

    private JdbcTarget verifyConfiguredUrls() {
        String baselineUrl = configuredScalar(DATASOURCE_URL_PROPERTY);
        if (!StringUtils.hasText(baselineUrl)) {
            throw new IllegalStateException(
                "Seeder refused: " + DATASOURCE_URL_PROPERTY + " is required");
        }
        JdbcTarget baseline = verifiedTarget(
            baselineUrl.strip(),
            properties.isAllowRemoteHost()
        );
        verifyConfiguredUrlOverride(baseline, HIKARI_JDBC_URL_PROPERTY);
        verifyConfiguredUrlOverride(baseline, FLYWAY_URL_PROPERTY);
        return baseline;
    }

    private void verifyConfiguredUrlOverride(JdbcTarget baseline, String propertyName) {
        String value = configuredScalar(propertyName);
        if (!StringUtils.hasText(value)) {
            return;
        }
        JdbcTarget configuredTarget = verifiedTarget(
            value.strip(),
            properties.isAllowRemoteHost()
        );
        verifySameTarget(baseline, configuredTarget, propertyName);
    }

    private void verifyNoUnsafePreconnectionDriverProperties() {
        verifyNoUnsafePreconnectionDriverProperties(HIKARI_DATA_SOURCE_PROPERTIES_PROPERTY);
        verifyNoUnsafePreconnectionDriverProperties(FLYWAY_JDBC_PROPERTIES_PROPERTY);
    }

    private void verifyNoUnsafePreconnectionDriverProperties(String propertyName) {
        for (String key : configuredMap(propertyName).keySet()) {
            if (!isUnsafePreconnectionDriverProperty(key)) {
                continue;
            }
            throw new IllegalStateException(
                "Seeder refused: " + propertyName + " contains unsafe pre-connection property "
                    + key);
        }
    }

    private static boolean isUnsafePreconnectionDriverProperty(String key) {
        String normalizedKey = normalizedPropertyKey(key);
        return TARGET_SELECTING_DRIVER_PROPERTY_KEYS.contains(normalizedKey)
            || PRE_METADATA_EXECUTABLE_DRIVER_PROPERTY_KEYS.contains(normalizedKey);
    }

    private static String normalizedPropertyKey(String key) {
        StringBuilder normalized = new StringBuilder(key.length());
        key.codePoints()
            .filter(Character::isLetterOrDigit)
            .map(Character::toLowerCase)
            .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private void verifyHikariConnectionInitSql() {
        String statement = configuredScalar(HIKARI_CONNECTION_INIT_SQL_PROPERTY);
        if (!StringUtils.hasText(statement)
                || PROJECT_HIKARI_CONNECTION_INIT_SQL.equals(statement.strip())) {
            return;
        }
        throw new IllegalStateException(
            "Seeder refused: " + HIKARI_CONNECTION_INIT_SQL_PROPERTY
                + " must be unset or exactly " + PROJECT_HIKARI_CONNECTION_INIT_SQL);
    }

    private void verifyNoHikariConnectionTestQuery() {
        String statement = configuredScalar(HIKARI_CONNECTION_TEST_QUERY_PROPERTY);
        if (!StringUtils.hasText(statement)) {
            return;
        }
        throw new IllegalStateException(
            "Seeder refused: " + HIKARI_CONNECTION_TEST_QUERY_PROPERTY
                + " executes before target verification and must be unset");
    }

    private void verifyConfiguredSchemas(JdbcTarget configuredTarget) {
        String defaultSchema = configuredScalar(FLYWAY_DEFAULT_SCHEMA_PROPERTY);
        if (StringUtils.hasText(defaultSchema)) {
            verifyConfiguredSchema(
                FLYWAY_DEFAULT_SCHEMA_PROPERTY,
                defaultSchema,
                configuredTarget.database()
            );
        }
        for (String schema : configuredCollection(FLYWAY_SCHEMAS_PROPERTY)) {
            verifyConfiguredSchema(
                FLYWAY_SCHEMAS_PROPERTY,
                schema,
                configuredTarget.database()
            );
        }
    }

    private static void verifyConfiguredSchema(
            String propertyName,
            String configuredSchema,
            String targetDatabase) {
        String candidate = configuredSchema.strip();
        if (!StringUtils.hasText(candidate) || candidate.equals(targetDatabase)) {
            return;
        }
        throw new IllegalStateException(
            "Seeder refused: " + propertyName + " names " + candidate
                + " instead of the configured target database " + targetDatabase);
    }

    private void verifyNoFlywayInitSql() {
        for (String statement : configuredCollection(FLYWAY_INIT_SQL_PROPERTY)) {
            if (!StringUtils.hasText(statement)) {
                continue;
            }
            throw new IllegalStateException(
                "Seeder refused: " + FLYWAY_INIT_SQL_PROPERTY
                    + " may switch the Flyway session to another database and must be unset");
        }
    }

    private String configuredScalar(String propertyName) {
        return Binder.get(environment)
            .bind(propertyName, Bindable.of(String.class))
            .orElse(null);
    }

    private List<String> configuredCollection(String propertyName) {
        return Binder.get(environment)
            .bind(propertyName, Bindable.listOf(String.class))
            .orElseGet(List::of);
    }

    private Map<String, String> configuredMap(String propertyName) {
        return Binder.get(environment)
            .bind(propertyName, Bindable.mapOf(String.class, String.class))
            .orElseGet(Map::of);
    }

    private void verifyMetadata(DataSource effectiveDataSource, JdbcTarget configuredTarget) {
        if (effectiveDataSource == null) {
            throw new IllegalStateException("Seeder refused: effective datasource is unavailable");
        }
        try (Connection connection = effectiveDataSource.getConnection()) {
            JdbcTarget metadataTarget = verifiedTarget(
                connection.getMetaData().getURL(),
                properties.isAllowRemoteHost()
            );
            verifySameTarget(configuredTarget, metadataTarget, "effective datasource metadata URL");
            verifyEffectiveDatabase(effectiveDatabase(connection), configuredTarget.database());
        } catch (SQLException ex) {
            throw new IllegalStateException("Seeder refused: could not verify effective JDBC target", ex);
        }
    }

    private static void verifySameTarget(
            JdbcTarget baseline,
            JdbcTarget candidate,
            String candidateSource) {
        if (baseline.matches(candidate)) {
            return;
        }
        throw new IllegalStateException(
            "Seeder refused: " + candidateSource + " target "
                + candidate.displayTarget() + " disagrees with " + DATASOURCE_URL_PROPERTY
                + " target " + baseline.displayTarget());
    }

    private static String effectiveDatabase(Connection connection) throws SQLException {
        String catalog = connection.getCatalog();
        if (StringUtils.hasText(catalog)) {
            return catalog.strip();
        }
        String schema = connection.getSchema();
        return schema == null ? "" : schema.strip();
    }

    private static void verifyEffectiveDatabase(String effectiveDatabase, String targetDatabase) {
        if (!StringUtils.hasText(effectiveDatabase)) {
            throw new IllegalStateException(
                "Seeder refused: the effective connection reports no current database");
        }
        if (PRODUCTION_DATABASE.equalsIgnoreCase(effectiveDatabase)) {
            throw new IllegalStateException(
                "Seeder refused: effective catalog connex_pub is a protected production target");
        }
        if (!effectiveDatabase.equals(targetDatabase)) {
            throw new IllegalStateException(
                "Seeder refused: effective catalog " + effectiveDatabase
                    + " is not the configured target database " + targetDatabase);
        }
    }

    private static JdbcTarget parse(String url) {
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException(
                "Seeder refused: only simple jdbc:mysql:// URLs are supported");
        }
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            String host = unbracketed(uri.getHost());
            String rawPath = uri.getRawPath();
            verifyNoUnsafePreconnectionQuery(uri.getRawQuery());
            if (!StringUtils.hasText(host)
                    || !StringUtils.hasText(rawPath)
                    || !rawPath.startsWith("/")
                    || rawPath.length() == 1
                    || rawPath.indexOf('/', 1) >= 0) {
                throw new IllegalStateException(
                    "Seeder refused: JDBC URL must name one unambiguous host and database");
            }
            String database = URLDecoder.decode(rawPath.substring(1), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(database)) {
                throw new IllegalStateException("Seeder refused: JDBC URL database name is blank");
            }
            int port = uri.getPort() < 0 ? DEFAULT_MYSQL_PORT : uri.getPort();
            return new JdbcTarget(host, port, database);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Seeder refused: JDBC URL is malformed", ex);
        }
    }

    private static void verifyNoUnsafePreconnectionQuery(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return;
        }
        for (String parameter : rawQuery.split("&")) {
            int separator = parameter.indexOf('=');
            String rawKey = separator < 0 ? parameter : parameter.substring(0, separator);
            String key = URLDecoder.decode(rawKey.strip(), StandardCharsets.UTF_8).strip();
            if (isUnsafePreconnectionDriverProperty(key)) {
                throw new IllegalStateException(
                    "Seeder refused: JDBC URL contains unsafe pre-connection query parameter "
                        + key);
            }
        }
    }

    private static String unbracketed(String host) {
        if (host != null && host.length() > 1 && host.charAt(0) == '['
                && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private record JdbcTarget(String host, int port, String database) {

        /**
         * Reports whether another target names the same MySQL server and exact database.
         *
         * @param other target to compare
         * @return whether host matches case-insensitively and port and database match exactly
         */
        boolean matches(JdbcTarget other) {
            return host.equalsIgnoreCase(other.host)
                && port == other.port
                && database.equals(other.database);
        }

        /**
         * Renders the target identity for a refusal message.
         *
         * @return the {@code host:port/database} identity of this target
         */
        String displayTarget() {
            return host.toLowerCase(Locale.ROOT)
                + ":"
                + port
                + "/"
                + database;
        }
    }
}
