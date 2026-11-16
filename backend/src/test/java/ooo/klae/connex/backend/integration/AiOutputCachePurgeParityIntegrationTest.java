package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.support.AiOutputCachePlaneFixture;

/** Real-MySQL DML parity against the frozen original, with identical states and complete rollback. */
class AiOutputCachePurgeParityIntegrationTest {
    @ParameterizedTest
    @CsvSource({"active,false", "tearing_down,false", "active,true", "tearing_down,true"})
    void everyBranchKeepsTheOriginalCountAndEntireResultingTable(String lifecycle, boolean restricted) throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(false, 2)) {
            fixture.control.update("UPDATE organization SET lifecycle_state = ? WHERE id = ?", lifecycle, fixture.orgId);
            fixture.control.update("UPDATE workspace SET lifecycle_state = ? WHERE id = ?", lifecycle, fixture.workspaceId);
            if (restricted) {
                fixture.control.update("UPDATE person SET archived_at = NOW(), suspended_at = NOW(), "
                    + "provision_ceased_at = NOW() WHERE id = ?", fixture.personId);
            }
            assertParity(fixture);
        }
    }

    @Test
    void organizationWideNarrativesHaveNoNewLimitAndDuplicateLinkPathsDeleteOnce() throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(false, 2)) {
            for (int index = 0; index < 120; index++) {
                fixture.addCache(fixture.workspaceIds.get(2), "report.narrative:v4:ja", 8000 + index, 0, true);
            }
            assertParity(fixture);
        }
    }

    @Test
    void emptyOrForeignAllowlistCannotDeleteEvenTheOrganizationWideReportBranch() throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(false, 1)) {
            List<Map<String, Object>> before = fixture.rows();
            TransactionTemplate transaction = transaction(fixture);
            transaction.executeWithoutResult(status -> {
                for (String scope : List.of("[]", "[" + fixture.workspaceIds.get(3) + "]")) {
                    assertEquals(0, fixture.cache.deleteForPerson(fixture.workspaceId, scope, fixture.personId));
                    assertEquals(before, fixture.rows());
                }
                status.setRollbackOnly();
            });
            assertEquals(before, fixture.rows());
        }
    }

    private void assertParity(AiOutputCachePlaneFixture fixture) {
        List<Map<String, Object>> initial = fixture.rows();
        transaction(fixture).executeWithoutResult(status -> {
            var connection = DataSourceUtils.getConnection(fixture.connections);
            Object savepoint = status.createSavepoint();
            int original = fixture.legacy.deleteForPerson(fixture.workspaceId, fixture.personId);
            List<Map<String, Object>> expected = fixture.rows();
            status.rollbackToSavepoint(savepoint);
            status.releaseSavepoint(savepoint);
            fixture.clearSessions();
            assertEquals(initial, fixture.rows());

            String scope = fixture.scopes.getFreshForWorkspace(fixture.workspaceId).workspaceIdsJson();
            int rewritten = fixture.cache.deleteForPerson(fixture.workspaceId, scope, fixture.personId);

            assertTrue(original > 0);
            assertEquals(fixture.cacheCases.stream().filter(row -> row.deleted()).count(), original);
            assertEquals(original, rewritten);
            assertSame(connection, DataSourceUtils.getConnection(fixture.connections));
            assertEquals(expected, fixture.rows());
            assertEquals(fixture.cacheCases.stream().filter(row -> !row.deleted()).map(row -> row.id()).sorted().toList(),
                fixture.rows().stream().map(row -> ((Number) row.get("id")).intValue()).toList());
            status.setRollbackOnly();
        });
        assertEquals(initial, fixture.rows());
    }

    private TransactionTemplate transaction(AiOutputCachePlaneFixture fixture) {
        var transaction = new TransactionTemplate(fixture.transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }
}
