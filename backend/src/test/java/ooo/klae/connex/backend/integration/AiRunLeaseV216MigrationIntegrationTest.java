package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

/**
 * Rollback safety of the run-lease migration, tested rather than asserted: V216 creates a new table
 * and touches nothing that an instance running the previous binary reads or writes, so a
 * {@code running} assistant turn that straddles the migration must come out byte-identical —
 * including its {@code updated_at}, which the absolute-lifetime expiry predicates read.
 */
class AiRunLeaseV216MigrationIntegrationTest {

    private static final String SCRATCH_CATALOG =
            "connex_ai_run_lease_v216_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final int WORKSPACE_ID = 216216;

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void createV215Catalog() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
                "CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping V216 migration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false,
                    "Cannot create V216 migration scratch catalog: " + exception.getMessage());
        }
        Flyway flyway = migrateTo("215");
        assertEquals(MigrationVersion.fromVersion("215"), flyway.info().current().getVersion());
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
    void v216AddsTheLeaseTableAndLeavesARunningTurnUntouched() throws SQLException {
        String turnBefore;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO ai_chat_session (workspace_id, id, created_by_user_id, title)
                    VALUES (216216, 4001, 9001, 'Run lease migration fixture')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_chat_turn
                        (workspace_id, id, session_id, requested_by_user_id, status)
                    VALUES (216216, 5001, 4001, 9001, 'running')
                    """);
            turnBefore = turnFingerprint(statement);
        }

        Flyway flyway = migrateTo("216");

        assertEquals(MigrationVersion.fromVersion("216"), flyway.info().current().getVersion());
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertEquals(turnBefore, turnFingerprint(statement),
                    "V216 must not touch any existing assistant turn row");
            assertEquals(
                    List.of("workspace_id", "subject_kind", "subject_id"),
                    indexColumns(connection, "PRIMARY"));
            assertEquals(
                    List.of("workspace_id", "expires_at", "subject_kind", "subject_id"),
                    indexColumns(connection, "idx_ai_run_lease_expiry"));
            assertEquals(
                    List.of("workspace_id", "released_at"),
                    indexColumns(connection, "idx_ai_run_lease_released"));
            assertEquals(
                    Set.of(
                            "chk_ai_run_lease_subject_kind",
                            "chk_ai_run_lease_subject_id",
                            "chk_ai_run_lease_epoch",
                            "chk_ai_run_lease_expiry",
                            "chk_ai_run_lease_release_pair"),
                    checkConstraints(connection));

            statement.executeUpdate(held(5001, "chat_turn", 1));
            statement.executeUpdate(held(5002, "agent_run", 1));
            assertThrows(SQLException.class, () -> statement.executeUpdate(held(5003, "mission", 1)));
            assertThrows(SQLException.class, () -> statement.executeUpdate(held(5004, "chat_turn", 0)));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO ai_run_lease
                        (workspace_id, subject_kind, subject_id, owner, epoch, expires_at)
                    VALUES (216216, 'chat_turn', -1, 'owner-a', 1,
                            DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 45 SECOND))
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    UPDATE ai_run_lease SET owner = NULL
                    WHERE workspace_id = 216216 AND subject_kind = 'chat_turn' AND subject_id = 5001
                    """));
            assertEquals(1, statement.executeUpdate("""
                    UPDATE ai_run_lease
                    SET owner = NULL, released_at = CURRENT_TIMESTAMP(6),
                        expires_at = CURRENT_TIMESTAMP(6)
                    WHERE workspace_id = 216216 AND subject_kind = 'chat_turn' AND subject_id = 5001
                    """));
        }
    }

    private static String held(int subjectId, String subjectKind, int epoch) {
        return "INSERT INTO ai_run_lease"
                + " (workspace_id, subject_kind, subject_id, owner, epoch, expires_at)"
                + " VALUES (" + WORKSPACE_ID + ", '" + subjectKind + "', " + subjectId
                + ", 'owner-a', " + epoch
                + ", DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 45 SECOND))";
    }

    private static String turnFingerprint(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery("""
                SELECT status, terminal_reason, created_at, updated_at
                FROM ai_chat_turn WHERE workspace_id = 216216 AND id = 5001
                """)) {
            assertTrue(rows.next());
            String fingerprint = rows.getString("status") + '|' + rows.getString("terminal_reason")
                    + '|' + rows.getString("created_at") + '|' + rows.getString("updated_at");
            assertFalse(rows.next());
            return fingerprint;
        }
    }

    private static List<String> indexColumns(Connection connection, String indexName)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_run_lease'
                  AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX
                """)) {
            statement.setString(1, indexName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        return columns;
    }

    private static Set<String> checkConstraints(Connection connection) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_run_lease'
                  AND CONSTRAINT_TYPE = 'CHECK'
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
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
