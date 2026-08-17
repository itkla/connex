package ooo.klae.connex.backend.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class RuleTriggerPublisherTest {

    @Test
    void suppressesTriggerWhileAutomationActive() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WorkflowTriggerIntake intake = mock(WorkflowTriggerIntake.class);
        AutomationScope scope = new AutomationScope();
        RuleTriggerPublisher publisher = new RuleTriggerPublisher(
            intake, events, scope, new WorkflowDocumentAutomationGate(true));

        boolean previous = scope.enter();
        publisher.publish(1, "deal", 5, "deal.stage_changed");
        scope.restore(previous);
        verifyNoInteractions(events);
        verifyNoInteractions(intake);

        publisher.publish(1, "deal", 5, "deal.stage_changed");
        verify(intake).enqueue(any(WorkflowTriggerDispatch.EntityChange.class));
        verify(events).publishEvent(any(RuleTriggerEvent.class));
    }

    @Test
    void closedDocumentFenceEnqueuesNoDocumentTrigger() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WorkflowTriggerIntake intake = mock(WorkflowTriggerIntake.class);
        RuleTriggerPublisher publisher = new RuleTriggerPublisher(
            intake, events, new AutomationScope(), new WorkflowDocumentAutomationGate(false));

        publisher.publish(1, "document", 9, "document.approved");
        verifyNoInteractions(events);
        verifyNoInteractions(intake);

        publisher.publish(1, "deal", 5, "deal.won");
        verify(intake).enqueue(any(WorkflowTriggerDispatch.EntityChange.class));
        verify(events).publishEvent(any(RuleTriggerEvent.class));
    }

    @Test
    void openDocumentFenceEnqueuesDocumentTriggers() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WorkflowTriggerIntake intake = mock(WorkflowTriggerIntake.class);
        RuleTriggerPublisher publisher = new RuleTriggerPublisher(
            intake, events, new AutomationScope(), new WorkflowDocumentAutomationGate(true));

        publisher.publish(1, "document", 9, "document.approved");

        verify(intake).enqueue(any(WorkflowTriggerDispatch.EntityChange.class));
        verify(events).publishEvent(any(RuleTriggerEvent.class));
    }
}
