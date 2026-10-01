package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.CampaignAudienceExport;
import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;
import ooo.klae.connex.backend.mappers.CampaignAudienceExportMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignAudienceV199MigrationIntegrationTest {

    private static String scratchUrl;
    private static String username;
    private static String password;
    private static MySqlScratchCatalog scratchCatalog;

    @BeforeAll
    static void createV198EquivalentCatalogWithCampaignAudienceRows() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
                "CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping V199 migration test");
        try {
            scratchCatalog = MySqlScratchCatalog.create(configuredUrl, username, password,
                "connex_campaign_v199_it_", "utf8mb4", "utf8mb4_0900_ai_ci");
            scratchUrl = scratchCatalog.url();
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
                            'attempt', 'idempotency_key', 'lease_until', 'reconciliation_required_at',
                            'failure_reason', 'outcome_classification', 'late_outcome'))
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
                INSERT INTO campaign_audience_snapshot (
                    id, campaign_id, workspace_id, version, record_type, definition_json,
                    estimated_included, excluded_total, excluded_consent,
                    excluded_suppressed, excluded_restricted)
                VALUES (65202, 65201, 65201, 2, 'person', '{}', 2, 0, 0, 0, 0)
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
                INSERT INTO campaign_audience_export (
                    id, workspace_id, campaign_id, snapshot_id, connector, external_list_id,
                    status, total_members, pushed_count, failed_count)
                VALUES (65206, 65201, 65201, 65202, 'http_list', 'legacy-running-list',
                    'running', 2, 0, 0)
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
        if (scratchCatalog != null) {
            scratchCatalog.close();
        }
    }

    @Test
    void v199BackfillsLegacyRowsAndEnforcesTheNewAudienceContract() throws Exception {
        Flyway flyway = migrateTo("199");
        SqlSessionFactory sessionFactory = sqlSessionFactory();

        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                SqlSession session = sessionFactory.openSession(connection)) {
            assertEquals(scratchCatalog.name(), session.getConnection().getCatalog());
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
                  AND outcome_classification IS NULL
                """));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_export
                WHERE id = 65202
                  AND status = 'failed'
                  AND frozen_member_ids_json IS NULL
                  AND pushed_member_ids_json IS NULL
                  AND pushed_count = 0
                  AND failed_count = 2
                  AND reconciliation_required_at IS NULL
                  AND outcome_classification IS NULL
                """));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_export
                WHERE id = 65206
                  AND status = 'running'
                  AND lease_until IS NULL
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
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET failure_reason = 'recipient@example.test'
                WHERE id = 65203
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET late_outcome = 'unknown'
                WHERE id = 65203
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET outcome_classification = 'unknown'
                WHERE id = 65203
                """));
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO campaign_audience_export (
                    id, workspace_id, campaign_id, snapshot_id, connector, external_list_id, status,
                    total_members, pushed_count, failed_count)
                VALUES (65204, 65201, 65201, 65201, 'http_list', NULL, 'running', 0, 0, 0)
                """));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_audience_export
                WHERE id = 65204
                  AND status = 'running'
                  AND lease_until IS NULL
                  AND reconciliation_required_at IS NULL
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
                INSERT INTO campaign_audience_snapshot (
                    id, campaign_id, workspace_id, version, record_type, definition_json,
                    estimated_included, excluded_total, excluded_consent,
                    excluded_suppressed, excluded_restricted)
                VALUES (65203, 65201, 65201, 3, 'person', '{}', 1, 0, 0, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO campaign_audience_export (
                    id, workspace_id, campaign_id, snapshot_id, connector, external_list_id,
                    frozen_member_ids_json, pushed_member_ids_json, status, attempt, lease_until,
                    total_members, pushed_count, failed_count)
                VALUES (65207, 65201, 65201, 65203, 'http_list', 'ambiguous-list',
                    JSON_ARRAY(76), JSON_ARRAY(76), 'running', 1,
                    DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE), 1, 0, 0)
                """);
            String originMainDuplicateFence = """
                SELECT EXISTS (
                  SELECT 1
                  FROM campaign_audience_export
                  WHERE workspace_id = 65201
                    AND campaign_id = 65201
                    AND snapshot_id = 65203
                    AND connector = 'http_list'
                    AND status IN ('draft', 'running', 'completed')
                )
                """;
            assertFenceClassification(
                    statement, session, 1, "leased running without outcome",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE)
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 1, "expired leased running without outcome",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET lease_until = NULL
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 1, "legacy null-lease running without outcome",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'running', pushed_count = 0, failed_count = 0,
                    lease_until = NULL, reconciliation_required_at = UTC_TIMESTAMP(6),
                    outcome_classification = 'ambiguous'
                WHERE workspace_id = 65201
                  AND id = 65207
                  AND status = 'running'
                  AND reconciliation_required_at IS NULL
                """));
            assertFenceClassification(
                    statement, session, 1, "flagged running with ambiguous outcome",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'draft', lease_until = NULL, reconciliation_required_at = NULL,
                    outcome_classification = NULL
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 1, "draft without outcome",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'completed', outcome_classification = 'confirmed_delivery'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 1, "completed with confirmed delivery",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET outcome_classification = 'operator_delivered'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 1, "completed with operator delivery",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'failed', pushed_count = 0, failed_count = 1,
                    outcome_classification = 'operator_not_delivered'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 0, "failed with operator non-delivery",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET outcome_classification = 'confirmed_no_delivery'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 0, "failed with confirmed non-delivery",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET outcome_classification = 'definite_no_side_effect'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 0, "failed with definite no side effect",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET outcome_classification = 'no_eligible_members'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 0, "failed with no eligible members",
                    originMainDuplicateFence);
            assertEquals(1, statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET pushed_count = 1, failed_count = 0
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertFenceClassification(
                    statement, session, 0, "failed with accepted-member history",
                    originMainDuplicateFence);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET outcome_classification = 'ambiguous'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'running', reconciliation_required_at = NULL,
                    outcome_classification = 'ambiguous'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET outcome_classification = 'operator_delivered'
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET outcome_classification = NULL
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'draft', reconciliation_required_at = UTC_TIMESTAMP(6)
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'completed', reconciliation_required_at = UTC_TIMESTAMP(6)
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET reconciliation_required_at = UTC_TIMESTAMP(6)
                WHERE workspace_id = 65201 AND id = 65207
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE campaign_audience_export
                SET status = 'running', lease_until = UTC_TIMESTAMP(6),
                    reconciliation_required_at = UTC_TIMESTAMP(6)
                WHERE workspace_id = 65201 AND id = 65207
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

    private static void assertFenceClassification(
            Statement statement,
            SqlSession session,
            long expected,
            String state,
            String originMainDuplicateFence) throws SQLException {
        String oracle = "outcome_classification is ignored by the origin/main and current "
                + "duplicate fences, so both must agree for state: " + state;
        assertEquals(expected, scalar(statement, originMainDuplicateFence), oracle);
        session.clearCache();
        assertEquals(expected == 1, session.getMapper(CampaignAudienceExportMapper.class)
                .existsActiveForSnapshotConnector(65201, 65201, 65203, "http_list"), oracle);
    }

    private static SqlSessionFactory sqlSessionFactory() throws IOException {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CampaignAudienceExport", CampaignAudienceExport.class);
        String resource = "mappers/CampaignAudienceExportMapper.xml";
        try (InputStream input = CampaignAudienceV199MigrationIntegrationTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Missing mapper resource " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static String stringScalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

}
