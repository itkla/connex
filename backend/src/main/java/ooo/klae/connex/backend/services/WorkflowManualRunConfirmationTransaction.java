package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

/** Atomically consumes an expiring manual scope token before record fan-out. */
@Service
@RequiredArgsConstructor
public class WorkflowManualRunConfirmationTransaction {

    private final WorkflowMapper workflowMapper;
    private final WorkflowOperationsMapper operationsMapper;
    private final WorkflowTriggerOutboxMapper outboxMapper;

    @Transactional
    public WorkflowInvocation confirm(
            int workspaceId,
            int workflowId,
            int requesterId,
            byte[] tokenHash,
            byte[] scopeHash,
            byte[] confirmationKey) {
        Workflow workflow = workflowMapper.getByIdForUpdate(workspaceId, workflowId);
        if (workflow == null) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        WorkflowInvocation invocation = operationsMapper.getInvocationByTokenForUpdate(
            workspaceId, workflowId, tokenHash);
        if (invocation == null || invocation.getRequestedById() == null
                || invocation.getRequestedById() != requesterId) {
            throw new ResourceNotFoundException("Manual workflow scope not found");
        }
        LocalDateTime now = LocalDateTime.now();
        if (invocation.getExpiresAt().isBefore(now)
                && "prepared".equals(invocation.getStatus())) {
            operationsMapper.updateInvocationStatus(
                workspaceId, invocation.getId(), "expired", now);
            throw new ConflictException("Manual workflow scope expired");
        }
        if (!MessageDigest.isEqual(invocation.getScopeHash(), scopeHash)) {
            throw new ConflictException("Manual workflow scope changed");
        }
        if (invocation.getConfirmationKey() != null) {
            if (!MessageDigest.isEqual(invocation.getConfirmationKey(), confirmationKey)) {
                throw new ConflictException("Manual workflow scope was already confirmed");
            }
            return invocation;
        }
        if (invocation.getReadyCount() < 1) {
            throw new ConflictException("Manual workflow scope has no runnable records");
        }
        requireRunnableWorkflow(workflow, invocation);
        outboxMapper.ensureWorkspaceGate(workspaceId);
        if (operationsMapper.confirmInvocation(
                workspaceId,
                invocation.getId(),
                requesterId,
                confirmationKey,
                now) != 1) {
            throw new ConflictException("Manual workflow scope changed; prepare it again");
        }
        invocation.setConfirmationKey(confirmationKey);
        invocation.setStatus("confirmed");
        invocation.setConfirmedAt(now);
        return invocation;
    }

    private static void requireRunnableWorkflow(
            Workflow workflow,
            WorkflowInvocation invocation) {
        if (workflow.getArchivedAt() != null) {
            throw new ConflictException("Archived workflows cannot accept manual runs");
        }
        if (!workflow.isEnabled()) {
            throw new ConflictException("Disabled workflows cannot accept manual runs");
        }
        if (workflow.getIntakePausedAt() != null) {
            throw new ConflictException("Paused workflows cannot accept manual runs");
        }
        if (!"canonical".equals(workflow.getRuntimeOwner())) {
            throw new ConflictException("Workflow is not owned by the canonical runtime");
        }
        if (workflow.getActiveVersionId() == null
                || workflow.getActiveVersionId() != invocation.getWorkflowVersionId()) {
            throw new ConflictException("Workflow version changed; prepare the scope again");
        }
    }
}
