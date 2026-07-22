package ooo.klae.connex.backend.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * In-process realtime publisher targeting the local STOMP simple broker.
 * Frames are addressed by principal name, and Spring's user-destination
 * resolution fans them out to every live session of that user on this
 * instance. Single-JVM by design; this is the bean a cross-instance
 * implementation replaces.
 */
@Component
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
@RequiredArgsConstructor
public class SimpNotificationRealtimePublisher implements NotificationRealtimePublisher {

    private static final String NOTIFICATIONS_QUEUE = "/queue/notifications";

    private final SimpMessagingTemplate messagingTemplate;
    private final UserMapper userMapper;

    @Override
    public void send(int recipientId, RealtimeNotificationPayload payload) {
        User recipient = userMapper.getUserById(recipientId);
        if (recipient == null || recipient.getUsername() == null) {
            return;
        }
        messagingTemplate.convertAndSendToUser(recipient.getUsername(), NOTIFICATIONS_QUEUE, payload);
    }
}
