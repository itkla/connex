package ooo.klae.connex.backend.services;

/**
 * Compatibility event emitted after durable intake joins the source transaction. While the
 * durable worker gate is disabled, the legacy owner consumes it after commit using the same key.
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
