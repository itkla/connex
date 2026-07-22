package ooo.klae.connex.backend.notifications;

import ooo.klae.connex.backend.beans.Notification;

/**
 * Delivery boundary for a generated notification.
 */
public interface NotificationDispatcher {
    String channel();

    int dispatch(Notification notification);
}
