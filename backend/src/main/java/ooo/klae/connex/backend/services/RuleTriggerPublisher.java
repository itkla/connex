package ooo.klae.connex.backend.services;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Publishes a {@link RuleTriggerEvent} for a committed entity change. Mutating services call this
 * after a successful write; {@link RuleTriggerListener} consumes it once the transaction commits.
 */
@Component
@RequiredArgsConstructor
public class RuleTriggerPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /** Records that {@code entityId} of {@code recordType} underwent {@code event} in the workspace. */
    public void publish(int workspaceId, String recordType, int entityId, String event) {
        eventPublisher.publishEvent(new RuleTriggerEvent(workspaceId, recordType, entityId, event));
    }
}
