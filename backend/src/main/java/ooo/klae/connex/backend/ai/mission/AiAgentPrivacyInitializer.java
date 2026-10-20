package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Placement-aware reconciliation that never invents a replacement privacy generation. */
@Component
@Slf4j
public class AiAgentPrivacyInitializer {
    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final AiAgentPrivacyService privacyService;
    private final AiAgentStartupRecoveryService startupRecovery;
    private final ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService assistantPrivacy;
    private final JobRunRecorder jobRunRecorder;
    private final PlatformTransactionManager transactionManager;
    private final AutomationExecutor automationExecutor;
    private final SystemActor systemActor;
    private final ObjectProvider<TaskExecutor> initializationExecutor;
    private final AtomicBoolean ready = new AtomicBoolean();

    /** Creates initialization with its bounded, managed, non-propagating provisioning executor. */
    public AiAgentPrivacyInitializer(WorkspaceMapper workspaceMapper, TenantWorkScope tenantWorkScope,
            AiAgentPrivacyService privacyService, AiAgentStartupRecoveryService startupRecovery,
            ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService assistantPrivacy,
            JobRunRecorder jobRunRecorder, PlatformTransactionManager transactionManager,
            AutomationExecutor automationExecutor, SystemActor systemActor,
            @Qualifier("aiAgentPrivacyInitializationExecutor") ObjectProvider<TaskExecutor> initializationExecutor) {
        this.workspaceMapper = workspaceMapper;
        this.tenantWorkScope = tenantWorkScope;
        this.privacyService = privacyService;
        this.startupRecovery = startupRecovery;
        this.assistantPrivacy = assistantPrivacy;
        this.jobRunRecorder = jobRunRecorder;
        this.transactionManager = transactionManager;
        this.automationExecutor = automationExecutor;
        this.systemActor = systemActor;
        this.initializationExecutor = initializationExecutor;
    }

    /** Signals a committed control-plane workspace whose tenant barrier can now be initialized. */
    public record WorkspaceProvisioned(int workspaceId) {}

    /** Exposes fail-closed initialization readiness to mission admission. */
    public boolean isReady() {
        return ready.get();
    }

    /** Reconciles after migrations and before the one-shot seeder closes its ready context. */
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    @EventListener(ApplicationReadyEvent.class)
    public void startup() {
        reconcile();
    }

    /** Recovers provisioning crashes and initialization failures without depending on AI enablement. */
    @Scheduled(fixedDelayString = "${connex.ai.mission-privacy-reconcile-delay:60s}",
            initialDelayString = "${connex.ai.mission-privacy-reconcile-initial-delay:60s}")
    public synchronized void reconcile() {
        int after = 0;
        boolean successful = true;
        while (true) {
            int cursor = after;
            List<Integer> ids = tenantWorkScope.unroutedRead(() -> workspaceMapper.findWorkspaceIdsPage(cursor, 100));
            if (ids.isEmpty()) {
                break;
            }
            for (int workspaceId : ids) {
                try {
                    initialize(workspaceId);
                } catch (RuntimeException failure) {
                    successful = false;
                }
            }
            after = ids.getLast();
        }
        after = 0;
        while (true) {
            int cursor = after;
            List<Integer> ids = tenantWorkScope.unroutedRead(
                    () -> assistantPrivacy.incompleteOrganizationIdsPage(cursor, 100));
            if (ids.isEmpty()) {
                ready.set(successful);
                return;
            }
            for (int organizationId : ids) {
                try {
                    tenantWorkScope.unrouted(() -> {
                        assistantPrivacy.reconcileOrganizationCompletion(organizationId);
                        return null;
                    });
                } catch (RuntimeException failure) {
                    successful = false;
                }
            }
            after = ids.getLast();
        }
    }

    /**
     * Suspends provisioning resources before resolving placement and installing the system scope.
     * AFTER_COMMIT still retains the control transaction's connection until its callback returns.
     */
    public void initialize(int workspaceId) {
        TransactionTemplate detached = new TransactionTemplate(transactionManager);
        detached.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        detached.executeWithoutResult(status -> automationExecutor.runAs(
                workspaceId, systemActor.user(), "system", () -> {
            JobRunDetail started = JobRunDetail.startedUtc();
            JobRunStatus outcome = JobRunStatus.SUCCEEDED;
            try {
                privacyService.initialize(workspaceId);
                privacyService.reconcileLegacyTurns(workspaceId);
                startupRecovery.reconcile(workspaceId);
                return null;
            } catch (RuntimeException failure) {
                outcome = JobRunStatus.FAILED;
                throw failure;
            } finally {
                jobRunRecorder.record(JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION, workspaceId,
                        outcome, new JobRunDetail(started.startedAt(), Map.of("phase", "initialization")));
            }
        }));
    }

    /**
     * Routed provisioning hands off without waiting: AFTER_COMMIT retains the provisioning
     * connection until this callback returns. Workers install their own route and prepared scopes.
     * Single-database provisioning retains synchronous initialization and scheduled recovery.
     * A rejected handoff or failed initialization keeps global mission readiness closed until a
     * successful scheduled reconciliation, without affecting the committed workspace provision.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void provisioned(WorkspaceProvisioned event) {
        if (tenantWorkScope.isCatalogPerPlacement()) {
            try {
                initializationExecutor.getObject().execute(() -> initializeProvisioned(event.workspaceId()));
            } catch (RuntimeException failure) {
                ready.set(false);
                log.warn("AI privacy initialization handoff failed for workspace {}; scheduled reconciliation will retry",
                    event.workspaceId());
            }
            return;
        }
        initializeProvisioned(event.workspaceId());
    }

    private void initializeProvisioned(int workspaceId) {
        try {
            initialize(workspaceId);
        } catch (RuntimeException failure) {
            ready.set(false);
        }
    }
}
