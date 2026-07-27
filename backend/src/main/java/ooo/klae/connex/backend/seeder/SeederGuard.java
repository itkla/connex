package ooo.klae.connex.backend.seeder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * <p>Every configured URL must resolve to the same {@code host:port/database}, and every
 * configured Flyway schema must name that agreed database, so Flyway cannot migrate one
 * catalog while the fixture writers populate another.
 */
@Component
@RequiredArgsConstructor
public class SeederGuard {

    private static final String PRODUCTION_DATABASE = "connex_pub";
    private static final String SEEDER = "seeder";
    private static final int DEFAULT_MYSQL_PORT = 3306;
    private static final String[] FLYWAY_SCHEMA_PROPERTIES = {
        "spring.flyway.schemas",
        "spring.flyway.default-schema"
    };
    private static final Set<String> DATABASE_SELECTING_QUERY_KEYS = Set.of("dbname", "database");
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
        verifyConfiguredSchemas(configuredTarget.database());

        Set<DataSource> effectiveDataSources =
            Collections.newSetFromMap(new IdentityHashMap<>());
        effectiveDataSources.add(dataSource);
        if (additionalDataSources != null) {
            Collections.addAll(effectiveDataSources, additionalDataSources);
        }
        for (DataSource effectiveDataSource : effectiveDataSources) {
            verifyMetadata(effectiveDataSource);
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
    }

    private JdbcTarget verifyConfiguredUrls() {
        JdbcTarget agreedTarget = null;
        Set<String> canonicalTargets = new LinkedHashSet<>();
        for (String url : configuredUrls()) {
            verifyJdbcUrl(url, properties.isAllowRemoteHost());
            JdbcTarget target = parse(url.strip());
            if (canonicalTargets.add(target.canonicalTarget())) {
                agreedTarget = target;
            }
        }
        if (canonicalTargets.size() > 1) {
            throw new IllegalStateException(
                "Seeder refused: configured JDBC URLs disagree on the target host, port"
                    + " or database " + canonicalTargets);
        }
        if (agreedTarget == null) {
            throw new IllegalStateException("Seeder refused: no JDBC URL is configured");
        }
        return agreedTarget;
    }

    private Set<String> configuredUrls() {
        Set<String> urls = new LinkedHashSet<>();
        addConfiguredUrl(urls, "spring.datasource.url", true);
        addConfiguredUrl(urls, "spring.datasource.hikari.jdbc-url", false);
        addConfiguredUrl(urls, "spring.datasource.hikari.jdbcUrl", false);
        addConfiguredUrl(urls, "spring.flyway.url", false);
        return urls;
    }

    private void addConfiguredUrl(Set<String> urls, String propertyName, boolean required) {
        String value = environment.getProperty(propertyName);
        if (!StringUtils.hasText(value)) {
            if (required) {
                throw new IllegalStateException("Seeder refused: " + propertyName + " is required");
            }
            return;
        }
        urls.add(value.strip());
    }

    private void verifyConfiguredSchemas(String targetDatabase) {
        for (String propertyName : FLYWAY_SCHEMA_PROPERTIES) {
            for (String schema : configuredSchemas(propertyName)) {
                String candidate = schema.strip();
                if (!StringUtils.hasText(candidate) || candidate.equalsIgnoreCase(targetDatabase)) {
                    continue;
                }
                throw new IllegalStateException(
                    "Seeder refused: " + propertyName + " names " + candidate
                        + " instead of the configured target database " + targetDatabase);
            }
        }
    }

    private List<String> configuredSchemas(String propertyName) {
        List<String> bound = Binder.get(environment)
            .bind(propertyName, Bindable.listOf(String.class))
            .orElseGet(List::of);
        if (!bound.isEmpty()) {
            return bound;
        }
        String value = environment.getProperty(propertyName);
        return StringUtils.hasText(value) ? List.of(value.split(",")) : List.of();
    }

    private void verifyMetadata(DataSource effectiveDataSource) {
        if (effectiveDataSource == null) {
            throw new IllegalStateException("Seeder refused: effective datasource is unavailable");
        }
        try (Connection connection = effectiveDataSource.getConnection()) {
            verifyJdbcUrl(connection.getMetaData().getURL(), properties.isAllowRemoteHost());
            String catalog = connection.getCatalog();
            if (catalog != null && PRODUCTION_DATABASE.equalsIgnoreCase(catalog.strip())) {
                throw new IllegalStateException(
                    "Seeder refused: effective catalog connex_pub is a protected production target");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Seeder refused: could not verify effective JDBC target", ex);
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
            verifyNoDatabaseSelectingQuery(uri.getRawQuery());
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

    private static void verifyNoDatabaseSelectingQuery(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return;
        }
        for (String parameter : rawQuery.split("&")) {
            int separator = parameter.indexOf('=');
            String key = separator < 0 ? parameter : parameter.substring(0, separator);
            if (DATABASE_SELECTING_QUERY_KEYS.contains(key.strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                    "Seeder refused: JDBC URL selects its database through the query parameter "
                        + key.strip());
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
         * Renders the comparable identity of this target, so that two configured URLs agree only
         * when they name the same MySQL server and the same database on it.
         *
         * @return the lowercase {@code host:port/database} identity of this target
         */
        String canonicalTarget() {
            return host.toLowerCase(Locale.ROOT)
                + ":"
                + port
                + "/"
                + database.toLowerCase(Locale.ROOT);
        }
    }
}
