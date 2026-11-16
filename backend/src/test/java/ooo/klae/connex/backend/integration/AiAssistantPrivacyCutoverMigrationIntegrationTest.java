package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

/** Exercises the historical zero cutover on retained tables in a legacy-collation MySQL catalog. */
class AiAssistantPrivacyCutoverMigrationIntegrationTest {
    private static final List<String> RETAINED = List.of("ai_chat_session", "ai_chat_message", "ai_chat_turn", "ai_chat_tool_call",
            "ai_assistant_proposal_set", "ai_agent_run", "ai_agent_segment", "ai_agent_privacy_work");
    private static MySqlScratchCatalog catalog;
    private static String url = "";
    private static String username = "";
    private static String password = "";
    private static List<String> tableIdentities = List.of();

    @BeforeAll
    static void migratePopulatedV235() throws Exception {
        String configuredUser = System.getenv("CONNEX_DB_USERNAME");
        String configuredPassword = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(configuredUser != null && configuredPassword != null, "MySQL credentials required for V236/V237 cutover");
        username = Objects.requireNonNull(configuredUser);
        password = Objects.requireNonNull(configuredPassword);
        String configured = System.getenv().getOrDefault("CONNEX_DB_URL", "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        catalog = MySqlScratchCatalog.create(configured, username, password, "connex_assistant_v237_it_", "utf8mb4", "utf8mb4_unicode_ci");
        url = catalog.url();
        migrate("235");
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.4"));
            statement.executeUpdate("INSERT INTO organization (id,name,slug) VALUES (237000,'Cutover fixture','assistant-cutover-237000')");
            statement.executeUpdate("INSERT INTO workspace (id,org_id,name,slug) VALUES (237000,237000,'Cutover fixture','assistant-cutover-237000')");
            statement.executeUpdate("INSERT INTO ai_agent_privacy_barrier (workspace_id) VALUES (237000)");
            statement.executeUpdate("INSERT INTO person (id,workspace_id,name,suspended_at) VALUES (237000,237000,'Pre-cutover restricted contact','2026-09-01')");
            statement.executeUpdate("INSERT INTO ai_chat_session (id,workspace_id,created_by_user_id,title,title_user_set) VALUES (237000,237000,9001,'Private user-set title',TRUE)");
            statement.executeUpdate("INSERT INTO ai_chat_message (id,workspace_id,session_id,seq,author_kind,content) VALUES (237000,237000,237000,1,'user','Uncited private free text')");
            statement.executeUpdate("INSERT INTO ai_chat_message (id,workspace_id,session_id,seq,author_kind,content,structured_json) VALUES (237001,237000,237000,2,'system','Copied private summary without citations','{\"kind\":\"history_summary\",\"throughSeq\":1}')");
            statement.executeUpdate("INSERT INTO ai_chat_turn (id,workspace_id,session_id,requested_by_user_id) VALUES (237000,237000,237000,9001)");
            statement.executeUpdate("INSERT INTO ai_assistant_proposal_set (workspace_id,proposal_key,session_id,origin_turn_id,message_id,step_number,call_ordinal,tool_name,invocation_mode,row_count,intent_fingerprint,scope_revision,privacy_generation) VALUES (237000,'cutover-parent',237000,237000,237000,1,0,'add_tag','scalar',1,REPEAT('a',64),'scope-1',1)");
            statement.executeUpdate("INSERT INTO ai_chat_tool_call (workspace_id,message_id,tool_name,server_proposal,proposal_key,proposal_ordinal,proposal_prepared_at) VALUES (237000,237000,'add_tag',TRUE,'cutover-parent',1,UTC_TIMESTAMP(6))");
            statement.executeUpdate("INSERT INTO ai_agent_run (id,workspace_id,session_id,owner_user_id,client_request_key,mission_type,runtime_contract_version,locale,goal,privacy_generation,request_hash,scope_json,scope_fingerprint,execution_deadline_at,hard_deadline_at) VALUES (237000,237000,237000,9001,'23700000-0000-0000-0000-000000000000','record_review_v1',1,'en','Private goal',1,UNHEX(REPEAT('ab',32)),'{}',UNHEX(REPEAT('cd',32)),'2030-01-01','2030-01-01')");
            statement.executeUpdate("INSERT INTO ai_agent_segment (workspace_id,run_id,segment_no,attempt_no,execution_generation,privacy_generation,status,reserved_steps,reserved_tokens,provider_declaration_fingerprint,deadline_at,settled_at) VALUES (237000,237000,1,1,1,1,'completed',4,65536,UNHEX(REPEAT('ab',32)),'2030-01-01','2026-10-01')");
            statement.executeUpdate("INSERT INTO ai_agent_privacy_work (workspace_id,operation_key,operation_fingerprint,reason,cutoff_generation,invalidated_generation) VALUES (237000,'23700000-0000-0000-0000-000000000001',UNHEX(REPEAT('bc',32)),'restriction',1,2)");
            tableIdentities = identities(connection);
        }
        migrate("237");
    }

