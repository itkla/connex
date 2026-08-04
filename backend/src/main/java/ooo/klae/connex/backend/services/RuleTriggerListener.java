package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Runs entity-change rules after the triggering mutation commits, off the request thread. Mirrors the
 * notification subsystem's after-commit async pattern, so rules only ever see committed state and
 * never block the originating request. Never propagates failures back to the publisher.
 */
@Component
@RequiredArgsConstructor
public class RuleTriggerListener {

    private final WorkflowTriggerIntake workflowTriggerIntake;
    private final TenantWorkScope tenantWorkScope;
    private static final Logger log = LoggerFactory.getLogger(RuleTriggerListener.class);

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTrigger(RuleTriggerEvent event) {
        try {
            tenantWorkScope.inWorkspace(event.workspaceId(), () ->
                workflowTriggerIntake.enqueue(new WorkflowTriggerDispatch.EntityChange(
                    event.workspaceId(),
                    event.recordType(),
                    event.entityId(),
                    event.event(),
                    event.triggerKey(),
                    event.occurredAt())));
        } catch (Exception e) {
            log.warn(
                "Workflow intake failed recordType={} recordId={} event={} exceptionClass={}",
                event.recordType(), event.entityId(), event.event(),
                e.getClass().getSimpleName());
        }
    }
}
