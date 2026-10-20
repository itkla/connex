package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeDto;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentDsrControlMapper;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.services.DataSubjectRequestControlOperations;
import ooo.klae.connex.backend.services.DataSubjectRequestService;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Recoverable C1/T1/T2/C2 orchestration; no transaction crosses the control and tenant phases. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RequiredArgsConstructor
@Slf4j
public class AiAgentDsrPurgeService {
    private final DataSubjectRequestService requestService;
    private final DataSubjectRequestControlOperations controlOperations;
    private final AiAgentDsrControlMapper controlMapper;
    private final AiAgentPrivacyMapper privacyMapper;
    private final AiAgentPrivacyService privacyService;
    private final TenantLifecycleControlMapper lifecycleMapper;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final SystemActor systemActor;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;
    private final JobRunRecorder jobRunRecorder;
    private final AtomicInteger recoveryCursor = new AtomicInteger();

    /** Accepts an authorized operation once, then installs its immutable tenant cutoffs. */
    public AiAgentHoldingsPurgeDto initiate(int orgId, long requestId, int actorId, AiAgentHoldingsPurgeRequest request) {
        AiAgentPurgeAcceptance accepted = requestService.acceptMissionPurge(orgId, requestId, actorId, request);
        try {
            recover(accepted);
        } catch (RuntimeException failure) {
            log.warn("Job failed jobName={} phase=initiation exceptionClass={}",
                    JobRunRecorder.AI_AGENT_DSR_RECOVERY, failure.getClass().getSimpleName());
        }
        return projection(accepted);
    }

    /** Returns current administrator-visible metadata; reads do not trigger deletion. */
    public AiAgentHoldingsPurgeDto get(int orgId, long requestId, int actorId, String operationId) {
        AiAgentPurgeAcceptance accepted = tenantWorkScope.unroutedRead(
                () -> controlOperations.missionPurgeOperation(orgId, requestId, actorId, operationId));
        return projection(accepted);
    }

    /** Recovers accepted authority without consulting mutable request subject linkage or actor membership. */
    @Scheduled(fixedDelayString = "${connex.ai.mission-dsr-recovery-delay:10s}", initialDelay = 15000)
    public void recoverAccepted() {
        List<AuditLog> receipts = tenantWorkScope.unroutedRead(
                () -> controlMapper.pendingAcceptances(recoveryCursor.get(), 20));
        if (receipts.isEmpty()) {
            recoveryCursor.set(0);
            return;
        }
        for (AuditLog receipt : receipts) {
            JobRunDetail started = JobRunDetail.startedUtc();
            boolean failed = false;
            try {
                recover(AiAgentPurgeAcceptance.from(receipt, objectMapper));
            } catch (RuntimeException failure) {
                failed = true;
                log.warn("Job failed jobName={} phase=recovery exceptionClass={}",
                        JobRunRecorder.AI_AGENT_DSR_RECOVERY, failure.getClass().getSimpleName());
            } finally {
                jobRunRecorder.record(JobRunRecorder.AI_AGENT_DSR_RECOVERY, null,
                        failed ? JobRunStatus.FAILED : JobRunStatus.SUCCEEDED,
                        new JobRunDetail(started.startedAt(), Map.of("phase", "recovery")));
                recoveryCursor.set(receipt.getId());
            }
        }
    }

