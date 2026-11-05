package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowOperationsLegacyRunView;
import ooo.klae.connex.backend.beans.WorkflowOperationsRunView;
import ooo.klae.connex.backend.dto.WorkflowRunSummaryDto;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;

/** Exercises ledger identity during pagination and unavailable persisted ownership. */
@ExtendWith(MockitoExtension.class)
class WorkflowOperationsServiceTest {
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper versionMapper;
    @Mock private WorkflowOperationsMapper operationsMapper;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private WorkflowRunReadService runReadService;
    @Mock private WorkspaceService workspaceService;
    @Mock private WorkflowRuntimeProperties properties;
    private WorkflowOperationsService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowOperationsService(workflowMapper, versionMapper, operationsMapper,
            canonicalizer, runReadService, workspaceService, properties);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
    }

    @Test
    void collidingLedgerIdsAndTimestampsRemainDistinctAcrossPages() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 1, 12, 0);
        WorkflowOperationsRunView canonical = new WorkflowOperationsRunView();
        canonical.setId(1L);
        canonical.setWorkflowId(11);
        canonical.setStartedAt(time);
        WorkflowOperationsLegacyRunView legacy = new WorkflowOperationsLegacyRunView();
        legacy.setId(1);
        legacy.setWorkflowId(11);
        legacy.setStatus("matched");
        WorkflowOperationsLegacyRunView legacyTwo = new WorkflowOperationsLegacyRunView();
        legacyTwo.setId(2);
        legacyTwo.setWorkflowId(11);
        legacyTwo.setStatus("matched");
        when(runReadService.legacySummary(legacyTwo)).thenReturn(summary("legacy", 2, time));
        when(runReadService.canonicalSummary(canonical)).thenReturn(summary("canonical", 1, time));
        when(runReadService.legacySummary(legacy)).thenReturn(summary("legacy", 1, time));
        when(operationsMapper.getOperationsRuns(eq(7), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(2))).thenReturn(List.of(canonical));
        when(operationsMapper.getLegacyOperationsRuns(eq(7), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(2))).thenReturn(List.of(legacyTwo, legacy));

        var first = service.runs(null, null, null, 1, null);

        assertEquals("canonical-1", first.items().getFirst().run().runKey());
        when(operationsMapper.getOperationsRuns(eq(7), isNull(), isNull(), isNull(),
            eq(time), eq(1L), eq(2))).thenReturn(List.of());
        when(operationsMapper.getLegacyOperationsRuns(eq(7), isNull(), isNull(), isNull(),
            eq(time), eq(Long.MAX_VALUE), eq(2))).thenReturn(List.of(legacyTwo, legacy));
        var second = service.runs(null, null, null, 1, first.nextCursor());
        assertEquals("legacy-2", second.items().getFirst().run().runKey());
        when(operationsMapper.getOperationsRuns(eq(7), isNull(), isNull(), isNull(),
            eq(time), eq(0L), eq(2))).thenReturn(List.of());
        when(operationsMapper.getLegacyOperationsRuns(eq(7), isNull(), isNull(), isNull(),
            eq(time), eq(2L), eq(2))).thenReturn(List.of(legacy));
        var third = service.runs(null, null, null, 1, second.nextCursor());
        assertEquals("legacy-1", third.items().getFirst().run().runKey());
        assertNull(third.nextCursor());
        assertFalse(third.items().getFirst().run().stepDetailAvailable());
    }

    @Test
    void enabledCanonicalOwnerIsUnavailableWithoutChangingIt() {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setRuntimeOwner("canonical");
        workflow.setEnabled(true);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(versionMapper.listByWorkflow(7, 11)).thenReturn(List.of());
        when(operationsMapper.getOpenInterventionsByWorkflow(7, 11, 50)).thenReturn(List.of());

        var detail = service.workflow(11);

        assertEquals("unavailable", detail.health().state());
        assertEquals(List.of("execution_unavailable"), detail.health().signals());
        assertEquals("canonical", workflow.getRuntimeOwner());
    }

    private static WorkflowRunSummaryDto summary(String source, long id, LocalDateTime time) {
        return new WorkflowRunSummaryDto(source + "-" + id, source, "succeeded", null, null, null,
            null, time, time, 0L, null, "canonical".equals(source));
    }
}
