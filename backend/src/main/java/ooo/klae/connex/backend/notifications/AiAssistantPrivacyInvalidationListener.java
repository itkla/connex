package ooo.klae.connex.backend.notifications;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Withdraws connected views using workspace cutoff metadata without loading or publishing retained content. */
@Component
@RequiredArgsConstructor
public class AiAssistantPrivacyInvalidationListener {
    private final AiAssistantPrivacyService authority;
    private final TenantWorkScope workScope;
    private final AutomationExecutor automation;
    private final SystemActor systemActor;
    private final PlatformTransactionManager transactions;
    private final AiChatRealtimeDispatcher realtime;

    /** Releases the completed transaction's connection before resolving each placement and audience. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidated(AiAssistantPrivacyService.Invalidated event) {
        TransactionTemplate detached = new TransactionTemplate(transactions);
        detached.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        detached.executeWithoutResult(status -> {
            List<Integer> workspaces = workScope.unroutedRead(() -> authority.operationWorkspaceIds(
                    event.organizationId(), event.operationKey()));
            for (int workspaceId : workspaces) {
                try {
                    if (workScope.unroutedRead(() -> authority.organizationId(workspaceId)) != event.organizationId()) {
                        continue;
                    }
                    automation.runAs(workspaceId, systemActor.user(), "system", () -> {
                        realtime.workspacePrivacyNow(workspaceId, event.cutoffGeneration());
                        return null;
                    });
                } catch (RuntimeException unavailablePlacement) {
                    continue;
                }
            }
        });
    }
}
