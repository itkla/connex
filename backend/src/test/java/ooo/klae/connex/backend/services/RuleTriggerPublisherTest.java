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
        AutomationScope scope = new AutomationScope();
        RuleTriggerPublisher publisher = new RuleTriggerPublisher(events, scope);

        boolean previous = scope.enter();
        publisher.publish(1, "deal", 5, "deal.stage_changed");
        scope.restore(previous);
        verifyNoInteractions(events);

        publisher.publish(1, "deal", 5, "deal.stage_changed");
        verify(events).publishEvent(any(RuleTriggerEvent.class));
    }
}
