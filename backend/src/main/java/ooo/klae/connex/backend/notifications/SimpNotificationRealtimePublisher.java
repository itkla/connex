package ooo.klae.connex.backend.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * In-process realtime publisher targeting the local STOMP simple broker.
 * Frames are addressed by the recipient's opaque routing token, which the handshake derives from
 * the same immutable account id, and Spring's user-destination resolution fans them out to every
 * live session of that account on this instance. Single-JVM by design; this is the bean a
 * cross-instance implementation replaces.
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
    private final RealtimeRoutingIdentityResolver routingIdentities;

    @Override
    public void send(int recipientId, RealtimeNotificationPayload payload) {
        User recipient = userMapper.getUserById(recipientId);
        if (recipient == null) {
            return;
        }
        messagingTemplate.convertAndSendToUser(
                routingIdentities.destinationFor(recipient.getId()), NOTIFICATIONS_QUEUE, payload);
    }
}
