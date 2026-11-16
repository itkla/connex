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

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.PendingInvitationExpiryBackfillTransaction;

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

            Harness harness = harness(catalog);
            try (Connection connection = connection(catalog)) {
                assertNull(deadline(connection, 1), "The migration performs no backfill DML");
                assertNull(deadline(connection, 4));
            }
            assertEquals(1, harness.page(1, 0).written());
            assertEquals(1, harness.page(2, 0).written());
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

            Harness harness = harness(catalog);
            assertEquals(1, harness.page(1, 0).written());
            assertEquals(0, harness.page(1, 0).written());
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

    @Test
    void unfinishedBackfillUsesOneUtcDeadlineAndCannotOutliveIt(@TempDir Path migrations) throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyRows(catalog);
            migration(catalog, migrations).migrate();
            Harness harness = harness(catalog);
            String deadline = harness.jdbc().queryForObject("""
                SELECT CAST(installed_on AT TIME ZONE '+00:00' AS DATETIME) + INTERVAL 30 DAY
                FROM flyway_schema_history WHERE version = '247' AND success = 1
                """, String.class);
            assertNotNull(deadline);
            assertEquals(1, harness.page(1, 0).written());
            assertEquals(deadline, harness.jdbc().queryForObject(
                "SELECT expires_at FROM workspace_member WHERE workspace_id = 1 AND user_id = 1", String.class));
            assertEquals(1, harness.mapper().activateMember(2, 4));
            harness.jdbc().update("UPDATE workspace_member SET status = 'pending' WHERE workspace_id = 2 AND user_id = 4");
            harness.jdbc().update("UPDATE flyway_schema_history SET installed_on = CURRENT_TIMESTAMP() - INTERVAL 31 DAY WHERE version = '247'");
            assertEquals(0, harness.mapper().activateMember(2, 4), "Unfilled legacy grants expire at the fixed cutover");
            assertEquals(1, harness.page(2, 0).written());
            assertEquals(0, harness.mapper().activateMember(2, 4), "Persisting an expired cutover cannot revive a grant");
            assertEquals(deadline, harness.jdbc().queryForObject(
                "SELECT expires_at FROM workspace_member WHERE workspace_id = 1 AND user_id = 1", String.class));
        }
    }

    @Test
    void pagesCommitIndependentlyRetryWithoutExtensionAndNeverLockUnrelatedActiveRows(@TempDir Path migrations)
            throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyRows(catalog);
            migration(catalog, migrations).migrate();
            Harness harness = harness(catalog);
            for (int userId = 10; userId < 215; userId++) {
                harness.jdbc().update("INSERT INTO workspace_member (workspace_id, user_id, status) VALUES (1, ?, 'pending')", userId);
            }
            try (Connection blocker = connection(catalog)) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement()) {
                    statement.executeQuery("SELECT * FROM workspace_member WHERE workspace_id = 1 AND user_id = 2 FOR UPDATE").close();
                    var first = harness.page(1, 0);
                    assertEquals(100, first.scanned());
                    assertEquals(99, first.written());
                    try (Connection observer = connection(catalog)) {
                        assertNotNull(deadline(observer, 1), "The completed page is independently committed");
                        assertNull(deadline(observer, 214));
                    }
                    assertEquals(0, harness.page(1, 0).written(), "A restart cannot extend completed deadlines");
                    assertEquals(100, harness.page(1, first.lastUserId()).written());
                    assertEquals(7, harness.page(1, first.lastUserId() + 100).written());
                    blocker.rollback();
                }
            }
            assertNull(harness.jdbc().queryForObject(
                "SELECT expires_at FROM workspace_member WHERE workspace_id = 2 AND user_id = 4", String.class));
            Long generation = harness.jdbc().queryForObject(
                "SELECT membership_id FROM workspace_member WHERE workspace_id = 2 AND user_id = 4", Long.class);
            assertNotNull(generation);
            assertEquals(0, harness.mapper().backfillPendingDeadline(1, 4, generation));
            harness.jdbc().update("DELETE FROM workspace_member WHERE workspace_id = 2 AND user_id = 4");
            harness.jdbc().update("INSERT INTO workspace_member (workspace_id, user_id, status) VALUES (2, 4, 'pending')");
            assertEquals(0, harness.mapper().backfillPendingDeadline(2, 4, generation), "A stale generation cannot change a replacement");
        }
    }

    @Test
    void failedPageRollsBackOnlyItsOwnChangesAndRetryUsesTheOriginalDeadline(@TempDir Path migrations) throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyRows(catalog);
            migration(catalog, migrations).migrate();
            Harness harness = harness(catalog);
            harness.page(2, 0);
            String committedDeadline = harness.jdbc().queryForObject(
                "SELECT expires_at FROM workspace_member WHERE workspace_id = 2 AND user_id = 4", String.class);
            Long generation = harness.jdbc().queryForObject(
                "SELECT membership_id FROM workspace_member WHERE workspace_id = 1 AND user_id = 1", Long.class);
            assertNotNull(generation);
            assertThrows(IllegalStateException.class, () -> harness.transaction().executeWithoutResult(status -> {
                assertEquals(1, harness.mapper().backfillPendingDeadline(1, 1, generation));
                throw new IllegalStateException("Simulated interrupted page");
            }));
            assertNull(harness.jdbc().queryForObject(
                "SELECT expires_at FROM workspace_member WHERE workspace_id = 1 AND user_id = 1", String.class));
            assertEquals(1, harness.page(1, 0).written());
            assertEquals(committedDeadline, harness.jdbc().queryForObject(
                "SELECT expires_at FROM workspace_member WHERE workspace_id = 1 AND user_id = 1", String.class));
        }
    }

    private static Harness harness(MySqlScratchCatalog catalog) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(catalog.url(), username, password) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (var statement = connection.createStatement()) {
                    statement.execute("SET SESSION time_zone = '+09:00'");
                    statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
                }
                return connection;
            }
        };
        var configuration = new Configuration(new Environment("invitation-expiry",
            new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        String resource = "mappers/WorkspaceMapper.xml";
        try (var input = PendingInvitationExpiryV247MigrationIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        WorkspaceMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(WorkspaceMapper.class);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        return new Harness(new JdbcTemplate(dataSource), mapper, transaction);
    }

    private record Harness(JdbcTemplate jdbc, WorkspaceMapper mapper, TransactionTemplate transaction) {
        PendingInvitationExpiryBackfillTransaction.Batch page(int workspaceId, int afterUserId) {
            return transaction.execute(status -> new PendingInvitationExpiryBackfillTransaction(mapper).backfillPage(workspaceId, afterUserId));
        }
    }

    private static void createLegacyRows(MySqlScratchCatalog catalog) throws SQLException {
        try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE workspace_member (
                    workspace_id INT NOT NULL, user_id INT NOT NULL,
                    status VARCHAR(16) NOT NULL, grant_authorization_version INT NOT NULL DEFAULT 1,
                    membership_id BIGINT NOT NULL AUTO_INCREMENT,
                    PRIMARY KEY (workspace_id, user_id), UNIQUE KEY (membership_id)
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
