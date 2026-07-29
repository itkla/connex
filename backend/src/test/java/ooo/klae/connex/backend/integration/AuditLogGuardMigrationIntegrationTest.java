package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

/** Verifies the audit append-only guard across representative real-MySQL upgrades. */
class AuditLogGuardMigrationIntegrationTest {
    private static String configuredUrl;
    private static String bootstrapUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void configureDatabase() {
        configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping audit migration integration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
    }

    @Test
    void emptyAndV1CatalogsMigrateToExactFinalGuards() throws SQLException {
        try (ScratchCatalog empty = ScratchCatalog.create()) {
            flyway(empty.url(), null).migrate();
            seedAuditRow(empty.url());
            assertFinalState(empty.url());
        }
        try (ScratchCatalog v1 = ScratchCatalog.create()) {
            flyway(v1.url(), "1").migrate();
            flyway(v1.url(), null).migrate();
            seedAuditRow(v1.url());
            assertFinalState(v1.url());
        }
    }

    @Test
    void deployedV126CatalogRepairsMissingFinalGuardsBeforeV127() throws SQLException {
        try (ScratchCatalog catalog = ScratchCatalog.create()) {
            flyway(catalog.url(), "126").migrate();
            execute(catalog.url(), "DROP TRIGGER trg_audit_log_no_update_v127");
            execute(catalog.url(), "DROP TRIGGER trg_audit_log_no_delete");

            flyway(catalog.url(), null).migrate();

            seedAuditRow(catalog.url());
            assertFinalState(catalog.url());
        }
    }

