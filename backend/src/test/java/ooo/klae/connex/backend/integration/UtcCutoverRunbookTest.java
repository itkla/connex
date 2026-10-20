package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Executes the operator SQL verbatim in a disposable catalog, including timing checks and reruns. */
class UtcCutoverRunbookTest {

    private static MySqlScratchCatalog catalog;
    private static String username;
    private static String password;

    @BeforeAll
    static void createCatalog() throws SQLException {
        String url = System.getenv("CONNEX_DB_URL");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(url != null && username != null && password != null, "MySQL environment required");
        catalog = MySqlScratchCatalog.create(url, username, password, "connex_2168_e_cutover_", "utf8mb4", null);
        try (Connection connection = DriverManager.getConnection(catalog.url(), username, password);
                Statement statement = connection.createStatement()) {
            createTables(statement);
        }
    }

    @AfterAll
    static void dropCatalog() throws SQLException {
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    void repairsFutureEventsInEveryStatusPreservesNonfutureValuesAndIsIdempotent() throws Exception {
        withFixture(statement -> {
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE workflow_run SET status = 'succeeded', finished_at = UTC_TIMESTAMP(6) WHERE id = 1
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE workflow_invocation SET status = 'confirmed', confirmation_key = REPEAT('a', 16),
                    confirmed_at = UTC_TIMESTAMP(6) WHERE id = 1
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE workflow_invocation SET status = 'succeeded', completed_at = UTC_TIMESTAMP(6) WHERE id = 2
                """));
            List<String> preserved = preserved(statement);
            Map<String, String> result = executeRunbook(statement, 32400);
            assertEquals("1", result.get("safe_to_shift"));
            assertEquals("5", result.get("expected_run_rows"));
            assertEquals("4", result.get("run_start_values"));
            assertEquals("2", result.get("run_finish_values"));
            assertEquals("1", result.get("run_cancellation_values"));
            assertEquals("5", result.get("changed_run_rows"));
            assertEquals("6", result.get("changed_step_rows"));
            assertEquals("6", result.get("expected_step_rows"));
            assertEquals("3", result.get("expected_attempt_rows"));
            assertEquals("3", result.get("changed_attempt_rows"));
            assertEquals("9", result.get("expected_invocation_rows"));
            assertEquals("9", result.get("changed_invocation_rows"));
            assertEquals("3", result.get("prepared_scopes_to_expire"));
            assertEquals("3", result.get("expired_prepared_scopes"));
            assertEquals("9", result.get("invocation_creation_values"));
            assertEquals("7", result.get("invocation_confirmation_values"));
            assertEquals("5", result.get("invocation_completion_values"));
            assertEquals("9", result.get("invocation_expiry_values"));
            assertEquals(List.of("[0]"), query(statement,
                "SELECT COUNT(*) FROM workflow_invocation WHERE status = 'prepared'"));
            assertEquals(List.of("[3]"), query(statement,
                "SELECT COUNT(*) FROM workflow_invocation WHERE id IN (1, 4, 7) AND status = 'expired'"
                    + " AND completed_at = @utc_cutover_as_of AND completed_at >= created_at"));
            assertEquals(List.of("[3]"), query(statement,
                "SELECT COUNT(*) FROM workflow_invocation WHERE id IN (1, 2, 8)"
                    + " AND created_at = TIMESTAMPADD(MINUTE, -2, @fixture_now)"
                    + " AND expires_at = TIMESTAMPADD(MINUTE, 13, @fixture_now)"));
            assertEquals(List.of("[5]"), query(statement, """
                SELECT COUNT(*) FROM workflow_invocation WHERE id >= 10
                    AND created_at = TIMESTAMPADD(HOUR, -1, @fixture_now) AND completed_at = @fixture_now
                    AND expires_at = @fixture_now
                """));
            assertEquals(List.of("[2]"), query(statement, """
                SELECT COUNT(*) FROM workflow_run WHERE id IN (4, 7)
                    AND started_at = TIMESTAMPADD(HOUR, -1, @fixture_now)
                    AND finished_at = @fixture_now AND finished_at >= started_at
                """));
            assertEquals(List.of("[1]"), query(statement, """
                SELECT COUNT(*) FROM workflow_step_run WHERE id = 4
                    AND started_at = TIMESTAMPADD(HOUR, -1, @fixture_now) AND finished_at = @fixture_now
                """));
            assertEquals(List.of("[2]"), query(statement, """
                SELECT COUNT(*) FROM workflow_step_attempt WHERE id IN (2, 3)
                    AND started_at = TIMESTAMPADD(HOUR, -1, @fixture_now) AND finished_at = @fixture_now
                """));
            assertCleanPostflight(result);
            assertEquals(preserved, preserved(statement));
            List<String> repaired = fingerprint(statement);
            statement.execute("COMMIT");

            Map<String, String> rerun = executeRunbook(statement, 32400);
            assertEquals("1", rerun.get("safe_to_shift"));
            assertEquals("0", rerun.get("changed_run_rows"));
            assertEquals("0", rerun.get("changed_step_rows"));
            assertEquals("0", rerun.get("changed_invocation_rows"));
            assertEquals("0", rerun.get("changed_attempt_rows"));
            assertEquals("0", rerun.get("expired_prepared_scopes"));
            assertCleanPostflight(rerun);
            assertEquals(repaired, fingerprint(statement));
            statement.execute("COMMIT");
            assertEquals(1, statement.executeUpdate("""
                UPDATE workflow_run SET status = 'succeeded', finished_at = UTC_TIMESTAMP(6) WHERE id = 1
                """));
            assertEquals(1, statement.executeUpdate("""
                UPDATE workflow_step_run SET status = 'succeeded', finished_at = UTC_TIMESTAMP(6) WHERE id = 1
                """));
            assertEquals(0, statement.executeUpdate("""
                UPDATE workflow_invocation SET status = 'confirmed', confirmation_key = REPEAT('a', 16),
                    confirmed_at = UTC_TIMESTAMP(6) WHERE id = 1 AND status = 'prepared'
                """));
            assertEquals(2, statement.executeUpdate("""
                UPDATE workflow_invocation SET status = 'succeeded', completed_at = UTC_TIMESTAMP(6)
                WHERE id IN (2, 3) AND status IN ('confirmed', 'running')
                """));
        });
    }

    @Test
    void interventionRequiredRunCanBeManuallyRetriedAndCompletedAfterRepair() throws Exception {
        withFixture(statement -> {
            List<String> originalStart = query(statement, "SELECT started_at FROM workflow_run WHERE id = 7");
            Map<String, String> result = executeRunbook(statement, 32400);
            assertEquals("1", result.get("safe_to_shift"));
            assertCleanPostflight(result);
            statement.execute("COMMIT");
            List<String> correctedStart = query(statement, "SELECT started_at FROM workflow_run WHERE id = 7");
            assertNotEquals(originalStart, correctedStart);
            assertEquals(1, scheduleManualRetry(statement));
            assertEquals(correctedStart, query(statement, "SELECT started_at FROM workflow_run WHERE id = 7"));
            assertEquals(List.of("[waiting, retry, null]"), query(statement,
                "SELECT status, wait_kind, finished_at FROM workflow_run WHERE id = 7"));
            assertEquals(1, statement.executeUpdate("""
                UPDATE workflow_run SET status = 'succeeded', wait_kind = NULL, resume_at = NULL,
                    finished_at = UTC_TIMESTAMP(6) WHERE id = 7
                """));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"workflow_run", "workflow_step_run", "workflow_step_attempt", "workflow_invocation"})
    void refusesExactFutureOnlySubtractionThatWouldInvertChronology(String table) throws Exception {
        withFixture(statement -> {
            if (table.equals("workflow_invocation")) {
                statement.executeUpdate("""
                    UPDATE workflow_invocation SET created_at = TIMESTAMPADD(MINUTE, -5, @fixture_now),
                        confirmed_at = TIMESTAMPADD(MINUTE, 5, @fixture_now),
                        expires_at = TIMESTAMPADD(MINUTE, 10, @fixture_now) WHERE id = 3
                    """);
            } else {
                statement.executeUpdate("UPDATE " + table + " SET status = 'succeeded',"
                    + " started_at = TIMESTAMPADD(MINUTE, -5, @fixture_now),"
                    + " finished_at = TIMESTAMPADD(MINUTE, 5, @fixture_now) WHERE id = 1");
            }
            List<String> before = fingerprint(statement);
            Map<String, String> result = executeRunbook(statement, 32400);
            assertEquals("0", result.get("safe_to_shift"));
            for (String count : List.of("changed_run_rows", "changed_step_rows", "changed_attempt_rows",
                    "changed_invocation_rows", "expired_prepared_scopes")) {
                assertEquals("0", result.get(count), count);
            }
            assertEquals(before, fingerprint(statement));
            statement.execute("ROLLBACK");
        });
    }

    @Test
    void refusesAnEventThatWouldRemainFutureAfterShifting() throws Exception {
        withFixture(statement -> {
            statement.executeUpdate("""
                INSERT INTO workflow_run (workspace_id, id, status, started_at)
                VALUES (7, 8, 'running', TIMESTAMPADD(HOUR, 10, @fixture_now))
                """);
            List<String> before = fingerprint(statement);
            Map<String, String> result = executeRunbook(statement, 32400);
            assertEquals("0", result.get("safe_to_shift"));
            assertEquals("0", result.get("changed_run_rows"));
            assertEquals("0", result.get("changed_step_rows"));
            assertEquals("0", result.get("changed_invocation_rows"));
            assertEquals("0", result.get("changed_attempt_rows"));
            assertEquals("0", result.get("expired_prepared_scopes"));
            assertEquals(before, fingerprint(statement));
            statement.execute("ROLLBACK");
        });
    }

    @Test
    void refusesAStepEventThatWouldRemainFutureEvenWithAnEligibleParent() throws Exception {
        withFixture(statement -> {
            statement.executeUpdate("""
                UPDATE workflow_step_run SET started_at = TIMESTAMPADD(HOUR, 10, @fixture_now) WHERE id = 1
                """);
            List<String> before = fingerprint(statement);
            Map<String, String> result = executeRunbook(statement, 32400);
            assertEquals("0", result.get("safe_to_shift"));
            assertEquals("0", result.get("changed_run_rows"));
            assertEquals("0", result.get("changed_step_rows"));
            assertEquals("0", result.get("changed_invocation_rows"));
            assertEquals("0", result.get("changed_attempt_rows"));
            assertEquals("0", result.get("expired_prepared_scopes"));
            assertEquals(before, fingerprint(statement));
            statement.execute("ROLLBACK");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"created_at", "confirmed_at"})
    void refusesAnInvocationEventBeyondTheVerifiedOffset(String column) throws Exception {
        withFixture(statement -> {
            if (column.equals("created_at")) {
                statement.executeUpdate("""
                    UPDATE workflow_invocation SET created_at = TIMESTAMPADD(HOUR, 10, @fixture_now),
                        confirmed_at = TIMESTAMPADD(HOUR, 10, @fixture_now),
                        expires_at = TIMESTAMPADD(HOUR, 11, @fixture_now) WHERE id = 2
                    """);
            } else {
                statement.executeUpdate("""
                    UPDATE workflow_invocation SET confirmed_at = TIMESTAMPADD(HOUR, 10, @fixture_now) WHERE id = 2
                    """);
            }
            List<String> before = fingerprint(statement);
            Map<String, String> result = executeRunbook(statement, 32400);
            assertEquals("0", result.get("safe_to_shift"));
            assertEquals("0", result.get("changed_run_rows"));
            assertEquals("0", result.get("changed_step_rows"));
            assertEquals("0", result.get("changed_invocation_rows"));
            assertEquals("0", result.get("changed_attempt_rows"));
            assertEquals("0", result.get("expired_prepared_scopes"));
            assertEquals(before, fingerprint(statement));
            statement.execute("ROLLBACK");
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -36000, 50401})
    void refusesUnsupportedOffsets(int offset) throws Exception {
        withFixture(statement -> {
            List<String> before = fingerprint(statement);
            Map<String, String> result = executeRunbook(statement, offset);
            assertEquals("0", result.get("safe_to_shift"));
            assertEquals("0", result.get("changed_run_rows"));
            assertEquals("0", result.get("changed_step_rows"));
            assertEquals("0", result.get("changed_invocation_rows"));
            assertEquals("0", result.get("changed_attempt_rows"));
            assertEquals("0", result.get("expired_prepared_scopes"));
            assertEquals(before, fingerprint(statement));
            statement.execute("ROLLBACK");
        });
    }

    @Test
    void dsrRepairRequiresCompleteProvenanceReviewPreservesCallerDatesAndIsIdempotent() throws Exception {
        withFixture(statement -> {
            List<String> before = query(statement, "SELECT * FROM data_subject_request ORDER BY org_id, id");
            Map<String, String> preview = executeSqlBlock(statement, 32400, 1);
            assertEquals("responded", preview.get("preview_status_7_5"));
            assertEquals("closed", preview.get("preview_status_7_4"));
            assertEquals("refused", preview.get("preview_status_7_6"));
            assertEquals("responded", preview.get("preview_status_7_7"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO utc_cutover_dsr_review
                SELECT org_id, id, received_at, 2 FROM data_subject_request WHERE id = 1
                """));
            Map<String, String> missing = executeSqlBlock(statement, 32400, 2);
            assertEquals("0", missing.get("dsr_safe_to_shift"));
            assertEquals("0", missing.get("changed_dsr_rows"));
            assertEquals(before, query(statement, "SELECT * FROM data_subject_request ORDER BY org_id, id"));
            statement.execute("ROLLBACK");
            statement.executeUpdate("""
                INSERT INTO utc_cutover_dsr_review
                SELECT org_id, id, received_at, id = 1 FROM data_subject_request WHERE id IN (1, 2)
                """);
            Map<String, String> missingResponded = executeSqlBlock(statement, 32400, 2);
            assertEquals("0", missingResponded.get("dsr_safe_to_shift"));
            assertEquals("0", missingResponded.get("changed_dsr_rows"));
            assertEquals(before, query(statement, "SELECT * FROM data_subject_request ORDER BY org_id, id"));
            statement.execute("ROLLBACK");
            statement.executeUpdate("""
                INSERT INTO utc_cutover_dsr_review
                SELECT org_id, id, received_at, TRUE FROM data_subject_request WHERE id = 5
                """);
            List<String> untouched = query(statement,
                "SELECT * FROM data_subject_request WHERE id NOT IN (1, 5) ORDER BY org_id, id");
            List<String> preserved = query(statement, """
                SELECT org_id, id, status, identity_verified_at, due_at, responded_at, closed_at, created_at, updated_at
                FROM data_subject_request ORDER BY org_id, id
                """);
            Map<String, String> result = executeSqlBlock(statement, 32400, 2);
            assertEquals("1", result.get("dsr_safe_to_shift"));
            assertEquals("2", result.get("expected_dsr_rows"));
            assertEquals("2", result.get("changed_dsr_rows"));
            assertEquals("0", result.get("dsr_mismatches"));
            assertEquals("0", result.get("dsr_remaining_hazards"));
            assertEquals(preserved, query(statement, """
                SELECT org_id, id, status, identity_verified_at, due_at, responded_at, closed_at, created_at, updated_at
                FROM data_subject_request ORDER BY org_id, id
                """));
            assertEquals(untouched, query(statement,
                "SELECT * FROM data_subject_request WHERE id NOT IN (1, 5) ORDER BY org_id, id"));
            assertEquals(List.of("[2]"), query(statement, """
                SELECT COUNT(*) FROM data_subject_request WHERE id IN (1, 5) AND received_at <= UTC_TIMESTAMP(6)
                    AND (responded_at IS NULL OR received_at <= responded_at)
                """));
            statement.execute("COMMIT");
            List<String> repaired = query(statement, "SELECT * FROM data_subject_request ORDER BY org_id, id");
            executeSqlBlock(statement, 32400, 1);
            Map<String, String> rerun = executeSqlBlock(statement, 32400, 2);
            assertEquals("1", rerun.get("dsr_safe_to_shift"));
            assertEquals("0", rerun.get("changed_dsr_rows"));
            assertEquals("0", rerun.get("dsr_mismatches"));
            assertEquals("0", rerun.get("dsr_remaining_hazards"));
            assertEquals(repaired, query(statement, "SELECT * FROM data_subject_request ORDER BY org_id, id"));
            statement.execute("COMMIT");
            assertEquals(1, statement.executeUpdate("""
                UPDATE data_subject_request SET status = 'closed', closed_at = UTC_TIMESTAMP()
                WHERE id = 5 AND received_at <= UTC_TIMESTAMP()
                    AND (responded_at IS NULL OR responded_at <= UTC_TIMESTAMP())
                """));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong_org", "stale_time", "terminal", "refused", "already_closed", "outside_offset"})
    void dsrRepairRejectsUnverifiedOrIneligibleApprovals(String fault) throws Exception {
        withFixture(statement -> {
            executeSqlBlock(statement, 32400, 1);
            statement.executeUpdate("""
                INSERT INTO utc_cutover_dsr_review
                SELECT org_id, id, received_at, id <> 2 FROM data_subject_request WHERE id IN (1, 2, 5)
                """);
            switch (fault) {
                case "wrong_org" -> statement.executeUpdate("UPDATE utc_cutover_dsr_review SET org_id = 999 WHERE id = 1");
                case "stale_time" -> statement.executeUpdate("""
                    UPDATE utc_cutover_dsr_review SET reviewed_received_at = TIMESTAMPADD(SECOND, -1, reviewed_received_at)
                    WHERE id = 1
                    """);
                case "terminal" -> statement.executeUpdate("""
                    INSERT INTO utc_cutover_dsr_review SELECT org_id, id, received_at, TRUE
                    FROM data_subject_request WHERE id = 4
                    """);
                case "refused" -> statement.executeUpdate("""
                    INSERT INTO utc_cutover_dsr_review SELECT org_id, id, received_at, TRUE
                    FROM data_subject_request WHERE id = 6
                    """);
                case "already_closed" -> statement.executeUpdate("""
                    INSERT INTO utc_cutover_dsr_review SELECT org_id, id, received_at, TRUE
                    FROM data_subject_request WHERE id = 7
                    """);
                case "outside_offset" -> {
                    statement.executeUpdate("UPDATE data_subject_request SET received_at = TIMESTAMPADD(HOUR, 10, @fixture_now) WHERE id = 1");
                    statement.executeUpdate("""
                        UPDATE utc_cutover_dsr_review SET reviewed_received_at = TIMESTAMPADD(HOUR, 10, @fixture_now) WHERE id = 1
                        """);
                }
                default -> throw new IllegalArgumentException(fault);
            }
            List<String> before = query(statement, "SELECT * FROM data_subject_request ORDER BY org_id, id");
            Map<String, String> result = executeSqlBlock(statement, 32400, 2);
            assertEquals("0", result.get("dsr_safe_to_shift"));
            assertEquals("0", result.get("changed_dsr_rows"));
            assertEquals(before, query(statement, "SELECT * FROM data_subject_request ORDER BY org_id, id"));
            statement.execute("ROLLBACK");
        });
    }

    private static int scheduleManualRetry(Statement statement) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        String resource = "mappers/WorkflowRunMapper.xml";
        try (InputStream input = UtcCutoverRunbookTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameters = Map.of("workspaceId", 7, "id", 7L, "expectedNodeId", "action");
        BoundSql sql = configuration.getMappedStatement(
            "ooo.klae.connex.backend.mappers.WorkflowRunMapper.scheduleManualRetry").getBoundSql(parameters);
        try (var retry = statement.getConnection().prepareStatement(sql.getSql())) {
            for (int index = 0; index < sql.getParameterMappings().size(); index++) {
                retry.setObject(index + 1, parameters.get(sql.getParameterMappings().get(index).getProperty()));
            }
            return retry.executeUpdate();
        }
    }

    private static void assertCleanPostflight(Map<String, String> result) {
        for (String count : List.of("run_mismatches", "step_mismatches", "attempt_mismatches",
                "invocation_mismatches", "prepared_mismatches", "remaining_hazards")) {
            assertEquals("0", result.get(count), count);
        }
    }

    private static Map<String, String> executeRunbook(Statement statement, int offset) throws Exception {
        return executeSqlBlock(statement, offset, 0);
    }

    private static Map<String, String> executeSqlBlock(Statement statement, int offset, int block) throws Exception {
        String document = Files.readString(Path.of("../docs/backend/UTC_CUTOVER.md"));
        String[] blocks = document.split("```sql\n", -1);
        assertTrue(block + 1 < blocks.length, "The documented SQL block must exist");
        int end = blocks[block + 1].indexOf("```");
        assertTrue(end >= 0);
        String sql = blocks[block + 1].substring(0, end)
            .replace("SET @utc_cutover_offset_seconds = 32400;",
                "SET @utc_cutover_offset_seconds = " + offset + ";");
        Map<String, String> observations = new HashMap<>();
        for (String command : sql.split(";")) {
            if (!command.isBlank() && statement.execute(command)) {
                try (ResultSet rows = statement.getResultSet()) {
                    while (rows.next()) {
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            String label = rows.getMetaData().getColumnLabel(column);
                            observations.put(label, rows.getString(column));
                            if (label.equals("status")) {
                                observations.put("preview_status_" + rows.getInt("org_id") + "_" + rows.getLong("id"),
                                    rows.getString(column));
                            }
                        }
                    }
                }
            }
        }
        return observations;
    }

    private static List<String> preserved(Statement statement) throws SQLException {
        List<String> rows = query(statement, """
            SELECT id, status, updated_at, resume_at, lease_until, current_node_id,
                wait_kind, failure_node_id, failure_code, failure_message
            FROM workflow_run ORDER BY id
            """);
        rows.addAll(query(statement, "SELECT * FROM workflow_run WHERE id IN (3, 5, 6) ORDER BY id"));
        rows.addAll(query(statement, "SELECT started_at, finished_at FROM workflow_run WHERE id = 2"));
        rows.addAll(query(statement, "SELECT id, status, workflow_run_id FROM workflow_step_run ORDER BY id"));
        rows.addAll(query(statement, "SELECT * FROM workflow_step_run WHERE id = 5"));
        rows.addAll(query(statement, "SELECT id, status, workflow_step_run_id FROM workflow_step_attempt ORDER BY id"));
        rows.addAll(query(statement, "SELECT * FROM workflow_step_attempt WHERE id = 4"));
        rows.addAll(query(statement, "SELECT id, confirmation_key, updated_at FROM workflow_invocation ORDER BY id"));
        rows.addAll(query(statement, "SELECT * FROM workflow_invocation WHERE id IN (5, 6) ORDER BY id"));
        rows.addAll(query(statement, "SELECT id, created_at, expires_at FROM workflow_invocation WHERE id IN (4, 7) ORDER BY id"));
        return rows;
    }

    private static List<String> fingerprint(Statement statement) throws SQLException {
        List<String> rows = query(statement, "SELECT * FROM workflow_run ORDER BY id");
        rows.addAll(query(statement, "SELECT * FROM workflow_step_run ORDER BY id"));
        rows.addAll(query(statement, "SELECT * FROM workflow_step_attempt ORDER BY id"));
        rows.addAll(query(statement, "SELECT * FROM workflow_invocation ORDER BY id"));
        return rows;
    }

    private static List<String> query(Statement statement, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    values.add(rows.getString(column));
                }
                result.add(values.toString());
            }
        }
        return result;
    }

    private static void withFixture(FixtureCheck check) throws Exception {
        try (Connection connection = DriverManager.getConnection(catalog.url(), username, password);
                Statement statement = connection.createStatement()) {
            createFixture(statement);
            check.run(statement);
        }
    }

    private static void createTables(Statement statement) throws SQLException {
        statement.execute("""
            CREATE TABLE workflow_run (
                workspace_id INT NOT NULL, id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL,
                started_at DATETIME(6) NOT NULL, cancel_requested_at DATETIME(6), finished_at DATETIME(6),
                updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                resume_at DATETIME(6), lease_until DATETIME(6), current_node_id VARCHAR(64) DEFAULT 'action',
                wait_kind VARCHAR(32), failure_node_id VARCHAR(64), failure_code VARCHAR(64), failure_message TEXT,
                CHECK (finished_at IS NULL OR finished_at >= started_at),
                CHECK ((status IN ('queued', 'running', 'waiting')) = (finished_at IS NULL))
            ) ENGINE=InnoDB
            """);
        statement.execute("""
            CREATE TABLE workflow_step_run (
                workspace_id INT NOT NULL, id BIGINT PRIMARY KEY, workflow_run_id BIGINT NOT NULL,
                status VARCHAR(32) NOT NULL, started_at DATETIME(6) NOT NULL, finished_at DATETIME(6),
                CHECK (finished_at IS NULL OR finished_at >= started_at),
                CHECK ((status IN ('queued', 'running', 'waiting')) = (finished_at IS NULL))
            ) ENGINE=InnoDB
            """);
        statement.execute("""
            CREATE TABLE workflow_step_attempt (
                workspace_id INT NOT NULL, id BIGINT PRIMARY KEY, workflow_step_run_id BIGINT NOT NULL,
                status VARCHAR(32) NOT NULL, started_at DATETIME(6) NOT NULL, finished_at DATETIME(6),
                CHECK ((status = 'running') = (finished_at IS NULL))
            ) ENGINE=InnoDB
            """);
        statement.execute("""
            CREATE TABLE workflow_invocation (
                workspace_id INT NOT NULL, id BIGINT PRIMARY KEY, status VARCHAR(16) NOT NULL,
                confirmation_key BINARY(16), created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
                confirmed_at DATETIME(6), completed_at DATETIME(6),
                updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                CHECK (expires_at >= created_at
                    AND (confirmed_at IS NULL OR confirmed_at >= created_at)
                    AND (completed_at IS NULL OR completed_at >= created_at)),
                CHECK ((status = 'prepared' AND confirmation_key IS NULL AND confirmed_at IS NULL AND completed_at IS NULL)
                    OR (status IN ('confirmed', 'running') AND confirmation_key IS NOT NULL
                        AND confirmed_at IS NOT NULL AND completed_at IS NULL)
                    OR (status IN ('succeeded', 'partial', 'failed') AND confirmation_key IS NOT NULL
                        AND confirmed_at IS NOT NULL AND completed_at IS NOT NULL)
                    OR (status = 'cancelled' AND completed_at IS NOT NULL
                        AND ((confirmation_key IS NULL AND confirmed_at IS NULL)
                            OR (confirmation_key IS NOT NULL AND confirmed_at IS NOT NULL)))
                    OR (status = 'expired' AND confirmation_key IS NULL AND confirmed_at IS NULL AND completed_at IS NOT NULL))
            ) ENGINE=InnoDB
            """);
        statement.execute("""
            CREATE TABLE data_subject_request (
                org_id INT NOT NULL, id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL,
                received_at DATETIME NOT NULL, identity_verified_at DATETIME, due_at DATETIME,
                responded_at DATETIME, closed_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB
            """);
    }

    private static void createFixture(Statement statement) throws SQLException {
        statement.execute("SET time_zone = '+00:00'");
        statement.execute("SET @fixture_now = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))");
        statement.execute("START TRANSACTION");
        statement.executeUpdate("DELETE FROM data_subject_request");
        statement.executeUpdate("DELETE FROM workflow_invocation");
        statement.executeUpdate("DELETE FROM workflow_step_attempt");
        statement.executeUpdate("DELETE FROM workflow_step_run");
        statement.executeUpdate("DELETE FROM workflow_run");
        statement.executeUpdate("""
            INSERT INTO workflow_run (workspace_id, id, status, started_at, finished_at) VALUES
                (7, 1, 'queued', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL),
                (7, 2, 'running', TIMESTAMPADD(HOUR, -1, @fixture_now), NULL),
                (7, 3, 'waiting', TIMESTAMPADD(DAY, -1, @fixture_now), NULL),
                (7, 4, 'succeeded', TIMESTAMPADD(HOUR, 8, @fixture_now), TIMESTAMPADD(HOUR, 9, @fixture_now)),
                (7, 5, 'running', @fixture_now, NULL),
                (7, 6, 'failed', TIMESTAMPADD(DAY, -1, @fixture_now), @fixture_now),
                (7, 7, 'intervention_required', TIMESTAMPADD(HOUR, 8, @fixture_now), TIMESTAMPADD(HOUR, 9, @fixture_now)),
                (8, 9, 'queued', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL)
            """);
        statement.executeUpdate("""
            UPDATE workflow_run SET cancel_requested_at = TIMESTAMPADD(HOUR, 9, @fixture_now) WHERE id = 2
            """);
        statement.executeUpdate("""
            UPDATE workflow_run SET failure_node_id = 'action', failure_code = 'provider_failure',
                failure_message = 'retryable failure' WHERE id = 7
            """);
        statement.executeUpdate("""
            UPDATE workflow_run SET updated_at = TIMESTAMPADD(DAY, -1, @fixture_now),
                resume_at = CASE WHEN status = 'waiting' THEN TIMESTAMPADD(HOUR, 11, @fixture_now) END,
                lease_until = CASE WHEN status = 'running' THEN TIMESTAMPADD(HOUR, 12, @fixture_now) END
            """);
        statement.executeUpdate("""
            INSERT INTO workflow_step_run VALUES
                (7, 1, 1, 'running', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL),
                (7, 2, 2, 'waiting', TIMESTAMPADD(HOUR, 8, @fixture_now), NULL),
                (7, 3, 4, 'running', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL),
                (7, 4, 1, 'succeeded', TIMESTAMPADD(HOUR, 8, @fixture_now), TIMESTAMPADD(HOUR, 9, @fixture_now)),
                (7, 5, 3, 'queued', TIMESTAMPADD(DAY, -1, @fixture_now), NULL),
                (7, 6, 7, 'running', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL),
                (8, 9, 9, 'queued', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL)
            """);
        statement.executeUpdate("""
            INSERT INTO workflow_step_attempt VALUES
                (7, 1, 1, 'running', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL),
                (7, 2, 4, 'succeeded', TIMESTAMPADD(HOUR, 8, @fixture_now), TIMESTAMPADD(HOUR, 9, @fixture_now)),
                (7, 3, 6, 'failed', TIMESTAMPADD(HOUR, 8, @fixture_now), TIMESTAMPADD(HOUR, 9, @fixture_now)),
                (7, 4, 5, 'failed', TIMESTAMPADD(HOUR, -1, @fixture_now), @fixture_now)
            """);
        statement.executeUpdate("""
            INSERT INTO workflow_invocation (workspace_id, id, status, confirmation_key, created_at, expires_at,
                confirmed_at, completed_at) VALUES
                (7, 1, 'prepared', NULL, TIMESTAMPADD(SECOND, 32280, @fixture_now),
                    TIMESTAMPADD(SECOND, 33180, @fixture_now), NULL, NULL),
                (7, 2, 'confirmed', REPEAT('a', 16), TIMESTAMPADD(SECOND, 32280, @fixture_now),
                    TIMESTAMPADD(SECOND, 33180, @fixture_now), TIMESTAMPADD(SECOND, 32340, @fixture_now), NULL),
                (7, 3, 'running', REPEAT('a', 16), TIMESTAMPADD(SECOND, 32220, @fixture_now),
                    TIMESTAMPADD(SECOND, 33120, @fixture_now), TIMESTAMPADD(SECOND, 32340, @fixture_now), NULL),
                (7, 4, 'prepared', NULL, TIMESTAMPADD(MINUTE, -5, @fixture_now),
                    TIMESTAMPADD(MINUTE, 10, @fixture_now), NULL, NULL),
                (7, 5, 'confirmed', REPEAT('a', 16), TIMESTAMPADD(MINUTE, -5, @fixture_now),
                    TIMESTAMPADD(MINUTE, 10, @fixture_now), TIMESTAMPADD(MINUTE, -4, @fixture_now), NULL),
                (7, 6, 'running', REPEAT('a', 16), TIMESTAMPADD(MINUTE, -5, @fixture_now),
                    TIMESTAMPADD(MINUTE, 10, @fixture_now), TIMESTAMPADD(MINUTE, -4, @fixture_now), NULL),
                (7, 7, 'prepared', NULL, TIMESTAMPADD(MINUTE, -60, @fixture_now),
                    TIMESTAMPADD(MINUTE, -45, @fixture_now), NULL, NULL),
                (8, 8, 'confirmed', REPEAT('a', 16), TIMESTAMPADD(SECOND, 32280, @fixture_now),
                    TIMESTAMPADD(SECOND, 33180, @fixture_now), TIMESTAMPADD(SECOND, 32340, @fixture_now), NULL)
            """);
        List<String> terminalStatuses = List.of("succeeded", "partial", "failed", "cancelled", "expired");
        for (int index = 0; index < terminalStatuses.size(); index++) {
            String status = terminalStatuses.get(index);
            try (var insert = statement.getConnection().prepareStatement("""
                    INSERT INTO workflow_invocation (workspace_id, id, status, confirmation_key, created_at, expires_at,
                        confirmed_at, completed_at, updated_at) VALUES (7, ?, ?, ?, TIMESTAMPADD(HOUR, 8, @fixture_now),
                        TIMESTAMPADD(HOUR, 9, @fixture_now), CASE WHEN ? THEN NULL ELSE TIMESTAMPADD(HOUR, 8, @fixture_now) END,
                        TIMESTAMPADD(HOUR, 9, @fixture_now), @fixture_now)
                    """)) {
                insert.setInt(1, 10 + index);
                insert.setString(2, status);
                insert.setBytes(3, status.equals("expired") ? null : new byte[16]);
                insert.setBoolean(4, status.equals("expired"));
                insert.executeUpdate();
            }
        }
        statement.executeUpdate("""
            INSERT INTO data_subject_request (org_id, id, status, received_at, closed_at, updated_at) VALUES
                (7, 1, 'in_progress', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL, TIMESTAMPADD(DAY, -1, @fixture_now)),
                (8, 2, 'received', TIMESTAMPADD(HOUR, 12, @fixture_now), NULL, @fixture_now),
                (7, 3, 'verifying', TIMESTAMPADD(HOUR, -1, @fixture_now), NULL, @fixture_now),
                (7, 4, 'closed', TIMESTAMPADD(HOUR, 9, @fixture_now), TIMESTAMPADD(HOUR, 10, @fixture_now), @fixture_now),
                (7, 5, 'responded', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL, @fixture_now),
                (7, 6, 'refused', TIMESTAMPADD(HOUR, 9, @fixture_now), NULL, @fixture_now),
                (7, 7, 'responded', TIMESTAMPADD(HOUR, 9, @fixture_now), TIMESTAMPADD(HOUR, 10, @fixture_now), @fixture_now)
            """);
        statement.execute("COMMIT");
    }

    @FunctionalInterface
    private interface FixtureCheck {
        void run(Statement statement) throws Exception;
    }
}
