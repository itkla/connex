package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        catalog = MySqlScratchCatalog.create(url, username, password, "connex_2168_c_cutover_", "utf8mb4", null);
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
    void repairsOnlyActiveFutureEventsPreservesDeadlinesAndHistoryAndIsIdempotent() throws Exception {
        withFixture(statement -> {
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE workflow_run SET status = 'succeeded', finished_at = UTC_TIMESTAMP(6) WHERE id = 1
                """));
            List<String> preserved = preserved(statement);
            Map<String, String> result = executeRunbook(statement, 32400);
            assertEquals("1", result.get("safe_to_shift"));
            assertEquals("3", result.get("expected_run_rows"));
            assertEquals("2", result.get("run_start_values"));
            assertEquals("1", result.get("cancellation_values"));
            assertEquals("3", result.get("changed_run_rows"));
            assertEquals("3", result.get("changed_step_rows"));
            assertEquals("3", result.get("expected_step_rows"));
            assertCleanPostflight(result);
            assertEquals(preserved, preserved(statement));
            List<String> repaired = fingerprint(statement);
            statement.execute("COMMIT");

            Map<String, String> rerun = executeRunbook(statement, 32400);
            assertEquals("1", rerun.get("safe_to_shift"));
            assertEquals("0", rerun.get("changed_run_rows"));
            assertEquals("0", rerun.get("changed_step_rows"));
            assertCleanPostflight(rerun);
            assertEquals(repaired, fingerprint(statement));
            statement.execute("COMMIT");
            assertEquals(1, statement.executeUpdate("""
                UPDATE workflow_run SET status = 'succeeded', finished_at = UTC_TIMESTAMP(6) WHERE id = 1
                """));
            assertEquals(1, statement.executeUpdate("""
                UPDATE workflow_step_run SET status = 'succeeded', finished_at = UTC_TIMESTAMP(6) WHERE id = 1
                """));
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
            assertEquals(before, fingerprint(statement));
            statement.execute("ROLLBACK");
        });
    }

    private static void assertCleanPostflight(Map<String, String> result) {
        for (String count : List.of("run_mismatches", "step_mismatches", "remaining_future_rows")) {
            assertEquals("0", result.get(count), count);
        }
    }

    private static Map<String, String> executeRunbook(Statement statement, int offset) throws Exception {
        String document = Files.readString(Path.of("../docs/backend/UTC_CUTOVER.md"));
        int start = document.indexOf("```sql\n");
        assertTrue(start >= 0, "The documented SQL must exist");
        int end = document.indexOf("```", start + 7);
        assertTrue(end > start);
        String sql = document.substring(start + 7, end)
            .replace("SET @utc_cutover_offset_seconds = 32400;",
                "SET @utc_cutover_offset_seconds = " + offset + ";");
        Map<String, String> observations = new HashMap<>();
        for (String command : sql.split(";")) {
            if (!command.isBlank() && statement.execute(command)) {
                try (ResultSet rows = statement.getResultSet()) {
                    assertTrue(rows.next());
                    for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                        observations.put(rows.getMetaData().getColumnLabel(column), rows.getString(column));
                    }
                }
            }
        }
        return observations;
    }

    private static List<String> preserved(Statement statement) throws SQLException {
        List<String> rows = query(statement, """
            SELECT id, status, finished_at, updated_at, resume_at, lease_until
            FROM workflow_run ORDER BY id
            """);
        rows.addAll(query(statement, "SELECT * FROM workflow_run WHERE id IN (3, 4, 5, 6, 7) ORDER BY id"));
        rows.addAll(query(statement, "SELECT * FROM workflow_step_run WHERE id IN (3, 4, 5, 6) ORDER BY id"));
        return rows;
    }

    private static List<String> fingerprint(Statement statement) throws SQLException {
        List<String> rows = query(statement, "SELECT * FROM workflow_run ORDER BY id");
        rows.addAll(query(statement, "SELECT * FROM workflow_step_run ORDER BY id"));
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
                resume_at DATETIME(6), lease_until DATETIME(6),
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
    }

    private static void createFixture(Statement statement) throws SQLException {
        statement.execute("SET time_zone = '+00:00'");
        statement.execute("SET @fixture_now = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))");
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
    }

    @FunctionalInterface
    private interface FixtureCheck {
        void run(Statement statement) throws Exception;
    }
}
