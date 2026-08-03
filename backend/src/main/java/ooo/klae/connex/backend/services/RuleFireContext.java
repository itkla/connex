package ooo.klae.connex.backend.services;

/**
 * Per-fire data passed to a rule action: the workspace, the firing rule, the triggering record
 * (type + id), the member who is the action target (task assignee / notification recipient), and a
 * suffix that makes the fire's dedupe/notification keys unique.
 */
public record RuleFireContext(
    int workspaceId,
    int ruleId,
    String recordType,
    int entityId,
    int targetUserId,
    String dedupeSuffix
) implements AutomationActionContext {

    @Override
    public String notificationDedupeKey() {
        return "rule:" + ruleId + ":" + entityId + ":" + dedupeSuffix;
    }
}
