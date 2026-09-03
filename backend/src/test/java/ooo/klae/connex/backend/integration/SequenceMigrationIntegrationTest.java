package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class SequenceMigrationIntegrationTest {

    private static final String SCRATCH_CATALOG =
        "connex_sequence_it_" + UUID.randomUUID().toString().replace("-", "");

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void migrateOnlyTheTenantV198Lineage() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping sequence migration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false, "Cannot create sequence scratch catalog: " + exception.getMessage());
        }

        Flyway flyway = Flyway.configure()
            .dataSource(scratchUrl, username, password)
            .locations("classpath:db/migration/tenant")
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("197"))
            .target(MigrationVersion.fromVersion("198"))
            .outOfOrder(false)
            .load();
        flyway.baseline();
        flyway.migrate();
        flyway.validate();
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
    void v198CreatesTheImmutablePayloadAndSeparatePublisherPointerWithoutControlPlaneForeignKeys()
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password)) {
            assertEquals(
                List.of(
                    "sequence",
                    "sequence_step",
                    "sequence_step_content",
                    "sequence_version",
                    "sequence_version_publisher"),
                tableNames(connection));
            assertEquals(0, count(connection, """
                SELECT COUNT(*)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND referenced_table_name IN ('app_user', 'workspace')
                  AND table_name LIKE 'sequence%'
                """));
            assertEquals(0, count(connection, """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'sequence_version'
                  AND column_name = 'published_by_id'
                """));
            assertEquals(1, count(connection, """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'sequence_step_content'
                  AND column_name = 'body_text'
                  AND data_type = 'mediumtext'
                """));
        }
    }

    @Test
    void checksRejectInvalidSendPoliciesAndStepVocabulary() throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO sequence
                    (workspace_id, name, visibility, timezone, weekday_mask,
                     send_window_start, send_window_end)
                VALUES (71, 'Invalid weekdays', 'personal', 'UTC', 0, '09:00', '17:00')
                """));
            long sequenceId = insertSequence(statement, 71);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO sequence_step
                    (workspace_id, sequence_id, position, step_type,
                     delay_value, delay_unit, advance_policy)
                VALUES (71, %d, 0, 'sms', 0, 'hours', 'automatic')
                """.formatted(sequenceId)));
        }
    }

    @Test
    void deletingASequenceCascadesDraftContentAndImmutableVersions() throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                Statement statement = connection.createStatement()) {
            long sequenceId = insertSequence(statement, 72);
            statement.executeUpdate("""
                INSERT INTO sequence_step
                    (workspace_id, sequence_id, position, step_type,
                     delay_value, delay_unit, advance_policy)
                VALUES (72, %d, 0, 'send_email', 0, 'hours', 'automatic')
                """.formatted(sequenceId));
            long stepId = lastInsertId(statement);
            statement.executeUpdate("""
                INSERT INTO sequence_step_content (workspace_id, step_id, locale, subject)
                VALUES (72, %d, 'en', 'Hello')
                """.formatted(stepId));
            String definition = "{\"schemaVersion\":1,\"steps\":[]}";
            statement.executeUpdate("""
                INSERT INTO sequence_version
                    (workspace_id, sequence_id, version_number, definition_json, definition_hash)
                VALUES (72, %d, 1, '%s', UNHEX(SHA2('%s', 256)))
                """.formatted(sequenceId, definition, definition));
            long versionId = lastInsertId(statement);
            statement.executeUpdate("""
                INSERT INTO sequence_version_publisher
                    (workspace_id, version_id, published_by_id)
                VALUES (72, %d, 1701)
                """.formatted(versionId));

            statement.executeUpdate("DELETE FROM sequence WHERE id = " + sequenceId);

            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM sequence_step"));
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM sequence_step_content"));
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM sequence_version"));
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM sequence_version_publisher"));
        }
    }

    private static long insertSequence(Statement statement, int workspaceId) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO sequence
                (workspace_id, name, visibility, timezone, weekday_mask,
                 send_window_start, send_window_end)
            VALUES (%d, 'Prospecting', 'personal', 'UTC', 31, '09:00', '17:00')
            """.formatted(workspaceId));
        return lastInsertId(statement);
    }

    private static List<String> tableNames(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<String> values = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return List.copyOf(values);
            }
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static long lastInsertId(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String withCatalog(String configuredUrl, String catalog) {
        int queryIndex = configuredUrl.indexOf('?');
        String query = queryIndex >= 0 ? configuredUrl.substring(queryIndex) : "";
        String base = queryIndex >= 0 ? configuredUrl.substring(0, queryIndex) : configuredUrl;
        int slashIndex = base.lastIndexOf('/');
        return base.substring(0, slashIndex + 1) + catalog + query;
    }
}
