package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Persists a durable trigger envelope in the source mutation transaction. A mutation made
 * <em>by</em> a workflow action does not enqueue another trigger, preventing automation loops, and a
 * record type still behind its rolling-deployment fence enqueues nothing at all.
 */
@Component
@RequiredArgsConstructor
public class RuleTriggerPublisher {

    private final WorkflowTriggerIntake workflowTriggerIntake;
    private final ApplicationEventPublisher eventPublisher;
    private final AutomationScope automationScope;
    private final WorkflowDocumentAutomationGate documentAutomationGate;

    /** Records that {@code entityId} of {@code recordType} underwent {@code event} in the workspace. */
    public void publish(int workspaceId, String recordType, int entityId, String event) {
        if (automationScope.isActive() || !documentAutomationGate.permits(recordType)) {
            return;
        }
        String triggerKey = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        workflowTriggerIntake.enqueue(new WorkflowTriggerDispatch.EntityChange(
            workspaceId,
            recordType,
            entityId,
            event,
            triggerKey,
            occurredAt));
        eventPublisher.publishEvent(new RuleTriggerEvent(
            workspaceId,
            recordType,
            entityId,
            event,
            triggerKey,
            occurredAt));
    }
}
