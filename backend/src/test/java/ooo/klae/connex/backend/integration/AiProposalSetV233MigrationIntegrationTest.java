package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

/** Exercises dormant proposal-set storage against populated legacy storage and real MySQL constraints. */
class AiProposalSetV233MigrationIntegrationTest {
    private static MySqlScratchCatalog catalog;
    private static String url = "";
    private static String username = "";
    private static String password = "";
    private static List<String> legacyColumns = List.of();
    private static List<String> legacyCalls = List.of();
    private static List<String> legacyTableIdentity = List.of();

    @BeforeAll
    static void migratePopulatedLegacyCatalog() throws SQLException {
        String configuredUsername = System.getenv("CONNEX_DB_USERNAME");
        String configuredPassword = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(configuredUsername != null && configuredPassword != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD required for V233 migration test");
        username = Objects.requireNonNull(configuredUsername);
        password = Objects.requireNonNull(configuredPassword);
        String configured = System.getenv().getOrDefault("CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        catalog = MySqlScratchCatalog.create(configured, username, password,
                "connex_proposal_set_v233_it_", "utf8mb4", "utf8mb4_unicode_ci");
        url = catalog.url();
        assertEquals(MigrationVersion.fromVersion("231"),
                Objects.requireNonNull(migrate("231").info().current()).getVersion());
        try (Connection connection = connection()) {
            Ancestry ancestry = insertAncestry(connection, 233000);
            for (boolean trusted : List.of(false, true)) {
                for (String status : List.of("proposed", "approved", "rejected", "executed", "failed")) {
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO ai_chat_tool_call
                                (workspace_id, message_id, tool_name, status, arguments_json,
                                 thought_signature, result_json, executed_by_user_id, executed_at,
                                 idempotency_key, server_proposal, created_at, updated_at)
                            VALUES (?, ?, 'assign_owner', ?, '{"target":{"id":31}}', 'stored-signature',
                                    '{"tier":"confirm","before":{"ownerId":41}}', 9001,
                                    '2020-01-03 00:00:00.111111', ?, ?,
                                    '2020-01-01 00:00:00.123456', '2020-01-02 00:00:00.654321')
                            """)) {
                        insert.setInt(1, ancestry.workspaceId());
                        insert.setInt(2, ancestry.messageId());
                        insert.setString(3, status);
                        insert.setString(4, "legacy-" + trusted + "-" + status);
                        insert.setBoolean(5, trusted);
                        assertEquals(1, insert.executeUpdate());
                    }
                }
            }
            legacyColumns = legacyColumns(connection);
            legacyCalls = legacyCalls(connection);
            legacyTableIdentity = toolCallTableIdentity(connection);
            assertEquals(1, legacyTableIdentity.size());
        }
        assertEquals(MigrationVersion.fromVersion("233"),
                Objects.requireNonNull(migrate("233").info().current()).getVersion());
    }

    @AfterAll
    static void removeCatalog() throws SQLException {
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    void migrationRequiresOnlineAlterationsWithoutRebuildingTheRetainedTable() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/tenant/V233__ai_assistant_proposal_set_storage.sql"))
                .replaceAll("(?m)--[^\\r\\n]*", "");
        List<String> alterations = Arrays.stream(migration.split(";"))
                .map(String::strip).filter(sql -> sql.startsWith("ALTER TABLE ")).toList();
        assertEquals(5, alterations.size());
        for (String sql : alterations) {
            if (sql.contains("ADD COLUMN")) {
                assertTrue(sql.contains("ALGORITHM=INSTANT, LOCK=DEFAULT"), sql);
                assertFalse(sql.contains("ADD CONSTRAINT"), sql);
                assertFalse(sql.contains("ADD UNIQUE KEY"), sql);
            } else {
                assertTrue(sql.contains("ALGORITHM=INPLACE, LOCK=NONE"), sql);
            }
        }
        try (Connection connection = connection()) {
            assertEquals(legacyTableIdentity, toolCallTableIdentity(connection));
        }
    }

    @Test
    void migrationPreservesEveryLegacyStatusTrustEnvelopeAndTimestampWithoutBackfill() throws SQLException {
        try (Connection connection = connection()) {
            assertEquals(10, legacyCalls.size());
            assertEquals(legacyColumns, legacyColumns(connection));
            assertEquals(legacyCalls, legacyCalls(connection));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM ai_assistant_proposal_set WHERE workspace_id = 233000
                    """));
            assertEquals(10, count(connection, """
                    SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id = 233000
                      AND proposal_key IS NULL AND proposal_ordinal IS NULL AND proposal_prepared_at IS NULL
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history WHERE version = '233' AND success = TRUE
                      AND script LIKE '%V233__ai_assistant_proposal_set_storage.sql'
                    """));
        }
    }

    @Test
    void explicitKeyCollationsAndRequiredChatAncestrySurviveLegacyCatalogDefaults() throws SQLException {
        try (Connection connection = connection()) {
            assertEquals(List.of("[utf8mb4_unicode_ci]"), rows(connection, """
                    SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA
                    WHERE SCHEMA_NAME = DATABASE()
                    """));
            assertEquals(List.of("[ai_assistant_proposal_set, ascii, ascii_bin]",
                    "[ai_chat_tool_call, ascii, ascii_bin]"), rows(connection, """
                    SELECT TABLE_NAME, CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'proposal_key'
                      AND TABLE_NAME IN ('ai_assistant_proposal_set', 'ai_chat_tool_call')
                    ORDER BY TABLE_NAME
                    """));
            assertEquals(List.of("[ai_assistant_proposal_set, message_id, NO]",
                    "[ai_assistant_proposal_set, origin_turn_id, NO]",
                    "[ai_assistant_proposal_set, session_id, NO]",
                    "[ai_chat_tool_call, message_id, NO]"), rows(connection, """
                    SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND ((TABLE_NAME = 'ai_assistant_proposal_set'
                            AND COLUMN_NAME IN ('session_id', 'origin_turn_id', 'message_id'))
                        OR (TABLE_NAME = 'ai_chat_tool_call' AND COLUMN_NAME = 'message_id'))
                    ORDER BY TABLE_NAME, COLUMN_NAME
                    """));
            assertEquals(List.of("[ai_assistant_proposal_set, fk_ai_assistant_proposal_set_message, CASCADE]",
                    "[ai_assistant_proposal_set, fk_ai_assistant_proposal_set_turn, CASCADE]",
                    "[ai_chat_tool_call, fk_ai_chat_tool_call_proposal, CASCADE]"), rows(connection, """
                    SELECT TABLE_NAME, CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                    WHERE CONSTRAINT_SCHEMA = DATABASE()
                      AND CONSTRAINT_NAME IN ('fk_ai_assistant_proposal_set_message',
                        'fk_ai_assistant_proposal_set_turn', 'fk_ai_chat_tool_call_proposal')
                    ORDER BY TABLE_NAME, CONSTRAINT_NAME
                    """));
        }
    }

    @Test
    void parentChecksConstrainCallIdentityModeCountAndTechnicalPublication() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            Ancestry ancestry = insertAncestry(connection, 233001);
            insertParent(connection, ancestry, "constraints", "scalar", 1, 1, 0);
            assertEquals(List.of("[staged, null, null]"), rows(connection, """
                    SELECT publication_state, failure_reason, bulk_turn_slot FROM ai_assistant_proposal_set
                    WHERE workspace_id = 233001 AND proposal_key = 'constraints'
                    """));
            for (String assignment : List.of("proposal_key = ''", "step_number = 0", "step_number = 65",
                    "call_ordinal = -1", "call_ordinal = 5", "invocation_mode = 'unknown'", "row_count = 0",
                    "row_count = 2", "invocation_mode = 'bulk', row_count = 21", "intent_fingerprint = 'short'",
                    "scope_revision = ''", "privacy_generation = -1", "publication_state = 'approved'",
                    "publication_state = 'failed'", "failure_reason = 'call_failed'",
                    "publication_state = 'ready', failure_reason = 'cancelled'",
                    "publication_state = 'failed', failure_reason = 'exception text'")) {
                assertRejected(connection, 3819,
                        "UPDATE ai_assistant_proposal_set SET " + assignment + " WHERE proposal_key = 'constraints'");
            }
            for (String reason : List.of("call_failed", "cancelled", "timed_out", "execution_lost")) {
                try (var update = connection.prepareStatement("""
                        UPDATE ai_assistant_proposal_set SET publication_state = 'failed', failure_reason = ?
                        WHERE workspace_id = ? AND proposal_key = 'constraints'
                        """)) {
                    update.setString(1, reason);
                    update.setInt(2, ancestry.workspaceId());
                    assertEquals(1, update.executeUpdate());
                }
            }
            assertEquals(1, statement.executeUpdate("""
                    UPDATE ai_assistant_proposal_set
                    SET invocation_mode = 'bulk', row_count = 20, publication_state = 'ready', failure_reason = NULL
                    WHERE workspace_id = 233001 AND proposal_key = 'constraints'
                    """));
            assertEquals(List.of("[1]"), rows(connection, """
                    SELECT bulk_turn_slot FROM ai_assistant_proposal_set
                    WHERE workspace_id = 233001 AND proposal_key = 'constraints'
                    """));
            connection.rollback();
        }
    }

    @Test
    void callIdentityAndBulkSlotRemainUniqueEvenAfterCallFailure() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            Ancestry ancestry = insertAncestry(connection, 233002);
            insertParent(connection, ancestry, "bulk", "bulk", 1, 1, 0);
            assertEquals(1, statement.executeUpdate("""
                    UPDATE ai_assistant_proposal_set SET publication_state = 'failed', failure_reason = 'call_failed'
                    WHERE workspace_id = 233002 AND proposal_key = 'bulk'
                    """));
            SQLException duplicateBulk = assertThrows(SQLException.class,
                    () -> insertParent(connection, ancestry, "second-bulk", "bulk", 2, 2, 0));
            assertEquals(1062, duplicateBulk.getErrorCode());
            SQLException duplicateCall = assertThrows(SQLException.class,
                    () -> insertParent(connection, ancestry, "same-call", "scalar", 1, 1, 0));
            assertEquals(1062, duplicateCall.getErrorCode());
            insertParent(connection, ancestry, "scalar-one", "scalar", 1, 2, 0);
            insertParent(connection, ancestry, "scalar-two", "scalar", 1, 2, 1);
            assertEquals(3, count(connection,
                    "SELECT COUNT(*) FROM ai_assistant_proposal_set WHERE workspace_id = 233002"));
            connection.rollback();
        }
    }

    @Test
    void childTriggersRequireTrustedCompleteMembershipMetadataAndUniqueOrdinals() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            Ancestry ancestry = insertAncestry(connection, 233003);
            insertParent(connection, ancestry, "children", "bulk", 20, 1, 0);
            for (String values : List.of(
                    "NULL, 1, '2026-10-01', TRUE",
                    "'children', NULL, '2026-10-01', TRUE",
                    "'children', 1, NULL, TRUE",
                    "'children', 0, '2026-10-01', TRUE",
                    "'children', 21, '2026-10-01', TRUE",
                    "'children', 1, '2026-10-01', FALSE")) {
                assertRejected(connection, 1644, """
                        INSERT INTO ai_chat_tool_call
                            (workspace_id, message_id, tool_name, proposal_key, proposal_ordinal,
                             proposal_prepared_at, server_proposal)
                        VALUES (233003, 233003, 'add_tag',
                        """ + values + ")");
            }
            insertChild(connection, ancestry, "children", 1);
            for (String assignment : List.of("proposal_key = NULL", "proposal_ordinal = NULL",
                    "proposal_prepared_at = NULL", "proposal_ordinal = 0", "proposal_ordinal = 21",
                    "server_proposal = FALSE")) {
                assertRejected(connection, 1644,
                        "UPDATE ai_chat_tool_call SET " + assignment + " WHERE workspace_id = 233003");
            }
            SQLException duplicate = assertThrows(SQLException.class,
                    () -> insertChild(connection, ancestry, "children", 1));
            assertEquals(1062, duplicate.getErrorCode());
            insertChild(connection, ancestry, "children", 20);
            assertEquals(2, statement.executeUpdate("""
                    UPDATE ai_chat_tool_call
                    SET proposal_key = NULL, proposal_ordinal = NULL, proposal_prepared_at = NULL
                    WHERE workspace_id = 233003
                    """));
            assertRejected(connection, 1644, """
                    UPDATE ai_chat_tool_call SET proposal_ordinal = 1 WHERE workspace_id = 233003
                    """);
            assertRejected(connection, 1644, """
                    UPDATE ai_chat_tool_call SET proposal_prepared_at = CURRENT_TIMESTAMP(6) WHERE workspace_id = 233003
                    """);
            connection.rollback();
        }
    }

    @Test
    void foreignKeysRejectForeignWorkspaceSessionTurnMessageAndOrphanMembership() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            Ancestry ancestry = insertAncestry(connection, 233004);
            Ancestry foreign = insertAncestry(connection, 233005);
            insertParent(connection, ancestry, "ancestry", "scalar", 1, 1, 0);
            assertRejected(connection, 1452, """
                    UPDATE ai_assistant_proposal_set SET workspace_id = 233005 WHERE proposal_key = 'ancestry'
                    """);
            assertRejected(connection, 1452, """
                    UPDATE ai_assistant_proposal_set SET session_id = 233005 WHERE proposal_key = 'ancestry'
                    """);
            assertRejected(connection, 1452, """
                    UPDATE ai_assistant_proposal_set SET origin_turn_id = 233005 WHERE proposal_key = 'ancestry'
                    """);
            assertRejected(connection, 1452, """
                    UPDATE ai_assistant_proposal_set SET message_id = 233005 WHERE proposal_key = 'ancestry'
                    """);
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO ai_chat_session (workspace_id, id, created_by_user_id, title)
                    VALUES (233004, 333006, 9001, 'Other session in the same workspace')
                    """));
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO ai_chat_message (workspace_id, id, session_id, seq, author_kind, content)
                    VALUES (233004, 333006, 333006, 1, 'user', 'Other session message')
                    """));
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO ai_chat_turn (workspace_id, id, session_id, requested_by_user_id)
                    VALUES (233004, 333006, 333006, 9001)
                    """));
            for (String column : List.of("session_id", "origin_turn_id", "message_id")) {
                assertRejected(connection, 1452, "UPDATE ai_assistant_proposal_set SET " + column
                        + " = 333006 WHERE workspace_id = 233004 AND proposal_key = 'ancestry'");
            }
            assertRejected(connection, 1048, """
                    UPDATE ai_assistant_proposal_set SET message_id = NULL WHERE proposal_key = 'ancestry'
                    """);
            insertChild(connection, ancestry, "ancestry", 1);
            assertRejected(connection, 1048, """
                    UPDATE ai_chat_tool_call SET message_id = NULL WHERE workspace_id = 233004
                    """);
            assertRejected(connection, 1452, """
                    UPDATE ai_chat_tool_call SET workspace_id = 233005, message_id = 233005
                    WHERE workspace_id = 233004
                    """);
            assertRejected(connection, 1452, """
                    UPDATE ai_chat_tool_call SET proposal_key = 'missing' WHERE workspace_id = 233004
                    """);
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO ai_chat_message (workspace_id, id, session_id, seq, author_kind, content)
                    VALUES (233004, 333004, 233004, 2, 'assistant', 'Different message in the same session')
                    """));
            assertRejected(connection, 1452, """
                    UPDATE ai_chat_tool_call SET message_id = 333004 WHERE workspace_id = 233004
                    """);
            insertParent(connection, foreign, "ancestry", "scalar", 1, 1, 0);
            insertChild(connection, foreign, "ancestry", 1);
            assertEquals(2, count(connection,
                    "SELECT COUNT(*) FROM ai_assistant_proposal_set WHERE proposal_key = 'ancestry'"));
            connection.rollback();
        }
    }

    @Test
    void deletingParentMessageTurnOrSessionCascadesOnlyThroughOwnedChildren() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            int workspaceId = 233010;
            for (String table : List.of("ai_assistant_proposal_set", "ai_chat_message", "ai_chat_turn", "ai_chat_session")) {
                Ancestry ancestry = insertAncestry(connection, workspaceId++);
                insertParent(connection, ancestry, "cascade", "scalar", 1, 1, 0);
                insertChild(connection, ancestry, "cascade", 1);
                try (var insert = connection.prepareStatement("""
                        INSERT INTO ai_chat_tool_call (workspace_id, message_id, tool_name, server_proposal)
                        VALUES (?, ?, 'assign_owner', TRUE)
                        """)) {
                    insert.setInt(1, ancestry.workspaceId());
                    insert.setInt(2, ancestry.messageId());
                    assertEquals(1, insert.executeUpdate());
                }
                String scope = " WHERE workspace_id = " + ancestry.workspaceId();
                assertEquals(1, statement.executeUpdate("DELETE FROM " + table + scope));
                assertEquals(0, count(connection, "SELECT COUNT(*) FROM ai_assistant_proposal_set" + scope));
                assertEquals(0, count(connection,
                        "SELECT COUNT(*) FROM ai_chat_tool_call" + scope + " AND proposal_key IS NOT NULL"));
                int remainingMessage = table.equals("ai_chat_message") || table.equals("ai_chat_session") ? 0 : 1;
                int remainingTurn = table.equals("ai_chat_turn") || table.equals("ai_chat_session") ? 0 : 1;
                assertEquals(remainingMessage, count(connection, "SELECT COUNT(*) FROM ai_chat_message" + scope));
                assertEquals(remainingTurn, count(connection, "SELECT COUNT(*) FROM ai_chat_turn" + scope));
                assertEquals(remainingMessage, count(connection, "SELECT COUNT(*) FROM ai_chat_tool_call" + scope));
            }
            connection.rollback();
        }
    }

    private static Ancestry insertAncestry(Connection connection, int identity) throws SQLException {
        try (var session = connection.prepareStatement("""
                INSERT INTO ai_chat_session (workspace_id, id, created_by_user_id, title)
                VALUES (?, ?, 9001, 'Proposal storage migration fixture')
                """); var message = connection.prepareStatement("""
                INSERT INTO ai_chat_message (workspace_id, id, session_id, seq, author_kind, author_user_id, content)
                VALUES (?, ?, ?, 1, 'user', 9001, 'Review these proposals')
                """); var turn = connection.prepareStatement("""
                INSERT INTO ai_chat_turn (workspace_id, id, session_id, requested_by_user_id)
                VALUES (?, ?, ?, 9001)
                """)) {
            session.setInt(1, identity);
            session.setInt(2, identity);
            assertEquals(1, session.executeUpdate());
            for (int parameter = 1; parameter <= 3; parameter++) {
                message.setInt(parameter, identity);
                turn.setInt(parameter, identity);
            }
            assertEquals(1, message.executeUpdate());
            assertEquals(1, turn.executeUpdate());
        }
        return new Ancestry(identity, identity, identity, identity);
    }

    private static void insertParent(Connection connection, Ancestry ancestry, String key,
            String mode, int rowCount, int step, int ordinal) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO ai_assistant_proposal_set
                    (workspace_id, proposal_key, session_id, origin_turn_id, message_id, requested_by_user_id,
                     step_number, call_ordinal, tool_name, invocation_mode, row_count, intent_fingerprint,
                     scope_revision, privacy_generation)
                VALUES (?, ?, ?, ?, ?, 9001, ?, ?, 'add_tag', ?, ?, ?, 'scope-1', 0)
                """)) {
            insert.setInt(1, ancestry.workspaceId());
            insert.setString(2, key);
            insert.setInt(3, ancestry.sessionId());
            insert.setInt(4, ancestry.turnId());
            insert.setInt(5, ancestry.messageId());
            insert.setInt(6, step);
            insert.setInt(7, ordinal);
            insert.setString(8, mode);
            insert.setInt(9, rowCount);
            insert.setString(10, "a".repeat(64));
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertChild(Connection connection, Ancestry ancestry, String key, int ordinal)
            throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO ai_chat_tool_call
                    (workspace_id, message_id, tool_name, server_proposal,
                     proposal_key, proposal_ordinal, proposal_prepared_at)
                VALUES (?, ?, 'add_tag', TRUE, ?, ?, '2026-10-01 00:00:00.123456')
                """)) {
            insert.setInt(1, ancestry.workspaceId());
            insert.setInt(2, ancestry.messageId());
            insert.setString(3, key);
            insert.setInt(4, ordinal);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void assertRejected(Connection connection, int errorCode, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(sql), sql);
            assertEquals(errorCode, failure.getErrorCode(), sql);
        }
    }

    private static List<String> legacyColumns(Connection connection) throws SQLException {
        return rows(connection, """
                SELECT ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT,
                       EXTRA, CHARACTER_SET_NAME, COLLATION_NAME
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chat_tool_call'
                  AND COLUMN_NAME NOT IN ('proposal_key', 'proposal_ordinal', 'proposal_prepared_at')
                ORDER BY ORDINAL_POSITION
                """);
    }

    private static List<String> toolCallTableIdentity(Connection connection) throws SQLException {
        return rows(connection, """
                SELECT TABLE_ID FROM information_schema.INNODB_TABLES
                WHERE NAME = CONCAT(DATABASE(), '/ai_chat_tool_call')
                """);
    }

    private static List<String> legacyCalls(Connection connection) throws SQLException {
        return rows(connection, """
                SELECT workspace_id, id, message_id, tool_name, status, arguments_json, thought_signature,
                       result_json, executed_by_user_id, executed_at, idempotency_key,
                       created_at, updated_at, server_proposal
                FROM ai_chat_tool_call WHERE workspace_id = 233000 ORDER BY id
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

    private static int count(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static Flyway migrate(String version) {
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").callbacks(
                        new AuditLogV126MigrationCallback(), new ForeignKeyChecksProbe())
                .baselineOnMigrate(true).baselineVersion(MigrationVersion.fromVersion("0"))
                .target(MigrationVersion.fromVersion(version)).load();
        flyway.migrate();
        return flyway;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private static final class ForeignKeyChecksProbe extends BaseCallback {
        @Override
        public boolean supports(Event event, Context context) {
            return (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE)
                    && context.getMigrationInfo() != null
                    && MigrationVersion.fromVersion("233").equals(context.getMigrationInfo().getVersion());
        }

        @Override
        public void handle(Event event, Context context) {
            try {
                assertEquals(1, count(context.getConnection(), "SELECT @@SESSION.foreign_key_checks"));
                if (event == Event.AFTER_EACH_MIGRATE) {
                    assertEquals(1, count(context.getConnection(),
                            "SELECT @connex_v233_foreign_key_checks IS NULL"));
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not verify migration-session FK checks", exception);
            }
        }
    }

    private record Ancestry(int workspaceId, int sessionId, int messageId, int turnId) {}
}
