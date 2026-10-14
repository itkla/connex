package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

/** Exercises the expand/contract cutover against fresh and populated real MySQL catalogs. */
class CampaignUnsubscribeTokenMigrationIntegrationTest {

    @Test
    void populatedUpgradePreservesEveryStatusAcrossAnInterruptedCutover() throws SQLException {
        try (MySqlScratchCatalog catalog = catalog()) {
            migrate(catalog, "225");
            try (Connection connection = connection(catalog);
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO campaign (id, workspace_id, name, type, status)
                        VALUES (22601, 22601, 'Migration', 'email', 'draft')
                        """);
                statement.executeUpdate("""
                        INSERT INTO campaign_message (id, workspace_id, campaign_id, channel, name, status)
                        VALUES (22601, 22601, 22601, 'email', 'Migration', 'final')
                        """);
                statement.executeUpdate("""
                        INSERT INTO campaign_audience_snapshot
                            (id, workspace_id, campaign_id, version, record_type, definition_json,
                             estimated_included, excluded_total, excluded_consent, excluded_suppressed,
                             excluded_restricted)
                        VALUES (22601, 22601, 22601, 1, 'person', '{}', 0, 0, 0, 0, 0)
                        """);
                statement.executeUpdate("""
                        INSERT INTO campaign_send
                            (id, workspace_id, campaign_id, snapshot_id, message_id, message_version)
                        VALUES (22601, 22601, 22601, 22601, 22601, 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO campaign_delivery
                            (id, workspace_id, send_id, address, status, skip_reason, unsubscribe_token)
                        SELECT 22600 + n, 22601, 22601, 'legacy@example.test', status,
                               IF(status = 'skipped', 'suppressed', NULL), LPAD(HEX(n), 64, '0')
                        FROM (
                            SELECT 1 n, 'pending' status UNION ALL SELECT 2, 'dispatching'
                            UNION ALL SELECT 3, 'dispatched' UNION ALL SELECT 4, 'skipped'
                            UNION ALL SELECT 5, 'failed' UNION ALL SELECT 6, 'delivered'
                            UNION ALL SELECT 7, 'bounced' UNION ALL SELECT 8, 'complained'
                        ) statuses
                        """);
            }
            migrate(catalog, "226");
            try (Connection connection = connection(catalog);
                    Statement statement = connection.createStatement()) {
                assertEquals(8, scalar(statement, """
                        SELECT COUNT(*) FROM campaign_delivery d
                        JOIN campaign_delivery_unsubscribe_token t
                          ON t.workspace_id = d.workspace_id AND t.delivery_id = d.id
                         AND t.token_hash = d.unsubscribe_token_hash
                        """));
                assertEquals(2, scalar(statement, """
                        SELECT COUNT(*) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'campaign_delivery'
                          AND COLUMN_NAME IN ('unsubscribe_token', 'unsubscribe_token_hash')
                        """));
            }
            assertEquals(MigrationVersion.fromVersion("227"),
                    migrate(catalog, "227").info().current().getVersion());
            try (Connection connection = connection(catalog);
                    Statement statement = connection.createStatement()) {
                assertEquals(8, scalar(statement, """
                        SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token t
                        WHERE token_hash = LOWER(SHA2(LPAD(HEX(delivery_id - 22600), 64, '0'), 256))
                        """));
                assertContract(statement);
                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        INSERT INTO campaign_delivery_unsubscribe_token (token_hash, workspace_id, delivery_id)
                        VALUES (REPEAT('a', 64), 22602, 22601)
                        """));
                statement.executeUpdate("""
                        INSERT INTO campaign_delivery_unsubscribe_token (token_hash, workspace_id, delivery_id)
                        VALUES (REPEAT('b', 64), 22601, 22601), (REPEAT('c', 64), 22601, 22601)
                        """);
                assertEquals(10, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
                statement.executeUpdate("DELETE FROM campaign_delivery WHERE id = 22601");
                assertEquals(7, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
            }
        }
    }

    @Test
    void freshUpgradeHasOnlyDigestStorage() throws SQLException {
        try (MySqlScratchCatalog catalog = catalog()) {
            migrate(catalog, "227");
            try (Connection connection = connection(catalog);
                    Statement statement = connection.createStatement()) {
                assertContract(statement);
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
            }
        }
    }

    @Test
    void partiallyCommittedDdlRecoversForwardWithoutReconstructingBearers() throws SQLException, IOException {
        try (MySqlScratchCatalog catalog = catalog()) {
            try (Connection connection = connection(catalog); Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE campaign_delivery (
                            id INT PRIMARY KEY, workspace_id INT NOT NULL,
                            unsubscribe_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                            unsubscribe_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                                GENERATED ALWAYS AS (LOWER(SHA2(unsubscribe_token, 256))) STORED,
                            UNIQUE KEY uq_campaign_delivery_workspace_id (workspace_id, id),
                            UNIQUE KEY uq_campaign_delivery_token (unsubscribe_token),
                            UNIQUE KEY uq_campaign_delivery_token_hash (unsubscribe_token_hash)
                        )
                        """);
                statement.executeUpdate("INSERT INTO campaign_delivery (id, workspace_id, unsubscribe_token)"
                        + " VALUES (1, 22601, REPEAT('a', 64)), (2, 22601, REPEAT('b', 64))");
                String expand = sql("V226__campaign_delivery_unsubscribe_hash_history.sql");
                statement.execute(expand.substring(0, expand.indexOf(';')));
                Flyway interruptedExpand = recoveryFlyway(catalog, "226");
                assertThrows(FlywayException.class, interruptedExpand::migrate);
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
                statement.execute("DROP TABLE campaign_delivery_unsubscribe_token");
                interruptedExpand.repair();
                interruptedExpand.migrate();
                assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
                statement.execute(sql("V227__drop_campaign_delivery_unsubscribe_tokens.sql"));
                Flyway interruptedContract = recoveryFlyway(catalog, "227");
                assertThrows(FlywayException.class, interruptedContract::migrate);
                assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
                statement.execute("""
                        ALTER TABLE campaign_delivery
                            ADD COLUMN unsubscribe_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                            ADD COLUMN unsubscribe_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                                GENERATED ALWAYS AS (LOWER(SHA2(unsubscribe_token, 256))) STORED,
                            ADD UNIQUE KEY uq_campaign_delivery_token (unsubscribe_token),
                            ADD UNIQUE KEY uq_campaign_delivery_token_hash (unsubscribe_token_hash)
                        """);
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery WHERE unsubscribe_token IS NOT NULL"));
                interruptedContract.repair();
                interruptedContract.migrate();
                assertContract(statement);
                assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"
                        + " WHERE token_hash IN (LOWER(SHA2(REPEAT('a', 64), 256)), LOWER(SHA2(REPEAT('b', 64), 256)))"));
            }
        }
    }

    private static String sql(String filename) throws IOException {
        return new String(new ClassPathResource("db/migration/tenant/" + filename).getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    private static Flyway recoveryFlyway(MySqlScratchCatalog catalog, String target) {
        return Flyway.configure()
                .dataSource(catalog.url(), System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"))
                .locations("classpath:db/migration/tenant")
                .baselineOnMigrate(true).baselineVersion(MigrationVersion.fromVersion("225"))
                .target(MigrationVersion.fromVersion(target)).load();
    }

    private static void assertContract(Statement statement) throws SQLException {
        assertEquals(0, scalar(statement, """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'campaign_delivery'
                  AND COLUMN_NAME IN ('unsubscribe_token', 'unsubscribe_token_hash')
                """));
        assertEquals(0, scalar(statement, """
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'campaign_delivery'
                  AND INDEX_NAME IN ('uq_campaign_delivery_token', 'uq_campaign_delivery_token_hash')
                """));
        try (ResultSet rows = statement.executeQuery("""
                SELECT COLUMN_TYPE, CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_KEY
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'campaign_delivery_unsubscribe_token'
                  AND COLUMN_NAME = 'token_hash'
                """)) {
            assertTrue(rows.next());
            assertEquals("char(64)", rows.getString(1));
            assertEquals("ascii", rows.getString(2));
            assertEquals("ascii_bin", rows.getString(3));
            assertEquals("PRI", rows.getString(4));
        }
    }

    private static MySqlScratchCatalog catalog() throws SQLException {
        assumeTrue(System.getenv("CONNEX_DB_USERNAME") != null && System.getenv("CONNEX_DB_PASSWORD") != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set");
        return MySqlScratchCatalog.create(System.getenv("CONNEX_DB_URL"),
                System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"),
                "connex_unsubscribe_it_", "utf8mb4", "utf8mb4_0900_ai_ci");
    }

    private static Flyway migrate(MySqlScratchCatalog catalog, String version) {
        Flyway flyway = Flyway.configure()
                .dataSource(catalog.url(), System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"))
                .locations("classpath:db/migration")
                .callbacks(new AuditLogV126MigrationCallback())
                .target(MigrationVersion.fromVersion(version)).load();
        flyway.migrate();
        return flyway;
    }

    private static Connection connection(MySqlScratchCatalog catalog) throws SQLException {
        return DriverManager.getConnection(catalog.url(),
                System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"));
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
