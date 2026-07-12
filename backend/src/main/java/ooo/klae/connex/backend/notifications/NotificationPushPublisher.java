package ooo.klae.connex.backend.notifications;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.NotificationDto;

/**
 * Publishes realtime push events for after-commit delivery to the recipient's
 * live sessions. Publishing is transaction-aware: the listener sends only once
 * the surrounding transaction commits, so a rolled-back notification is never
 * pushed.
 */
@Component
@RequiredArgsConstructor
public class NotificationPushPublisher {
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Queues a {@code created} frame for a newly persisted notification.
     * @param recipientId the recipient user id
     * @param notification the rendered notification
     * @param dedupeKey the notification's dedupe key
     */
    public void created(int recipientId, NotificationDto notification, String dedupeKey) {
        eventPublisher.publishEvent(new NotificationPushEvent(
                recipientId, "created", notification, dedupeKey));
    }

    /**
     * Queues an {@code updated} frame for a materially changed notification.
     * @param recipientId the recipient user id
     * @param notification the rendered notification
     * @param dedupeKey the notification's dedupe key
     */
    public void updated(int recipientId, NotificationDto notification, String dedupeKey) {
        eventPublisher.publishEvent(new NotificationPushEvent(
                recipientId, "updated", notification, dedupeKey));
    }

    /**
     * Queues an {@code invalidated} frame when the durable inbox changed without a rendered item.
     * @param recipientId the recipient user id
     */
    public void invalidated(int recipientId) {
        eventPublisher.publishEvent(new NotificationPushEvent(
                recipientId, "invalidated", null, null));
    }
}
