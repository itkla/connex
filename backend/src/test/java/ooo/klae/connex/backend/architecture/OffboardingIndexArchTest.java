package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the user-reference indexes the offboarding fan-out depends on (#440
 * increment 3). The guard counts are {@code FOR UPDATE} equality reads and the
 * {@code *Anywhere} erasures are cross-workspace writes: without a leading
 * index on each column they degrade to full-table locking scans that freeze
 * writes platform-wide during every account deletion. Most of these indexes
 * exist only implicitly today (created by the cross-plane foreign keys), so
 * the FK-drop migration must replace them explicitly — this test fails the
 * build if it forgets.
 */
@SpringBootTest
class OffboardingIndexArchTest {

    private static final Map<String, String> REQUIRED_LEADING_INDEXES = Map.ofEntries(
        Map.entry("note", "author_id"),
        Map.entry("activity", "created_by_id"),
        Map.entry("introduction", "introducer_user_id"),
        Map.entry("notification", "recipient_id"),
        Map.entry("deal_collaborator", "user_id"),
        Map.entry("deal", "owner_id"),
        Map.entry("task", "assigned_to_id"),
        Map.entry("attachment", "uploaded_by_id"),
        Map.entry("rule", "run_as_user_id"),
        Map.entry("saved_view", "user_id"),
        Map.entry("user_dashboard", "user_id"),
        Map.entry("company_share", "granted_by"),
        Map.entry("person_share", "granted_by"),
        Map.entry("pipeline_share", "granted_by"));

    @Autowired private DataSource dataSource;

    @Test
    void everyOffboardingColumnHasALeadingIndex() throws Exception {
        List<String> missing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                        + " AND COLUMN_NAME = ? AND SEQ_IN_INDEX = 1")) {
            for (Map.Entry<String, String> required : REQUIRED_LEADING_INDEXES.entrySet()) {
                statement.setString(1, required.getKey());
                statement.setString(2, required.getValue());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) == 0) {
                        missing.add(required.getKey() + "." + required.getValue());
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
            "Offboarding statements need a leading index on each of these columns; the FK-drop "
                + "migration must create explicit replacements for the FK-implicit ones it removes: "
                + missing);
    }

    /**
     * {@code rule.created_by_id} is asserted separately because {@code rule}
     * appears twice in the fan-out ({@code run_as_user_id} above).
     */
    @Test
    void ruleCreatedByHasALeadingIndex() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rule'"
                        + " AND COLUMN_NAME = 'created_by_id' AND SEQ_IN_INDEX = 1")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertTrue(resultSet.getInt(1) > 0,
                    "rule.created_by_id needs a leading index (FK-implicit today; explicit after the FK drop)");
            }
        }
    }
}
