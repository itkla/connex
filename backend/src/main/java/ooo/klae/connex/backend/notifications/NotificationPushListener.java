package ooo.klae.connex.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

/**
 * Pushes queued realtime frames after the originating transaction commits,
 * off the request thread. A push failure is logged and swallowed — realtime is
 * a live view over the durable inbox, and clients fall back to polling.
 */
@Component
@RequiredArgsConstructor
public class NotificationPushListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationPushListener.class);

    private final NotificationRealtimePublisher realtimePublisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPush(NotificationPushEvent event) {
        try {
            realtimePublisher.send(event.recipientId(), event.payload());
        } catch (RuntimeException exception) {
            log.warn("Realtime notification push failed for recipient {}: {}",
                    event.recipientId(), exception.getMessage());
        }
    }
}