    @AfterAll
    static void removeCatalog() throws SQLException {
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    void existingRowsRemainGenerationZero() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            for (String table : RETAINED.subList(0, 7)) {
                try (var rows = statement.executeQuery("SELECT organization_privacy_generation FROM " + table + " WHERE workspace_id=237000")) {
                    assertTrue(rows.next(), table);
                    assertEquals(0, rows.getLong(1), table);
                }
            }
            try (var rows = statement.executeQuery("SELECT f.generation,o.cutoff_generation,o.invalidated_generation FROM ai_assistant_privacy_fence f JOIN ai_assistant_privacy_operation o ON o.organization_id=f.organization_id AND o.operation_key=f.bootstrap_operation_id WHERE f.organization_id=237000")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
                assertEquals(0, rows.getLong(2));
                assertEquals(1, rows.getLong(3));
            }
        }
    }

    @Test
    void v1WorkRemainsValid() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT content_scope,cutoff_generation,invalidated_generation,HEX(operation_fingerprint) FROM ai_agent_privacy_work WHERE workspace_id=237000 AND operation_key='23700000-0000-0000-0000-000000000001'")) {
            assertTrue(rows.next());
            assertEquals("organization_mission_holdings_v1", rows.getString(1));
            assertEquals(1, rows.getLong(2));
            assertEquals(2, rows.getLong(3));
            assertEquals("BC".repeat(32), rows.getString(4));
        }
    }

    @Test
    void preCutoverRestrictionLeavesFreeTextAndSummaryAtZero() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT suspended_at FROM person WHERE workspace_id=237000 AND id=237000")) {
                assertTrue(rows.next());
                assertNotNull(rows.getTimestamp(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=237000 AND session_id=237000 AND organization_privacy_generation=0")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM ai_chat_session s JOIN ai_assistant_privacy_fence f ON f.organization_id=237000 WHERE s.workspace_id=237000 AND s.organization_privacy_generation=f.generation")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void bootstrapZeroCutoffIsValidOnlyForV2() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            String values = " (workspace_id,operation_key,operation_fingerprint,reason,cutoff_generation,invalidated_generation,content_scope) VALUES (237000,'23700000-0000-0000-0000-000000000002',UNHEX(REPEAT('cd',32)),'bootstrap',0,1,";
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO ai_agent_privacy_work" + values + "'organization_mission_holdings_v1')"));
            assertEquals(1, statement.executeUpdate("INSERT INTO ai_agent_privacy_work" + values + "'organization_assistant_holdings_v2')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE ai_agent_privacy_work SET content_scope='organization_mission_holdings_v1' WHERE workspace_id=237000 AND cutoff_generation=0"));
        }
    }

    @Test
    void ddlDoesNotCopyRetainedChatTables() throws Exception {
        try (Connection connection = connection()) {
            assertEquals(tableIdentities, identities(connection));
        }
        String ddl = Files.readString(Path.of("src/main/resources/db/migration/tenant/V237__ai_assistant_privacy_cutover.sql"));
        assertFalse(ddl.contains("ALGORITHM=COPY"));
        assertFalse(ddl.contains("foreign_key_checks"));
        assertFalse(ddl.contains("UPDATE ai_chat"));
    }

    private static List<String> identities(Connection connection) throws SQLException {
        List<String> identities = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT TABLE_ID FROM information_schema.INNODB_TABLES WHERE NAME=CONCAT(DATABASE(),'/',?)")) {
            for (String table : RETAINED) {
                query.setString(1, table);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next(), table);
                    identities.add(table + ":" + rows.getLong(1));
                }
            }
        }
        return List.copyOf(identities);
    }

    private static void migrate(String version) {
        Flyway flyway = Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .callbacks(new AuditLogV126MigrationCallback()).baselineOnMigrate(true).baselineVersion(MigrationVersion.fromVersion("0"))
                .target(MigrationVersion.fromVersion(version)).load();
        flyway.migrate();
        assertEquals(MigrationVersion.fromVersion(version), Objects.requireNonNull(flyway.info().current()).getVersion());
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
