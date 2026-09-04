package ooo.klae.connex.backend.services;

import java.util.Set;

import ooo.klae.connex.backend.tenant.Permission;

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
    String dedupeSuffix,
    Integer lockedActorUserId,
    Set<Permission> lockedPermissions
) implements AutomationActionContext {

    public RuleFireContext {
        lockedPermissions = Set.copyOf(lockedPermissions);
    }

    public RuleFireContext(
            int workspaceId,
            int ruleId,
            String recordType,
            int entityId,
            int targetUserId,
            String dedupeSuffix) {
        this(
            workspaceId,
            ruleId,
            recordType,
            entityId,
            targetUserId,
            dedupeSuffix,
            null,
            Set.of());
    }

    boolean hasLockedAuthorization() {
        return lockedActorUserId != null;
    }

    @Override
    public String notificationDedupeKey() {
        return "rule:" + ruleId + ":" + entityId + ":" + dedupeSuffix;
    }
}
