package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

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

    private static final List<String[]> REQUIRED_LEADING_INDEXES = List.of(
        new String[] {"note", "author_id"},
        new String[] {"activity", "created_by_id"},
        new String[] {"introduction", "introducer_user_id"},
        new String[] {"notification", "recipient_id"},
        new String[] {"notification", "actor_id"},
        new String[] {"deal_collaborator", "user_id"},
        new String[] {"deal", "owner_id"},
        new String[] {"company", "owner_id"},
        new String[] {"person", "owner_id"},
        new String[] {"task", "assigned_to_id"},
        new String[] {"attachment", "uploaded_by_id"},
        new String[] {"rule", "run_as_user_id"},
        new String[] {"rule", "created_by_id"},
        new String[] {"saved_view", "user_id"},
        new String[] {"saved_view_pin", "user_id"},
        new String[] {"saved_view_default", "user_id"},
        new String[] {"user_dashboard", "user_id"},
        new String[] {"relationship_signal_state", "user_id"},
        new String[] {"report_definition", "created_by"},
        new String[] {"report_snapshot", "generated_by"},
        new String[] {"campaign", "owner_user_id"},
        new String[] {"campaign", "created_by_id"},
        new String[] {"campaign_audience_snapshot", "created_by_id"},
        new String[] {"contact_channel_consent_event", "created_by_id"},
        new String[] {"suppression_entry", "created_by_id"},
        new String[] {"company_share", "granted_by"},
        new String[] {"person_share", "granted_by"},
        new String[] {"pipeline_share", "granted_by"},
        new String[] {"workflow", "draft_run_as_user_id"},
        new String[] {"workflow", "created_by_id"},
        new String[] {"workflow", "updated_by_id"},
        new String[] {"workflow", "intake_paused_by_id"},
        new String[] {"workflow_version", "run_as_user_id"},
        new String[] {"workflow_version", "created_by_id"},
        new String[] {"workflow_version", "published_by_id"},
        new String[] {"workflow_recipe_origin", "installed_by_id"},
        new String[] {"workflow_invocation", "requested_by_id"},
        new String[] {"workflow_intervention", "owner_user_id"});

    @Autowired private DataSource dataSource;

    @Test
    void everyOffboardingColumnHasALeadingIndex() throws Exception {
        List<String> missing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                        + " AND COLUMN_NAME = ? AND SEQ_IN_INDEX = 1")) {
            for (String[] required : REQUIRED_LEADING_INDEXES) {
                statement.setString(1, required[0]);
                statement.setString(2, required[1]);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) == 0) {
                        missing.add(required[0] + "." + required[1]);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
            "Offboarding statements need a leading index on each of these columns; the FK-drop "
                + "migration must create explicit replacements for the FK-implicit ones it removes: "
                + missing);
    }

}
