package ooo.klae.connex.backend.notifications;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.beans.Notification;

/**
 * Fixed notification quiet-hours bypass classification.
 */
@Component
public class NotificationQuietHoursBypassPolicy {

    /**
     * Reports whether a notification may bypass quiet hours.
     * @param notification notification being delivered
     * @return false because no current notification type is a security bypass
     */
    public boolean bypasses(Notification notification) {
        return false;
    }
}
