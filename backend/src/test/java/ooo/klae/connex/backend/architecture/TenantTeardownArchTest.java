package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.Cascade;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.Direct;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;

/**
 * Build-enforced tenant teardown contract for the live migrated schema. Every
 * org-data table must declare export, delete, and residual-scan reach in
 * {@link TenantLifecycleRegistry}; direct deletes must be indexed and every
 * declared cascade must be a real in-plane {@code ON DELETE CASCADE} edge whose
 * chain terminates at a direct workspace key.
 */
@SpringBootTest
class TenantTeardownArchTest {

    @Autowired private DataSource dataSource;

    @Test
    void everyOrgDataTableHasExactlyOneLifecycleDeclaration() {
        assertEquals(TablePlaneRegistry.ORG_DATA_TABLES,
            TenantLifecycleRegistry.declarations().keySet(),
            "New org-data table has no TenantLifecycleRegistry declaration. Declare DIRECT(workspace "
                + "column) or CASCADE(verified parent) so teardown, export, and residual verification "
                + "cover it.");
    }

    @Test
    void everyCascadeIsLiveAndTerminatesAtDirect() throws Exception {
        List<String> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values()) {
                if (!(declaration.reach() instanceof Cascade cascade)) {
                    continue;
                }
                verifyCascade(connection, declaration, cascade, violations);
                verifyTerminatingChain(declaration, violations);
            }
        }
        assertFalse(violations.isEmpty(),
            "The teardown registry must verify at least one in-plane cascade.");
        assertTrue(violations.stream().allMatch(value -> value.startsWith("verified:")),
            "Declared CASCADE lifecycle edges must match live ON DELETE CASCADE foreign keys: "
                + violations);
    }

    @Test
    void everyDirectWorkspaceColumnExistsAndLeadsAnIndex() throws Exception {
        List<String> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values()) {
                if (!(declaration.reach() instanceof Direct direct)) {
                    continue;
                }
                if (!columnExists(connection, declaration.table(), direct.workspaceColumn())) {
                    violations.add(declaration.table() + "." + direct.workspaceColumn()
                        + " does not exist");
                    continue;
                }
                if (!leadingIndexExists(connection, declaration.table(), direct.workspaceColumn())) {
                    violations.add(declaration.table() + "." + direct.workspaceColumn()
                        + " is not the leading column of an index");
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "DIRECT teardown predicates must use real workspace columns with leading indexes so "
                + "tenant deletion never full-scans: " + violations);
    }

    @Test
    void preparationsAreLiveNullableColumnsAndRestrictiveDeleteOrderIsCovered() throws Exception {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> deleteOrder = new HashMap<>();
        Map<Integer, String> deleteOrderOwner = new HashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values()) {
                if (declaration.direct()) {
                    if (deleteOrder.put(declaration.table(), declaration.deleteOrder()) != null) {
                        violations.add("duplicate delete order table " + declaration.table());
                    }
                    String existing = deleteOrderOwner.put(
                        declaration.deleteOrder(),
                        declaration.table());
                    if (existing != null) {
                        violations.add("delete order " + declaration.deleteOrder()
                            + " is shared by " + existing + " and " + declaration.table());
                    }
                }
                for (var preparation : declaration.preparations()) {
                    NullifyReference nullify = (NullifyReference) preparation;
                    if (!nullableColumnExists(connection, declaration.table(), nullify.column())) {
                        violations.add(declaration.table() + "." + nullify.column()
                            + " preparation column is absent or non-nullable");
                    }
                }
            }
            verifyRestrictiveEdges(connection, deleteOrder, violations);
        }
        assertTrue(violations.isEmpty(),
            "Every in-plane RESTRICT/NO ACTION edge needs child-before-parent delete order or an "
                + "explicit nullable preparation: " + violations);
    }

    private void verifyCascade(
            Connection connection,
            TableLifecycle declaration,
            Cascade cascade,
            List<String> results) throws Exception {
        String sql = "SELECT k.COLUMN_NAME, k.REFERENCED_COLUMN_NAME, r.DELETE_RULE"
            + " FROM information_schema.KEY_COLUMN_USAGE k"
            + " JOIN information_schema.REFERENTIAL_CONSTRAINTS r"
            + " ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA"
            + " AND r.TABLE_NAME = k.TABLE_NAME"
            + " AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME"
            + " WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = ?"
            + " AND k.CONSTRAINT_NAME = ? AND k.REFERENCED_TABLE_NAME = ?"
            + " ORDER BY k.ORDINAL_POSITION";
        List<String> liveLinks = new ArrayList<>();
        String deleteRule = null;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, declaration.table());
            statement.setString(2, cascade.constraintName());
            statement.setString(3, cascade.parentTable());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    liveLinks.add(resultSet.getString(1) + "->" + resultSet.getString(2));
                    deleteRule = resultSet.getString(3);
                }
            }
        }
        List<String> declaredLinks = cascade.columns().stream()
            .map(link -> link.childColumn() + "->" + link.parentColumn())
            .toList();
        if (declaredLinks.equals(liveLinks) && "CASCADE".equals(deleteRule)) {
            results.add("verified:" + declaration.table());
        } else {
            results.add(declaration.table() + " declared " + declaredLinks
                + " but live=" + liveLinks + ", deleteRule=" + deleteRule);
        }
    }

    private void verifyTerminatingChain(TableLifecycle start, List<String> violations) {
        Set<String> visited = new HashSet<>();
        TableLifecycle current = start;
        while (current.reach() instanceof Cascade cascade) {
            if (!visited.add(current.table())) {
                violations.add(start.table() + " cascade chain is cyclic");
                return;
            }
            current = TenantLifecycleRegistry.declarations().get(cascade.parentTable());
            if (current == null) {
                violations.add(start.table() + " cascade parent is unregistered");
                return;
            }
        }
        if (!(current.reach() instanceof Direct)) {
            violations.add(start.table() + " cascade chain does not terminate in DIRECT");
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
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

    private boolean nullableColumnExists(Connection connection, String table, String column)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && "YES".equals(resultSet.getString(1));
            }
        }
    }

    private boolean leadingIndexExists(Connection connection, String table, String column)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.STATISTICS"
                    + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                    + " AND COLUMN_NAME = ? AND SEQ_IN_INDEX = 1")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private void verifyRestrictiveEdges(
            Connection connection,
            Map<String, Integer> deleteOrder,
            List<String> violations) throws Exception {
        String sql = "SELECT k.TABLE_NAME, k.CONSTRAINT_NAME, k.COLUMN_NAME,"
            + " k.REFERENCED_TABLE_NAME, r.DELETE_RULE"
            + " FROM information_schema.KEY_COLUMN_USAGE k"
            + " JOIN information_schema.REFERENTIAL_CONSTRAINTS r"
            + " ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA"
            + " AND r.TABLE_NAME = k.TABLE_NAME"
            + " AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME"
            + " WHERE k.TABLE_SCHEMA = DATABASE() AND k.REFERENCED_TABLE_NAME IS NOT NULL"
            + " AND r.DELETE_RULE IN ('RESTRICT', 'NO ACTION')";
        Map<String, RestrictiveEdge> edges = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String child = resultSet.getString(1);
                String constraint = resultSet.getString(2);
                String column = resultSet.getString(3);
                String parent = resultSet.getString(4);
                if (!TablePlaneRegistry.ORG_DATA_TABLES.contains(child)
                        || !TablePlaneRegistry.ORG_DATA_TABLES.contains(parent)) {
                    continue;
                }
                String key = child + "." + constraint;
                edges.computeIfAbsent(key,
                    ignored -> new RestrictiveEdge(child, constraint, parent, new ArrayList<>()))
                    .columns().add(column);
            }
        }
        for (RestrictiveEdge edge : edges.values()) {
            TableLifecycle childDeclaration = TenantLifecycleRegistry.require(edge.child());
            boolean prepared = childDeclaration.preparations().stream()
                .filter(NullifyReference.class::isInstance)
                .map(NullifyReference.class::cast)
                .anyMatch(value -> edge.columns().contains(value.column()));
            Integer childOrder = effectiveDeleteOrder(edge.child(), deleteOrder);
            Integer parentOrder = effectiveDeleteOrder(edge.parent(), deleteOrder);
            if (!prepared && (childOrder == null || parentOrder == null
                    || childOrder >= parentOrder)) {
                violations.add(edge.child() + " (" + edge.constraint() + ") -> "
                    + edge.parent()
                    + " is not child-before-parent and has no preparation");
            }
        }
    }

    private Integer effectiveDeleteOrder(String table, Map<String, Integer> deleteOrder) {
        Set<String> visited = new HashSet<>();
        TableLifecycle declaration = TenantLifecycleRegistry.require(table);
        while (declaration.reach() instanceof Cascade cascade) {
            if (!visited.add(declaration.table())) {
                return null;
            }
            declaration = TenantLifecycleRegistry.declarations().get(cascade.parentTable());
            if (declaration == null) {
                return null;
            }
        }
        return deleteOrder.get(declaration.table());
    }

    private record RestrictiveEdge(
            String child,
            String constraint,
            String parent,
            List<String> columns) {
    }
}
