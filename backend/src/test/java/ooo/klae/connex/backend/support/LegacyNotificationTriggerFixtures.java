package ooo.klae.connex.backend.support;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Reproduces pre-baseline invalidation hooks without modifying released migrations. */
public final class LegacyNotificationTriggerFixtures {
    public static final List<String> MIGRATIONS = List.of(
        "db/migration/tenant/V249__drop_legacy_notification_state_triggers.sql",
        "db/migration/control/V250__drop_legacy_notification_membership_state_triggers.sql");

    private LegacyNotificationTriggerFixtures() {}

    /** Installs all six historical hook names in the caller's disposable test catalog. */
    public static void install(JdbcTemplate jdbc) {
        for (String event : List.of("insert", "update", "delete")) {
            String row = "delete".equals(event) ? "OLD" : "NEW";
            jdbc.execute("CREATE TRIGGER trg_notification_state_" + event + " AFTER " + event
                + " ON notification FOR EACH ROW INSERT INTO notification_recipient_state"
                + " (recipient_id, state_version) VALUES (" + row + ".recipient_id, 1)"
                + " ON DUPLICATE KEY UPDATE state_version = state_version + 1");
            jdbc.execute("CREATE TRIGGER trg_notification_membership_state_" + event + " AFTER " + event
                + " ON workspace_member FOR EACH ROW INSERT INTO notification_recipient_state"
                + " (recipient_id, state_version) VALUES (" + row + ".user_id, 1)"
                + " ON DUPLICATE KEY UPDATE state_version = state_version + 1");
        }
    }

    /** Replays the actual forward migrations, including for cleanup after a failed assertion. */
    public static void apply(JdbcTemplate jdbc) {
        var populator = new ResourceDatabasePopulator();
        MIGRATIONS.forEach(path -> populator.addScript(new ClassPathResource(path)));
        populator.execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
    }

    /** Removes fixture hooks independently of the migration under test. */
    public static void remove(JdbcTemplate jdbc) {
        for (String prefix : List.of("trg_notification_state_", "trg_notification_membership_state_")) {
            for (String event : List.of("insert", "update", "delete")) {
                jdbc.execute("DROP TRIGGER IF EXISTS " + prefix + event);
            }
        }
    }
}
