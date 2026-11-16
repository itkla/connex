package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/** Pipeline scope reuse in Deal callers without changing any Deal mapper statement. */
@ExtendWith(MockitoExtension.class)
class DealPipelineVisibilityScopeTest {
    @Mock private DealMapper dealMapper;
    @Mock private PipelineMapper pipelineMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private DuplicateDecisionLockService duplicateDecisionLockService;
    private final RecordVisibilityScopeControlAccess scopes =
        ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    @InjectMocks private DealService service;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "recordVisibilityScopeControlAccess", scopes);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scopes.getForWorkspace(7)).thenReturn(new RecordVisibilityScope(90, List.of(7, 8, 9)));
    }

    @Test
    void topOpenAndWonSummariesReuseOneScopeAcrossEveryRelatedLabel() {
        Deal open = deal(41);
        Deal won = deal(42);
        Pipeline pipeline = new Pipeline();
        pipeline.setId(12);
        pipeline.setName("Visible pipeline");
        Stage stage = new Stage();
        stage.setId(13);
        stage.setName("Visible stage");
        when(dealMapper.topOpenDeals(7, "USD", null)).thenReturn(List.of(open));
        when(dealMapper.topWonDeals(7, "USD", null)).thenReturn(List.of(won));
        when(pipelineMapper.getPipelineById(7, "[7,8,9]", 12)).thenReturn(pipeline);
        when(pipelineMapper.getVisibleStageById(7, "[7,8,9]", 13)).thenReturn(stage);

        var result = service.getTopDeals("USD", null);

        assertEquals(1, result.topOpen().size());
        assertEquals(1, result.topWon().size());
        assertEquals("Visible pipeline", result.topOpen().getFirst().getPipelineName());
        assertEquals("Visible stage", result.topWon().getFirst().getStageName());
        verify(scopes, times(1)).getForWorkspace(7);
        verify(pipelineMapper, times(2)).getPipelineById(7, "[7,8,9]", 12);
        verify(pipelineMapper, times(2)).getVisibleStageById(7, "[7,8,9]", 13);
    }

    @Test
    void creationPreparesPipelineScopeBeforeAuthorizationAndOrganizationMutex() {
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        var stopped = new IllegalStateException("Stop after the lock-order checkpoint");
        doThrow(stopped).when(duplicateDecisionLockService).lockCurrentOrganization();

        assertSame(stopped, assertThrows(IllegalStateException.class, () -> service.create(deal(41))));

        var order = inOrder(scopes, workspaceService, duplicateDecisionLockService);
        order.verify(scopes).getForWorkspace(7);
        order.verify(workspaceService).lockAndRequirePermissions(eq(7), anyMap());
        order.verify(duplicateDecisionLockService).lockCurrentOrganization();
        verify(scopes, times(1)).getForWorkspace(7);
    }

    private static Deal deal(int id) {
        Deal deal = new Deal();
        deal.setId(id);
        deal.setPipelineId(12);
        deal.setStageId(13);
        return deal;
    }
}
