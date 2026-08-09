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
 * Deployment compatibility listener that preserves after-commit legacy execution only while the
 * durable canonical worker gate is disabled. When the gate is enabled it is inert and the committed
 * outbox owns delivery. Remove it after deployments no longer support the disabled runtime gate.
 */
@Component
@RequiredArgsConstructor
public class RuleTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(RuleTriggerListener.class);

    private final WorkflowRuntimeProperties properties;
    private final RuleEngineService ruleEngineService;
    private final TenantWorkScope tenantWorkScope;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTrigger(RuleTriggerEvent event) {
        if (properties.enabled()) {
            return;
        }
        try {
            tenantWorkScope.inWorkspace(event.workspaceId(), () ->
                ruleEngineService.onEntityChange(new WorkflowTriggerDispatch.EntityChange(
                    event.workspaceId(),
                    event.recordType(),
                    event.entityId(),
                    event.event(),
                    event.triggerKey(),
                    event.occurredAt())));
        } catch (RuntimeException failure) {
            log.warn(
                "Legacy workflow trigger failed recordType={} recordId={} event={} "
                    + "exceptionClass={}",
                event.recordType(),
                event.entityId(),
                event.event(),
                failure.getClass().getSimpleName());
        }
    }
}
