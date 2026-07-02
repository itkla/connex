package ooo.klae.connex.backend.notifications;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;

/**
 * The single fan-out point for a generated notification. The {@code in_app}
 * channel always delivers (it is the inbox). Any other channel — currently
 * {@code email} — delivers only when the recipient has opted in for that
 * (type, channel) preference AND the notification is new: reconciliation
 * re-dispatches idempotent reminders every cycle, so email is gated to the
 * first occurrence (by {@code dedupe_key}) to avoid repeat sends. The in-app
 * dispatch propagates its failure (it is the load-bearing inbox, and callers such
 * as the rule engine record that failure); secondary channels are isolated so an
 * email outage never blocks in-app delivery.
 *
 * <p>The first-occurrence check is a pre-read: two reconcile passes for the same
 * workspace running concurrently can each observe "new" and both email once. This
 * is bounded to a single duplicate per brand-new reminder (repeat reminders are
 * always suppressed) and is tracked for a claim-based hardening.
 */
@Component
@RequiredArgsConstructor
public class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);
    private static final String IN_APP = "in_app";

    private final List<NotificationDispatcher> dispatchers;
    private final NotificationMapper notificationMapper;
    private final PreferenceMapper preferenceMapper;

    /**
     * Delivers a notification across every eligible channel.
     * @param notification the generated notification
     */
    public void deliver(Notification notification) {
        boolean firstOccurrence = isFirstOccurrence(notification);

        for (NotificationDispatcher dispatcher : dispatchers) {
            if (IN_APP.equals(dispatcher.channel())) {
                dispatcher.dispatch(notification);
            }
        }

        if (!firstOccurrence) {
            return;
        }
        for (NotificationDispatcher dispatcher : dispatchers) {
            if (IN_APP.equals(dispatcher.channel())) {
                continue;
            }
            if (preferenceMapper.isEnabledOptIn(
                    notification.getRecipientId(), notification.getType(), dispatcher.channel())) {
                safeDispatch(dispatcher, notification);
            }
        }
    }

    private boolean isFirstOccurrence(Notification notification) {
        if (notification.getDedupeKey() == null || notification.getDedupeKey().isBlank()) {
            return true;
        }
        return !notificationMapper.existsByDedupe(
                notification.getWorkspaceId(), notification.getRecipientId(), notification.getDedupeKey());
    }

    private void safeDispatch(NotificationDispatcher dispatcher, Notification notification) {
        try {
            dispatcher.dispatch(notification);
        } catch (RuntimeException e) {
            log.warn("Notification dispatch on channel {} failed for recipient {}: {}",
                    dispatcher.channel(), notification.getRecipientId(), e.getMessage());
        }
    }
}
