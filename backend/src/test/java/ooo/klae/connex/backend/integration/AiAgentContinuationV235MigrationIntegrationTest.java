package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import ooo.klae.connex.backend.ai.mission.AiAgentContinuationCodec;
import ooo.klae.connex.backend.ai.mission.AiAgentContinuationCodec.Snapshot;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceVersion;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.MemberProvenance;
import ooo.klae.connex.backend.ai.mission.AiAgentPlanService;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import tools.jackson.databind.json.JsonMapper;

/** Real-MySQL online cutover and lossless continuation transport through every mapper writer. */
class AiAgentContinuationV235MigrationIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static MySqlScratchCatalog catalog;
    private static String url = "";
    private static String username = "";
    private static String password = "";
    private static List<String> tableIdentity = List.of();
    private static List<String> oldRunColumns = List.of();
    private static List<String> oldRun = List.of();
    private static List<String> oldSegment = List.of();

    @BeforeAll
    static void migratePopulatedV234() throws SQLException {
        String configuredUsername = System.getenv("CONNEX_DB_USERNAME");
        String configuredPassword = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(configuredUsername != null && configuredPassword != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD required for V235 migration test");
        username = Objects.requireNonNull(configuredUsername);
        password = Objects.requireNonNull(configuredPassword);
        String configured = System.getenv().getOrDefault("CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        catalog = MySqlScratchCatalog.create(configured, username, password,
                "connex_mission_v235_it_", "utf8mb4", "utf8mb4_unicode_ci");
        url = catalog.url();
        assertEquals(MigrationVersion.fromVersion("234"),
                Objects.requireNonNull(migrate("234").info().current()).getVersion());
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.4"),
                    "V235 online guards must be exercised on MySQL 8.4");
            assertEquals(List.of("[workspace_id]", "[generated_at]", "[id]"), rows(connection, """
                    SELECT COLUMN_NAME FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deal_document'
                      AND INDEX_NAME = 'idx_deal_document_recency'
                    ORDER BY SEQ_IN_INDEX
                    """));
            insertSession(connection, 234000);
            statement.executeUpdate("""
                    INSERT INTO ai_agent_run
                        (id, workspace_id, session_id, owner_user_id, client_request_key, request_hash,
                         mission_type, locale, goal, scope_json, scope_fingerprint, privacy_generation,
                         execution_deadline_at, hard_deadline_at)
                    VALUES (234000, 234000, 234000, 9001, '23400000-0000-0000-0000-000000000000',
                            UNHEX(REPEAT('ab', 32)), 'record_review_v1', 'ja', 'Legacy mission goal',
                            '{"kind":"record","recordKind":"company","recordId":4}',
                            UNHEX(REPEAT('cd', 32)), 1, '2030-01-01', '2030-01-01')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_agent_segment
                        (id, workspace_id, run_id, segment_no, attempt_no, execution_generation,
                         privacy_generation, status, reserved_steps, reserved_tokens,
                         provider_declaration_fingerprint, deadline_at, settled_at)
                    VALUES (234000, 234000, 234000, 1, 1, 1, 1, 'interrupted', 4, 65536,
                            UNHEX(REPEAT('ef',32)), '2030-01-01', '2026-10-01')
                    """);
            tableIdentity = tableIdentity(connection);
            oldRunColumns = columnNames(connection, "ai_agent_run");
            oldRun = rows(connection, "SELECT * FROM ai_agent_run WHERE id = 234000");
            oldSegment = rows(connection, "SELECT * FROM ai_agent_segment WHERE id = 234000");
        }
        assertEquals(MigrationVersion.fromVersion("235"),
                Objects.requireNonNull(migrate("235").info().current()).getVersion());
        migrate("238");
    }

    @AfterAll
    static void removeCatalog() throws SQLException {
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    void upgradesPopulatedV234WithoutRebuildingMissionTables() throws SQLException {
        try (Connection connection = connection()) {
            assertEquals(tableIdentity, tableIdentity(connection));
            assertEquals(2, tableIdentity.size());
            String columns = String.join(", ", oldRunColumns);
            assertEquals(oldRun, rows(connection, "SELECT " + columns + " FROM ai_agent_run WHERE id = 234000"));
            assertEquals(oldSegment, rows(connection, "SELECT * FROM ai_agent_segment WHERE id = 234000"));
            assertEquals(List.of("[1, null, 0, null, null, 0, null, null, null, 0]"), rows(connection, """
                    SELECT runtime_contract_version, plan_schema_version, plan_revision, plan_json,
                           checkpoint_schema_version, checkpoint_revision, checkpoint_json,
                           memory_schema_version, memory_json, last_committed_segment_no
                    FROM ai_agent_run WHERE id = 234000
                    """));
            assertEquals(List.of("[2]"), rows(connection, """
                    SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_run'
                      AND COLUMN_NAME = 'runtime_contract_version'
                    """));
        }
    }

    @Test
    void onlineGuardsAndAttemptTriggersEnforceContract() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/tenant/V235__ai_agent_continuation.sql"));
        String uncommented = migration.replaceAll("(?m)--[^\\r\\n]*", "");
        List<String> alterations = Arrays.stream(uncommented.split(";"))
                .map(String::strip).filter(sql -> sql.startsWith("ALTER TABLE ")).toList();
        assertEquals(2, alterations.size());
        assertTrue(alterations.getFirst().contains("ALGORITHM=INSTANT, LOCK=DEFAULT"));
        assertTrue(alterations.get(1).contains("ALGORITHM=INPLACE, LOCK=NONE"));
        assertFalse(uncommented.contains("ADD CONSTRAINT"));
        assertFalse(migration.toLowerCase(java.util.Locale.ROOT).contains("from the"));
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (int segmentNo : List.of(1, 8)) {
                for (int attemptNo : List.of(1, 3)) {
                    if (segmentNo != 1 || attemptNo != 1) {
                        assertEquals(1, statement.executeUpdate(segmentInsert(segmentNo, attemptNo)));
                    }
                }
            }
            for (int[] invalid : List.of(new int[] {0, 1}, new int[] {9, 1}, new int[] {2, 0}, new int[] {2, 4})) {
                SQLException failure = assertThrows(SQLException.class,
                        () -> statement.executeUpdate(segmentInsert(invalid[0], invalid[1])));
                assertEquals(1644, failure.getErrorCode());
            }
            for (String assignment : List.of("segment_no = 2", "attempt_no = 2", "segment_no = 9", "attempt_no = 4")) {
                SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "UPDATE ai_agent_segment SET " + assignment + " WHERE id = 234000"));
                assertEquals(1644, failure.getErrorCode());
            }
            assertEquals(1, statement.executeUpdate(
                    "UPDATE ai_agent_segment SET known_steps = 1 WHERE id = 234000"));
            connection.rollback();
        }
    }

    @Test
    void canonicalJsonRoundTripsThroughNonUtf8Connection() throws Exception {
        String cp1252 = url.replaceAll("(?i)(characterEncoding|connectionCollation)=[^&]*&?", "");
        cp1252 += (cp1252.contains("?") ? "&" : "?") + "characterEncoding=Cp1252&connectionCollation=latin1_bin";
        try (Connection connection = DriverManager.getConnection(cp1252, username, password);
                SqlSession session = mapperFactory(cp1252).openSession(connection)) {
            connection.setAutoCommit(false);
            assertEquals(List.of("[latin1]"), rows(connection, "SELECT @@character_set_client"));
            insertSession(connection, 234001);
            var mapper = session.getMapper(AiAgentRunMapper.class);
            var codec = new AiAgentContinuationCodec();
            var plans = new AiAgentPlanService(codec);
            var run = newRun(234001);
            assertEquals(1, mapper.insertRun(run));
            var initial = codec.initialize(run);
            assertEquals(1, mapper.updateContinuation(run, 1, 0, null, 0, 0));
            assertStored(connection, mapper, run, codec, null);
            int goalMessageId = insertGoal(connection, run);

            AiAgentSegment first = claim(connection, mapper, run, 1);
            var arguments = JSON.createObjectNode();
            arguments.put("expectedPlanRevision", "1");
            arguments.put("expectedCheckpointRevision", "1");
            var item = arguments.putArray("items").addObject();
            item.putNull("key");
            item.put("label", "日本語😀 記録を確認");
            item.put("status", "done");
            var completion = item.putObject("completion");
            completion.put("summary", "担当者😀 が確認した不変の事実");
            completion.putArray("citations").add("record_1");
            var reference = new EvidenceReference("company", 4, new EvidenceVersion("record", 0, "a".repeat(64)));
            var prepared = plans.prepare(initial, arguments, 1,
                    value -> new DurableText(value, List.of(new MemberProvenance(9001, "担当者😀"))),
                    handles -> List.of(reference));
            long state = run.getStateVersion();
            codec.apply(run, prepared.snapshot());
            run.setStateVersion(state + 1);
            assertEquals(1, mapper.updateContinuation(run, state, run.getExecutionGeneration(), first.getId(), 1, 1));
            byte[] immutableFacts = JSON.writeValueAsBytes(prepared.snapshot().checkpoint().completedFacts());
            assertStored(connection, mapper, run, codec, immutableFacts);
            assertBookkeepingRoundTrip(connection, mapper, run, goalMessageId, "set_todos", prepared);

            var checkpointArgs = JSON.createObjectNode();
            checkpointArgs.put("expectedPlanRevision", "2");
            checkpointArgs.put("expectedCheckpointRevision", "2");
            checkpointArgs.putNull("nextPlanItemKey");
            checkpointArgs.put("phase", "reporting");
            var memory = checkpointArgs.putObject("memory");
            memory.put("summary", "次の区間へ引き継ぐ日本語😀");
            memory.putArray("citations").add("record_1");
            var checkpoint = plans.prepareCheckpoint(prepared.snapshot(), checkpointArgs, 1,
                    value -> new DurableText(value, List.of(new MemberProvenance(9001, "担当者😀"))),
                    handles -> List.of(reference));
            state = run.getStateVersion();
            codec.apply(run, checkpoint.snapshot());
            run.setStateVersion(state + 1);
            assertEquals(1, mapper.updateContinuation(run, state, run.getExecutionGeneration(), first.getId(), 2, 2));
            settle(connection, mapper, run, first, "queued");
            assertStored(connection, mapper, run, codec, immutableFacts);
            assertBookkeepingRoundTrip(connection, mapper, run, goalMessageId, "checkpoint_segment", checkpoint);

            run = mapper.getRun(run.getWorkspaceId(), run.getId());
            AiAgentSegment second = claim(connection, mapper, run, 2);
            assertStored(connection, mapper, run, codec, immutableFacts);
            settle(connection, mapper, run, second, "done");
            assertStored(connection, mapper, run, codec, immutableFacts);
            assertEquals(1, run.getLastCommittedSegmentNo());
            assertEquals(2, mapper.listSegments(run.getWorkspaceId(), run.getId()).size());
            var populatedInsert = newRun(234001);
            populatedInsert.setClientRequestKey("23400000-0000-0000-0000-000000000002");
            populatedInsert.setId(nextRunId(connection));
            int expectedId = populatedInsert.getId();
            var insertInitial = codec.initialize(populatedInsert);
            var insertPlan = plans.prepare(insertInitial, arguments, 1,
                    value -> new DurableText(value, List.of(new MemberProvenance(9001, "担当者😀"))),
                    handles -> List.of(reference));
            codec.apply(populatedInsert, insertPlan.snapshot());
            assertEquals(1, mapper.insertRun(populatedInsert));
            assertEquals(expectedId, populatedInsert.getId());
            assertStored(connection, mapper, populatedInsert, codec, immutableFacts);
            connection.rollback();
        }
    }

    private static void assertBookkeepingRoundTrip(Connection connection, AiAgentRunMapper mapper,
            AiAgentRun run, int goalMessageId, String tool, AiAgentPlanService.Prepared prepared) throws SQLException {
        var call = new AiChatToolCall();
        call.setWorkspaceId(run.getWorkspaceId());
        call.setMessageId(goalMessageId);
        call.setMissionRunId(run.getId());
        call.setPrivacyGeneration(run.getPrivacyGeneration());
        call.setToolName(tool);
        call.setArgumentsJson(JSON.writeValueAsString(prepared.arguments()));
        call.setResultJson(JSON.writeValueAsString(prepared.result()));
        call.setExecutedByUserId(9001);
        call.setIdempotencyKey("utf8-" + tool);
        assertEquals(1, mapper.insertMissionBookkeepingCall(call));
        var stored = mapper.findMissionBookkeepingCall(run.getWorkspaceId(), run.getId(), call.getIdempotencyKey());
        assertNotNull(stored);
        assertEquals(prepared.arguments(), JSON.readTree(stored.getArgumentsJson()));
        assertEquals(prepared.result(), JSON.readTree(stored.getResultJson()));
        try (var select = connection.prepareStatement("""
                SELECT CAST(arguments_json AS BINARY), CAST(result_json AS BINARY)
                FROM ai_chat_tool_call WHERE workspace_id = ? AND id = ?
                """)) {
            select.setInt(1, run.getWorkspaceId());
            select.setInt(2, call.getId());
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                var storedArguments = JSON.readTree(new String(rows.getBytes(1), StandardCharsets.UTF_8));
                var storedResult = JSON.readTree(new String(rows.getBytes(2), StandardCharsets.UTF_8));
                assertEquals(prepared.arguments(), storedArguments);
                assertEquals(prepared.result(), storedResult);
                if ("set_todos".equals(tool)) {
                    assertArrayEquals("担当者😀 が確認した不変の事実".getBytes(StandardCharsets.UTF_8),
                            storedArguments.get("items").get(0).get("completion").get("summary")
                                    .get("text").stringValue().getBytes(StandardCharsets.UTF_8));
                } else {
                    assertArrayEquals("次の区間へ引き継ぐ日本語😀".getBytes(StandardCharsets.UTF_8),
                            storedArguments.get("memory").get("summary").get("text")
                                    .stringValue().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private static int insertGoal(Connection connection, AiAgentRun run) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO ai_chat_message
                    (workspace_id, session_id, seq, author_kind, author_user_id, content,
                     mission_run_id, mission_message_kind, privacy_generation)
                VALUES (?, ?, 1, 'user', 9001, 'Review primary record', ?, 'goal', 1)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, run.getWorkspaceId());
            insert.setInt(2, Objects.requireNonNull(run.getSessionId()));
            insert.setInt(3, run.getId());
            assertEquals(1, insert.executeUpdate());
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getInt(1);
            }
        }
    }

    private static int nextRunId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT AUTO_INCREMENT FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_run'
                """)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static void assertStored(Connection connection, AiAgentRunMapper mapper, AiAgentRun expected,
            AiAgentContinuationCodec codec, byte[] expectedFacts) throws SQLException {
        var actual = mapper.getRun(expected.getWorkspaceId(), expected.getId());
        assertEquals(expected.getPlanJson(), actual.getPlanJson());
        assertEquals(expected.getCheckpointJson(), actual.getCheckpointJson());
        assertEquals(expected.getMemoryJson(), actual.getMemoryJson());
        Snapshot decoded = codec.decode(actual);
        assertNotNull(decoded);
        if (expectedFacts != null) {
            assertArrayEquals(expectedFacts, JSON.writeValueAsBytes(decoded.checkpoint().completedFacts()));
        }
        try (var select = connection.prepareStatement("""
                SELECT CAST(plan_json AS BINARY), CAST(checkpoint_json AS BINARY), CAST(memory_json AS BINARY)
                FROM ai_agent_run WHERE workspace_id = ? AND id = ?
                """)) {
            select.setInt(1, expected.getWorkspaceId());
            select.setInt(2, expected.getId());
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                assertArrayEquals(expected.getPlanJson().getBytes(StandardCharsets.UTF_8), rows.getBytes(1));
                assertArrayEquals(expected.getCheckpointJson().getBytes(StandardCharsets.UTF_8), rows.getBytes(2));
                assertArrayEquals(expected.getMemoryJson().getBytes(StandardCharsets.UTF_8), rows.getBytes(3));
            }
        }
    }

    private static AiAgentSegment claim(Connection connection, AiAgentRunMapper mapper, AiAgentRun run,
            int segmentNo) throws SQLException {
        int turnId;
        try (var insert = connection.prepareStatement("""
                INSERT INTO ai_chat_turn (workspace_id, session_id, requested_by_user_id, status)
                VALUES (?, ?, 9001, 'running')
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, run.getWorkspaceId());
            insert.setInt(2, Objects.requireNonNull(run.getSessionId()));
            assertEquals(1, insert.executeUpdate());
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                turnId = keys.getInt(1);
            }
        }
        var segment = new AiAgentSegment();
        segment.setWorkspaceId(run.getWorkspaceId());
        segment.setRunId(run.getId());
        segment.setTurnId(turnId);
        segment.setSegmentNo(segmentNo);
        segment.setAttemptNo(1);
        segment.setExecutionGeneration(run.getExecutionGeneration() + 1);
        segment.setPrivacyGeneration(run.getPrivacyGeneration());
        segment.setReservedSteps(4);
        segment.setReservedTokens(65536);
        segment.setStatus("running");
        segment.setProviderDeclarationFingerprint(new byte[32]);
        segment.setDeadlineAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        assertEquals(1, mapper.insertSegment(segment));
        try (var update = connection.prepareStatement("""
                UPDATE ai_chat_turn SET mission_run_id = ?, mission_segment_id = ?, privacy_generation = 1
                WHERE workspace_id = ? AND id = ?
                """)) {
            update.setInt(1, run.getId());
            update.setInt(2, segment.getId());
            update.setInt(3, run.getWorkspaceId());
            update.setInt(4, turnId);
            assertEquals(1, update.executeUpdate());
        }
        long state = run.getStateVersion();
        long generation = run.getExecutionGeneration();
        run.setStatus("running");
        run.setActiveSegmentId(segment.getId());
        run.setExecutionGeneration(generation + 1);
        run.setStateVersion(state + 1);
        run.setReservedSteps(4);
        run.setReservedTokens(65536);
        assertEquals(1, mapper.updateRun(run, state, generation));
        return segment;
    }

    private static void settle(Connection connection, AiAgentRunMapper mapper, AiAgentRun run,
            AiAgentSegment segment, String status) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE ai_chat_turn SET status = 'resolved', terminal_reason = NULL
                WHERE workspace_id = ? AND session_id = ? AND id = ? AND status = 'running'
                """)) {
            update.setInt(1, run.getWorkspaceId());
            update.setInt(2, Objects.requireNonNull(run.getSessionId()));
            update.setInt(3, Objects.requireNonNull(segment.getTurnId()));
            assertEquals(1, update.executeUpdate());
        }
        segment.setStatus("completed");
        segment.setKnownSteps(2);
        segment.setKnownInputTokens(1000);
        segment.setKnownOutputTokens(100);
        segment.setSettledAt(LocalDateTime.of(2026, 10, 20, 0, 0));
        assertEquals(1, mapper.updateSegment(segment, "running"));
        long state = run.getStateVersion();
        long generation = run.getExecutionGeneration();
        run.setStatus(status);
        run.setActiveSegmentId(null);
        run.setReservedSteps(0);
        run.setReservedTokens(0);
        run.setConsumedSteps(run.getConsumedSteps() + 2);
        run.setConsumedTokens(run.getConsumedTokens() + 1100);
        run.setStateVersion(state + 1);
        run.setExecutionGeneration(generation + 1);
        assertEquals(1, mapper.updateRun(run, state, generation));
    }

    private static AiAgentRun newRun(int workspaceId) {
        var run = new AiAgentRun();
        run.setWorkspaceId(workspaceId);
        run.setSessionId(workspaceId);
        run.setOwnerUserId(9001);
        run.setClientRequestKey("23400000-0000-0000-0000-000000000001");
        run.setMissionType("record_review_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(2);
        run.setLocale("ja");
        run.setGoal("Review primary record");
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"company\",\"recordId\":4}");
        run.setRequestHash(new byte[32]);
        run.setScopeFingerprint(new byte[32]);
        run.setStateVersion(1);
        run.setPrivacyGeneration(1);
        run.setMaxSteps(8);
        run.setMaxTokens(131072);
        run.setStatus("queued");
        run.setExecutionDeadlineAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        run.setHardDeadlineAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        run.setNextAttemptAt(LocalDateTime.of(2026, 10, 20, 0, 0));
        return run;
    }

    private static void insertSession(Connection connection, int identity) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO ai_chat_session (workspace_id, id, created_by_user_id, title)
                VALUES (?, ?, 9001, 'Mission continuation migration fixture')
                """)) {
            insert.setInt(1, identity);
            insert.setInt(2, identity);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static String segmentInsert(int segment, int attempt) {
        return """
                INSERT INTO ai_agent_segment
                    (workspace_id, run_id, segment_no, attempt_no, execution_generation, privacy_generation,
                     status, reserved_steps, reserved_tokens, provider_declaration_fingerprint, deadline_at, settled_at)
                VALUES (234000, 234000, %d, %d, 1, 1, 'completed', 4, 65536,
                        UNHEX(REPEAT('ab',32)), '2030-01-01', '2026-10-01')
                """.formatted(segment, attempt);
    }

    private static SqlSessionFactory mapperFactory(String jdbcUrl) throws Exception {
        var dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        var configuration = new Configuration(new Environment("mission-v235", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mappers/AiAgentRunMapper.xml";
        try (InputStream input = AiAgentContinuationV235MigrationIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static Flyway migrate(String version) {
        Flyway flyway = Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .callbacks(new AuditLogV126MigrationCallback()).baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0")).target(MigrationVersion.fromVersion(version)).load();
        flyway.migrate();
        return flyway;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private static List<String> tableIdentity(Connection connection) throws SQLException {
        return rows(connection, """
                SELECT NAME, TABLE_ID FROM information_schema.INNODB_TABLES
                WHERE NAME IN (CONCAT(DATABASE(), '/ai_agent_run'), CONCAT(DATABASE(), '/ai_agent_segment'))
                ORDER BY NAME
                """);
    }

    private static List<String> columnNames(Connection connection, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT COLUMN_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION
                """)) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return names;
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
}
