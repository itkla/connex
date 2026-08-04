package ooo.klae.connex.backend.services;

/** Stable record and idempotency context shared by legacy and canonical action execution. */
public interface AutomationActionContext {

    int workspaceId();

    String recordType();

    int entityId();

    int targetUserId();

    String notificationDedupeKey();
}
