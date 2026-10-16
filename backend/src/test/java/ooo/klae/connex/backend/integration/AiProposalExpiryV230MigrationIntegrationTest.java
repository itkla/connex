package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

/** Exercises the expiry compatibility marker against populated pre-cutover storage. */
class AiProposalExpiryV230MigrationIntegrationTest {
    private static MySqlScratchCatalog catalog;
    private static String url;
    private static String username;
    private static String password;

    @BeforeAll
    static void createPreCutoverCatalog() throws SQLException {
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD required for V230 migration test");
        String configured = System.getenv().getOrDefault("CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        catalog = MySqlScratchCatalog.create(configured, username, password,
                "connex_proposal_expiry_v230_it_", "utf8mb4", "utf8mb4_0900_ai_ci");
        url = catalog.url();
        assertEquals(MigrationVersion.fromVersion("229"), migrate("229").info().current().getVersion());
    }

    @AfterAll
    static void removeCatalog() throws SQLException {
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    void expiryMarkerDoesNotAlterStorageOrTrust() throws SQLException {
        List<String> schemaBefore;
        List<String> callsBefore;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO ai_chat_session (workspace_id, id, created_by_user_id, title)
                    VALUES (230230, 4001, 9001, 'Expiry migration fixture')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_chat_message
                        (workspace_id, id, session_id, seq, author_kind, author_user_id, content)
                    VALUES (230230, 5001, 4001, 1, 'user', 9001, 'Review this proposal')
                    """);
            int id = 6000;
            for (boolean trusted : List.of(false, true)) {
                for (String status : List.of("proposed", "approved", "rejected", "executed", "failed")) {
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO ai_chat_tool_call
                                (workspace_id, id, message_id, tool_name, status, arguments_json,
                                 result_json, server_proposal, created_at, updated_at)
                            VALUES (230230, ?, 5001, 'assign_owner', ?, '{"target":{"id":31}}',
                                    '{"tier":"confirm"}', ?, '2020-01-01 00:00:00.123456',
                                    '2020-01-02 00:00:00.654321')
                            """)) {
                        insert.setInt(1, ++id);
                        insert.setString(2, status);
                        insert.setBoolean(3, trusted);
                        assertEquals(1, insert.executeUpdate());
                    }
                }
            }
            schemaBefore = schema(connection);
            callsBefore = calls(connection);
        }

        assertEquals(MigrationVersion.fromVersion("230"), migrate("230").info().current().getVersion());

        try (Connection connection = connection()) {
            assertEquals(schemaBefore, schema(connection), "V230 must change no application schema");
            assertEquals(callsBefore, calls(connection), "V230 must preserve trust, status and timestamps");
            try (var statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM flyway_schema_history
                    WHERE version = '230' AND success = TRUE
                      AND script LIKE '%V230__ai_proposal_review_expiry_cutover.sql'
                    """); ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private static List<String> schema(Connection connection) throws SQLException {
        List<String> snapshot = new ArrayList<>();
        for (String query : List.of(
                """
                SELECT TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
                       COLUMN_DEFAULT, EXTRA, CHARACTER_SET_NAME, COLLATION_NAME
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """,
                """
                SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, INDEX_TYPE
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
                ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
                """,
                """
                SELECT TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE, ENFORCED
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
                ORDER BY TABLE_NAME, CONSTRAINT_NAME
                """,
                """
                SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, ORDINAL_POSITION,
                       REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
                ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION
                """)) {
            snapshot.addAll(rows(connection, query));
        }
        return snapshot;
    }

    private static List<String> calls(Connection connection) throws SQLException {
        return rows(connection, """
                SELECT * FROM ai_chat_tool_call WHERE workspace_id = 230230 ORDER BY id
                """);
    }

    private static List<String> rows(Connection connection, String query) throws SQLException {
        List<String> snapshot = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    values.add(rows.getString(column));
                }
                snapshot.add(values.toString());
            }
        }
        return snapshot;
    }

    private static Flyway migrate(String version) {
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").callbacks(new AuditLogV126MigrationCallback())
                .baselineOnMigrate(true).baselineVersion(MigrationVersion.fromVersion("0"))
                .target(MigrationVersion.fromVersion(version)).load();
        flyway.migrate();
        return flyway;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
