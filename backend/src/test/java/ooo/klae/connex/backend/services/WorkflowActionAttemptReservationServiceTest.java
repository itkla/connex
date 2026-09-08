package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepAttempt;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowActionRetryPolicy.RetrySafety;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

@ExtendWith(MockitoExtension.class)
class WorkflowActionAttemptReservationServiceTest {

    @Mock private WorkflowRunMapper runMapper;
    @Mock private WorkflowActionRetryPolicy retryPolicy;
    @Mock private WorkflowRuntimeProperties properties;

    @Test
    void reservationPersistsUtcStepAndAttemptStartsInHonolulu() {
        WorkflowActionAttemptReservationService service =
            new WorkflowActionAttemptReservationService(runMapper, retryPolicy, properties);
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setId(31L);
        run.setCurrentNodeId("action");
        RuleAction action = new RuleAction();
        action.setType("create_task");
        CompiledWorkflow compiled = mock(CompiledWorkflow.class);
        when(compiled.node("action")).thenReturn(
            new WorkflowNode.Action("action", action));
        when(runMapper.getOwnedByIdForUpdate(7, 31L, "owner")).thenReturn(run);
        when(retryPolicy.safety(action)).thenReturn(RetrySafety.TRANSACTIONAL);
        when(runMapper.nextSequence(7, 31L)).thenReturn(2);
        doAnswer(call -> {
            call.<WorkflowStepRun>getArgument(0).setId(41L);
            return null;
        }).when(runMapper).insertStep(any());

        TimeZone originalTimezone = TimeZone.getDefault();
        WorkflowActionAttemptReservationService.Reservation reservation;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            reservation = service.reserve(7, 31L, "action", "owner", compiled);
        } finally {
            TimeZone.setDefault(originalTimezone);
        }

        assertNotNull(reservation);
        assertEquals(41L, reservation.stepRunId());
        ArgumentCaptor<WorkflowStepRun> step = ArgumentCaptor.forClass(WorkflowStepRun.class);
        ArgumentCaptor<WorkflowStepAttempt> attempt = ArgumentCaptor.forClass(
            WorkflowStepAttempt.class);
        verify(runMapper).insertStep(step.capture());
        verify(runMapper).insertAttempt(attempt.capture());
        LocalDateTime utcFloor = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        assertTrue(step.getValue().getStartedAt().isAfter(utcFloor));
        assertEquals(step.getValue().getStartedAt(), attempt.getValue().getStartedAt());
    }
}
