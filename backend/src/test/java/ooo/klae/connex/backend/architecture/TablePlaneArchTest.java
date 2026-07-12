package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/**
 * Enforces the control-plane wall (#440 increment 3) against the live,
 * fully-migrated schema: every base table must be classified in exactly one
 * plane of {@link TablePlaneRegistry}, and no foreign key may cross the wall
 * in either direction. A new table or constraint that violates the partition
 * fails the build instead of silently re-coupling the planes — the property
 * Phase 4's per-org catalogs (#313) depend on.
 */
@SpringBootTest
class TablePlaneArchTest {

    /** Neither plane: migration bookkeeping owned by Flyway itself. */
    private static final Set<String> INFRASTRUCTURE_TABLES = Set.of("flyway_schema_history");

    @Autowired private DataSource dataSource;

    @Test
    void everyBaseTableIsClassifiedInExactlyOnePlane() throws Exception {
        List<String> unclassified = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT TABLE_NAME FROM information_schema.TABLES"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String table = resultSet.getString(1);
                int planes = 0;
                if (TablePlaneRegistry.CONTROL_PLANE_TABLES.contains(table)) planes++;
                if (TablePlaneRegistry.ORG_DATA_TABLES.contains(table)) planes++;
                if (INFRASTRUCTURE_TABLES.contains(table)) planes++;
                if (planes != 1) {
                    unclassified.add(table);
                }
            }
        }
        assertTrue(unclassified.isEmpty(),
            "Every table must belong to exactly one plane in TablePlaneRegistry — a new table needs an "
                + "explicit, reviewed placement decision (see #440): " + unclassified);
    }

    @Test
    void noForeignKeyCrossesThePlaneWall() throws Exception {
        List<String> crossings = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT TABLE_NAME, CONSTRAINT_NAME, REFERENCED_TABLE_NAME"
                        + " FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL"
                        + " GROUP BY TABLE_NAME, CONSTRAINT_NAME, REFERENCED_TABLE_NAME");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String child = resultSet.getString(1);
                String parent = resultSet.getString(3);
                boolean childOrg = TablePlaneRegistry.ORG_DATA_TABLES.contains(child);
                boolean parentOrg = TablePlaneRegistry.ORG_DATA_TABLES.contains(parent);
                if (childOrg != parentOrg) {
                    crossings.add(child + " -> " + parent + " (" + resultSet.getString(2) + ")");
                }
            }
        }
        assertTrue(crossings.isEmpty(),
            "Foreign keys must never cross the control-plane wall (#440 increment 3); replace the "
                + "constraint with service-layer validation (see UserOffboardingService for the pattern): "
                + crossings);
    }
}
