package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowOutboxTarget;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowTriggerAdmissionServiceTest {

    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private WorkflowRuntimeProperties properties;

    private WorkflowTriggerAdmissionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerAdmissionService(outboxMapper, properties);
        lenient().when(properties.maxTriggerFanout()).thenReturn(2);
    }

    @Test
    void absentTriggerTypeConsumesNoCapacityInsteadOfFailingActivation() {
        service.requireCapacity(7, 101, "person", null);
        service.requireCapacity(7, 101, "person", new RuleTrigger());

        verifyNoInteractions(outboxMapper);
    }

    @Test
    void entityTriggerWithoutEventsConsumesNoCapacity() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");

        service.requireCapacity(7, 101, "person", trigger);

        verify(outboxMapper, never())
            .findEntityTargets(anyInt(), anyString(), anyString(), anyInt());
    }

    @Test
    void entityTriggerSkipsAnAbsentEventWithoutQueryingIntakeSelectors() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(Collections.singletonList(null));

        service.requireCapacity(7, 101, "person", trigger);

        verify(outboxMapper, never())
            .findEntityTargets(anyInt(), anyString(), anyString(), anyInt());
    }

    @Test
    void scheduleTriggerWithoutCadenceConsumesNoCapacity() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");

        service.requireCapacity(7, 101, "person", trigger);

        verify(outboxMapper, never()).findScheduleTargets(anyInt(), anyString(), anyInt());
    }

    @Test
    void entityTriggerAtTheLimitIsRefusedExcludingTheMutatedWorkflow() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("Entity_Change");
        trigger.setEvents(List.of("person.owner_changed"));
        when(outboxMapper.findEntityTargets(7, "person", "person.owner_changed", 3))
            .thenReturn(List.of(target(101), target(202), target(303)));

        assertThrows(ConflictException.class,
            () -> service.requireCapacity(7, 101, "person", trigger));
    }

    @Test
    void entityTriggerAtTheLimitIsAdmittedWhenOneTargetIsTheMutatedWorkflow() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("person.owner_changed"));
        when(outboxMapper.findEntityTargets(7, "person", "person.owner_changed", 3))
            .thenReturn(List.of(target(101), target(202)));

        service.requireCapacity(7, 101, "person", trigger);

        verify(outboxMapper).findEntityTargets(7, "person", "person.owner_changed", 3);
    }

    @Test
    void scheduleTriggerAtTheLimitIsRefusedExcludingTheMutatedWorkflow() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("Daily");
        when(outboxMapper.findScheduleTargets(7, "daily", 3))
            .thenReturn(List.of(target(101), target(202), target(303)));

        assertThrows(ConflictException.class,
            () -> service.requireCapacity(7, 404, "person", trigger));
    }

    @Test
    void unrecognizedTriggerTypeFailsClosedInsteadOfConsumingNoCapacity() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("webhook");

        assertThrows(ConflictException.class,
            () -> service.requireCapacity(7, 101, "person", trigger));

        verifyNoInteractions(outboxMapper);
    }

    private static WorkflowOutboxTarget target(int workflowId) {
        WorkflowOutboxTarget target = new WorkflowOutboxTarget();
        target.setWorkflowId(workflowId);
        return target;
    }
}
