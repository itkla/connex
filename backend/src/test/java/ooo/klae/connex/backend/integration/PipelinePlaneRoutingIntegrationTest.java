package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.support.PipelineVisibilityParity;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/** Executes every Pipeline visibility statement in a three-table catalog without control data. */
class PipelinePlaneRoutingIntegrationTest {
    private RecordVisibilityPlaneFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.PIPELINES, true);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allNineStatementsKeepTheFrozenMatrixWithoutControlTables() throws Exception {
        for (String table : TablePlaneRegistry.CONTROL_PLANE_TABLES) {
            assertFalse(fixture.tenantTableExists(table), table);
        }
        assertEquals(List.of("pipeline", "pipeline_share", "stage"), fixture.controlJdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
            String.class, fixture.tenantCatalog));
        List<Map<String, Object>> expected = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            expected.add(PipelineVisibilityParity.legacy(fixture, fixture.pipelineIds.get(index), fixture.stageIds.get(index * 2)));
        }
        fixture.controlJdbc.update("DELETE FROM pipeline_share WHERE workspace_id = ?", fixture.workspaceId);
        fixture.controlJdbc.update("UPDATE pipeline SET name = 'Default Catalog Decoy' WHERE workspace_id = ?", fixture.workspaceId);
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        for (int index = 0; index < 5; index++) {
            int current = index;
            assertEquals(9, expected.get(index).size());
            new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status ->
                assertEquals(expected.get(current), PipelineVisibilityParity.tenant(fixture, scope,
                    fixture.pipelineIds.get(current), fixture.stageIds.get(current * 2)), "case " + current));
        }
    }

    @Test
    void sharedAndOwnedParentsHydrateOrderedStagesAndEmptyArrays() {
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        var shared = fixture.pipelineMapper.getPipelineById(fixture.workspaceId, scope, fixture.pipelineIds.get(1));
        assertEquals(fixture.workspaceIds.get(1), shared.getWorkspaceId());
        assertEquals(List.of(0, 2), java.util.Arrays.stream(shared.getStages()).map(stage -> stage.getPosition()).toList());
        var owned = fixture.pipelineMapper.getOwnedPipelineById(fixture.workspaceId, scope, fixture.pipelineIds.getFirst());
        assertEquals(2, owned.getStages().length);
        var empty = fixture.pipelineMapper.getOwnedPipelineById(fixture.workspaceId, scope, fixture.pipelineIds.get(5));
        assertNotNull(empty.getStages());
        assertEquals(0, empty.getStages().length);
        assertNull(fixture.pipelineMapper.getOwnedPipelineById(fixture.workspaceId, scope, fixture.pipelineIds.get(1)));
        assertTrue(fixture.pipelineMapper.getAllPipelines(fixture.workspaceId, scope).stream()
            .allMatch(pipeline -> pipeline.getStages() == null));
    }

    @Test
    void allStagesKeepsItsStageOwnerAnchorWhilePointReadsRequirePipelineIdentity() {
        int malformed = fixture.controlJdbc.queryForObject("SELECT MAX(id) + 1 FROM " + fixture.tenantCatalog + ".stage", Integer.class);
        fixture.controlJdbc.update("INSERT INTO " + fixture.tenantCatalog
            + ".stage (id, workspace_id, pipeline_id, name, position, is_success, is_failure) VALUES (?, ?, ?, ?, 99, FALSE, FALSE)",
            malformed, fixture.workspaceId, fixture.pipelineIds.get(3), "Malformed stage");
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        assertTrue(fixture.pipelineMapper.getAllStages(fixture.workspaceId, scope).stream().anyMatch(stage -> stage.getId() == malformed));
        assertNull(fixture.pipelineMapper.getVisibleStageById(fixture.workspaceId, scope, malformed));
        assertNull(fixture.pipelineMapper.getVisibleStageByIdForUpdate(fixture.workspaceId, scope, malformed));
    }

    @Test
    void authorizationLocksStayOnTheOriginalTenantConnectionUntilCommit() throws Exception {
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        int pipeline = fixture.pipelineIds.get(1);
        CountDownLatch attempting = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var waiting = new TransactionTemplate(fixture.transactionManager).execute(status -> {
                Connection original = DataSourceUtils.getConnection(fixture.routing);
                assertNotNull(fixture.pipelineMapper.getVisibleStageByIdForUpdate(fixture.workspaceId, scope, fixture.stageIds.get(2)));
                assertNotNull(fixture.pipelineMapper.getVisiblePipelineByIdForUpdate(fixture.workspaceId, scope, pipeline));
                assertSame(original, DataSourceUtils.getConnection(fixture.routing));
                try {
                    assertEquals(fixture.tenantCatalog, original.getCatalog());
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException(failure);
                }
                var future = executor.submit(() -> {
                    try (Connection connection = fixture.pool.getConnection()) {
                        connection.setCatalog(fixture.tenantCatalog);
                        connection.setAutoCommit(false);
                        try (var update = connection.prepareStatement("UPDATE pipeline SET name = name WHERE id = ?")) {
                            update.setInt(1, pipeline);
                            attempting.countDown();
                            update.executeUpdate();
                        } finally {
                            connection.rollback();
                        }
                    }
                    return null;
                });
                try {
                    assertTrue(attempting.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> future.get(250, TimeUnit.MILLISECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return future;
            });
            assertNotNull(waiting);
            waiting.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        fixture.tenantContext.clear();
        try (Connection connection = fixture.routing.getConnection()) {
            assertEquals(fixture.defaultCatalog, connection.getCatalog());
        }
    }
}
