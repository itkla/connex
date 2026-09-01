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

class RecordCreationTemplateMigrationIntegrationTest {

    private static final String SCRATCH_CATALOG =
        "connex_record_creation_it_" + UUID.randomUUID().toString().replace("-", "");

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void migrateOnlyTheTenantV194Lineage() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping record creation migration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false, "Cannot create record creation scratch catalog: " + exception.getMessage());
        }

        Flyway flyway = Flyway.configure()
            .dataSource(scratchUrl, username, password)
            .locations("classpath:db/migration/tenant")
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("193"))
            .target(MigrationVersion.fromVersion("194"))
            .outOfOrder(false)
            .load();
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
    void v194CreatesOnlyTheThreeTenantTablesWithChecksIndexesAndForeignKeys()
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password)) {
            assertEquals(
                List.of(
                    "record_creation_template",
                    "record_creation_template_set",
                    "record_creation_template_version"),
                tableNames(connection));
            assertEquals(10, count(connection, """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = ? AND constraint_type = 'CHECK'
                  AND table_name IN (
                    'record_creation_template_set',
                    'record_creation_template',
                    'record_creation_template_version'
                  )
                """, SCRATCH_CATALOG));
            assertEquals(
                List.of(
                    "idx_record_creation_template_created_by",
                    "idx_record_creation_template_current",
                    "idx_record_creation_template_order",
                    "idx_record_creation_template_set_default",
                    "idx_record_creation_template_updated_by",
                    "idx_record_creation_template_version_created_by",
                    "uq_record_creation_template_version_identity",
                    "uq_record_creation_template_version_number",
                    "uq_record_creation_template_workspace_id"),
                indexNames(connection));
            assertEquals(
                List.of(
                    "fk_record_creation_template_current_version:record_creation_template_version",
                    "fk_record_creation_template_set:record_creation_template_set",
                    "fk_record_creation_template_version_template:record_creation_template"),
                foreignKeyTargets(connection));
            assertEquals(0, count(connection, """
                SELECT COUNT(*)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND referenced_table_name IN ('app_user', 'workspace')
                  AND table_name LIKE 'record_creation_template%'
                """, SCRATCH_CATALOG));
        }
    }

    @Test
    void checksRejectInvalidTypesStatusesAndDefinitionShapes() throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO record_creation_template_set (workspace_id, record_type)
                VALUES (71, 'lead')
                """));
            statement.executeUpdate("""
                INSERT INTO record_creation_template_set (workspace_id, record_type)
                VALUES (71, 'person')
                """);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO record_creation_template
                    (workspace_id, record_type, status, archived_at)
                VALUES (71, 'person', 'archived', NULL)
                """));
            statement.executeUpdate("""
                INSERT INTO record_creation_template
                    (workspace_id, record_type, status)
                VALUES (71, 'person', 'disabled')
                """);
            long rootId = lastInsertId(statement);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO record_creation_template_version
                    (workspace_id, template_id, version_number, name_en, name_ja,
                     definition_json, definition_hash)
                VALUES (71, %d, 1, 'Invalid', '無効',
                        '{"schemaVersion":2,"groups":[]}', UNHEX(SHA2('invalid', 256)))
                """.formatted(rootId)));
        }
    }

    @Test
    void currentVersionIsRestrictedToTheSameTemplateAndRootCascadeRemovesVersions()
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                Statement statement = connection.createStatement()) {
            long firstRoot = insertRoot(statement, 72, "person");
            long secondRoot = insertRoot(statement, 72, "person");
            long firstVersion = insertVersion(statement, 72, firstRoot, 1, "First");
            long secondVersion = insertVersion(statement, 72, secondRoot, 1, "Second");

            statement.executeUpdate("""
                UPDATE record_creation_template SET current_version_id = %d WHERE id = %d
                """.formatted(firstVersion, firstRoot));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE record_creation_template SET current_version_id = %d WHERE id = %d
                """.formatted(secondVersion, firstRoot)));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM record_creation_template_version WHERE id = " + firstVersion));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM record_creation_template_set WHERE workspace_id = 72 "
                    + "AND record_type = 'person'"));

            statement.executeUpdate("""
                UPDATE record_creation_template
                SET current_version_id = NULL
                WHERE workspace_id = 72
                """);
            statement.executeUpdate(
                "DELETE FROM record_creation_template_set WHERE workspace_id = 72 AND record_type = 'person'");
            assertEquals(0, scalar(statement, """
                SELECT COUNT(*) FROM record_creation_template WHERE workspace_id = 72
                """));
            assertEquals(0, scalar(statement, """
                SELECT COUNT(*) FROM record_creation_template_version WHERE workspace_id = 72
                """));
        }
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
                return strings(resultSet);
            }
        }
    }

    private static List<String> indexNames(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name LIKE 'record_creation_template%'
                  AND index_name <> 'PRIMARY'
                ORDER BY index_name
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                return strings(resultSet);
            }
        }
    }

    private static List<String> foreignKeyTargets(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT CONCAT(constraint_name, ':', referenced_table_name)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND table_name LIKE 'record_creation_template%'
                ORDER BY constraint_name
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            try (ResultSet resultSet = statement.executeQuery()) {
                return strings(resultSet);
            }
        }
    }

    private static int count(Connection connection, String sql, String schema) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static long insertRoot(Statement statement, int workspaceId, String recordType)
            throws SQLException {
        statement.executeUpdate("""
            INSERT IGNORE INTO record_creation_template_set (workspace_id, record_type)
            VALUES (%d, '%s')
            """.formatted(workspaceId, recordType));
        statement.executeUpdate("""
            INSERT INTO record_creation_template (workspace_id, record_type, status)
            VALUES (%d, '%s', 'disabled')
            """.formatted(workspaceId, recordType));
        return lastInsertId(statement);
    }

    private static long insertVersion(
            Statement statement, int workspaceId, long rootId, int number, String name)
            throws SQLException {
        String definition = "{\"schemaVersion\":1,\"groups\":[]}";
        statement.executeUpdate("""
            INSERT INTO record_creation_template_version
                (workspace_id, template_id, version_number, name_en, name_ja,
                 definition_json, definition_hash)
            VALUES (%d, %d, %d, '%s', 'テンプレート', '%s', UNHEX(SHA2('%s', 256)))
            """.formatted(workspaceId, rootId, number, name, definition, definition));
        return lastInsertId(statement);
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

    private static List<String> strings(ResultSet resultSet) throws SQLException {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        while (resultSet.next()) {
            values.add(resultSet.getString(1));
        }
        return List.copyOf(values);
    }

    private static String withCatalog(String configuredUrl, String catalog) {
        int queryIndex = configuredUrl.indexOf('?');
        String query = queryIndex >= 0 ? configuredUrl.substring(queryIndex) : "";
        String base = queryIndex >= 0 ? configuredUrl.substring(0, queryIndex) : configuredUrl;
        int slashIndex = base.lastIndexOf('/');
        return base.substring(0, slashIndex + 1) + catalog + query;
    }
}