    private void recover(AiAgentPurgeAcceptance accepted) {
        AuditLog completed = tenantWorkScope.unroutedRead(() -> controlMapper.findCompletion(
                accepted.orgId(), accepted.requestId(), accepted.operationId()));
        if (completed != null) {
            return;
        }
        tenantWorkScope.inWorkspace(accepted.workspaceIds().getFirst(), () -> inAcceptedScope(
                accepted, accepted.workspaceIds().getFirst(), () -> {
            TransactionTemplate tenant = new TransactionTemplate(transactionManager);
            tenant.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tenant.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tenant.executeWithoutResult(status -> {
                for (int workspaceId : accepted.workspaceIds()) {
                    var workspace = lifecycleMapper.lockWorkspaceForShare(workspaceId);
                    if (workspace == null || workspace.orgId() != accepted.orgId()) {
                        throw new ConflictException("dsr_lifecycle_evidence_required");
                    }
                }
                if (lifecycleMapper.lockActiveOrganizationForShare(accepted.orgId()) == null) {
                    throw new ConflictException("dsr_lifecycle_evidence_required");
                }
                privacyService.invalidateWorkspaces(accepted.workspaceIds(), UUID.fromString(accepted.operationId()),
                        "dsr", accepted.requestId(), accepted.auditId());
            });
            return null;
        }));
        AiAgentHoldingsPurgeDto current = projection(accepted);
        if (current.completedWorkspaceCount() == current.workspaceCount()) {
            tenantWorkScope.unrouted(() -> {
                controlOperations.completeMissionPurge(accepted);
                return null;
            });
        }
    }

    private AiAgentHoldingsPurgeDto projection(AiAgentPurgeAcceptance accepted) {
        int complete = 0;
        boolean missing = false;
        boolean retrying = false;
        String finishedAt = null;
        for (int workspaceId : accepted.workspaceIds()) {
            AiAgentPrivacyWork work = tenantWorkScope.inLifecycleWorkspaceRead(workspaceId,
                    () -> readAcceptedWork(accepted, workspaceId));
            if (work == null) {
                missing = true;
            } else if ("complete".equals(work.getPhase()) && Long.valueOf(0).equals(work.getLastResidualCount())
                    && work.getVerifiedAt() != null && work.getCompletedAt() != null) {
                complete++;
                String completedAt = work.getCompletedAt().toInstant(java.time.ZoneOffset.UTC).toString();
                if (finishedAt == null || completedAt.compareTo(finishedAt) > 0) {
                    finishedAt = completedAt;
                }
            } else if (work.getRetryCount() > 0) {
                retrying = true;
            }
        }
        AuditLog completion = tenantWorkScope.unroutedRead(() -> controlMapper.findCompletion(
                accepted.orgId(), accepted.requestId(), accepted.operationId()));
        boolean completed = complete == accepted.workspaceIds().size() && completion != null;
        String state = completed ? "complete" : missing ? "invalidation_pending" : retrying ? "retrying" : "purging";
        return new AiAgentHoldingsPurgeDto(1, accepted.operationId(), Long.toString(accepted.requestId()),
                AiAgentPurgeAcceptance.SCOPE, AiAgentPurgeAcceptance.CUTOFF_POLICY, accepted.acceptedAt(), state,
                accepted.workspaceIds().size(), complete, completed ? finishedAt : null,
                retrying ? "cleanup_retry" : null);
    }

    /** Installs only frozen accepted workspace authority and restores the caller after every tenant phase. */
    private <T> T inAcceptedScope(AiAgentPurgeAcceptance accepted, int workspaceId, Supplier<T> work) {
        TenantContext.State previous = installAcceptedIdentity(accepted, workspaceId);
        try {
            return work.get();
        } finally {
            tenantContext.restore(previous);
        }
    }

    private AiAgentPrivacyWork readAcceptedWork(AiAgentPurgeAcceptance accepted, int workspaceId) {
        TenantContext.State previous = installAcceptedIdentity(accepted, workspaceId);
        try {
            TransactionTemplate read = new TransactionTemplate(transactionManager);
            read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            read.setReadOnly(true);
            return read.execute(status -> privacyMapper.getWork(workspaceId, accepted.operationId()));
        } finally {
            tenantContext.restore(previous);
        }
    }

    private TenantContext.State installAcceptedIdentity(AiAgentPurgeAcceptance accepted, int workspaceId) {
        TenantContext.State previous = tenantContext.snapshot();
        int systemUserId = tenantWorkScope.unroutedRead(systemActor::readUser).getId();
        tenantContext.set(workspaceId, accepted.orgId(), systemUserId, "system", tenantContext.getCatalog());
        return previous;
    }

}
