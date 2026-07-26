package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Applies the full lineage through V124 to a legacy-collation catalog and verifies
 * the canonical identity schema at the real MySQL boundary.
 */
class CanonicalIdentityMigrationIntegrationTest {

    private static final String SCRATCH_CATALOG =
        "connex_identity_it_" + UUID.randomUUID().toString().replace("-", "");
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
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping canonical identity migration test");
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
                "Cannot create canonical identity scratch catalog: " + exception.getMessage());
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
    void v123AndV124LandCompleteRerunnableIdentitySchema() throws SQLException {
        Flyway flyway = Flyway.configure()
            .dataSource(scratchUrl, username, password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .target(MigrationVersion.fromVersion("124"))
            .load();

        flyway.migrate();

        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password)) {
            assertEquals(
                "utf8mb4_unicode_ci",
                databaseCollation(connection));
            assertEquals(
                List.of("InnoDB", "InnoDB", "InnoDB"),
                tableEngines(connection));
            assertEquals(
                List.of(
                    CANONICAL_COLLATION,
                    CANONICAL_COLLATION,
                    CANONICAL_COLLATION,
                    CANONICAL_COLLATION),
                indexedColumnCollations(connection));
            assertEquals(
                List.of(
                    "company_identity:company",
                    "identity_collision:company_identity",
                    "identity_collision:person_identity",
                    "person_identity:person"),
                foreignKeyTargets(connection));
            assertEquals(4L, foreignKeyCount(connection));
            assertTrue(indexNames(connection).stream().allMatch(name -> name.length() <= 64));
            List<String> prefixes = normalizedValueIndexPrefixes(connection);
            assertEquals(2, prefixes.size());
            assertTrue(prefixes.stream().allMatch(prefix -> prefix == null));
            assertFullNormalizedValueIsIndexed(connection);
        }

        flyway.migrate();

        assertEquals(0, flyway.info().pending().length);
        flyway.validate();
    }

    private static String databaseCollation(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT @@collation_database")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static List<String> tableEngines(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT engine
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_name IN ('person_identity', 'company_identity', 'identity_collision')
                ORDER BY table_name
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                return strings(resultSet);
            }
        }
    }

    private static List<String> indexedColumnCollations(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT collation_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name IN ('person_identity', 'company_identity')
                  AND column_name IN ('kind', 'normalized_value')
                ORDER BY table_name, column_name
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                return strings(resultSet);
            }
        }
    }

    private static List<String> foreignKeyTargets(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT CONCAT(table_name, ':', referenced_table_name)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND table_name IN ('person_identity', 'company_identity', 'identity_collision')
                ORDER BY table_name, referenced_table_name
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                return strings(resultSet);
            }
        }
    }

    private static long foreignKeyCount(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND table_name IN ('person_identity', 'company_identity', 'identity_collision')
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static List<String> indexNames(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name IN ('person_identity', 'company_identity', 'identity_collision')
                ORDER BY index_name
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                return strings(resultSet);
            }
        }
    }

    private static List<String> normalizedValueIndexPrefixes(Connection connection)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT sub_part
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND index_name IN (
                    'uq_person_identity_workspace_kind_normalized_value_person_id',
                    'uq_company_identity_workspace_kind_normalized_value_company_id'
                  )
                  AND column_name = 'normalized_value'
                ORDER BY index_name
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<String> values = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return values;
            }
        }
    }

    private static void assertFullNormalizedValueIsIndexed(Connection connection)
            throws SQLException {
        int workspaceId = insertReturningId(
            connection,
            "INSERT INTO workspace (name, slug) VALUES ('Identity Migration', CONCAT('identity-', UUID()))");
        int personId = insertReturningId(
            connection,
            "INSERT INTO person (workspace_id, name) VALUES ("
                + workspaceId + ", 'Identity Migration Person')");
        String prefix = "a".repeat(511);
        try (var statement = connection.prepareStatement("""
                INSERT INTO person_identity (
                  workspace_id, person_id, kind, `value`, normalized_value,
                  source_system, acquired_at
                )
                VALUES (?, ?, 'external_id', ?, ?, 'backfill', CURRENT_TIMESTAMP)
                """)) {
            statement.setInt(1, workspaceId);
            statement.setInt(2, personId);
            statement.setString(3, prefix + "x");
            statement.setString(4, prefix + "x");
            statement.executeUpdate();
            statement.setInt(1, workspaceId);
            statement.setInt(2, personId);
            statement.setString(3, prefix + "y");
            statement.setString(4, prefix + "y");
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ?
                """)) {
            statement.setInt(1, workspaceId);
            statement.setInt(2, personId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(2, resultSet.getInt(1));
            }
        }
    }

    private static int insertReturningId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static List<String> strings(ResultSet resultSet) throws SQLException {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        while (resultSet.next()) {
            values.add(resultSet.getString(1));
        }
        return values;
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
