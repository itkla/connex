package ooo.klae.connex.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.NotificationReconciliationService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Reconciles source changes asynchronously after the source transaction commits, off the request thread.
 */
@Component
@RequiredArgsConstructor
public class NotificationSourceChangedListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationSourceChangedListener.class);

    private final NotificationReconciliationService reconciliationService;
    private final TenantWorkScope tenantWorkScope;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSourceChanged(NotificationSourceChangedEvent event) {
        try {
            tenantWorkScope.inWorkspace(event.workspaceId(), () ->
                reconciliationService.reconcileWorkspace(event.workspaceId(), false));
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