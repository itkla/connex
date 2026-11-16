package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Replays V244 after every committed SQL prefix without rebuilding a full tenant lineage. */
class RecordSearchV244MigrationIntegrationTest {
    private static final String RESOURCE = "db/migration/tenant/V244__record_search_keys.sql";
    private static String configuredUrl;
    private static String username;
    private static String password;

    private record IncompatibleObject(String table, String name, String definition, boolean index) { }
    private record IncompatibleTrigger(String name, String table, String timing, String event, String body) { }

    @BeforeAll
    static void requireDatabase() {
        configuredUrl = System.getenv("CONNEX_DB_URL");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(configuredUrl != null && username != null && password != null, "Database credentials required");
    }

    @ParameterizedTest(name = "replays committed SQL prefix {0}")
    @MethodSource("committedPrefixes")
    void resumesEveryCommittedPrefixThroughFlywayWithoutV244SuccessHistory(int statementCount) throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyTables(catalog);
            migration(catalog).baseline();
            List<String> statements = migrationStatements();
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                for (String sql : statements.subList(0, statementCount)) {
                    statement.execute(sql);
                }
                assertEquals(List.of("0"), rows(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '244'"));
            }
            Flyway flyway = migration(catalog);
            assertEquals(1, flyway.migrate().migrationsExecuted);
            var current = flyway.info().current();
            assertNotNull(current);
            assertEquals(MigrationVersion.fromVersion("244"), current.getVersion());
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (Connection connection = connection(catalog)) {
                assertEquals(List.of("1"), rows(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '244' AND success = 1"));
                assertSchemaAndLegacyBehavior(connection);
            }
        }
    }

    @ParameterizedTest(name = "rejects incompatible {0}")
    @MethodSource("incompatibleObjects")
    void incompatibleCommittedDefinitionsFailClosed(IncompatibleObject object) throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyTables(catalog);
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                if (object.index()) {
                    for (String table : List.of("person", "company")) {
                        statement.execute("ALTER TABLE " + table
                            + " ADD COLUMN search_name VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,"
                            + " ADD COLUMN search_phone VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,"
                            + " ADD COLUMN search_key_revision BIGINT NOT NULL DEFAULT 0, ALGORITHM=INSTANT");
                    }
                    statement.execute("ALTER TABLE " + object.table() + " ADD " + object.definition());
                } else {
                    statement.execute("ALTER TABLE " + object.table() + " ADD COLUMN "
                        + object.name() + " " + object.definition() + ", ALGORITHM=INSTANT");
                }
            }
            Flyway flyway = migration(catalog);
            FlywayException failure = assertThrows(FlywayException.class, flyway::migrate);
            String guard = "V244_incompatible_" + object.table() + "_"
                + (object.index()
                    ? (object.name().endsWith("missing_name") ? "search_missing_name_index" : "search_phone_index")
                    : object.name());
            assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains(guard.toLowerCase(Locale.ROOT)), failure::getMessage);
            try (Connection connection = connection(catalog)) {
                assertEquals(List.of("0"), rows(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '244' AND success = 1"));
                assertLegacySources(connection);
            }
        }
    }

    @Test
    void replayPreservesFilledSearchValuesAndUnrelatedTriggers() throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyTables(catalog);
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                for (String sql : migrationStatements()) {
                    statement.execute(sql);
                }
                for (String table : List.of("person", "company")) {
                    statement.executeUpdate("UPDATE " + table
                        + " SET search_name = 'retained-name', search_phone = '819096846208',"
                        + " search_key_revision = 7, updated_at = updated_at WHERE id = 1");
                }
            }
            assertEquals(1, migration(catalog).migrate().migrationsExecuted);
            try (Connection connection = connection(catalog)) {
                for (String table : List.of("person", "company")) {
                    assertEquals(List.of("retained-name|819096846208|7"), rows(connection,
                        "SELECT CONCAT(search_name, '|', search_phone, '|', search_key_revision) FROM " + table + " WHERE id = 1"));
                }
                assertEquals(List.of("trg_person_preserved"), rows(connection,
                    "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() AND TRIGGER_NAME = 'trg_person_preserved'"));
            }
        }
    }

    @Test
    void everyReplayStatementKeepsLegacyInvalidationLiveForFilledProjections() throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyTables(catalog);
            List<String> statements = migrationStatements();
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
                int step = 0;
                for (String sql : statements) {
                    for (String table : List.of("person", "company")) {
                        statement.executeUpdate("UPDATE " + table
                            + " SET search_name = 'filled-name', search_phone = 'filled-phone',"
                            + " search_key_revision = search_key_revision + 1 WHERE id = 1");
                    }
                    statement.execute(sql);
                    step++;
                    for (String table : List.of("person", "company")) {
                        statement.executeUpdate("UPDATE " + table + " SET name = 'Legacy step " + step
                            + "', phone = '090-0000-" + step + "' WHERE id = 1");
                        assertEquals(List.of("0|0"), rows(connection,
                            "SELECT CONCAT(COUNT(search_name), '|', COUNT(search_phone)) FROM " + table + " WHERE id = 1"),
                            "Invalidation must remain installed after replay statement " + step);
                    }
                }
            }
            assertEquals(1, migration(catalog).migrate().migrationsExecuted);
        }
    }

    @ParameterizedTest(name = "rejects incompatible trigger {0}")
    @MethodSource("incompatibleTriggers")
    void incompatibleInstalledTriggersFailClosedWithoutReplacement(IncompatibleTrigger trigger) throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyTables(catalog);
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER " + trigger.name() + " " + trigger.timing() + " "
                    + trigger.event() + " ON " + trigger.table() + " FOR EACH ROW " + trigger.body());
            }
            FlywayException failure = assertThrows(FlywayException.class, () -> migration(catalog).migrate());
            String owner = trigger.name().contains("person") ? "person" : "company";
            String guard = "V244_incompatible_" + owner + "_search_keys_trigger";
            assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains(guard.toLowerCase(Locale.ROOT)), failure::getMessage);
            try (Connection connection = connection(catalog)) {
                assertEquals(List.of(trigger.table() + "|" + trigger.timing() + "|" + trigger.event() + "|" + trigger.body()), rows(connection,
                    "SELECT CONCAT(EVENT_OBJECT_TABLE, '|', ACTION_TIMING, '|', EVENT_MANIPULATION, '|', ACTION_STATEMENT)"
                        + " FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() AND TRIGGER_NAME = '" + trigger.name() + "'"));
                assertEquals(List.of("0"), rows(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '244' AND success = 1"));
                assertLegacySources(connection);
            }
        }
    }

    private static IntStream committedPrefixes() throws IOException {
        return IntStream.rangeClosed(0, migrationStatements().size());
    }

    private static Stream<Arguments> incompatibleObjects() {
        List<IncompatibleObject> objects = new ArrayList<>();
        for (String table : List.of("person", "company")) {
            objects.add(new IncompatibleObject(table, "search_name",
                "VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL", false));
            objects.add(new IncompatibleObject(table, "search_phone",
                "VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL", false));
            objects.add(new IncompatibleObject(table, "search_key_revision", "BIGINT UNSIGNED NOT NULL DEFAULT 0", false));
            String index = "idx_" + table + "_workspace_search_phone";
            for (String definition : List.of(
                    "INDEX " + index + " (workspace_id, search_phone(16), id)",
                    "INDEX " + index + " (workspace_id, id, search_phone)",
                    "UNIQUE INDEX " + index + " (workspace_id, search_phone, id)",
                    "INDEX " + index + " (workspace_id, search_phone DESC, id)",
                    "INDEX " + index + " (workspace_id, search_phone, id) INVISIBLE")) {
                objects.add(new IncompatibleObject(table, index, definition, true));
            }
            String missing = "idx_" + table + "_workspace_search_missing_name";
            for (String definition : List.of(
                    "INDEX " + missing + " (workspace_id, search_name(2), id)",
                    "INDEX " + missing + " (workspace_id, id, search_name(1))",
                    "UNIQUE INDEX " + missing + " (workspace_id, search_name(1), id)",
                    "INDEX " + missing + " (workspace_id, search_name(1) DESC, id)",
                    "INDEX " + missing + " (workspace_id, search_name(1), id) INVISIBLE")) {
                objects.add(new IncompatibleObject(table, missing, definition, true));
            }
        }
        objects.add(new IncompatibleObject("person", "search_name",
            "VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT ''", false));
        objects.add(new IncompatibleObject("company", "search_phone",
            "VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'legacy'", false));
        objects.add(new IncompatibleObject("company", "search_key_revision", "BIGINT NULL DEFAULT 0", false));
        return objects.stream().map(Arguments::of);
    }

    private static Stream<Arguments> incompatibleTriggers() {
        List<IncompatibleTrigger> triggers = new ArrayList<>();
        for (String table : List.of("person", "company")) {
            String name = "trg_" + table + "_search_keys_legacy_write";
            String otherTable = table.equals("person") ? "company" : "person";
            triggers.add(new IncompatibleTrigger(name, table, "BEFORE", "UPDATE", "SET NEW.version = OLD.version"));
            triggers.add(new IncompatibleTrigger(name, table, "AFTER", "UPDATE", "DO 0"));
            triggers.add(new IncompatibleTrigger(name, table, "BEFORE", "INSERT", "SET NEW.version = 1"));
            triggers.add(new IncompatibleTrigger(name, otherTable, "BEFORE", "UPDATE", "SET NEW.version = OLD.version"));
        }
        return triggers.stream().map(Arguments::of);
    }

    private static List<String> migrationStatements() throws IOException {
        String migration;
        try (var input = RecordSearchV244MigrationIntegrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration " + RESOURCE);
            }
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder pending = new StringBuilder();
        for (String line : migration.lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith("DELIMITER ")) {
                delimiter = stripped.substring("DELIMITER ".length());
            } else {
                pending.append(line).append('\n');
                if (stripped.endsWith(delimiter)) {
                    String sql = pending.toString().stripTrailing();
                    statements.add(sql.substring(0, sql.length() - delimiter.length()).strip());
                    pending.setLength(0);
                }
            }
        }
        assertTrue(pending.toString().replaceAll("(?m)--[^\\r\\n]*", "").isBlank(),
            "Every migration statement must be covered by the interruption matrix");
        assertFalse(statements.isEmpty());
        return statements;
    }

    private static void createLegacyTables(MySqlScratchCatalog catalog) throws SQLException {
        try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
            for (String table : List.of("person", "company")) {
                statement.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, workspace_id INT NOT NULL,"
                    + " name VARCHAR(255), phone VARCHAR(64), version BIGINT NOT NULL DEFAULT 1,"
                    + " updated_at DATETIME(6) NOT NULL DEFAULT '2020-01-01', INDEX idx_" + table + "_workspace(workspace_id))");
                statement.executeUpdate("INSERT INTO " + table + " (id, workspace_id, name, phone)"
                    + " VALUES (1, 11, '山田 太郎', '090-9684-6208'), (2, 22, '別の 会社', NULL)");
            }
            statement.execute("CREATE TRIGGER trg_person_preserved BEFORE UPDATE ON person FOR EACH ROW SET NEW.version = OLD.version + 1");
        }
    }

    private static void assertSchemaAndLegacyBehavior(Connection connection) throws SQLException {
        assertLegacySources(connection);
        for (String table : List.of("person", "company")) {
            assertEquals(List.of(
                "search_key_revision|bigint|NO|0|<null>|<null>||",
                "search_name|varchar(1024)|YES|<null>|utf8mb4|utf8mb4_0900_ai_ci||",
                "search_phone|varchar(256)|YES|<null>|utf8mb4|utf8mb4_0900_ai_ci||"), rows(connection,
                "SELECT CONCAT(COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COALESCE(COLUMN_DEFAULT, '<null>'), '|',"
                    + " COALESCE(CHARACTER_SET_NAME, '<null>'), '|', COALESCE(COLLATION_NAME, '<null>'), '|', EXTRA, '|', GENERATION_EXPRESSION)"
                    + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                    + "' AND COLUMN_NAME LIKE 'search_%' ORDER BY COLUMN_NAME"));
            assertEquals(List.of("1|workspace_id|<null>|A|1|BTREE|YES|<null>",
                "2|search_phone|<null>|A|1|BTREE|YES|<null>", "3|id|<null>|A|1|BTREE|YES|<null>"), rows(connection,
                "SELECT CONCAT(SEQ_IN_INDEX, '|', COLUMN_NAME, '|', COALESCE(SUB_PART, '<null>'), '|', COLLATION, '|',"
                    + " NON_UNIQUE, '|', INDEX_TYPE, '|', IS_VISIBLE, '|', COALESCE(EXPRESSION, '<null>'))"
                    + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                    + "' AND INDEX_NAME = 'idx_" + table + "_workspace_search_phone' ORDER BY SEQ_IN_INDEX"));
            assertEquals(List.of("1|workspace_id|<null>|A|1|BTREE|YES|<null>",
                "2|search_name|1|A|1|BTREE|YES|<null>", "3|id|<null>|A|1|BTREE|YES|<null>"), rows(connection,
                "SELECT CONCAT(SEQ_IN_INDEX, '|', COLUMN_NAME, '|', COALESCE(SUB_PART, '<null>'), '|', COLLATION, '|',"
                    + " NON_UNIQUE, '|', INDEX_TYPE, '|', IS_VISIBLE, '|', COALESCE(EXPRESSION, '<null>'))"
                    + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                    + "' AND INDEX_NAME = 'idx_" + table + "_workspace_search_missing_name' ORDER BY SEQ_IN_INDEX"));
            assertEquals(List.of("0|0"), rows(connection, "SELECT CONCAT(COUNT(search_name), '|', COUNT(search_phone)) FROM " + table));
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + table + " SET search_name = 'name-key', search_phone = 'phone-key', search_key_revision = 1 WHERE id = 1");
                statement.executeUpdate("UPDATE " + table + " SET name = 'Legacy rename' WHERE id = 1");
                try (var result = statement.executeQuery("SELECT search_name, search_phone FROM " + table + " WHERE id = 1")) {
                    assertTrue(result.next());
                    assertNull(result.getString(1));
                    assertEquals("phone-key", result.getString(2));
                }
                statement.executeUpdate("UPDATE " + table + " SET search_name = 'name-key', search_phone = 'phone-key', search_key_revision = 2 WHERE id = 1");
                statement.executeUpdate("UPDATE " + table + " SET phone = '090-1111-2222' WHERE id = 1");
                try (var result = statement.executeQuery("SELECT search_name, search_phone FROM " + table + " WHERE id = 1")) {
                    assertTrue(result.next());
                    assertEquals("name-key", result.getString(1));
                    assertNull(result.getString(2));
                }
                statement.executeUpdate("UPDATE " + table + " SET name = 'Current writer', phone = '090-9684-6208',"
                    + " search_name = 'current-key', search_phone = 'current-phone', search_key_revision = 3 WHERE id = 1");
                assertEquals(List.of("current-key|current-phone|3"), rows(connection,
                    "SELECT CONCAT(search_name, '|', search_phone, '|', search_key_revision) FROM " + table + " WHERE id = 1"));
            }
        }
        assertEquals(List.of("6"), rows(connection, "SELECT version FROM person WHERE id = 1"));
        assertEquals(List.of("3"), rows(connection,
            "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()"));
    }

    private static void assertLegacySources(Connection connection) throws SQLException {
        for (String table : List.of("person", "company")) {
            assertEquals(List.of("1|11|山田 太郎|090-9684-6208|1|2020-01-01 00:00:00.000000", "2|22|別の 会社|<null>|1|2020-01-01 00:00:00.000000"), rows(connection,
                "SELECT CONCAT(id, '|', workspace_id, '|', name, '|', COALESCE(phone, '<null>'), '|', version, '|', updated_at) FROM " + table + " ORDER BY id"));
        }
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(result.getString(1));
            }
            return rows;
        }
    }

    private static Connection connection(MySqlScratchCatalog catalog) throws SQLException {
        return DriverManager.getConnection(catalog.url(), username, password);
    }

    private static MySqlScratchCatalog catalog() throws SQLException {
        return MySqlScratchCatalog.create(Objects.requireNonNull(configuredUrl), username, password,
            "record_search_v244_", "utf8mb4", "utf8mb4_0900_ai_ci");
    }

    private static Flyway migration(MySqlScratchCatalog catalog) {
        return Flyway.configure().dataSource(catalog.url(), username, password)
            .locations("classpath:db/migration/tenant").baselineOnMigrate(true).baselineVersion("243")
            .target("244").load();
    }
}
