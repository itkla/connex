package ooo.klae.connex.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Pushes queued realtime frames after the originating transaction commits,
 * off the request thread. A push failure is logged and swallowed — realtime is
 * a live view over the durable inbox, and clients fall back to polling.
 *
 * <p>The state-version read is a scoped statement running with no request
 * scope, so it needs an explicit catalog scope of its own (#995).
 * {@code notification_recipient_state} is a control-plane table and the event
 * carries no workspace, so the read is deliberately {@code unrouted} rather
 * than routed to a workspace catalog. Without it the whole listener would
 * throw and swallow the failure the moment catalog routing is enabled.
 */
@Component
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
@RequiredArgsConstructor
public class NotificationPushListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationPushListener.class);

    private final NotificationRealtimePublisher realtimePublisher;
    private final NotificationMapper notificationMapper;
    private final TenantWorkScope tenantWorkScope;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPush(NotificationPushEvent event) {
        try {
            long stateVersion = tenantWorkScope.unrouted(
                () -> notificationMapper.getStateVersion(event.recipientId()));
            RealtimeNotificationPayload payload = new RealtimeNotificationPayload(
                event.kind(), event.notification(), event.dedupeKey(), stateVersion);
            realtimePublisher.send(event.recipientId(), payload);
        } catch (RuntimeException exception) {
            log.warn("Realtime notification push failed for recipient {}: {}",
                    event.recipientId(), exception.getMessage());
        }
    }
}
