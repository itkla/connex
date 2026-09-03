package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignAudienceV199MigrationIntegrationTest {

    private static final String SCRATCH_CATALOG =
            "connex_campaign_v199_it_" + UUID.randomUUID().toString().replace("-", "");

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void createV198EquivalentCatalogWithCampaignAudienceRows() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
                "CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping V199 migration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false, "Cannot create V199 migration scratch catalog: " + exception.getMessage());
        }
        Flyway throughV198 = migrateTo("198");
        assertEquals(MigrationVersion.fromVersion("198"), throughV198.info().current().getVersion());
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND (
                    (TABLE_NAME = 'campaign_audience' AND COLUMN_NAME IN ('channel', 'purpose'))
                    OR (TABLE_NAME = 'campaign_audience_snapshot'
                        AND COLUMN_NAME IN ('channel', 'purpose', 'excluded_no_address'))
                    OR (TABLE_NAME = 'campaign_audience_export'
                        AND COLUMN_NAME IN ('frozen_member_ids_json', 'pushed_member_ids_json',
                            'attempt', 'lease_until'))
                    OR (TABLE_NAME = 'connector_config' AND COLUMN_NAME = 'config_version')
                  )
                """));
            statement.executeUpdate("""
                INSERT INTO campaign (id, workspace_id, name, type, status)
                VALUES (65201, 65201, 'Legacy campaign', 'email', 'draft')
                """);
            statement.executeUpdate("""
                INSERT INTO campaign_audience (
                    id, campaign_id, workspace_id, record_type, definition_json, mode)
                VALUES (65201, 65201, 65201, 'person', '{}', 'live')
                """);
            statement.executeUpdate("""
                INSERT INTO campaign_audience_snapshot (
                    id, campaign_id, workspace_id, version, record_type, definition_json,
                    estimated_included, excluded_total, excluded_consent,
                    excluded_suppressed, excluded_restricted)
                VALUES (65201, 65201, 65201, 1, 'person', '{}', 2, 3, 1, 1, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO campaign_audience_member (
                    id, snapshot_id, workspace_id, record_type, record_id, status, exclusion_reason)
                VALUES (65201, 65201, 65201, 'person', 76, 'excluded', 'suppressed')
                """);
            statement.executeUpdate("""
                INSERT INTO campaign_audience_export (
                    id, workspace_id, campaign_id, snapshot_id, connector, external_list_id,
                    status, total_members, pushed_count, failed_count)
                VALUES (65201, 65201, 65201, 65201, 'http_list', 'legacy-list',
                    'completed', 2, 2, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO campaign_audience_export (
                    id, workspace_id, campaign_id, snapshot_id, connector, external_list_id,
                    status, total_members, pushed_count, failed_count)
                VALUES (65202, 65201, 65201, 65201, 'http_list', 'possibly-accepted-list',
                    'failed', 2, 0, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO connector_config (
                    id, workspace_id, connector, endpoint, external_list_id,
                    credential_ref, credential_last4, enabled)
                VALUES (65201, 65201, 'http_list', 'https://legacy.example.test/audience',
                    'legacy-list', 'secret:v1:legacy', 'gacy', TRUE)
                """);
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
    void v199BackfillsLegacyRowsAndEnforcesTheNewAudienceContract() throws SQLException {
        Flyway flyway = migrateTo("199");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertEquals("email:marketing", stringScalar(statement, """
                SELECT CONCAT(channel, ':', purpose)
                FROM campaign_audience WHERE id = 65201
                """));
            assertEquals("email:marketing:0", stringScalar(statement, """
                SELECT CONCAT(channel, ':', purpose, ':', excluded_no_address)
                FROM campaign_audience_snapshot WHERE id = 65201
                """));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_member
                WHERE id = 65201 AND exclusion_reason = 'suppressed'
                """));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_export
                WHERE id = 65201
                  AND frozen_member_ids_json IS NULL
                  AND pushed_member_ids_json IS NULL
                """));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_export
                WHERE id = 65202
                  AND status = 'needs_reconciliation'
                  AND frozen_member_ids_json IS NULL
                  AND pushed_member_ids_json IS NULL
                  AND pushed_count IS NULL
                  AND failed_count IS NULL
                """));
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'completed', pushed_count = NULL, failed_count = NULL
                WHERE id = 65202 AND status = 'needs_reconciliation'
                """));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_export
                WHERE id = 65202
                  AND status = 'completed'
                  AND pushed_count IS NULL
                  AND failed_count IS NULL
                """));
            assertEquals(1, scalar(statement, """
                SELECT config_version FROM connector_config WHERE id = 65201
                """));
            statement.executeUpdate("""
                INSERT INTO campaign_audience_export (
                    id, workspace_id, campaign_id, snapshot_id, connector,
                    frozen_member_ids_json, pushed_member_ids_json, status, attempt, lease_until,
                    total_members, pushed_count, failed_count)
                VALUES (65203, 65201, 65201, 65201, 'http_list', JSON_ARRAY(), JSON_ARRAY(),
                    'running', 1, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE), 0, 0, 0)
                """);
            assertEquals(0, scalar(statement, """
                SELECT JSON_LENGTH(frozen_member_ids_json)
                FROM campaign_audience_export
                WHERE id = 65203
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET frozen_member_ids_json = JSON_OBJECT('id', 76)
                WHERE id = 65203
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET pushed_count = NULL, failed_count = 0
                WHERE id = 65203
                """));
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO campaign_audience_export (
                    id, workspace_id, campaign_id, snapshot_id, connector,
                    frozen_member_ids_json, pushed_member_ids_json, status,
                    total_members, pushed_count, failed_count)
                VALUES (65204, 65201, 65201, 65201, 'http_list', JSON_ARRAY(), JSON_ARRAY(),
                    'running', 0, 0, 0)
                """));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_export
                WHERE id = 65204 AND status = 'running' AND lease_until IS NULL
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO campaign_audience_export (
                    id, workspace_id, campaign_id, snapshot_id, connector,
                    frozen_member_ids_json, pushed_member_ids_json, status, lease_until,
                    total_members, pushed_count, failed_count)
                VALUES (65205, 65201, 65201, 65201, 'http_list', JSON_ARRAY(), JSON_ARRAY(),
                    'failed', DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE), 0, 0, 0)
                """));
            statement.executeUpdate("""
                INSERT INTO connector_config (
                    workspace_id, connector, endpoint, external_list_id,
                    credential_ref, credential_last4, enabled)
                VALUES (65201, 'http_list', 'https://current.example.test/audience',
                    'current-list', 'secret:v1:current', 'rent', TRUE)
                ON DUPLICATE KEY UPDATE
                    endpoint = VALUES(endpoint),
                    external_list_id = VALUES(external_list_id),
                    credential_ref = VALUES(credential_ref),
                    credential_last4 = VALUES(credential_last4),
                    enabled = VALUES(enabled),
                    config_version = config_version + 1
                """);
            assertEquals(2, scalar(statement, """
                SELECT config_version FROM connector_config WHERE id = 65201
                """));
            statement.executeUpdate("""
                INSERT INTO campaign (id, workspace_id, name, type, status)
                VALUES (65202, 65201, 'Rollback contract campaign', 'email', 'draft')
                """);
            statement.executeUpdate("""
                INSERT INTO campaign_audience (
                    campaign_id, workspace_id, record_type, definition_json, mode)
                VALUES (65202, 65201, 'person', '{}', 'live')
                """);
            statement.executeUpdate("""
                INSERT INTO campaign_audience_snapshot (
                    campaign_id, workspace_id, version, record_type, definition_json,
                    estimated_included, excluded_total, excluded_consent,
                    excluded_suppressed, excluded_restricted)
                VALUES (65202, 65201, 1, 'person', '{}', 0, 0, 0, 0, 0)
                """);
            assertEquals("email:marketing", stringScalar(statement, """
                SELECT CONCAT(channel, ':', purpose)
                FROM campaign_audience WHERE campaign_id = 65202
                """));
            assertEquals("email:marketing:0", stringScalar(statement, """
                SELECT CONCAT(channel, ':', purpose, ':', excluded_no_address)
                FROM campaign_audience_snapshot WHERE campaign_id = 65202
                """));
            statement.executeUpdate("""
                INSERT INTO campaign (id, workspace_id, name, type, status)
                VALUES (65203, 65201, 'Null contract campaign', 'email', 'draft')
                """);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO campaign_audience (
                    campaign_id, workspace_id, record_type, definition_json, mode, channel, purpose)
                VALUES (65203, 65201, 'person', '{}', 'live', NULL, NULL)
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO campaign_audience (
                    campaign_id, workspace_id, record_type, definition_json, mode, channel, purpose)
                VALUES (65203, 65201, 'person', '{}', 'live', 'fax', 'marketing')
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO campaign_audience_snapshot (
                    campaign_id, workspace_id, version, record_type, definition_json,
                    channel, purpose, estimated_included, excluded_total, excluded_consent,
                    excluded_suppressed, excluded_restricted, excluded_no_address)
                VALUES (65203, 65201, 1, 'person', '{}', NULL, NULL, 0, 0, 0, 0, 0, 0)
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO campaign_audience_snapshot (
                    campaign_id, workspace_id, version, record_type, definition_json,
                    channel, purpose, estimated_included, excluded_total, excluded_consent,
                    excluded_suppressed, excluded_restricted, excluded_no_address)
                VALUES (65203, 65201, 1, 'person', '{}', 'fax', 'marketing', 0, 0, 0, 0, 0, 0)
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO campaign_audience_snapshot (
                    campaign_id, workspace_id, version, record_type, definition_json,
                    channel, purpose, estimated_included, excluded_total, excluded_consent,
                    excluded_suppressed, excluded_restricted, excluded_no_address)
                VALUES (65203, 65201, 1, 'person', '{}', 'email', 'marketing', 0, 0, 0, 0, 0, 1)
                """));
            statement.executeUpdate("""
                INSERT INTO campaign_audience_member (
                    snapshot_id, workspace_id, record_type, record_id, status, exclusion_reason)
                VALUES (65201, 65201, 'person', 77, 'excluded', 'no_address')
                """);
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_member
                WHERE snapshot_id = 65201 AND exclusion_reason = 'no_address'
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO campaign_audience_member (
                    snapshot_id, workspace_id, record_type, record_id, status, exclusion_reason)
                VALUES (65201, 65201, 'person', 78, 'excluded', 'unknown_reason')
                """));
        }
        assertEquals(MigrationVersion.fromVersion("199"), flyway.info().current().getVersion());
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

    private static String stringScalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
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
