package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.support.PipelineVisibilityParity;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;

/** Pins all nine visibility statements and owned-parent nesting to the original MySQL queries. */
class PipelineVisibilityMapperTest {
    private RecordVisibilityPlaneFixture fixture;
    private JdbcTemplate controlJdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.PIPELINES, false);
        controlJdbc = fixture.controlJdbc;
        fixture.installTenant();
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allNineStatementsMatchFrozenSqlAcrossTheShareMatrix() {
        assertMatrix();
        String scope = scope();
        assertEquals(List.of(fixture.pipelineIds.get(0), fixture.pipelineIds.get(1), fixture.pipelineIds.get(4),
            fixture.pipelineIds.get(5)), fixture.pipelineMapper.getAllPipelines(fixture.workspaceId, scope).stream()
                .map(pipeline -> pipeline.getId()).sorted().toList());
        for (int index : List.of(2, 3)) {
            int pipeline = fixture.pipelineIds.get(index);
            int stage = fixture.stageIds.get(index * 2);
            assertNull(fixture.pipelineMapper.getPipelineById(fixture.workspaceId, scope, pipeline));
            assertNull(fixture.pipelineMapper.getVisibleStageById(fixture.workspaceId, scope, stage));
            assertFalse(fixture.pipelineMapper.pipelineExists(fixture.workspaceId, scope, pipeline));
            assertTrue(fixture.pipelineMapper.getStagesByPipelineId(fixture.workspaceId, scope, pipeline).isEmpty());
        }
    }

    @Test
    void fullSharedAndOwnedParentsKeepOrderedStagesAndEmptyArrays() {
        String scope = scope();
        for (int index : List.of(0, 1, 4)) {
            int pipeline = fixture.pipelineIds.get(index);
            Map<String, Object> parameters = fixture.parameters();
            parameters.put("id", pipeline);
            var expected = fixture.legacy.<ooo.klae.connex.backend.beans.Pipeline>selectList("PipelineMapper.getPipelineById", parameters).getFirst();
            var actual = fixture.pipelineMapper.getPipelineById(fixture.workspaceId, scope, pipeline);
            assertEquals(PipelineVisibilityParity.pipeline(expected), PipelineVisibilityParity.pipeline(actual));
            assertEquals(List.of(0, 2), Arrays.stream(actual.getStages()).map(stage -> stage.getPosition()).toList());
        }
        var empty = fixture.pipelineMapper.getOwnedPipelineById(fixture.workspaceId, scope, fixture.pipelineIds.get(5));
        assertNotNull(empty.getStages());
        assertEquals(0, empty.getStages().length);
        var owned = fixture.pipelineMapper.getOwnedPipelineById(fixture.workspaceId, scope, fixture.pipelineIds.getFirst());
        assertEquals(2, owned.getStages().length);
        assertNull(fixture.pipelineMapper.getOwnedPipelineById(fixture.workspaceId, scope, fixture.pipelineIds.get(1)));
        assertTrue(fixture.pipelineMapper.getAllPipelines(fixture.workspaceId, scope).stream()
            .allMatch(pipeline -> pipeline.getStages() == null));
    }

    @Test
    void searchFiltersBeforeItsTenRowLimitAndPreservesNameOrder() throws Exception {
        for (int index = 0; index < 12; index++) {
            fixture.addPipeline(fixture.workspaceId, "Pipeline Search %02d".formatted(index));
            fixture.addPipeline(fixture.workspaceIds.get(2), "Pipeline Hidden %02d".formatted(index));
        }
        Map<String, Object> parameters = fixture.parameters();
        parameters.put("query", "%Pipeline%");
        var expected = fixture.legacy.<ooo.klae.connex.backend.beans.Pipeline>selectList("PipelineMapper.search", parameters);
        var actual = fixture.pipelineMapper.search(fixture.workspaceId, scope(), "%Pipeline%");
        assertEquals(10, actual.size());
        assertEquals(expected.stream().map(pipeline -> pipeline.getId()).toList(), actual.stream().map(pipeline -> pipeline.getId()).toList());
        assertTrue(actual.stream().noneMatch(pipeline -> pipeline.getName().contains("Hidden")));
    }

    @Test
    void workspaceAndOrganizationLifecycleDoNotTightenTheLegacyCeilings() {
        controlJdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        controlJdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE org_id = ?", fixture.orgId);
        assertMatrix();
    }

    @Test
    void revokedSharesAreRecheckedEvenWithAnExistingScope() {
        String scope = scope();
        int pipeline = fixture.pipelineIds.get(1);
        assertNotNull(fixture.pipelineMapper.getPipelineById(fixture.workspaceId, scope, pipeline));
        controlJdbc.update("DELETE FROM pipeline_share WHERE pipeline_id = ? AND workspace_id = ?", pipeline, fixture.workspaceId);
        assertNull(fixture.pipelineMapper.getPipelineById(fixture.workspaceId, scope, pipeline));
        assertNull(fixture.pipelineMapper.getVisibleStageById(fixture.workspaceId, scope, fixture.stageIds.get(2)));
        assertMatrix();
    }

    private void assertMatrix() {
        String scope = scope();
        for (int index = 0; index < 5; index++) {
            int pipeline = fixture.pipelineIds.get(index);
            int stage = fixture.stageIds.get(index * 2);
            Map<String, Object> expected = PipelineVisibilityParity.legacy(fixture, pipeline, stage);
            assertEquals(9, expected.size());
            new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status ->
                assertEquals(expected, PipelineVisibilityParity.tenant(fixture, scope, pipeline, stage), "case " + pipeline));
        }
    }

    private String scope() {
        return fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
    }
}
