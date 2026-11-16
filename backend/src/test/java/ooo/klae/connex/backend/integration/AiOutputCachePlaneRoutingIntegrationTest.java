package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.support.AiOutputCachePlaneFixture;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/** Exercises real restriction locks, updates and purge SQL with no control tables in the tenant catalog. */
class AiOutputCachePlaneRoutingIntegrationTest {
    @Test
    void freshScopeBypassesBothTransactionMemoAndMyBatisCacheWithoutAnInterveningWrite() throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(false, 2)) {
            transaction(fixture).executeWithoutResult(status -> {
                var memo = fixture.scopes.getForWorkspace(fixture.workspaceId);
                int sibling;
                try {
                    sibling = fixture.addWorkspace(fixture.orgId);
                    fixture.addCache(sibling, "intro.rationale:en", fixture.personId, 0, true);
                } catch (java.sql.SQLException exception) {
                    throw new IllegalStateException(exception);
                }
                assertFalse(memo.workspaceIds().contains(sibling));
                int checkouts = fixture.connections.checkouts.get();
                var fresh = fixture.scopes.getFreshForWorkspace(fixture.workspaceId);
                assertTrue(fresh.workspaceIds().contains(sibling));
                assertSame(memo, fixture.scopes.getForWorkspace(fixture.workspaceId));
                assertTrue(fixture.cache.deleteForPerson(fixture.workspaceId, fresh.workspaceIdsJson(), fixture.personId) > 0);
                assertEquals(0, fixture.tenant.queryForObject(
                    "SELECT COUNT(*) FROM ai_output_cache WHERE workspace_id = ?", Integer.class, sibling));
                assertEquals(checkouts, fixture.connections.checkouts.get());
                status.setRollbackOnly();
            });
        }
    }

    @Test
    void freshDefaultCatalogScopeRetainsTheLockedConnectionWithoutAnotherCheckout() throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(false, 1)) {
            transaction(fixture).executeWithoutResult(status -> {
                var memo = fixture.scopes.getForWorkspace(fixture.workspaceId);
                assertNotNull(fixture.persons.getOwnedPersonByIdForUpdate(fixture.workspaceId, fixture.personId));
                Connection original = DataSourceUtils.getConnection(fixture.connections);
                int checkouts = fixture.connections.checkouts.get();
                var fresh = fixture.scopes.getFreshForWorkspace(fixture.workspaceId);
                assertEquals(memo, fresh);
                assertSame(memo, fixture.scopes.getForWorkspace(fixture.workspaceId));
                assertTrue(fixture.cache.deleteForPerson(fixture.workspaceId, fresh.workspaceIdsJson(), fixture.personId) > 0);
                assertSame(original, DataSourceUtils.getConnection(fixture.connections));
                assertEquals(fixture.defaultCatalog, catalog(original));
                assertEquals(checkouts, fixture.connections.checkouts.get());
                status.setRollbackOnly();
            });
        }
    }

    @Test
    void restrictionPurgeUsesEightTenantTablesAndNoExtraCheckoutWithAOneConnectionPool() throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(true, 1)) {
            List<String> tables = fixture.control.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? ORDER BY table_name", String.class, fixture.tenantCatalog);
            assertEquals(AiOutputCachePlaneFixture.TENANT_TABLES.stream().sorted().toList(), tables);
            for (String controlTable : TablePlaneRegistry.CONTROL_PLANE_TABLES) {
                assertFalse(tables.contains(controlTable), controlTable);
            }
            int defaultCount = fixture.control.queryForObject(
                "SELECT COUNT(*) FROM ai_output_cache WHERE workspace_id = ?", Integer.class, fixture.workspaceId);
            fixture.control.update("UPDATE ai_output_cache SET content_hash = ? WHERE workspace_id = ?",
                "b".repeat(64), fixture.workspaceId);

            fixture.workScope.inWorkspace(fixture.workspaceId, () -> {
                var initial = fixture.rows();
                transaction(fixture).executeWithoutResult(status -> {
                    Connection original = DataSourceUtils.getConnection(fixture.connections);
                    assertNotNull(fixture.persons.getOwnedPersonByIdForUpdate(fixture.workspaceId, fixture.personId));
                    assertEquals(1, fixture.persons.updateProcessingRestrictions(
                        fixture.workspaceId, fixture.personId, true, false));
                    int checkouts = fixture.connections.checkouts.get();
                    var prepared = fixture.tenantContext.getWorkspaceScopes();
                    assertNotNull(prepared);
                    var scope = fixture.scopes.getFreshForWorkspace(fixture.workspaceId);
                    assertSame(scope, fixture.scopes.getFreshForWorkspace(fixture.workspaceId));
                    assertEquals(fixture.cacheCases.stream().filter(row -> row.deleted()).count(),
                        fixture.cache.deleteForPerson(fixture.workspaceId, scope.workspaceIdsJson(), fixture.personId));
                    assertEquals(fixture.cacheCases.stream().filter(row -> !row.deleted()).map(row -> row.id()).sorted().toList(),
                        fixture.rows().stream().map(row -> ((Number) row.get("id")).intValue()).toList());
                    assertSame(original, DataSourceUtils.getConnection(fixture.connections));
                    assertEquals(fixture.tenantCatalog, catalog(original));
                    assertEquals(checkouts, fixture.connections.checkouts.get());
                    status.setRollbackOnly();
                });
                assertEquals(initial, fixture.rows());
                return null;
            });
            assertEquals(defaultCount, fixture.control.queryForObject(
                "SELECT COUNT(*) FROM ai_output_cache WHERE workspace_id = ?", Integer.class, fixture.workspaceId));
            assertEquals(0, fixture.pool.getHikariPoolMXBean().getActiveConnections());
            assertFalse(fixture.tenantContext.isResolved());
        }
    }

    @Test
    void missingOrMismatchedPreparedScopeRefusesWithoutAnotherCheckoutOrDeletion() throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(true, 1)) {
            fixture.workScope.inWorkspace(fixture.workspaceId, () -> {
                var initial = fixture.rows();
                transaction(fixture).executeWithoutResult(status -> {
                    int checkouts = fixture.connections.checkouts.get();
                    assertThrows(TenantScopeSnapshotMissingException.class,
                        () -> fixture.scopes.getFreshForWorkspace(fixture.workspaceIds.get(1)));
                    fixture.tenantContext.clear();
                    fixture.tenantContext.set(fixture.workspaceId, fixture.orgId, fixture.userId, "owner", fixture.tenantCatalog);
                    assertThrows(TenantScopeSnapshotMissingException.class,
                        () -> fixture.scopes.getFreshForWorkspace(fixture.workspaceId));
                    assertEquals(checkouts, fixture.connections.checkouts.get());
                    assertEquals(initial, fixture.rows());
                    status.setRollbackOnly();
                });
                return null;
            });
        }
    }

    @Test
    void failureAfterPurgeRollsBackRestrictionsAndCacheIncludingAllForeignRows() throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(true, 1)) {
            fixture.workScope.inWorkspace(fixture.workspaceId, () -> {
                var initial = fixture.rows();
                assertThrows(PurgeFailure.class, () -> transaction(fixture).executeWithoutResult(status -> {
                    assertNotNull(fixture.persons.getOwnedPersonByIdForUpdate(fixture.workspaceId, fixture.personId));
                    fixture.persons.updateProcessingRestrictions(fixture.workspaceId, fixture.personId, true, true);
                    var scope = fixture.scopes.getFreshForWorkspace(fixture.workspaceId);
                    assertTrue(fixture.cache.deleteForPerson(fixture.workspaceId, scope.workspaceIdsJson(), fixture.personId) > 0);
                    throw new PurgeFailure();
                }));
                assertEquals(initial, fixture.rows());
                assertEquals(1, fixture.tenant.queryForObject("SELECT COUNT(*) FROM person WHERE id = ? "
                    + "AND suspended_at IS NULL AND provision_ceased_at IS NULL", Integer.class, fixture.personId));
                return null;
            });
        }
    }

    @Test
    void frozenDmlOracleRefusesTheTenantCatalogBeforeExecuting() throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(true, 1)) {
            fixture.workScope.inWorkspace(fixture.workspaceId, () -> {
                var initial = fixture.rows();
                transaction(fixture).executeWithoutResult(status -> {
                    assertThrows(IllegalStateException.class,
                        () -> fixture.legacy.deleteForPerson(fixture.workspaceId, fixture.personId));
                    assertEquals(initial, fixture.rows());
                    status.setRollbackOnly();
                });
                return null;
            });
        }
    }

    private TransactionTemplate transaction(AiOutputCachePlaneFixture fixture) {
        var transaction = new TransactionTemplate(fixture.transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static String catalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class PurgeFailure extends RuntimeException { }
}
