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
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

/** Exercises the online singleton cutover and immutable provenance against populated MySQL 8.4. */
class AiAgentSingletonV238MigrationIntegrationTest {
    private static MySqlScratchCatalog catalog;
    private static String url;
    private static String username;
    private static String password;
    private static List<String> tableIds;
    private static List<String> oldRuns;

    @BeforeAll
    static void upgradesPopulatedV235() throws Exception {
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null, "MySQL credentials required for V238 migration tests");
        catalog = MySqlScratchCatalog.create(System.getenv().getOrDefault("CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED"),
                username, password,
                "connex_mission_v238_it_", "utf8mb4", "utf8mb4_unicode_ci");
        url = catalog.url();
        migrate("235");
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.4"));
            for (int runtime : List.of(1, 2)) {
                int id = 238000 + runtime;
                statement.executeUpdate("INSERT INTO ai_chat_session (id, workspace_id, created_by_user_id, title) VALUES ("
                        + id + ",238000,9001,'Legacy mission')");
                statement.executeUpdate(runInsert(id, id, runtime));
            }
            tableIds = tableIdentity(connection);
            oldRuns = rows(connection, "SELECT id, runtime_contract_version, goal, execution_deadline_at, hard_deadline_at "
                    + "FROM ai_agent_run WHERE workspace_id=238000 ORDER BY id");
        }
        migrate("238");
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    void upgradesPopulatedV235WithoutRebuildingRetainedTables() throws Exception {
        try (Connection connection = connection()) {
            assertEquals(tableIds, tableIdentity(connection));
            assertEquals(oldRuns, rows(connection, "SELECT id, runtime_contract_version, goal, execution_deadline_at, hard_deadline_at "
                    + "FROM ai_agent_run WHERE workspace_id=238000 ORDER BY id"));
        }
    }

    @Test
    void preservesRuntimeOneAndTwoRowsAndRuntimeTwoDefault() throws Exception {
        try (Connection connection = connection()) {
            assertEquals(List.of("2"), rows(connection, "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_agent_run' AND COLUMN_NAME='runtime_contract_version'"));
            assertEquals(List.of("0|null|null|null", "0|null|null|null"), rows(connection,
                    "SELECT proposal_membership_revision, proposal_frozen_at, proposal_first_prepared_at, review_expires_at "
                    + "FROM ai_agent_run WHERE workspace_id=238000 ORDER BY id"));
        }
    }

    @Test
    void rejectsCrossWorkspaceAndCrossRunActionLinks() throws Exception {
        try (Connection connection = fixture()) {
            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE ai_chat_tool_call SET mission_run_id=238001 WHERE workspace_id=238100"));
            assertThrows(SQLException.class, () -> execute(connection, actionInsert(238101, 238100, 238100)));
            assertThrows(SQLException.class, () -> execute(connection, actionInsert(238100, 238100, 238001)));
        }
    }

    @Test
    void rejectsChangedActionIdentityPreparationAndOriginSegment() throws Exception {
        try (Connection connection = fixture()) {
            for (String assignment : List.of("action_slot=UUID()", "plan_item_key='p0002'", "plan_revision=2",
                    "target_id=42", "prepared_at=DATE_ADD(prepared_at, INTERVAL 1 SECOND)",
                    "source_observed_at=DATE_ADD(source_observed_at, INTERVAL 1 SECOND)",
                    "preparation_id=UUID()", "intent_fingerprint=UNHEX(REPEAT('aa',32))",
                    "origin_segment_id=NULL", "origin_execution_generation=2")) {
                assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE ai_agent_action SET " + assignment + " WHERE workspace_id=238100"), assignment);
            }
        }
    }

    @Test
    void rejectsReclassifiedMissionProposalOrigins() throws Exception {
        try (Connection connection = fixture()) {
            for (String assignment : List.of("proposal_origin='legacy'", "mission_action_id=NULL",
                    "artifact_origin='reactive'", "mission_run_id=NULL", "server_proposal=FALSE",
                    "arguments_json=JSON_OBJECT('changed',TRUE)", "privacy_generation=2")) {
                assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE ai_chat_tool_call SET " + assignment + " WHERE workspace_id=238100"), assignment);
            }
        }
    }

    @Test
    void rejectsChangedFrozenReviewMetadataAndAcceptsErasedShape() throws Exception {
        try (Connection connection = fixture()) {
            execute(connection, "UPDATE ai_agent_action SET output_ordinal=1 WHERE workspace_id=238100");
            execute(connection, "UPDATE ai_agent_run SET proposal_membership_revision=2, proposal_frozen_at='2030-01-01', "
                    + "proposal_first_prepared_at='2029-12-31', review_expires_at='2030-01-03', status='waiting_approval' WHERE id=238100");
            for (String assignment : List.of("proposal_membership_revision=3", "proposal_frozen_at=NULL",
                    "proposal_first_prepared_at='2030-01-01'", "review_expires_at='2030-01-04'")) {
                assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE ai_agent_run SET " + assignment + " WHERE id=238100"));
            }
            execute(connection, "DELETE FROM ai_agent_action WHERE workspace_id=238100");
            assertEquals(List.of("0"), rows(connection, "SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=238100"));
            execute(connection, "UPDATE ai_agent_run SET status='cancelled', purged_at=CURRENT_TIMESTAMP(6), "
                    + "proposal_membership_revision=0, proposal_frozen_at=NULL, proposal_first_prepared_at=NULL, "
                    + "review_expires_at=NULL WHERE id=238100");
        }
    }

    @Test
    void enforcesOnlineAlgorithmsAndLeavesV233MembershipNull() throws Exception {
        String ddl = Files.readString(Path.of("src/main/resources/db/migration/tenant/V238__ai_agent_singleton_proposals.sql"));
        assertTrue(ddl.contains("ALGORITHM=INSTANT, LOCK=DEFAULT"));
        assertTrue(ddl.contains("ALGORITHM=INPLACE, LOCK=NONE"));
        assertFalse(ddl.contains("ALGORITHM=COPY"));
        assertFalse(ddl.toLowerCase(java.util.Locale.ROOT).contains("from the"));
        assertFalse(ddl.contains("CHECK (producer_run_id"));
        try (Connection connection = fixture()) {
            assertEquals(List.of("null|null|null"), rows(connection,
                    "SELECT proposal_key, proposal_ordinal, proposal_prepared_at FROM ai_chat_tool_call WHERE workspace_id=238100"));
        }
    }

    @Test
    void sourceVersionsRoundTripThroughNonUtf8Connection() throws Exception {
        String connectionUrl = url.replaceAll("(?i)(characterEncoding|connectionCollation)=[^&]*&?", "");
        connectionUrl += (connectionUrl.contains("?") ? "&" : "?") + "characterEncoding=Cp1252&connectionCollation=latin1_bin";
        try (Connection connection = DriverManager.getConnection(connectionUrl, username, password)) {
            connection.setAutoCommit(false);
            setupFixture(connection);
            byte[] source = sourceJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var statement = connection.prepareStatement(
                    "SELECT CAST(source_versions_json AS BINARY) FROM ai_agent_action WHERE workspace_id=?")) {
                statement.setInt(1, 238100);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    org.junit.jupiter.api.Assertions.assertArrayEquals(source, result.getBytes(1));
                }
            }
            connection.rollback();
        }
    }

    private Connection fixture() throws Exception {
        Connection connection = connection();
        connection.setAutoCommit(false);
        setupFixture(connection);
        return connection;
    }

    private void setupFixture(Connection connection) throws Exception {
        execute(connection, "INSERT INTO ai_chat_session (id,workspace_id,created_by_user_id,title) VALUES (238100,238100,9001,'Fixture')");
        execute(connection, runInsert(238100, 238100, 3));
        execute(connection, "INSERT INTO ai_agent_segment (id,workspace_id,run_id,segment_no,attempt_no,execution_generation,"
                + "privacy_generation,status,reserved_steps,reserved_tokens,provider_declaration_fingerprint,deadline_at,settled_at) "
                + "VALUES (238100,238100,238100,1,1,1,1,'completed',4,65536,UNHEX(REPEAT('ab',32)),'2030-01-01','2029-12-31')");
        execute(connection, actionInsert(238100, 238100, 238100));
        try (var statement = connection.prepareStatement("UPDATE ai_agent_action SET preparation_id=?,intent_fingerprint=UNHEX(REPEAT('ab',32)),"
                + "prepared_at='2029-12-31',source_observed_at='2029-12-30',source_versions_json=CONVERT(? USING utf8mb4),"
                + "origin_segment_id=238100,origin_execution_generation=1 WHERE workspace_id=238100")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setBytes(2, sourceJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            statement.executeUpdate();
        }
        execute(connection, "INSERT INTO ai_chat_message (id,workspace_id,session_id,seq,author_kind,author_user_id,content,"
                + "mission_run_id,mission_message_kind,privacy_generation) VALUES (238100,238100,238100,1,'user',9001,'Goal',238100,'goal',1)");
        execute(connection, "INSERT INTO ai_chat_tool_call (workspace_id,message_id,tool_name,status,arguments_json,server_proposal,"
                + "mission_run_id,artifact_origin,privacy_generation,proposal_origin,mission_action_id) "
                + "SELECT 238100,238100,'assign_owner','proposed','{}',TRUE,238100,'mission',1,'mission',id "
                + "FROM ai_agent_action WHERE workspace_id=238100");
    }

    private static String runInsert(int id, int session, int runtime) {
        return "INSERT INTO ai_agent_run (id,workspace_id,session_id,owner_user_id,client_request_key,request_hash,"
                + "mission_type,definition_version,runtime_contract_version,locale,goal,scope_json,scope_fingerprint,privacy_generation,"
                + "execution_deadline_at,hard_deadline_at) VALUES (" + id + "," + (runtime == 3 ? 238100 : 238000) + "," + session
                + ",9001,'" + UUID.randomUUID() + "',UNHEX(REPEAT('ab',32)),'"
                + (runtime == 3 ? "record_preparation_v1" : "record_review_v1") + "',1," + runtime
                + ",'en','Review','{\"kind\":\"record\",\"recordKind\":\"person\",\"recordId\":41}',UNHEX(REPEAT('cd',32)),1,'2030-01-01','2030-01-04')";
    }

    private static String actionInsert(int workspace, int root, int producer) {
        return "INSERT INTO ai_agent_action (workspace_id,root_run_id,producer_run_id,action_slot,semantic_slot_key,target_kind,target_id,"
                + "tool_name,plan_item_key,plan_revision,privacy_generation) VALUES (" + workspace + "," + root + "," + producer
                + ",'" + UUID.randomUUID() + "','person:41:assign_owner','person',41,'assign_owner','p0001',1,1)";
    }

    private static String sourceJson() {
        return "{\"schemaVersion\":1,\"sources\":[{\"kind\":\"person\",\"id\":41,\"source\":\"record\",\"limit\":0,\"sha256\":\""
                + "a".repeat(64) + "\",\"observedAt\":\"2029-12-30T00:00:00Z\"}]}";
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static List<String> tableIdentity(Connection connection) throws SQLException {
        return rows(connection, "SELECT NAME,TABLE_ID FROM information_schema.INNODB_TABLES WHERE NAME IN "
                + "(CONCAT(DATABASE(),'/ai_agent_run'),CONCAT(DATABASE(),'/ai_chat_tool_call')) ORDER BY NAME");
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) {
                var columns = new ArrayList<String>();
                for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
                    columns.add(Objects.toString(result.getString(index), "null"));
                }
                values.add(String.join("|", columns));
            }
        }
        return values;
    }

    private static void migrate(String version) {
        Flyway flyway = Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .callbacks(new AuditLogV126MigrationCallback()).baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0")).target(MigrationVersion.fromVersion(version)).load();
        flyway.migrate();
        assertNotNull(flyway.info().current());
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
