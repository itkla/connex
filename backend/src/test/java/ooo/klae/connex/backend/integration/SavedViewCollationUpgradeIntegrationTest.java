package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the collation footgun that wedged staging on V111: a long-lived
 * catalog whose default collation was {@code utf8mb4_unicode_ci} when
 * {@code saved_view} (V18, no explicit charset) was created. The composite
 * foreign key on {@code saved_view_default.record_type} rejects a collation
 * split (MySQL errno 3780), and because DDL auto-commits it half-applies and
 * leaves Flyway wedged. The regular {@link FlywayUpgradeIntegrationTest} runs
 * on a uniform {@code utf8mb4_0900_ai_ci} catalog and never exercises this
 * path. Here the scratch catalog defaults to {@code utf8mb4_unicode_ci}, the
 * full lineage is applied to latest, and V114 must land the FK columns on a
 * single canonical collation.
 */
class SavedViewCollationUpgradeIntegrationTest {
    private static final String SCRATCH_CATALOG =
        "connex_collation_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String CANONICAL_COLLATION = "utf8mb4_0900_ai_ci";

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void createLegacyCollationCatalog() {
        String configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping saved_view collation upgrade test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(
                false,
                "Cannot create saved_view collation scratch catalog: " + exception.getMessage());
        }
    }

    @AfterAll
    static void dropScratchCatalog() throws SQLException {
        if (!created) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + SCRATCH_CATALOG + "`");
        }
    }

    @Test
    void fullLineageLandsForeignKeyColumnsOnOneCollation() throws SQLException {
        Flyway.configure()
            .dataSource(scratchUrl, username, password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .load()
            .migrate();

        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password)) {
            assertEquals(
                "utf8mb4_unicode_ci",
                catalogCollation(connection),
                "precondition: the scratch catalog must default to the legacy collation");
            assertEquals(
                CANONICAL_COLLATION,
                columnCollation(connection, "saved_view", "record_type"),
                "V114 must normalize the parent key column collation");
            assertEquals(
                CANONICAL_COLLATION,
                columnCollation(connection, "saved_view_default", "record_type"),
                "V114 must normalize the child FK column collation to match the parent");
            assertEquals(
                1,
                foreignKeyCount(connection, "saved_view_default", "fk_saved_view_default_view"),
                "the sharing-preferences foreign key must survive the collation rewrite");
        }
    }

    private static String catalogCollation(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT @@collation_database")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static String columnCollation(Connection connection, String table, String column)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT collation_name
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static long foreignKeyCount(Connection connection, String table, String constraint)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = ? AND table_name = ?
                  AND constraint_name = ? AND constraint_type = 'FOREIGN KEY'
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            statement.setString(2, table);
            statement.setString(3, constraint);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static String withCatalog(String jdbcUrl, String catalog) {
        int authorityEnd = jdbcUrl.indexOf('/', "jdbc:mysql://".length());
        if (authorityEnd < 0) {
            throw new IllegalArgumentException("CONNEX_DB_URL must include a database path");
        }
        int queryStart = jdbcUrl.indexOf('?', authorityEnd);
        String suffix = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return jdbcUrl.substring(0, authorityEnd + 1) + catalog + suffix;
    }
}
