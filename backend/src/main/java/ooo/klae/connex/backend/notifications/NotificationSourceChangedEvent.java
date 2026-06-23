package ooo.klae.connex.backend.notifications;

/**
 * Signals that a workspace source may have changed reminder eligibility.
 */
public record NotificationSourceChangedEvent(
    int workspaceId,
    String sourceType,
    Integer sourceId
) {}