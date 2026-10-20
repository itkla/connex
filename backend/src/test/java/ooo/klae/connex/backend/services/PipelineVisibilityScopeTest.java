package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/** One scope covers nested pipeline reads and every sibling-validation query before writes. */
class PipelineVisibilityScopeTest {
    private final PipelineMapper pipelines = mock(PipelineMapper.class);
    private final DealMapper deals = mock(DealMapper.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final ReferenceService references = mock(ReferenceService.class);
    private final RecordVisibilityScopeControlAccess scopes = ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    private final PipelineService service = new PipelineService(pipelines, scopes, deals, mock(AuditService.class), workspace, references);
    private final Pipeline pipeline = new Pipeline();

    @BeforeEach
    void setUp() {
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scopes.getForWorkspace(7)).thenReturn(new RecordVisibilityScope(90, List.of(7, 8, 9)));
        pipeline.setId(12);
        pipeline.setWorkspaceId(7);
        pipeline.setStages(new Stage[0]);
        when(references.hydrateDeals(eq(7), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void parentAndStageReadsReuseOneScope() {
        when(pipelines.getPipelineById(7, "[7,8,9]", 12)).thenReturn(pipeline);
        when(pipelines.getStagesByPipelineId(7, "[7,8,9]", 12)).thenReturn(List.of(stage("First", false)));
        assertEquals(1, service.getStagesByPipelineId(12).size());
        var order = inOrder(scopes, pipelines);
        order.verify(scopes).withWorkspaceScopeRead(eq(7), any());
        order.verify(scopes).getForWorkspace(7);
        order.verify(pipelines).getPipelineById(7, "[7,8,9]", 12);
        order.verify(pipelines).getStagesByPipelineId(7, "[7,8,9]", 12);
        verifyNoMoreInteractions(scopes);
    }

    @Test
    void creationReusesScopeAcrossOwnedParentAndBothSiblingChecks() {
        when(pipelines.getOwnedPipelineById(7, "[7,8,9]", 12)).thenReturn(pipeline);
        when(pipelines.getStagesByPipelineId(7, "[7,8,9]", 12)).thenReturn(List.of(stage("First", false)));
        service.createStage(12, stage("Won", true));
        var order = inOrder(scopes, pipelines);
        order.verify(scopes).getForWorkspace(7);
        order.verify(pipelines).getOwnedPipelineById(7, "[7,8,9]", 12);
        order.verify(pipelines, times(2)).getStagesByPipelineId(7, "[7,8,9]", 12);
        order.verify(pipelines).insertStage(any(Stage.class));
        verifyNoMoreInteractions(scopes);
    }

    @Test
    void replacementResolvesBeforeAnyStageMutation() {
        Stage existing = stage("First", false);
        existing.setId(41);
        when(pipelines.getOwnedPipelineById(7, "[7,8,9]", 12)).thenReturn(pipeline);
        when(pipelines.getStagesByPipelineId(7, "[7,8,9]", 12)).thenReturn(List.of(existing));
        service.replaceStages(12, List.of(41), List.of(existing));
        var order = inOrder(scopes, pipelines);
        order.verify(scopes).getForWorkspace(7);
        order.verify(pipelines).getOwnedPipelineById(7, "[7,8,9]", 12);
        order.verify(pipelines).getStagesByPipelineId(7, "[7,8,9]", 12);
        order.verify(pipelines, times(2)).updateStage(any(Stage.class));
        verifyNoMoreInteractions(scopes);
    }

    @Test
    void anInvisibleOwnedParentRefusesWithoutWriting() {
        assertThrows(ResourceNotFoundException.class, () -> service.createStage(12, stage("First", false)));
        verify(scopes).getForWorkspace(7);
        verify(pipelines, never()).insertStage(any(Stage.class));
    }

    private Stage stage(String name, boolean success) {
        Stage stage = new Stage();
        stage.setName(name);
        stage.setPipeline(pipeline);
        stage.setWorkspaceId(7);
        stage.setSuccess(success);
        return stage;
    }
}
