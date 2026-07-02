package ooo.klae.connex.backend.notifications;

import com.fasterxml.jackson.annotation.JsonInclude;

import ooo.klae.connex.backend.dto.NotificationDto;

/**
 * Frame pushed to a recipient's realtime queue. {@code created} carries a
 * brand-new notification the client should surface; {@code updated} signals a
 * materially changed one (severity escalation or revival) worth a silent
 * refresh. The dedupe key lets clients suppress duplicate frames from
 * concurrent reconcile passes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeNotificationPayload(
        String kind,
        NotificationDto notification,
        String dedupeKey) {

    /**
     * Builds a frame for a newly created notification.
     * @param notification the rendered notification
     * @param dedupeKey the notification's dedupe key
     * @return the payload
     */
    public static RealtimeNotificationPayload created(NotificationDto notification, String dedupeKey) {
        return new RealtimeNotificationPayload("created", notification, dedupeKey);
    }

    /**
     * Builds a frame for a materially updated notification.
     * @param notification the rendered notification
     * @param dedupeKey the notification's dedupe key
     * @return the payload
     */
    public static RealtimeNotificationPayload updated(NotificationDto notification, String dedupeKey) {
        return new RealtimeNotificationPayload("updated", notification, dedupeKey);
    }
}
