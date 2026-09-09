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

/** Verifies the V210 closed allowlist for durable manual-preview reasons. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowV210MigrationIntegrationTest {

    private static final String SCRATCH_CATALOG =
        "connex_workflow_v210_it_" + UUID.randomUUID().toString().replace("-", "");

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
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping V210 migration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false,
                "Cannot create V210 migration scratch catalog: " + exception.getMessage());
        }
        Flyway flyway = migrateTo("209");
        assertEquals(MigrationVersion.fromVersion("209"), flyway.info().current().getVersion());
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
    void migrationAdmitsOnlyTheNewPreviewReasonsAndKeepsExecutionCategoriesClosed()
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(scratchUrl, username, password);
                Statement statement = connection.createStatement()) {
            seedInvocation(statement);
            assertThrows(SQLException.class, () -> insertPreviewReason(
                statement, 0, 70001, "active_run_exists"));

            Flyway flyway = migrateTo("210");
            assertEquals(
                MigrationVersion.fromVersion("210"), flyway.info().current().getVersion());
            insertPreviewReason(statement, 0, 70001, "entry_condition_not_matched");
            insertPreviewReason(statement, 1, 70002, "active_run_exists");
            insertPreviewReason(statement, 2, 70003, "cooldown_active");
            insertPreviewReason(statement, 3, 70004, "record_unavailable");
            assertThrows(SQLException.class, () -> insertPreviewReason(
                statement, 4, 70005, "unknown_enrollment_reason"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO workflow_invocation_record (
                    workspace_id, invocation_id, ordinal, record_id,
                    preview_status, preview_reason_code, execution_status,
                    execution_failure_category)
                VALUES (
                    65001, 65001, 5, 70006,
                    'ready', NULL, 'skipped', 'cooldown_active')
                """));
        }
    }

    private static void seedInvocation(Statement statement) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO workflow (
                id, workspace_id, name, enabled, draft_record_type,
                draft_execution_mode, draft_definition_json, draft_canvas_json)
            VALUES (
                65001, 65001, 'Preview reason migration', FALSE, 'deal', 'user',
                '{"schemaVersion":2,"entryNodeId":null,"nodes":[],"edges":[]}',
                '{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}')
            """);
        statement.executeUpdate("""
            INSERT INTO workflow_version (
                id, workspace_id, workflow_id, version_number, name, record_type,
                trigger_type, trigger_config, actions_json, execution_mode,
                definition_json, canvas_json, definition_hash)
            VALUES (
                65001, 65001, 65001, 1, 'Preview reason migration', 'deal',
                'manual', '{}', '[]', 'user',
                '{"schemaVersion":2,"entryNodeId":null,"nodes":[],"edges":[]}',
                '{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}',
                UNHEX(REPEAT('00', 32)))
            """);
        statement.executeUpdate("""
            INSERT INTO workflow_invocation (
                id, workspace_id, workflow_id, workflow_version_id,
                scope_kind, resolved_scope_kind, source_surface, record_type,
                scope_token_hash, scope_hash, scope_contract_json,
                exact_count, ready_count, skipped_count, status, expires_at)
            VALUES (
                65001, 65001, 65001, 65001,
                'single_record', 'single_record', 'record', 'deal',
                UNHEX(REPEAT('01', 32)), UNHEX(REPEAT('02', 32)), '{}',
                4, 0, 4, 'prepared', '2099-01-01 00:00:00')
            """);
    }

    private static void insertPreviewReason(
            Statement statement,
            int ordinal,
            int recordId,
            String reason) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO workflow_invocation_record (
                workspace_id, invocation_id, ordinal, record_id,
                preview_status, preview_reason_code, execution_status)
            VALUES (65001, 65001, %d, %d, 'skipped', '%s', 'skipped')
            """.formatted(ordinal, recordId, reason));
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
