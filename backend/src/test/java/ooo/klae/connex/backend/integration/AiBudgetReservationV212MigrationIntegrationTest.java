package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

class AiBudgetReservationV212MigrationIntegrationTest {
    private static final String SCRATCH_CATALOG =
            "connex_ai_budget_v207_it_" + UUID.randomUUID().toString().replace("-", "");
    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void createV206Catalog() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
                "CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping V212 migration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false,
                    "Cannot create V212 migration scratch catalog: " + exception.getMessage());
        }
        Flyway flyway = migrateTo("206");
        assertEquals(MigrationVersion.fromVersion("206"), flyway.info().current().getVersion());
    }

    @AfterAll
    static void dropScratchCatalog() throws SQLException {
        if (created) {
            try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + SCRATCH_CATALOG + "`");
            }
        }
    }

    @Test
    void v207BackfillsLegacyReservationsAndConstrainsLifecycle() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO organization (id, name, slug)
                    VALUES (65207, 'Budget migration fixture', 'budget-migration-v207')
                    """);
            statement.executeUpdate("""
                    INSERT INTO organization_ai_budget_reservation
                        (reservation_id, org_id, usage_day, reserved_tokens, expires_at)
                    VALUES
                        ('legacy-expired', 65207, '2026-09-15', 60, '2026-09-14 00:00:00'),
                        ('legacy-live', 65207, '2026-09-15', 40, '2099-09-15 00:00:00')
                    """);
        }

        Flyway flyway = migrateTo("207");

        assertEquals(MigrationVersion.fromVersion("207"), flyway.info().current().getVersion());
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("""
                    SELECT reservation_id, state, consumed_tokens, reserved_tokens
                    FROM organization_ai_budget_reservation ORDER BY reservation_id
                    """)) {
                assertTrue(rows.next());
                assertEquals("legacy-expired", rows.getString("reservation_id"));
                assertEquals("dispatched", rows.getString("state"));
                assertNull(rows.getObject("consumed_tokens"));
                assertEquals(60, rows.getLong("reserved_tokens"));
                assertTrue(rows.next());
                assertEquals("legacy-live", rows.getString("reservation_id"));
                assertEquals("dispatched", rows.getString("state"));
                assertNull(rows.getObject("consumed_tokens"));
                assertEquals(40, rows.getLong("reserved_tokens"));
                assertFalse(rows.next());
            }
            statement.executeUpdate("""
                    INSERT INTO organization_ai_budget_reservation
                        (reservation_id, org_id, usage_day, reserved_tokens, expires_at)
                    VALUES ('legacy-writer', 65207, '2026-09-15', 20, '2099-09-15 00:00:00')
                    """);
            try (ResultSet rows = statement.executeQuery("""
                    SELECT state, consumed_tokens FROM organization_ai_budget_reservation
                    WHERE reservation_id = 'legacy-writer'
                    """)) {
                assertTrue(rows.next());
                assertEquals("dispatched", rows.getString("state"));
                assertNull(rows.getObject("consumed_tokens"));
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    UPDATE organization_ai_budget_reservation SET state = 'refunded'
                    WHERE reservation_id = 'legacy-live'
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    UPDATE organization_ai_budget_reservation SET state = NULL
                    WHERE reservation_id = 'legacy-live'
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    UPDATE organization_ai_budget_reservation SET consumed_tokens = -1
                    WHERE reservation_id = 'legacy-live'
                    """));
            statement.executeUpdate("""
                    INSERT INTO organization_ai_budget_reservation
                        (reservation_id, org_id, usage_day, reserved_tokens, expires_at, state)
                    VALUES ('new-writer', 65207, '2026-09-15', 30, '2099-09-15 00:00:00', 'reserved')
                    """);
            assertEquals(1, statement.executeUpdate("""
                    UPDATE organization_ai_budget_reservation SET state = 'dispatched'
                    WHERE reservation_id = 'new-writer' AND state = 'reserved'
                    """));
            assertEquals(1, statement.executeUpdate("""
                    UPDATE organization_ai_budget_reservation SET state = 'settled', consumed_tokens = 30
                    WHERE reservation_id = 'new-writer' AND state = 'dispatched'
                    """));
            try (PreparedStatement index = connection.prepareStatement("""
                    SELECT COLUMN_NAME FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_ai_budget_reservation'
                      AND INDEX_NAME = 'idx_organization_ai_budget_reservation_expiry_state'
                    ORDER BY SEQ_IN_INDEX
                    """); ResultSet columns = index.executeQuery()) {
                assertTrue(columns.next());
                assertEquals("expires_at", columns.getString(1));
                assertTrue(columns.next());
                assertEquals("state", columns.getString(1));
                assertFalse(columns.next());
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

    private static String withCatalog(String configuredUrl, String catalog) {
        int queryIndex = configuredUrl.indexOf('?');
        String query = queryIndex >= 0 ? configuredUrl.substring(queryIndex) : "";
        String base = queryIndex >= 0 ? configuredUrl.substring(0, queryIndex) : configuredUrl;
        return base.substring(0, base.lastIndexOf('/') + 1) + catalog + query;
    }
}
