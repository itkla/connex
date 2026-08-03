package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowOutboxTarget;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowTriggerIntakeTransactionTest {

    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private SegmentMapper segmentMapper;
    @Mock private WorkflowRuntimeProperties properties;

    private WorkflowTriggerIntakeTransaction service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerIntakeTransaction(
            outboxMapper, segmentMapper, properties);
        when(properties.maxTriggerFanout()).thenReturn(128);
    }

    @Test
    void entityMutationCreatesOnePinnedTargetPerWorkflow() {
        when(outboxMapper.findEntityTargets(
            7, "company", "company.updated", 129)).thenReturn(List.of(
                target(11, 19L, 3L), target(12, 23L, 5L)));
        WorkflowTriggerDispatch.EntityChange dispatch =
            new WorkflowTriggerDispatch.EntityChange(
                7,
                "company",
                41,
                "company.updated",
                "event-7",
                Instant.parse("2026-08-02T12:00:00Z"));

        WorkflowDispatchResult result = service.enqueueEntityChange(dispatch);

        assertEquals(2, result.candidates());
        verify(outboxMapper).ensureWorkspaceGate(7);
        ArgumentCaptor<WorkflowTriggerOutbox> inserted = ArgumentCaptor.forClass(
            WorkflowTriggerOutbox.class);
        verify(outboxMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        assertEquals(List.of(11, 12), inserted.getAllValues().stream()
            .map(WorkflowTriggerOutbox::getWorkflowId).toList());
        assertEquals("entity:event-7", inserted.getAllValues().getFirst().getDedupeKey());
        assertEquals(41, inserted.getAllValues().getFirst().getRecordId());
    }

    @Test
    void theOneHundredTwentyNinthTargetFailsBeforeAnyInsert() {
        List<WorkflowOutboxTarget> targets = new ArrayList<>();
        for (int id = 1; id <= 129; id++) {
            targets.add(target(id, id, 0L));
        }
        when(outboxMapper.findEntityTargets(
            7, "company", "company.updated", 129)).thenReturn(targets);

        assertThrows(
            WorkflowExecutionException.class,
            () -> service.enqueueEntityChange(new WorkflowTriggerDispatch.EntityChange(
                7,
                "company",
                41,
                "company.updated",
                "event-7",
                Instant.parse("2026-08-02T12:00:00Z"))));

        verify(outboxMapper, never()).ensureWorkspaceGate(7);
        verify(outboxMapper, never()).insert(any());
    }

    private static WorkflowOutboxTarget target(
            int workflowId, long versionId, long generation) {
        WorkflowOutboxTarget target = new WorkflowOutboxTarget();
        target.setWorkflowId(workflowId);
        target.setWorkflowVersionId(versionId);
        target.setRuntimeGeneration(generation);
        target.setRecordType("company");
        return target;
    }
}
