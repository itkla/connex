package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.beans.WorkflowInvocationRecord;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Atomically consumes an expiring manual scope token before record fan-out. */
@Service
@RequiredArgsConstructor
public class WorkflowManualRunConfirmationTransaction {

    private final WorkflowMapper workflowMapper;
    private final WorkflowOperationsMapper operationsMapper;
    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowDefinitionValidator definitionValidator;
    private final WorkflowManualEligibilityService eligibilityService;
    private final WorkflowActionBindingService bindingService;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowInvocation confirm(
            int workspaceId,
            int workflowId,
            int requesterId,
            byte[] tokenHash,
            byte[] scopeHash,
            byte[] confirmationKey) {
        WorkflowInvocation discoveredInvocation = operationsMapper.getInvocationByToken(
            workspaceId, workflowId, tokenHash);
        if (discoveredInvocation == null
                || discoveredInvocation.getRequestedById() == null
                || discoveredInvocation.getRequestedById() != requesterId) {
            throw new ResourceNotFoundException("Manual workflow scope not found");
        }
        WorkflowVersion version = workflowVersionMapper.getById(
            workspaceId, workflowId, discoveredInvocation.getWorkflowVersionId());
        if (version == null) {
            throw new ConflictException("Workflow version is unavailable");
        }
        WorkflowDefinition definition = canonicalizer.parseDefinition(version.getDefinitionJson());
        Set<Permission> requiredPermissions = definitionValidator.validateForMutation(
            version.getRecordType(), version.getExecutionMode(), definition);
        lockAuthorization(
            workspaceId,
            requesterId,
            version,
            definition,
            requiredPermissions,
            discoveredInvocation);
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
        eligibilityService.requireLockedState(
            workflow, invocation.getWorkflowVersionId(), definition);
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

    private void lockAuthorization(
            int workspaceId,
            int requesterId,
            WorkflowVersion version,
            WorkflowDefinition definition,
            Set<Permission> requiredPermissions,
            WorkflowInvocation invocation) {
        Set<Permission> requesterRequired = new java.util.HashSet<>(requiredPermissions);
        requesterRequired.add(Permission.RULE_MANAGE);
        if (!workspaceService.permissionsFor(workspaceId, requesterId)
                .containsAll(requesterRequired)) {
            throw new ForbiddenException("Workflow action permission is required");
        }
        Integer actorMemberId = "system".equals(version.getExecutionMode())
            ? version.getCreatedById() : version.getRunAsUserId();
        if (actorMemberId == null || workspaceService.getRole(workspaceId, actorMemberId) == null) {
            throw new ConflictException("Workflow actor is unavailable");
        }
        if (!"system".equals(version.getExecutionMode())
                && !workspaceService.permissionsFor(workspaceId, actorMemberId)
                    .containsAll(requiredPermissions)) {
            throw new ConflictException("Workflow actor permission is unavailable");
        }
        Map<Integer, Set<Permission>> requiredByUser = new LinkedHashMap<>();
        mergePermissions(requiredByUser, requesterId, requesterRequired);
        mergePermissions(
            requiredByUser,
            actorMemberId,
            "system".equals(version.getExecutionMode()) ? Set.of() : requiredPermissions);
        for (int targetUserId : targetUserIds(
                workspaceId, version, definition, invocation)) {
            if (workspaceService.getRole(workspaceId, targetUserId) == null) {
                throw new ConflictException("Workflow target member is unavailable");
            }
            mergePermissions(requiredByUser, targetUserId, Set.of());
        }
        try {
            workspaceService.lockAndRequirePermissionsSnapshot(workspaceId, requiredByUser);
        } catch (ForbiddenException exception) {
            if (!workspaceService.permissionsFor(workspaceId, requesterId)
                    .containsAll(requesterRequired)) {
                throw exception;
            }
            throw new ConflictException("Workflow authorization changed; prepare it again");
        }
    }

    private List<Integer> targetUserIds(
            int workspaceId,
            WorkflowVersion version,
            WorkflowDefinition definition,
            WorkflowInvocation invocation) {
        List<Integer> targets = new ArrayList<>();
        List<WorkflowNode.Action> actions = definition.nodes().stream()
            .filter(WorkflowNode.Action.class::isInstance)
            .map(WorkflowNode.Action.class::cast)
            .toList();
        if (definition.schemaVersion() == 1) {
            for (WorkflowNode.Action action : actions) {
                String type = action.config().getType() == null
                    ? "" : action.config().getType().trim().toLowerCase(java.util.Locale.ROOT);
                if ("create_task".equals(type) || "notify".equals(type)) {
                    targets.add("system".equals(version.getExecutionMode())
                        ? version.getCreatedById() : version.getRunAsUserId());
                } else if ("assign_owner".equals(type)
                        && action.config().getTargetUserId() != null) {
                    targets.add(action.config().getTargetUserId());
                }
            }
            return targets.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        }
        String launchInputs = launchInputs(invocation.getScopeContractJson());
        List<WorkflowInvocationRecord> records = operationsMapper.getInvocationRecords(
            workspaceId, invocation.getId());
        for (WorkflowInvocationRecord record : records) {
            if (!"ready".equals(record.getPreviewStatus())) {
                continue;
            }
            WorkflowRun run = new WorkflowRun();
            run.setWorkspaceId(workspaceId);
            run.setRecordType(version.getRecordType());
            run.setRecordId(record.getRecordId());
            run.setTriggerType("manual");
            run.setLaunchInputsJson(launchInputs);
            for (WorkflowNode.Action action : actions) {
                Integer target = bindingService.discover(run, action.config()).targetUserId();
                if (target != null) targets.add(target);
            }
        }
        return targets.stream().distinct().sorted().toList();
    }

    private String launchInputs(String scopeContractJson) {
        try {
            JsonNode contract = objectMapper.readTree(scopeContractJson);
            JsonNode inputs = contract == null ? null : contract.get("resolvedInputs");
            return inputs != null && inputs.isObject()
                ? objectMapper.writeValueAsString(inputs) : "{}";
        } catch (Exception exception) {
            throw new ConflictException("Workflow launch inputs are unavailable");
        }
    }

    private static void mergePermissions(
            Map<Integer, Set<Permission>> requiredByUser,
            int userId,
            Set<Permission> permissions) {
        Set<Permission> merged = new java.util.HashSet<>(
            requiredByUser.getOrDefault(userId, Set.of()));
        merged.addAll(permissions);
        requiredByUser.put(userId, Set.copyOf(merged));
    }
}
