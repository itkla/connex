package ooo.klae.connex.backend.services;

import java.util.Set;

import ooo.klae.connex.backend.tenant.Permission;

/** Canonical action context with a run-and-node notification idempotency key. */
public record WorkflowActionContext(
    int workspaceId,
    long runId,
    String nodeId,
    String recordType,
    int entityId,
    int targetUserId,
    int actorUserId,
    Set<Permission> lockedPermissions
) implements AutomationActionContext {

    public WorkflowActionContext {
        lockedPermissions = Set.copyOf(lockedPermissions);
    }

    public WorkflowActionContext(
            int workspaceId,
            long runId,
            String nodeId,
            String recordType,
            int entityId,
            int targetUserId) {
        this(workspaceId, runId, nodeId, recordType, entityId, targetUserId, targetUserId, Set.of());
    }

    @Override
    public String notificationDedupeKey() {
        return "workflow:" + runId + ":node:" + nodeId;
    }
}