    @Test
    void v124UpgradeRepairsMissingLegacyGuardAndProtectsEveryV126Boundary()
            throws SQLException {
        try (ScratchCatalog catalog = ScratchCatalog.create()) {
            flyway(catalog.url(), "124").migrate();
            seedAuditRow(catalog.url());
            execute(catalog.url(), "DROP TRIGGER trg_audit_log_no_update");
            BoundaryProbe probe = new BoundaryProbe(catalog.url());

            flyway(catalog.url(), null, probe).migrate();

            assertEquals(11, probe.checkedStatements());
            assertEquals(11, probe.verifiedGuards());
            assertTrue(probe.nonceCleared());
            assertEquals(
                List.of(
                    "45000:1644",
                    "45000:1644",
                    "45000:1644",
                    "45000:1644",
                    "45000:1644",
                    "45000:1644",
                    "45000:1644",
                    "40001:1205",
                    "45000:1644",
                    "45000:1644",
                    "45000:1644"),
                probe.outcomes());
            assertEquals(11, probe.deleteOutcomes().size());
            assertTrue(probe.deleteOutcomes().stream().allMatch(
                outcome -> outcome.equals("45000:1644") || outcome.equals("40001:1205")));
            try (Connection connection = connection(catalog.url())) {
                assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM audit_log
                    WHERE action = 'migration.guard.test'
                      AND integrity_workspace_id IS NULL
                      AND integrity_org_id IS NULL
                      AND integrity_actor_id IS NULL
                      AND integrity_reference_state = 'captured'
                    """));
            }
            assertFinalState(catalog.url());
        }
    }

    @Test
    void malformedLegacyDefinitionStopsBeforeV126() throws SQLException {
        try (ScratchCatalog catalog = ScratchCatalog.create()) {
            flyway(catalog.url(), "124").migrate();
            seedAuditRow(catalog.url());
            execute(catalog.url(), "DROP TRIGGER trg_audit_log_no_update");
            execute(catalog.url(), """
                CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
                FOR EACH ROW SET NEW.summary = NEW.summary
                """);

            assertThrows(FlywayException.class, () -> flyway(catalog.url(), null).migrate());

            try (Connection connection = connection(catalog.url())) {
                assertEquals(0, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'organization'
                      AND COLUMN_NAME = 'lifecycle_state'
                    """));
                SQLException refusal = assertThrows(
                    SQLException.class,
                    () -> execute(connection, "UPDATE audit_log SET summary = summary"));
                assertEquals("45000", refusal.getSQLState());
            }
        }
    }

    @Test
    void malformedDeleteDefinitionInstallsAStrictRepairBeforeV126Stops()
            throws SQLException {
        try (ScratchCatalog catalog = ScratchCatalog.create()) {
            flyway(catalog.url(), "124").migrate();
            seedAuditRow(catalog.url());
            execute(catalog.url(), "DROP TRIGGER trg_audit_log_no_delete");
            execute(catalog.url(), """
                CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
                FOR EACH ROW SET @connex_audit_delete_probe = 1
                """);

            assertThrows(FlywayException.class, () -> flyway(catalog.url(), null).migrate());

            try (Connection connection = connection(catalog.url())) {
                assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.TRIGGERS
                    WHERE TRIGGER_SCHEMA = DATABASE()
                      AND TRIGGER_NAME = 'trg_audit_log_no_delete_repair'
                      AND EVENT_OBJECT_SCHEMA = DATABASE()
                      AND EVENT_OBJECT_TABLE = 'audit_log'
                      AND ACTION_TIMING = 'BEFORE'
                      AND EVENT_MANIPULATION = 'DELETE'
                      AND ACTION_ORIENTATION = 'ROW'
                      AND ACTION_CONDITION IS NULL
                      AND ACTION_STATEMENT =
                        'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''audit_log is append-only'''
                    """));
                SQLException refusal = assertThrows(
                    SQLException.class,
                    () -> execute(connection, "DELETE FROM audit_log"));
                assertEquals("45000", refusal.getSQLState());
            }
        }
    }

    @Test
    void failedV126ClearsTrustedStateAndLeavesTheTemporaryGuardEffective()
            throws SQLException {
        try (ScratchCatalog catalog = ScratchCatalog.create()) {
            flyway(catalog.url(), "124").migrate();
            seedAuditRow(catalog.url());
            execute(catalog.url(), """
                CREATE TRIGGER trg_audit_log_integrity_snapshot BEFORE INSERT ON audit_log
                FOR EACH ROW SET NEW.summary = NEW.summary
                """);
            FailureProbe probe = new FailureProbe();

            assertThrows(
                FlywayException.class,
                () -> flyway(catalog.url(), null, probe).migrate());

            assertTrue(probe.nonceCleared());
            try (Connection connection = connection(catalog.url())) {
                assertEquals(0, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.TRIGGERS
                    WHERE TRIGGER_SCHEMA = DATABASE()
                      AND TRIGGER_NAME = 'trg_audit_log_no_update'
                    """));
                assertEquals(1, scalar(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.TRIGGERS
                    WHERE TRIGGER_SCHEMA = DATABASE()
                      AND TRIGGER_NAME = 'trg_audit_log_v126_migration_guard'
                      AND EVENT_OBJECT_SCHEMA = DATABASE()
                      AND EVENT_OBJECT_TABLE = 'audit_log'
                      AND ACTION_TIMING = 'BEFORE'
                      AND EVENT_MANIPULATION = 'UPDATE'
                      AND ACTION_ORIENTATION = 'ROW'
                      AND ACTION_CONDITION IS NULL
                    """));
                SQLException refusal = assertThrows(
                    SQLException.class,
                    () -> execute(connection, "UPDATE audit_log SET summary = summary"));
                assertEquals("45000", refusal.getSQLState());
            }
        }
    }

    private static Flyway flyway(String url, String target, Callback... additionalCallbacks) {
        List<Callback> callbacks = new ArrayList<>();
        callbacks.add(new AuditLogV126MigrationCallback());
        callbacks.addAll(List.of(additionalCallbacks));
        var configuration = Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .callbacks(callbacks.toArray(Callback[]::new))
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"));
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private static void seedAuditRow(String url) throws SQLException {
        execute(url, """
            INSERT INTO audit_log (
                action, entity_type, summary, chain_scope_type, chain_scope_id,
                chain_index, prev_hash, row_hash)
            VALUES (
                'migration.guard.test', 'system', 'before V126', 'system', 0,
                900000001, REPEAT('0', 64), REPEAT('1', 64))
            """);
    }

    private static void assertFinalState(String url) throws SQLException {
        try (Connection connection = connection(url)) {
            assertEquals(3, scalar(connection, """
                SELECT COUNT(*)
                FROM information_schema.TRIGGERS
                WHERE TRIGGER_SCHEMA = DATABASE()
                  AND EVENT_OBJECT_SCHEMA = DATABASE()
                  AND EVENT_OBJECT_TABLE = 'audit_log'
                  AND TRIGGER_NAME IN (
                    'trg_audit_log_no_update',
                    'trg_audit_log_no_update_v127',
                    'trg_audit_log_no_delete')
                  AND ACTION_TIMING = 'BEFORE'
                  AND (
                    (
                      TRIGGER_NAME IN (
                        'trg_audit_log_no_update',
                        'trg_audit_log_no_update_v127')
                      AND EVENT_MANIPULATION = 'UPDATE'
                    )
                    OR (
                      TRIGGER_NAME = 'trg_audit_log_no_delete'
                      AND EVENT_MANIPULATION = 'DELETE'
                    )
                  )
                  AND ACTION_ORIENTATION = 'ROW'
                  AND ACTION_CONDITION IS NULL
                  AND ACTION_STATEMENT =
                    'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''audit_log is append-only'''
                """));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM information_schema.TRIGGERS
                WHERE TRIGGER_SCHEMA = DATABASE()
                  AND TRIGGER_NAME = 'trg_audit_log_v126_migration_guard'
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(DISTINCT INDEX_NAME)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'data_subject_request'
                  AND INDEX_NAME = 'idx_data_subject_request_org_subject_workspace'
                """));
            assertEquals(4, scalar(connection, """
                SELECT capacity
                FROM tenant_export_admission_control
                WHERE id = 1
                """));
            assertThrows(
                SQLException.class,
                () -> execute(
                    connection,
                    "UPDATE tenant_export_admission_control SET capacity = 0 WHERE id = 1"));
            assertThrows(
                SQLException.class,
                () -> execute(
                    connection,
                    "UPDATE tenant_export_admission_control SET capacity = 5 WHERE id = 1"));
            assertEquals(4, scalar(connection, """
                SELECT capacity
                FROM tenant_export_admission_control
                WHERE id = 1
                """));
            SQLException refusal = assertThrows(
                SQLException.class,
                () -> execute(connection, "UPDATE audit_log SET summary = summary"));
            assertEquals("45000", refusal.getSQLState());
            SQLException deleteRefusal = assertThrows(
                SQLException.class,
                () -> execute(connection, "DELETE FROM audit_log"));
            assertEquals("45000", deleteRefusal.getSQLState());
        }
    }

    private static Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection connection = connection(url)) {
            execute(connection, sql);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String withCatalog(String jdbcUrl, String catalog) {
        int authorityEnd = jdbcUrl.indexOf('/', "jdbc:mysql://".length());
        if (authorityEnd < 0) {
            throw new IllegalArgumentException("CONNEX_DB_URL must include a database path");
        }
        int queryStart = jdbcUrl.indexOf('?', authorityEnd);
        String suffix = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return jdbcUrl.substring(0, authorityEnd + 1) + catalog + suffix;
    }

    private static String sqlOutcome(Connection connection, String sql) {
        try {
            execute(connection, sql);
            return "allowed";
        } catch (SQLException exception) {
            return exception.getSQLState() + ":" + exception.getErrorCode();
        }
    }

    private static final class BoundaryProbe implements Callback {
        private final String url;
        private final List<String> outcomes = new ArrayList<>();
        private final List<String> deleteOutcomes = new ArrayList<>();
        private int checkedStatements;
        private int verifiedGuards;
        private boolean nonceCleared;

        private BoundaryProbe(String url) {
            this.url = url;
        }

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.AFTER_EACH_MIGRATE_STATEMENT
                || event == Event.AFTER_EACH_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return false;
        }

        @Override
        public void handle(Event event, Context context) {
            if (!isV126(context)) {
                return;
            }
            if (event == Event.AFTER_EACH_MIGRATE) {
                try (Statement statement = context.getConnection().createStatement();
                        ResultSet result = statement.executeQuery(
                            "SELECT @connex_audit_v126_nonce IS NULL")) {
                    result.next();
                    nonceCleared = result.getBoolean(1);
                    return;
                } catch (SQLException exception) {
                    throw new FlywayException(exception);
                }
            }
            checkedStatements++;
            try (Connection second = connection(url)) {
                if (scalar(second, """
                        SELECT COUNT(*)
                        FROM information_schema.TRIGGERS
                        WHERE TRIGGER_SCHEMA = DATABASE()
                          AND TRIGGER_NAME = 'trg_audit_log_v126_migration_guard'
                          AND EVENT_OBJECT_SCHEMA = DATABASE()
                          AND EVENT_OBJECT_TABLE = 'audit_log'
                          AND ACTION_TIMING = 'BEFORE'
                          AND EVENT_MANIPULATION = 'UPDATE'
                          AND ACTION_ORIENTATION = 'ROW'
                          AND ACTION_CONDITION IS NULL
                          AND ACTION_STATEMENT LIKE
                            'BEGIN IF CONNECTION_ID() <> %'
                          AND ACTION_STATEMENT LIKE
                            '%OR @connex_audit_v126_nonce IS NULL%'
                          AND ACTION_STATEMENT LIKE
                            '%OR BINARY @connex_audit_v126_nonce <> X''%''%'
                          AND ACTION_STATEMENT LIKE
                            '%SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''audit_log is append-only''%'
                        """) != 1) {
                    outcomes.add("invalid-guard");
                    return;
                }
                if (scalar(second, """
                        SELECT COUNT(*)
                        FROM information_schema.TRIGGERS
                        WHERE TRIGGER_SCHEMA = DATABASE()
                          AND TRIGGER_NAME = 'trg_audit_log_no_delete'
                          AND EVENT_OBJECT_SCHEMA = DATABASE()
                          AND EVENT_OBJECT_TABLE = 'audit_log'
                          AND ACTION_TIMING = 'BEFORE'
                          AND EVENT_MANIPULATION = 'DELETE'
                          AND ACTION_ORIENTATION = 'ROW'
                          AND ACTION_CONDITION IS NULL
                          AND ACTION_STATEMENT =
                            'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''audit_log is append-only'''
                        """) != 1) {
                    outcomes.add("invalid-delete-guard");
                    return;
                }
                verifiedGuards++;
                execute(second, "SET SESSION innodb_lock_wait_timeout = 1");
                outcomes.add(sqlOutcome(second, "UPDATE audit_log SET summary = summary"));
                deleteOutcomes.add(sqlOutcome(second, "DELETE FROM audit_log"));
            } catch (SQLException exception) {
                outcomes.add(exception.getSQLState() + ":" + exception.getErrorCode());
            }
        }

        @Override
        public String getCallbackName() {
            return "audit-log-v126-post-guard-boundary-probe";
        }

        private int checkedStatements() {
            return checkedStatements;
        }

        private int verifiedGuards() {
            return verifiedGuards;
        }

        private boolean nonceCleared() {
            return nonceCleared;
        }

        private List<String> outcomes() {
            return List.copyOf(outcomes);
        }

        private List<String> deleteOutcomes() {
            return List.copyOf(deleteOutcomes);
        }
    }

    private static final class FailureProbe implements Callback {
        private boolean nonceCleared;

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.AFTER_EACH_MIGRATE_ERROR;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return false;
        }

        @Override
        public void handle(Event event, Context context) {
            if (!isV126(context)) {
                return;
            }
            try (Statement statement = context.getConnection().createStatement();
                    ResultSet result = statement.executeQuery(
                        "SELECT @connex_audit_v126_nonce IS NULL")) {
                result.next();
                nonceCleared = result.getBoolean(1);
            } catch (SQLException exception) {
                throw new FlywayException(exception);
            }
        }

        @Override
        public String getCallbackName() {
            return "audit-log-v126-post-guard-failure-probe";
        }

        private boolean nonceCleared() {
            return nonceCleared;
        }
    }

    private static boolean isV126(Context context) {
        return context != null
            && context.getMigrationInfo() != null
            && context.getMigrationInfo().getVersion() != null
            && "126".equals(context.getMigrationInfo().getVersion().getVersion());
    }

    private record ScratchCatalog(String name, String url) implements AutoCloseable {
        private static ScratchCatalog create() throws SQLException {
            String name = "connex_audit_guard_it_"
                + UUID.randomUUID().toString().replace("-", "");
            try (Connection connection = DriverManager.getConnection(
                    bootstrapUrl,
                    username,
                    password);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            }
            return new ScratchCatalog(name, withCatalog(configuredUrl, name));
        }

        @Override
        public void close() throws SQLException {
            try (Connection connection = DriverManager.getConnection(
                    bootstrapUrl,
                    username,
                    password);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}
