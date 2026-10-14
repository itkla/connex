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
            migrate(catalog, "226");
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
                seedLegacyDiagnostics(statement);
            }
            migrate(catalog, "227");
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
                statement.executeUpdate("DELETE FROM campaign_delivery_unsubscribe_token WHERE delivery_id = 22605");
                assertEquals(7, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
            }
            assertEquals(MigrationVersion.fromVersion("228"),
                    migrate(catalog, "228").info().current().getVersion());
            try (Connection connection = connection(catalog);
                    Statement statement = connection.createStatement()) {
                assertEquals(8, scalar(statement, """
                        SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token t
                        WHERE token_hash = LOWER(SHA2(LPAD(HEX(delivery_id - 22600), 64, '0'), 256))
                        """));
                assertContract(statement);
                assertScrubbedDiagnostics(statement, 8);
                assertPreservedEventKeys(statement, 8);
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
            migrate(catalog, "228");
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
                            last_error VARCHAR(512) NULL, provider_message_id VARCHAR(255) NULL,
                            unsubscribe_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                            unsubscribe_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                                GENERATED ALWAYS AS (LOWER(SHA2(unsubscribe_token, 256))) STORED,
                            UNIQUE KEY uq_campaign_delivery_workspace_id (workspace_id, id),
                            UNIQUE KEY uq_campaign_delivery_token (unsubscribe_token),
                            UNIQUE KEY uq_campaign_delivery_token_hash (unsubscribe_token_hash)
                        )
                        """);
                createLegacyEventTable(statement);
                statement.executeUpdate("INSERT INTO campaign_delivery (id, workspace_id, unsubscribe_token)"
                        + " VALUES (1, 22601, REPEAT('a', 64)), (2, 22601, REPEAT('b', 64))");
                seedLegacyDiagnostics(statement);
                String expand = sql("V227__campaign_delivery_unsubscribe_hash_history.sql");
                statement.execute(expand.substring(0, expand.indexOf(';')));
                Flyway interruptedExpand = recoveryFlyway(catalog, "227");
                assertThrows(FlywayException.class, interruptedExpand::migrate);
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
                statement.execute("DROP TABLE campaign_delivery_unsubscribe_token");
                interruptedExpand.repair();
                interruptedExpand.migrate();
                assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
                for (String command : sql("V228__drop_campaign_delivery_unsubscribe_tokens.sql").split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command);
                    }
                }
                Flyway interruptedContract = recoveryFlyway(catalog, "228");
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
                assertScrubbedDiagnostics(statement, 2);
                assertPreservedEventKeys(statement, 2);
                assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"
                        + " WHERE token_hash IN (LOWER(SHA2(REPEAT('a', 64), 256)), LOWER(SHA2(REPEAT('b', 64), 256)))"));
            }
        }
    }

    @Test
    void contractBackfillCommitsBeforeFailedAlterAndCanRerunAfterHistoryRepair() throws SQLException {
        try (MySqlScratchCatalog catalog = catalog();
                Connection connection = connection(catalog);
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE campaign_delivery (
                        id INT PRIMARY KEY, workspace_id INT NOT NULL,
                        last_error VARCHAR(512) NULL, provider_message_id VARCHAR(255) NULL,
                        unsubscribe_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        unsubscribe_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                            GENERATED ALWAYS AS (LOWER(SHA2(unsubscribe_token, 256))) STORED,
                        UNIQUE KEY uq_campaign_delivery_workspace_id (workspace_id, id),
                        UNIQUE KEY uq_campaign_delivery_token (unsubscribe_token),
                        UNIQUE KEY uq_campaign_delivery_token_hash (unsubscribe_token_hash)
                    )
                    """);
            createLegacyEventTable(statement);
            statement.executeUpdate("INSERT INTO campaign_delivery (id, workspace_id, unsubscribe_token)"
                    + " VALUES (1, 22601, REPEAT('a', 64))");
            recoveryFlyway(catalog, "227").migrate();
            statement.executeUpdate("INSERT INTO campaign_delivery (id, workspace_id, unsubscribe_token)"
                    + " VALUES (2, 22601, REPEAT('b', 64))");
            seedLegacyDiagnostics(statement);
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
            statement.execute("ALTER TABLE campaign_delivery DROP INDEX uq_campaign_delivery_token");
            Flyway contract = recoveryFlyway(catalog, "228");
            assertThrows(FlywayException.class, contract::migrate);
            assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token"));
            assertScrubbedDiagnostics(statement, 2);
            assertPreservedEventKeys(statement, 2);
            statement.execute("ALTER TABLE campaign_delivery ADD UNIQUE KEY uq_campaign_delivery_token (unsubscribe_token)");
            contract.repair();
            contract.migrate();
            assertContract(statement);
            assertScrubbedDiagnostics(statement, 2);
            assertPreservedEventKeys(statement, 2);
            assertEquals(2, scalar(statement, """
                    SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token
                    WHERE token_hash IN (LOWER(SHA2(REPEAT('a', 64), 256)), LOWER(SHA2(REPEAT('b', 64), 256)))
                    """));
        }
    }

    private static void createLegacyEventTable(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE campaign_delivery_event (
                    workspace_id INT NOT NULL, delivery_id INT NOT NULL,
                    event_type VARCHAR(16) NOT NULL, detail VARCHAR(512) NULL,
                    provider_id VARCHAR(32) NULL, provider_event_id VARCHAR(255) NULL,
                    UNIQUE KEY uq_campaign_delivery_event_provider (workspace_id, provider_id, provider_event_id),
                    FOREIGN KEY (workspace_id, delivery_id)
                        REFERENCES campaign_delivery(workspace_id, id) ON DELETE CASCADE
                )
                """);
    }

    private static void seedLegacyDiagnostics(Statement statement) throws SQLException {
        statement.executeUpdate("""
                UPDATE campaign_delivery
                SET last_error = CONCAT('554 rejected ', UPPER(unsubscribe_token), ' repeated ',
                        UPPER(LEFT(unsubscribe_token, 32)), LOWER(RIGHT(unsubscribe_token, 32)), ' retained'),
                    provider_message_id = CONCAT('receipt ', UPPER(unsubscribe_token), ' repeated ',
                        UPPER(LEFT(unsubscribe_token, 32)), LOWER(RIGHT(unsubscribe_token, 32)), ' retained')
                """);
        statement.executeUpdate("""
                INSERT INTO campaign_delivery_event
                    (workspace_id, delivery_id, event_type, detail, provider_id, provider_event_id)
                SELECT workspace_id, id, 'failed',
                       IF(copies.n = 1, CONCAT('event ', UPPER(unsubscribe_token), ' repeated ',
                           UPPER(LEFT(unsubscribe_token, 32)), LOWER(RIGHT(unsubscribe_token, 32)), ' retained'), NULL),
                       'http_esp', CONCAT('legacy:', IF(copies.n = 1,
                           CONCAT(UPPER(LEFT(unsubscribe_token, 32)), LOWER(RIGHT(unsubscribe_token, 32))),
                           unsubscribe_token_hash), ':event')
                FROM campaign_delivery CROSS JOIN (SELECT 1 n UNION ALL SELECT 2) copies
                WHERE copies.n = 1 OR id = (SELECT MIN(id) FROM campaign_delivery)
                """);
        statement.executeUpdate("""
                INSERT INTO campaign_delivery_event
                    (workspace_id, delivery_id, event_type, detail, provider_id, provider_event_id)
                SELECT workspace_id, id, 'failed', 'same-collision', 'http_esp',
                       CONCAT('same:', IF(copies.n = 1, unsubscribe_token, unsubscribe_token_hash), ':',
                           IF(copies.n = 1, unsubscribe_token_hash, unsubscribe_token))
                FROM campaign_delivery CROSS JOIN (SELECT 1 n UNION ALL SELECT 2) copies
                WHERE id = (SELECT MIN(id) FROM campaign_delivery)
                """);
        statement.executeUpdate("""
                INSERT INTO campaign_delivery_event
                    (workspace_id, delivery_id, event_type, detail, provider_id, provider_event_id)
                SELECT first.workspace_id, IF(copies.n = 1, first.id, last.id), 'failed',
                       'cross-collision', 'http_esp', CONCAT('cross:',
                           IF(copies.n = 1, first.unsubscribe_token, first.unsubscribe_token_hash), ':',
                           IF(copies.n = 1, last.unsubscribe_token_hash, last.unsubscribe_token))
                FROM campaign_delivery first CROSS JOIN campaign_delivery last
                CROSS JOIN (SELECT 1 n UNION ALL SELECT 2) copies
                WHERE first.id = (SELECT MIN(id) FROM campaign_delivery)
                  AND last.id = (SELECT MAX(id) FROM campaign_delivery)
                """);
    }

    private static void assertScrubbedDiagnostics(Statement statement, int deliveries) throws SQLException {
        assertEquals(deliveries, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery d
                JOIN campaign_delivery_unsubscribe_token t
                  ON t.workspace_id = d.workspace_id AND t.delivery_id = d.id
                WHERE d.last_error = '554 rejected [redacted] repeated [redacted] retained'
                  AND d.provider_message_id = CONCAT('receipt ', t.token_hash, ' repeated ', t.token_hash, ' retained')
                """));
        assertEquals(deliveries, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery_event
                WHERE detail = 'event [redacted] repeated [redacted] retained'
                """));
    }

    private static void assertPreservedEventKeys(Statement statement, int deliveries) throws SQLException {
        assertEquals(deliveries, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery_event e
                JOIN campaign_delivery_unsubscribe_token t
                  ON t.workspace_id = e.workspace_id AND t.delivery_id = e.delivery_id
                WHERE e.provider_id = 'http_esp'
                  AND e.provider_event_id = CONCAT('legacy:', t.token_hash, ':event')
                """));
        assertEquals(deliveries + 5, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery_event WHERE provider_id = 'http_esp'
                """));
        assertEquals(deliveries + 2, scalar(statement, """
                SELECT COUNT(DISTINCT provider_event_id) FROM campaign_delivery_event
                WHERE provider_id = 'http_esp'
                """));
        assertEquals(3, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery_event
                WHERE provider_id = 'http_esp' AND provider_event_id IS NULL
                """));
        assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery_event e
                JOIN campaign_delivery_unsubscribe_token t
                  ON t.workspace_id = e.workspace_id AND t.delivery_id = e.delivery_id
                WHERE e.detail = 'same-collision'
                  AND e.provider_event_id = CONCAT('same:', t.token_hash, ':', t.token_hash)
                """));
        assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery_event e
                JOIN campaign_delivery_unsubscribe_token first ON first.workspace_id = e.workspace_id
                JOIN campaign_delivery_unsubscribe_token last ON last.workspace_id = e.workspace_id
                WHERE first.delivery_id = (SELECT MIN(id) FROM campaign_delivery)
                  AND last.delivery_id = (SELECT MAX(id) FROM campaign_delivery)
                  AND e.detail = 'cross-collision'
                  AND e.provider_event_id = CONCAT('cross:', first.token_hash, ':', last.token_hash)
                """));
        assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery_event
                WHERE detail = 'same-collision' AND provider_event_id IS NULL
                """));
        assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM campaign_delivery_event
                WHERE detail = 'cross-collision' AND provider_event_id IS NULL
                """));
        assertEquals(0, scalar(statement, """
                SELECT COUNT(*) FROM (
                    SELECT workspace_id, provider_id, provider_event_id
                    FROM campaign_delivery_event WHERE provider_event_id IS NOT NULL
                    GROUP BY workspace_id, provider_id, provider_event_id HAVING COUNT(*) > 1
                ) duplicate_keys
                """));
        SQLException duplicate = assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO campaign_delivery_event
                    (workspace_id, delivery_id, event_type, provider_id, provider_event_id)
                SELECT workspace_id, delivery_id, event_type, provider_id, provider_event_id
                FROM campaign_delivery_event WHERE provider_id = 'http_esp' AND provider_event_id IS NOT NULL LIMIT 1
                """));
        assertEquals(1062, duplicate.getErrorCode());
    }

    private static String sql(String filename) throws IOException {
        return new String(new ClassPathResource("db/migration/tenant/" + filename).getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    private static Flyway recoveryFlyway(MySqlScratchCatalog catalog, String target) {
        return Flyway.configure()
                .dataSource(catalog.url(), System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"))
                .locations("classpath:db/migration/tenant")
                .baselineOnMigrate(true).baselineVersion(MigrationVersion.fromVersion("226"))
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
