package ooo.klae.connex.backend.services;

/**
 * Published after a workspace mutation commits, so the rule engine can run the entity-change rules
 * that match it. {@code recordType} + {@code event} name the change (e.g. {@code deal} /
 * {@code deal.stage_changed}); {@code entityId} is the changed record.
 */
public record RuleTriggerEvent(
    int workspaceId,
    String recordType,
    int entityId,
    String event,
    String triggerKey,
    java.time.Instant occurredAt
) {
}
