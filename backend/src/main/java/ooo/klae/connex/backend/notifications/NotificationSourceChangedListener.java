package ooo.klae.connex.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.NotificationReconciliationService;

/**
 * Reconciles source changes after the source transaction commits.
 */
@Component
@RequiredArgsConstructor
public class NotificationSourceChangedListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationSourceChangedListener.class);

    private final NotificationReconciliationService reconciliationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSourceChanged(NotificationSourceChangedEvent event) {
        try {
            reconciliationService.reconcileWorkspace(event.workspaceId());
        } catch (Exception exception) {
            log.error(
                "Notification reconciliation failed after source change workspace={} sourceType={} sourceId={}",
                event.workspaceId(),
                event.sourceType(),
                event.sourceId(),
                exception
            );
        }
    }
}