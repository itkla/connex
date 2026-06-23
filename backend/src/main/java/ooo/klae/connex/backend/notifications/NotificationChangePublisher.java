package ooo.klae.connex.backend.notifications;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Publishes source changes for after-commit reminder reconciliation.
 */
@Component
@RequiredArgsConstructor
public class NotificationChangePublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void publish(int workspaceId, String sourceType, Integer sourceId) {
        eventPublisher.publishEvent(new NotificationSourceChangedEvent(workspaceId, sourceType, sourceId));
    }
}