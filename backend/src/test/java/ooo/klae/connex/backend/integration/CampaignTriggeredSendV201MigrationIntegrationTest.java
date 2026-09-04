package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignTriggeredSendV201MigrationIntegrationTest {

    private static final String SCRATCH_CATALOG =
            "connex_campaign_v201_it_" + UUID.randomUUID().toString().replace("-", "");

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void createV200Catalog() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
                "CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb"
                        + "?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping V201 migration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false,
                    "Cannot create V201 migration scratch catalog: " + exception.getMessage());
        }
        Flyway throughV200 = migrateTo("200");
        assertEquals(MigrationVersion.fromVersion("200"),
                throughV200.info().current().getVersion());
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
    void migratesFreshV200CatalogWithStandardForeignKeysAndIndexableDedupeColumns()
            throws SQLException {
        Flyway flyway = migrateTo("201");

        try (Connection connection = connection()) {
            assertForeignKey(connection, "campaign_audience_snapshot",
                    "fk_campaign_snapshot_campaign",
                    List.of("workspace_id", "campaign_id"), "campaign",
                    List.of("workspace_id", "id"), "uq_campaign_workspace_id");
            assertForeignKey(connection, "campaign_audience_snapshot",
                    "fk_campaign_snapshot_triggered_message",
                    List.of("workspace_id", "triggered_message_id"), "campaign_message",
                    List.of("workspace_id", "id"), "uq_campaign_message_workspace_id");
            assertForeignKey(connection, "campaign_send", "fk_campaign_send_campaign",
                    List.of("workspace_id", "campaign_id"), "campaign",
                    List.of("workspace_id", "id"), "uq_campaign_workspace_id");
            assertForeignKey(connection, "campaign_send", "fk_campaign_send_snapshot",
                    List.of("workspace_id", "snapshot_id"), "campaign_audience_snapshot",
                    List.of("workspace_id", "id"), "uq_campaign_snapshot_workspace_id");
            assertForeignKey(connection, "campaign_send", "fk_campaign_send_message",
                    List.of("workspace_id", "message_id"), "campaign_message",
                    List.of("workspace_id", "id"), "uq_campaign_message_workspace_id");
            assertForeignKey(connection, "campaign_delivery", "fk_campaign_delivery_send",
                    List.of("workspace_id", "send_id"), "campaign_send",
                    List.of("workspace_id", "id"), "uq_campaign_send_workspace_id");
            assertForeignKey(connection, "campaign_delivery", "fk_campaign_delivery_person",
                    List.of("person_id"), "person", List.of("id"), "PRIMARY");
            assertForeignKey(connection, "workflow_step_run", "fk_workflow_step_run",
                    List.of("workspace_id", "workflow_run_id"), "workflow_run",
                    List.of("workspace_id", "id"), "uq_workflow_run_workspace_id");
            assertForeignKey(connection, "workflow_trigger_outbox",
                    "fk_workflow_trigger_outbox_version",
                    List.of("workspace_id", "workflow_id", "workflow_version_id"),
                    "workflow_version", List.of("workspace_id", "workflow_id", "id"),
                    "uq_workflow_version_identity");

            assertEquals(List.of("workspace_id", "triggered_message_id",
                            "triggered_message_version"),
                    indexColumns(connection, "campaign_audience_snapshot",
                            "uq_campaign_snapshot_triggered_revision"));
            assertUniqueIndex(connection, "campaign_audience_snapshot",
                    "uq_campaign_snapshot_triggered_revision");
            assertEquals(List.of("workspace_id", "message_id", "triggered_message_version"),
                    indexColumns(connection, "campaign_send", "uq_campaign_send_triggered"));
            assertUniqueIndex(connection, "campaign_send", "uq_campaign_send_triggered");
            assertEquals(List.of("workspace_id", "send_id", "person_id", "dedupe_active"),
                    indexColumns(connection, "campaign_delivery",
                            "uq_campaign_delivery_send_person"));
            assertUniqueIndex(connection, "campaign_delivery",
                    "uq_campaign_delivery_send_person");
            assertStoredGeneratedColumn(connection, "campaign_send", "triggered_message_version",
                    List.of("origin", "message_version"), List.of());
            assertStoredGeneratedColumn(connection, "campaign_delivery", "dedupe_active",
                    List.of("reconciliation_outcome"), List.of("person_id"));
            assertTrue(columnDefinition(
                    connection, "delivery_provider_config", "config_generation")
                    .startsWith("bigint|"));
            assertEquals("tinyint(1)||", columnDefinition(
                    connection, "delivery_provider_config", "idempotent_submission"));
            assertEquals("0", columnDefault(
                    connection, "delivery_provider_config", "idempotent_submission"));
            assertEquals("char(64)|ascii|ascii_bin", columnDefinition(
                    connection, "campaign_delivery", "attempt_target_fingerprint"));
            assertTrue(columnDefinition(
                    connection, "campaign_delivery", "last_error_code")
                    .startsWith("varchar(32)|"));
            assertDedupeContract(connection);
        }
        assertEquals(MigrationVersion.fromVersion("201"), flyway.info().current().getVersion());
    }

    private static void assertDedupeContract(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO campaign (id, workspace_id, name, type, status)
                    VALUES (65501, 65501, 'Triggered migration', 'email', 'draft')
                    """);
            statement.executeUpdate("""
                    INSERT INTO campaign_message (
                        id, workspace_id, campaign_id, channel, name, status)
                    VALUES (65501, 65501, 65501, 'email', 'Triggered migration', 'final')
                    """);
            statement.executeUpdate("""
                    INSERT INTO campaign_audience_snapshot (
                        id, campaign_id, workspace_id, version, record_type, definition_json,
                        channel, purpose, origin, triggered_message_id, triggered_message_version,
                        estimated_included, excluded_total, excluded_consent, excluded_suppressed,
                        excluded_restricted, excluded_no_address)
                    VALUES (65501, 65501, 65501, 1, 'person', '{}', 'email',
                        'Triggered (system)', 'triggered', 65501, 1, 0, 0, 0, 0, 0, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO campaign_send (
                        id, workspace_id, campaign_id, snapshot_id, origin, message_id,
                        message_version, channel, purpose, status)
                    VALUES (65501, 65501, 65501, 65501, 'triggered', 65501,
                        1, 'email', 'marketing', 'triggered')
                    """);
            statement.executeUpdate("""
                    INSERT INTO person (id, workspace_id, name, email)
                    VALUES (65501, 65501, 'Triggered recipient', 'triggered@example.test')
                    """);
            statement.executeUpdate("""
                    INSERT INTO campaign_delivery (
                        id, workspace_id, send_id, person_id, address, status, unsubscribe_token)
                    VALUES (65501, 65501, 65501, 65501, 'triggered@example.test', 'pending',
                        REPEAT('a', 64))
                    """);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO campaign_delivery (
                        id, workspace_id, send_id, person_id, address, status, unsubscribe_token)
                    VALUES (65502, 65501, 65501, 65501, 'triggered@example.test', 'pending',
                        REPEAT('b', 64))
                    """));
            statement.executeUpdate("""
                    UPDATE campaign_delivery
                    SET status = 'failed', reconciliation_outcome = 'operator_not_delivered'
                    WHERE id = 65501
                    """);
            statement.executeUpdate("""
                    INSERT INTO campaign_delivery (
                        id, workspace_id, send_id, person_id, address, status, unsubscribe_token)
                    VALUES (65502, 65501, 65501, 65501, 'triggered@example.test', 'pending',
                        REPEAT('b', 64))
                    """);
            assertEquals(2, scalar(statement, """
                    SELECT COUNT(*) FROM campaign_delivery
                    WHERE workspace_id = 65501 AND send_id = 65501 AND person_id = 65501
                    """));
        }
    }

    private static void assertForeignKey(
            Connection connection,
            String childTable,
            String constraint,
            List<String> childColumns,
            String parentTable,
            List<String> parentColumns,
            String parentIndex) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND CONSTRAINT_NAME = ?
                ORDER BY ORDINAL_POSITION
                """)) {
            statement.setString(1, childTable);
            statement.setString(2, constraint);
            try (ResultSet resultSet = statement.executeQuery()) {
                for (int index = 0; index < childColumns.size(); index++) {
                    assertTrue(resultSet.next());
                    assertEquals(childColumns.get(index), resultSet.getString("COLUMN_NAME"));
                    assertEquals(parentTable, resultSet.getString("REFERENCED_TABLE_NAME"));
                    assertEquals(parentColumns.get(index),
                            resultSet.getString("REFERENCED_COLUMN_NAME"));
                    assertMatchingColumnType(connection, childTable, childColumns.get(index),
                            parentTable, parentColumns.get(index));
                }
                assertTrue(!resultSet.next());
            }
        }
        assertEquals(parentColumns, indexColumns(connection, parentTable, parentIndex));
        assertUniqueIndex(connection, parentTable, parentIndex);
    }

    private static void assertMatchingColumnType(
            Connection connection,
            String childTable,
            String childColumn,
            String parentTable,
            String parentColumn) throws SQLException {
        assertEquals(columnDefinition(connection, parentTable, parentColumn),
                columnDefinition(connection, childTable, childColumn));
    }

    private static String columnDefinition(
            Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT CONCAT_WS('|', COLUMN_TYPE, COALESCE(CHARACTER_SET_NAME, ''),
                    COALESCE(COLLATION_NAME, ''))
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static String columnDefault(
            Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COLUMN_DEFAULT
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static List<String> indexColumns(
            Connection connection, String table, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COLUMN_NAME
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                ORDER BY SEQ_IN_INDEX
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                var columns = new java.util.ArrayList<String>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME"));
                }
                return List.copyOf(columns);
            }
        }
    }

    private static void assertUniqueIndex(
            Connection connection, String table, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT NON_UNIQUE
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt("NON_UNIQUE"));
                assertTrue(!resultSet.next());
            }
        }
    }

    private static void assertStoredGeneratedColumn(
            Connection connection,
            String table,
            String column,
            List<String> requiredFragments,
            List<String> forbiddenFragments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXTRA, GENERATION_EXPRESSION
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("STORED GENERATED", resultSet.getString("EXTRA"));
                String expression = resultSet.getString("GENERATION_EXPRESSION").toLowerCase();
                requiredFragments.forEach(fragment -> assertTrue(expression.contains(fragment)));
                forbiddenFragments.forEach(fragment -> assertTrue(!expression.contains(fragment)));
            }
        }
    }

    private static Flyway migrateTo(String version) {
        Flyway flyway = Flyway.configure()
                .dataSource(scratchUrl, username, password)
                .locations("classpath:db/migration")
                .callbacks(new AuditLogV126MigrationCallback())
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .target(MigrationVersion.fromVersion(version))
                .load();
        flyway.migrate();
        return flyway;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(scratchUrl, username, password);
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
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
