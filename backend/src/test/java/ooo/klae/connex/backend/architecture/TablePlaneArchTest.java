package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry;
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
    void objectStorageIdentityIsControlPlaneState() {
        assertTrue(TablePlaneRegistry.CONTROL_PLANE_TABLES.contains(
            "object_storage_backend_identity"));
    }

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

    @Test
    void everyDirectWorkspaceKeyedControlTableHasAnExplicitLifecycleDisposition()
            throws Exception {
        assertEquals(
            TablePlaneRegistry.CONTROL_PLANE_WORKSPACE_DATA_TABLES,
            ControlWorkspaceLifecycleRegistry.declarations().keySet());
        List<String> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT TABLE_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'workspace_id'");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String table = resultSet.getString(1);
                if (!TablePlaneRegistry.CONTROL_PLANE_TABLES.contains(table)) {
                    continue;
                }
                int dispositions = 0;
                if (TablePlaneRegistry.CONTROL_PLANE_WORKSPACE_STATE_TABLES.contains(table)) {
                    dispositions++;
                }
                if (ControlWorkspaceLifecycleRegistry.declarations().containsKey(table)) {
                    dispositions++;
                }
                if (dispositions != 1) {
                    violations.add(table);
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Every direct workspace-keyed control table must be declared exactly once as reviewed "
                + "control state or lifecycle-enrolled workspace data: " + violations);
    }

    @Test
    void controlWorkspaceLifecycleKeysAreLiveIndexedAndUnique() throws Exception {
        List<String> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (ControlWorkspaceLifecycleRegistry.TableLifecycle declaration
                    : ControlWorkspaceLifecycleRegistry.declarations().values()) {
                if (!columnExists(connection, declaration.table(), declaration.workspaceColumn())) {
                    violations.add(declaration.table() + "." + declaration.workspaceColumn()
                        + " is missing");
                }
                if (!leadingIndexExists(
                        connection, declaration.table(), declaration.workspaceColumn(), false)) {
                    violations.add(declaration.table() + "." + declaration.workspaceColumn()
                        + " does not lead an index");
                }
                if (!columnExists(connection, declaration.table(), declaration.exportKey())) {
                    violations.add(declaration.table() + "." + declaration.exportKey()
                        + " is missing");
                }
                if (!leadingIndexExists(
                        connection, declaration.table(), declaration.exportKey(), true)) {
                    violations.add(declaration.table() + "." + declaration.exportKey()
                        + " does not lead a unique index");
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Control-workspace lifecycle declarations need live indexed workspace and keyset "
                + "columns: " + violations);
    }

    private boolean columnExists(Connection connection, String table, String column)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean leadingIndexExists(
            Connection connection,
            String table,
            String column,
            boolean unique) throws Exception {
        String uniquePredicate = unique ? " AND NON_UNIQUE = 0" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.STATISTICS"
                    + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                    + " AND COLUMN_NAME = ? AND SEQ_IN_INDEX = 1" + uniquePredicate)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }
}
