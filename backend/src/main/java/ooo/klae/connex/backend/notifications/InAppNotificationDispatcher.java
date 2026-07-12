package ooo.klae.connex.backend.notifications;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.mappers.NotificationMapper;

/**
 * Persists the active in-app delivery.
 */
@Component
@RequiredArgsConstructor
public class InAppNotificationDispatcher implements NotificationDispatcher {
    private final NotificationMapper notificationMapper;

    @Override
    public String channel() {
        return "in_app";
    }

    @Override
    public int dispatch(Notification notification) {
        return notificationMapper.upsert(notification);
    }
}
