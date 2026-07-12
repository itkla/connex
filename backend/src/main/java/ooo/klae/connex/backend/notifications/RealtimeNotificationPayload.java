package ooo.klae.connex.backend.notifications;

import com.fasterxml.jackson.annotation.JsonInclude;

import ooo.klae.connex.backend.dto.NotificationDto;

/**
 * Frame pushed to a recipient's realtime queue. {@code created} carries a
 * brand-new notification the client should surface; {@code updated} and
 * {@code invalidated} signal a durable inbox change worth a silent refresh.
 * The dedupe key lets clients suppress duplicate created frames from concurrent
 * reconcile passes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeNotificationPayload(
        String kind,
        NotificationDto notification,
        String dedupeKey,
        long stateVersion) {

    /**
     * Builds a frame for a newly created notification.
     * @param notification the rendered notification
     * @param dedupeKey the notification's dedupe key
     * @param stateVersion the recipient's notification-state version
     * @return the payload
     */
    public static RealtimeNotificationPayload created(
            NotificationDto notification, String dedupeKey, long stateVersion) {
        return new RealtimeNotificationPayload("created", notification, dedupeKey, stateVersion);
    }

    /**
     * Builds a frame for a materially updated notification.
     * @param notification the rendered notification
     * @param dedupeKey the notification's dedupe key
     * @param stateVersion the recipient's notification-state version
     * @return the payload
     */
    public static RealtimeNotificationPayload updated(
            NotificationDto notification, String dedupeKey, long stateVersion) {
        return new RealtimeNotificationPayload("updated", notification, dedupeKey, stateVersion);
    }

    /**
     * Builds a frame for a durable notification-view change without a rendered item.
     * @param stateVersion the recipient's notification-state version
     * @return the payload
     */
    public static RealtimeNotificationPayload invalidated(long stateVersion) {
        return new RealtimeNotificationPayload("invalidated", null, null, stateVersion);
    }
}
