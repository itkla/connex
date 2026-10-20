package ooo.klae.connex.backend.ai.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyFence;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Organization authority remains independent of mission workspace generations. */
@Service
@RequiredArgsConstructor
public class AiAssistantPrivacyService {
    public static final String CONTENT_SCOPE = "organization_assistant_holdings_v2";
    private static final Set<String> REASONS = Set.of("restriction", "dsr", "offboarding", "account_erasure");
    private final AiAssistantPrivacyControlMapper controlMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ApplicationEventPublisher events;

    /** Content-free source identity delivered only after durable organization invalidation commits. */
    public record Invalidated(int organizationId, String operationKey, long cutoffGeneration) {}

    /** Resolves organization identity on the control plane without inventing authority. */
    public int organizationId(int workspaceId) {
        Integer organizationId = workspaceMapper.getOrgId(workspaceId);
        if (organizationId == null) {
            throw unavailable();
        }
        return organizationId;
    }

    /** Hydrates separately routed control metadata before notification filtering and pagination. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public java.util.Map<Integer, Long> notificationWorkspaceGenerations() {
        java.util.Map<Integer, Long> generations = new java.util.HashMap<>();
        for (AiAssistantWorkspacePrivacy workspace : controlMapper.notificationWorkspaceGenerations()) {
            if (workspace.workspaceId() <= 0 || workspace.generation() <= 0
                    || generations.put(workspace.workspaceId(), workspace.generation()) != null) {
                throw unavailable();
            }
        }
        return java.util.Map.copyOf(generations);
    }

    /** Missing authority is unavailable, including during initialization or restore. */
    public long current(int organizationId) {
        return requireFence(controlMapper.getFence(organizationId)).getGeneration();
    }

    /** Reads publication authority outside any input or output snapshot on a separate connection. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public long freshCurrent(int organizationId) {
        return requireFence(controlMapper.getFence(organizationId)).getGeneration();
    }

    /** Retains organization authority on the caller's connection through commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long retain(int organizationId, long expectedGeneration) {
        long actual = requireFence(controlMapper.lockFenceForShare(organizationId)).getGeneration();
        if (expectedGeneration <= 0 || actual != expectedGeneration) {
            throw new ConflictException("assistant_privacy_unavailable");
        }
        return actual;
    }

    /** Creates authority only in the organization provisioning transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void provision(int organizationId) {
        provisionControl(controlMapper, organizationId);
    }

    /** Creates new-organization authority before its first workspace; the initializer enrolls placement after commit. */
    public static void provisionControl(AiAssistantPrivacyControlMapper controlMapper, int organizationId) {
        String key = UUID.randomUUID().toString();
        controlMapper.insertFence(organizationId, key);
        AiAssistantPrivacyOperation operation = operation(organizationId, key, 0, "bootstrap", null, null);
        operation.setOperationFingerprint(fingerprint("assistant-bootstrap-v1|" + organizationId + "|" + key));
        controlMapper.insertOperation(operation);
    }

    /** Reads the immutable accepted operation for replay and recovery. */
    public AiAssistantPrivacyOperation findOperation(int organizationId, String operationKey) {
        return controlMapper.getOperation(organizationId, operationKey);
    }

    /** Enumerates original identities for routed reconciliation, including completed bootstrap work. */
    public List<AiAssistantPrivacyOperation> operations(int organizationId) {
        requireFence(controlMapper.getFence(organizationId));
        return controlMapper.listOperations(organizationId);
    }

    /** Includes durable placement intents that were discovered after acceptance. */
    public List<Integer> operationWorkspaceIds(int organizationId, String operationKey) {
        return controlMapper.operationWorkspaceIds(organizationId, operationKey);
    }

