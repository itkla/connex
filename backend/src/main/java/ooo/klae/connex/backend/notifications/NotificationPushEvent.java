package ooo.klae.connex.backend.notifications;

/**
 * Signals that a realtime frame should be pushed to a recipient once the
 * surrounding transaction commits.
 */
public record NotificationPushEvent(
        int recipientId,
        RealtimeNotificationPayload payload
) {}
