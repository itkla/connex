package ooo.klae.connex.backend.services;

/** Canonical action context with a run-and-node notification idempotency key. */
public record WorkflowActionContext(
    int workspaceId,
    long runId,
    String nodeId,
    String recordType,
    int entityId,
    int targetUserId
) implements AutomationActionContext {

    @Override
    public String notificationDedupeKey() {
        return "workflow:" + runId + ":node:" + nodeId;
    }
}
