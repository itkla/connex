package ooo.klae.connex.backend.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Owns one disposable catalog while leaving migration lineage and collation choices to each test. */
final class MySqlScratchCatalog implements AutoCloseable {
    private final String name;
    private final String url;
    private final String bootstrapUrl;
    private final String username;
    private final String password;

    private MySqlScratchCatalog(String name, String url, String bootstrapUrl,
            String username, String password) {
        this.name = name;
        this.url = url;
        this.bootstrapUrl = bootstrapUrl;
        this.username = username;
        this.password = password;
    }

    /** A null collation intentionally inherits the server's default for the explicit charset. */
    static MySqlScratchCatalog create(String configuredUrl, String username, String password,
            String prefix, String charset, String collation) throws SQLException {
        String name = uniqueName(prefix);
        String bootstrapUrl = withCatalog(configuredUrl, "mysql");
        MySqlScratchCatalog catalog = new MySqlScratchCatalog(name, withCatalog(configuredUrl, name),
            bootstrapUrl, username, password);
        boolean created = false;
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET " + identifier(charset)
                + (collation == null ? "" : " COLLATE " + identifier(collation)));
            created = true;
        } catch (SQLException failure) {
            if (created) {
                try {
                    catalog.close();
                } catch (SQLException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        return catalog;
    }

    static String uniqueName(String prefix) {
        String configuredPrefix = System.getenv("CONNEX_TEST_CATALOG_PREFIX");
        return identifier((configuredPrefix == null ? prefix : configuredPrefix)
            + UUID.randomUUID().toString().replace("-", ""));
    }

    String name() {
        return name;
    }

    String url() {
        return url;
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }

    private static String withCatalog(String jdbcUrl, String catalog) {
        int authorityEnd = jdbcUrl.indexOf('/', "jdbc:mysql://".length());
        if (authorityEnd < 0) {
            throw new IllegalArgumentException("CONNEX_DB_URL must include a database path");
        }
        int queryStart = jdbcUrl.indexOf('?', authorityEnd);
        String suffix = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return jdbcUrl.substring(0, authorityEnd + 1) + identifier(catalog) + suffix;
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid test catalog identifier");
        }
        return value;
    }
}
