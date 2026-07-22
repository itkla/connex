package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Notification;

class NotificationQuietHoursBypassPolicyTest {

    @Test
    void noCurrentNotificationTypeBypassesQuietHours() {
        NotificationQuietHoursBypassPolicy policy = new NotificationQuietHoursBypassPolicy();
        Notification notification = new Notification();
        notification.setType("task.due");
        notification.setSeverity("critical");

        assertFalse(policy.bypasses(notification));
    }
}
