package ooo.klae.connex.backend.services;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Publishes a {@link RuleTriggerEvent} for a committed entity change. Mutating services call this
 * after a successful write; {@link RuleTriggerListener} consumes it once the transaction commits.
 * A mutation made <em>by</em> a rule action (i.e. while {@link AutomationScope} is active) does not
 * publish, so automation cannot re-trigger rules and loop.
 */
@Component
@RequiredArgsConstructor
public class RuleTriggerPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final AutomationScope automationScope;

    /** Records that {@code entityId} of {@code recordType} underwent {@code event} in the workspace. */
    public void publish(int workspaceId, String recordType, int entityId, String event) {
        if (automationScope.isActive()) {
            return;
        }
        eventPublisher.publishEvent(new RuleTriggerEvent(
            workspaceId,
            recordType,
            entityId,
            event,
            java.util.UUID.randomUUID().toString(),
            java.time.Instant.now()));
    }
}
