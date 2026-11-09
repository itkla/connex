package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Applies the real cutover to pre-deadline membership storage and a committed DDL prefix. */
class PendingInvitationExpiryV247MigrationIntegrationTest {
    private static final String SCRIPT = "V247__pending_invitation_expiry.sql";
    private static String configuredUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void requireDatabase() {
        configuredUrl = System.getenv("CONNEX_DB_URL");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(configuredUrl != null && username != null && password != null, "Database credentials required");
    }

    @Test
    void historicalPendingGrantsReceiveThirtyDaysWithoutChangingConsumedOrRevokedGrants(@TempDir Path migrations)
            throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyRows(catalog);
            Flyway flyway = migration(catalog, migrations);
            String beforeMigration;
            try (Connection connection = connection(catalog)) {
                beforeMigration = utcNow(connection);
            }

            assertEquals(1, flyway.migrate().migrationsExecuted);

            try (Connection connection = connection(catalog)) {
                assertThirtyDayDeadline(connection, 1, beforeMigration);
                assertThirtyDayDeadline(connection, 4, beforeMigration);
                assertNull(deadline(connection, 2));
                assertNull(deadline(connection, 3));
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM workspace_member WHERE user_id = 3")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "Revoked pending memberships have been deleted");
                }
            }
            assertEquals(0, flyway.migrate().migrationsExecuted);
        }
    }

    @Test
    void committedColumnPrefixPreservesExistingFutureAndExpiredDeadlines(@TempDir Path migrations) throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyRows(catalog);
            String beforeMigration;
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE workspace_member ADD COLUMN expires_at DATETIME NULL, ALGORITHM=INSTANT");
                statement.executeUpdate("""
                    INSERT INTO workspace_member (workspace_id, user_id, status, expires_at)
                    VALUES (1, 5, 'pending', '2099-01-01 00:00:00'), (1, 6, 'pending', '2020-01-01 00:00:00')
                    """);
                beforeMigration = utcNow(connection);
            }

            assertEquals(1, migration(catalog, migrations).migrate().migrationsExecuted);

            try (Connection connection = connection(catalog)) {
                assertThirtyDayDeadline(connection, 1, beforeMigration);
                assertEquals("2099-01-01 00:00:00", deadline(connection, 5));
                assertEquals("2020-01-01 00:00:00", deadline(connection, 6));
                assertNull(deadline(connection, 2));
            }
        }
    }

    @Test
    void incompatibleCommittedColumnFailsBeforeChangingAnyGrant(@TempDir Path migrations) throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyRows(catalog);
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE workspace_member ADD COLUMN expires_at VARCHAR(40) NULL");
            }
            Flyway flyway = migration(catalog, migrations);

            FlywayException failure = assertThrows(FlywayException.class, flyway::migrate);

            assertTrue(failure.getMessage().contains("V247_incompatible_workspace_member_expires_at"));
            try (Connection connection = connection(catalog)) {
                assertNull(deadline(connection, 1));
                assertNull(deadline(connection, 2));
                assertNull(deadline(connection, 4));
            }
        }
    }

    private static void createLegacyRows(MySqlScratchCatalog catalog) throws SQLException {
        try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE workspace_member (
                    workspace_id INT NOT NULL, user_id INT NOT NULL,
                    status VARCHAR(16) NOT NULL, PRIMARY KEY (workspace_id, user_id)
                )
                """);
            statement.executeUpdate("""
                INSERT INTO workspace_member (workspace_id, user_id, status)
                VALUES (1, 1, 'pending'), (1, 2, 'active'), (1, 3, 'pending'), (2, 4, 'pending')
                """);
            statement.executeUpdate("DELETE FROM workspace_member WHERE workspace_id = 1 AND user_id = 3");
        }
    }

    private static void assertThirtyDayDeadline(Connection connection, int userId, String beforeMigration)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT expires_at >= CAST(? AS DATETIME) + INTERVAL 30 DAY
                    AND expires_at <= UTC_TIMESTAMP() + INTERVAL 30 DAY
                FROM workspace_member WHERE user_id = ?
                """)) {
            statement.setString(1, beforeMigration);
            statement.setInt(2, userId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
            }
        }
    }

    private static String utcNow(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT UTC_TIMESTAMP()")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static String deadline(Connection connection, int userId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT expires_at FROM workspace_member WHERE user_id = ?")) {
            statement.setInt(1, userId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static Flyway migration(MySqlScratchCatalog catalog, Path migrations) throws Exception {
        try (var input = PendingInvitationExpiryV247MigrationIntegrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/control/" + SCRIPT)) {
            assertNotNull(input);
            Files.copy(input, migrations.resolve(SCRIPT));
        }
        return Flyway.configure().dataSource(catalog.url(), username, password)
            .locations("filesystem:" + migrations).baselineOnMigrate(true).baselineVersion("246").load();
    }

    private static MySqlScratchCatalog catalog() throws SQLException {
        return MySqlScratchCatalog.create(configuredUrl, username, password,
            "connex_invitation_expiry_", "utf8mb4", "utf8mb4_0900_ai_ci");
    }

    private static Connection connection(MySqlScratchCatalog catalog) throws SQLException {
        return DriverManager.getConnection(catalog.url(), username, password);
    }
}