    /** Advances once under organization X after callers retain their local restriction fences. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AiAssistantPrivacyOperation invalidateOrganization(
            int organizationId, UUID operationId, String reason, Long dsrId, Integer auditId) {
        if (!REASONS.contains(reason) || ("dsr".equals(reason) != (dsrId != null))) {
            throw new IllegalArgumentException("Invalid assistant privacy operation");
        }
        AiAssistantPrivacyPublicationGuard.retainInvalidation(organizationId);
        AiAssistantPrivacyFence fence = requireFence(controlMapper.lockFence(organizationId));
        AiAssistantPrivacyOperation existing = controlMapper.getOperation(organizationId, operationId.toString());
        if (existing != null) {
            if (!Objects.equals(existing.getReason(), reason) || !Objects.equals(existing.getDsrRequestId(), dsrId)) {
                throw new ConflictException("assistant_privacy_operation_conflict");
            }
            return existing;
        }
        if (fence.getGeneration() == Long.MAX_VALUE) {
            throw unavailable();
        }
        AiAssistantPrivacyOperation operation = operation(organizationId, operationId.toString(), fence.getGeneration(), reason, dsrId, auditId);
        controlMapper.insertOperation(operation);
        if (controlMapper.advanceFence(organizationId, fence.getGeneration()) != 1) {
            throw unavailable();
        }
        controlMapper.reconcileWorkspaceIntents(organizationId, operation.getOperationKey());
        events.publishEvent(new Invalidated(organizationId, operation.getOperationKey(), operation.getCutoffGeneration()));
        return operation;
    }

    /** Binds the strict acceptance audit before C1 commits and before routed T1 enrollment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachAcceptanceAudit(int organizationId, String operationKey, int auditId) {
        AiAssistantPrivacyOperation operation = controlMapper.getOperation(organizationId, operationKey);
        if (operation == null || auditId <= 0) {
            throw new ConflictException("assistant_privacy_operation_conflict");
        }
        operation.setControlAcceptanceAuditId(auditId);
        byte[] fingerprint = operationFingerprint(operation);
        if (controlMapper.attachAcceptanceAudit(organizationId, operationKey, auditId, fingerprint) != 1) {
            throw new ConflictException("assistant_privacy_operation_conflict");
        }
    }

    /** Reconciles discovery under the original operation; workspace disappearance preserves its intent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileIntents(int organizationId, String operationKey) {
        requireFence(controlMapper.lockFenceForShare(organizationId));
        controlMapper.reconcileWorkspaceIntents(organizationId, operationKey);
    }

    /** Records exact routed work verification after its uncapped residual check. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyWorkspace(AiAssistantPrivacyOperation operation, int workspaceId, String catalog, long revision) {
        var fence = requireFence(controlMapper.lockFenceForShare(operation.getOrganizationId()));
        if (fence.getGeneration() < operation.getInvalidatedGeneration()) {
            throw unavailable();
        }
        controlMapper.verifyWorkspace(operation.getOrganizationId(), operation.getOperationKey(), workspaceId, catalog, revision);
    }

    /** Catalog coverage and every durable placement receipt are checked while retaining organization X. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reconcileCompletion(int organizationId, String operationKey) {
        AiAssistantPrivacyFence fence = requireFence(controlMapper.lockFence(organizationId));
        AiAssistantPrivacyOperation operation = controlMapper.lockOperation(organizationId, operationKey);
        if (operation == null) {
            throw new ConflictException("assistant_privacy_operation_conflict");
        }
        if (fence.getGeneration() < operation.getInvalidatedGeneration()) {
            throw unavailable();
        }
        controlMapper.reconcileWorkspaceIntents(organizationId, operationKey);
        if (controlMapper.countUnverified(organizationId, operationKey) != 0) {
            return false;
        }
        controlMapper.completeOperation(organizationId, operationKey);
        controlMapper.completeBootstrap(organizationId, operationKey);
        return true;
    }

    private static AiAssistantPrivacyOperation operation(int organizationId, String key, long cutoff, String reason, Long dsrId, Integer auditId) {
        AiAssistantPrivacyOperation operation = new AiAssistantPrivacyOperation();
        operation.setOrganizationId(organizationId);
        operation.setOperationKey(key);
        operation.setContentScope(CONTENT_SCOPE);
        operation.setCutoffGeneration(cutoff);
        operation.setInvalidatedGeneration(Math.incrementExact(cutoff));
        operation.setReason(reason);
        operation.setDsrRequestId(dsrId);
        operation.setControlAcceptanceAuditId(auditId);
        operation.setOperationFingerprint(operationFingerprint(operation));
        return operation;
    }

    private static byte[] operationFingerprint(AiAssistantPrivacyOperation operation) {
        return fingerprint("assistant-operation-v2|" + operation.getOrganizationId() + "|" + operation.getOperationKey() + "|" + CONTENT_SCOPE
                + "|" + operation.getReason() + "|" + operation.getDsrRequestId() + "|" + operation.getControlAcceptanceAuditId()
                + "|" + operation.getCutoffGeneration() + "|" + operation.getInvalidatedGeneration());
    }

    private static AiAssistantPrivacyFence requireFence(AiAssistantPrivacyFence fence) {
        if (fence == null || fence.getGeneration() <= 0) {
            throw unavailable();
        }
        return fence;
    }

    /** Hashes canonical operation metadata without storing retained chat content. */
    public static byte[] fingerprint(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("assistant_privacy_initializing");
    }
}
