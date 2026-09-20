package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the {@code campaign_delivery} index that bounds scheduler discovery of abandoned audience
 * attempts (#1705).
 *
 * <p>{@code CampaignSendMapper.workspaceIdsWithQueuedSends} runs once per scheduler tick for every
 * pinned catalog and carries no workspace predicate, so no index leading with {@code workspace_id}
 * can be seeked for its abandoned-attempt arm. Only an index leading with {@code status} and
 * {@code dispatch_lease_owner}, with {@code frequency_reserved_at} behind them, turns that arm into
 * two equalities plus a range and lets a tick with nothing to recover read no delivery rows.
 * Without it the optimizer falls back to a scan — of {@code campaign_delivery}, or of
 * {@code campaign_send} with one delivery probe per send — on every tick, so discovery cost follows
 * send and delivery history instead of outstanding recovery work, the regression this guard exists
 * to prevent. The statement's row semantics are pinned elsewhere and stay green either way,
 * which is why the index itself needs a live-schema guard.
 */
@SpringBootTest
class DeliveryRecoveryIndexArchTest {

    private static final String GUARDED_TABLE = "campaign_delivery";

    private static final List<String> REQUIRED_LEADING_COLUMNS =
        List.of("status", "dispatch_lease_owner", "frequency_reserved_at");

    @Autowired private DataSource dataSource;

    @Test
    void abandonedAudienceAttemptDiscoveryKeepsItsLeadingIndex() throws Exception {
        Map<String, List<String>> leadingColumnsByIndex = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                        + " AND SEQ_IN_INDEX <= ? ORDER BY INDEX_NAME, SEQ_IN_INDEX")) {
            statement.setString(1, GUARDED_TABLE);
            statement.setInt(2, REQUIRED_LEADING_COLUMNS.size());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    leadingColumnsByIndex
                        .computeIfAbsent(resultSet.getString(1), name -> new ArrayList<>())
                        .add(resultSet.getString(2));
                }
            }
        }
        assertTrue(leadingColumnsByIndex.containsValue(REQUIRED_LEADING_COLUMNS),
            "Scheduler discovery of abandoned audience attempts needs a " + GUARDED_TABLE
                + " index whose first columns are " + REQUIRED_LEADING_COLUMNS
                + " in that order, or every tick full-scans the table for every catalog; found "
                + leadingColumnsByIndex);
    }

}
