package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

/** Verifies the V208 schema-v2 admission and all-or-none date-run source constraints. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowV208MigrationIntegrationTest {

    private static final String SCRATCH_CATALOG =
        "connex_workflow_v208_it_" + UUID.randomUUID().toString().replace("-", "");

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void createCatalog() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb"
                + "?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping V208 migration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false,
                "Cannot create V208 migration scratch catalog: " + exception.getMessage());
        }
        Flyway flyway = migrateTo("208");
        assertEquals(MigrationVersion.fromVersion("208"), flyway.info().current().getVersion());
    }

    @AfterAll
    static void dropCatalog() throws SQLException {
        if (!created) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + SCRATCH_CATALOG + "`");
        }
    }

    @Test
    void admitsSchemaTwoAndRejectsPartialDateSourceOnInsertAndUpdate() throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO workflow (
                    id, workspace_id, name, enabled, draft_record_type,
                    draft_execution_mode, draft_definition_json, draft_canvas_json)
                VALUES (
                    65001, 65001, 'Date migration', FALSE, 'deal', 'user',
                    '{"schemaVersion":2,"entryNodeId":null,"nodes":[],"edges":[]}',
                    '{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}')
                """);
            statement.executeUpdate("""
                INSERT INTO workflow_version (
                    id, workspace_id, workflow_id, version_number, name, record_type,
                    trigger_type, trigger_config, actions_json, execution_mode,
                    definition_json, canvas_json, definition_hash)
                VALUES (
                    65001, 65001, 65001, 1, 'Date migration', 'deal',
                    'date', '{}', '[]', 'user',
                    '{"schemaVersion":2,"entryNodeId":null,"nodes":[],"edges":[]}',
                    '{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}',
                    UNHEX(REPEAT('00', 32)))
                """);
            statement.executeUpdate("""
                UPDATE workflow
                SET active_version_id = 65001, runtime_owner = 'canonical'
                WHERE workspace_id = 65001 AND id = 65001
                """);
            statement.executeUpdate("""
                INSERT INTO workflow_run (
                    id, workspace_id, workflow_id, workflow_version_id, status,
                    trigger_type, trigger_event, trigger_key, record_type, record_id,
                    dedupe_key, execution_mode, current_node_id)
                VALUES (
                    65001, 65001, 65001, 65001, 'queued',
                    'manual', 'manual', 'manual-1', 'deal', 70001,
                    'manual-1', 'user', 'trigger')
                """);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE workflow_run
                SET trigger_type = 'date',
                    trigger_event = 'expectedCloseDate',
                    trigger_key = '2027-03-31',
                    date_source_date = '2027-03-31',
                    date_scheduled_local_date = '2027-03-01',
                    date_due_at = '2027-03-01 19:00:00'
                WHERE workspace_id = 65001 AND id = 65001
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO workflow_run (
                    id, workspace_id, workflow_id, workflow_version_id, status,
                    trigger_type, trigger_event, trigger_key, record_type, record_id,
                    dedupe_key, execution_mode, current_node_id,
                    date_source_date, date_scheduled_local_date, date_due_at)
                VALUES (
                    65002, 65001, 65001, 65001, 'queued',
                    'date', 'expectedCloseDate', '2027-03-31', 'deal', 70001,
                    'date-1', 'user', 'trigger',
                    '2027-03-31', '2027-03-01', '2027-03-01 19:00:00')
                """));
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

    private static String withCatalog(String configuredUrl, String catalog) {
        int queryIndex = configuredUrl.indexOf('?');
        String query = queryIndex >= 0 ? configuredUrl.substring(queryIndex) : "";
        String base = queryIndex >= 0 ? configuredUrl.substring(0, queryIndex) : configuredUrl;
        int slashIndex = base.lastIndexOf('/');
        return base.substring(0, slashIndex + 1) + catalog + query;
    }
}
