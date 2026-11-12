package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import ooo.klae.connex.backend.support.LegacyNotificationTriggerFixtures;

/** Applies the forward migrations to legacy catalogs, fresh catalogs and committed DDL prefixes. */
class LegacyNotificationTriggerMigrationIntegrationTest {
    private static String url;
    private static String username;
    private static String password;

    @BeforeAll
    static void requireDatabase() {
        url = System.getenv("CONNEX_DB_URL");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(url != null && username != null && password != null, "Database credentials required");
    }

    @Test
    void removesOnlyTheSixLegacyTriggersAndPreservesLiveRows(@TempDir Path migrations) throws Exception {
        try (var catalog = catalog()) {
            JdbcTemplate jdbc = schema(catalog);
            LegacyNotificationTriggerFixtures.install(jdbc);
            jdbc.update("INSERT INTO workspace_member VALUES (1, 7)");
            jdbc.update("INSERT INTO notification VALUES (1, 7, 'Retained notification')");
            long version = stateVersion(jdbc);
            assertEquals(7, triggerCount(jdbc));

            assertEquals(2, migration(catalog, migrations).migrate().migrationsExecuted);

            assertOnlyRetainedTrigger(jdbc);
            assertEquals("Retained notification", jdbc.queryForObject("SELECT title FROM notification", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM workspace_member", Integer.class));
            assertEquals(version, stateVersion(jdbc));
            LegacyNotificationTriggerFixtures.apply(jdbc);
            assertOnlyRetainedTrigger(jdbc);
            assertEquals(version, stateVersion(jdbc));
        }
    }

    @Test
    void freshCatalogWithoutLegacyTriggersIsUnchanged(@TempDir Path migrations) throws Exception {
        try (var catalog = catalog()) {
            JdbcTemplate jdbc = schema(catalog);
            jdbc.update("INSERT INTO notification_recipient_state VALUES (7, 31)");

            Flyway flyway = migration(catalog, migrations);
            assertEquals(2, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);

            assertOnlyRetainedTrigger(jdbc);
            assertEquals(31, stateVersion(jdbc));
        }
    }

    @Test
    void resumesAfterAnAutocommittedDropPrefix(@TempDir Path migrations) throws Exception {
        try (var catalog = catalog()) {
            JdbcTemplate jdbc = schema(catalog);
            LegacyNotificationTriggerFixtures.install(jdbc);
            jdbc.execute("DROP TRIGGER trg_notification_state_insert");
            jdbc.execute("DROP TRIGGER trg_notification_membership_state_update");

            assertEquals(2, migration(catalog, migrations).migrate().migrationsExecuted);

            assertOnlyRetainedTrigger(jdbc);
        }
    }

    @Test
    void noOpDuplicateNotificationWriteAdvancesOnlyTheLegacyTriggerVersion(@TempDir Path migrations) throws Exception {
        try (var catalog = catalog()) {
            JdbcTemplate jdbc = schema(catalog);
            LegacyNotificationTriggerFixtures.install(jdbc);
            jdbc.update("INSERT INTO notification VALUES (1, 7, 'Unchanged notification')");
            long before = stateVersion(jdbc);
            String redelivery = "INSERT INTO notification VALUES (1, 7, 'Unchanged notification')"
                + " ON DUPLICATE KEY UPDATE title = notification.title";

            jdbc.update(redelivery);
            assertEquals(before + 1, stateVersion(jdbc));

            assertEquals(2, migration(catalog, migrations).migrate().migrationsExecuted);

            jdbc.update(redelivery);
            jdbc.update(redelivery);
            assertEquals(before + 1, stateVersion(jdbc));
            assertEquals("Unchanged notification", jdbc.queryForObject("SELECT title FROM notification", String.class));
        }
    }

    private static JdbcTemplate schema(MySqlScratchCatalog catalog) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(catalog.url(), username, password));
        jdbc.execute("CREATE TABLE notification (id INT PRIMARY KEY, recipient_id INT, title VARCHAR(100))");
        jdbc.execute("CREATE TABLE workspace_member (workspace_id INT, user_id INT, PRIMARY KEY (workspace_id, user_id))");
        jdbc.execute("CREATE TABLE notification_recipient_state (recipient_id INT PRIMARY KEY, state_version BIGINT NOT NULL)");
        jdbc.execute("CREATE TRIGGER retained_notification_guard BEFORE INSERT ON notification FOR EACH ROW SET NEW.title = NEW.title");
        return jdbc;
    }

    private static long stateVersion(JdbcTemplate jdbc) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(
            "SELECT state_version FROM notification_recipient_state WHERE recipient_id = 7", Long.class));
    }

    private static int triggerCount(JdbcTemplate jdbc) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()", Integer.class));
    }

    private static void assertOnlyRetainedTrigger(JdbcTemplate jdbc) {
        assertEquals(List.of("retained_notification_guard"), jdbc.queryForList(
            "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() ORDER BY TRIGGER_NAME",
            String.class));
    }

    private static Flyway migration(MySqlScratchCatalog catalog, Path migrations) throws Exception {
        for (String resource : LegacyNotificationTriggerFixtures.MIGRATIONS) {
            try (var input = LegacyNotificationTriggerMigrationIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                Files.copy(input, migrations.resolve(Path.of(resource).getFileName()));
            }
        }
        return Flyway.configure().dataSource(catalog.url(), username, password)
            .locations("filesystem:" + migrations).baselineOnMigrate(true).baselineVersion("248").load();
    }

    private static MySqlScratchCatalog catalog() throws Exception {
        return MySqlScratchCatalog.create(url, username, password,
            "connex_legacy_notification_", "utf8mb4", "utf8mb4_0900_ai_ci");
    }
}
