package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AuditEntryDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Executes data-subject request lifecycle work against the control catalog.
 *
 * <p>Every mutation locks the actor, linked workspace roots in ascending order,
 * the organization, current administrator membership, and request row. This
 * serializes with organization and workspace teardown in the global control
 * order: a request cannot enter a lifecycle fence window, and a linked request
 * committed just before the fence is still caught by teardown's link clearing.
 * After preliminary tenant-person proof, the caller retains the actor user,
 * linked workspace, and organization roots while locking that person across
 * this control transaction, so concurrent account offboarding cannot invert
 * the order and person deletion cannot leave a dangling subject link.
 */
@Component
@RequiredArgsConstructor
public class DataSubjectRequestControlOperations {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_OFFSET = 100_000;
    private static final Set<String> STATUSES = Set.of(
        "received", "verifying", "in_progress", "responded", "refused", "closed");
    private static final Set<String> AUDIT_DIFF_FIELDS = Set.of(
        "requestType", "status", "subjectWorkspaceId", "subjectPersonId", "receivedAt",
        "identityVerifiedAt", "dueAt", "respondedAt", "closedAt");

    private final DataSubjectRequestMapper dataSubjectRequestMapper;
    private final WorkspaceMapper workspaceMapper;
    private final TenantLifecycleControlMapper tenantLifecycleControlMapper;
    private final UserMapper userMapper;
    private final OrgMemberService orgMemberService;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;
    private final ooo.klae.connex.backend.mappers.AiAgentDsrControlMapper missionPurgeMapper;
    private final tools.jackson.databind.ObjectMapper missionJson;

    @Transactional(readOnly = true)
    public List<DataSubjectRequestDto> list(int orgId, int actorId, String status, int limit, int offset) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        String normalizedStatus = status == null || status.isBlank()
            ? null
            : normalizeStatus(status);
        return dataSubjectRequestMapper.findByOrg(
            orgId, normalizedStatus, cap(limit), normalizeOffset(offset)).stream()
            .map(DataSubjectRequestDto::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public DataSubjectRequestDto get(int orgId, long requestId, int actorId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return DataSubjectRequestDto.from(findRequest(orgId, requestId));
    }

    @Transactional(readOnly = true)
    public void requireMutationAccess(int orgId, int actorId) {
        if (!tenantLifecycleControlMapper.isOrgAdminForLifecycle(orgId, actorId)) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        sessionSecurityService.requireRecentAuthentication(actorId);
    }

    @Transactional(readOnly = true)
    public DataSubjectRequest loadForMutation(int orgId, long requestId, int actorId) {
        requireMutationAccess(orgId, actorId);
        return findRequest(orgId, requestId);
    }

    @Transactional(readOnly = true)
    public boolean workspaceBelongsToOrg(int orgId, int workspaceId) {
        return workspaceMapper.findByOrgId(orgId).stream()
            .anyMatch(workspace -> workspace.getId() == workspaceId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T withLockedSubjectRoots(
            int orgId,
            int actorId,
            Set<Integer> workspaceIds,
            Supplier<T> work) {
        lockMutationRoots(orgId, actorId, new TreeSet<>(workspaceIds));
        return work.get();
    }

    @Transactional
    public DataSubjectRequestDto create(int orgId, int actorId, DataSubjectRequest request) {
        Integer workspaceId = request.getSubjectWorkspaceId();
        lockMutationRoots(
            orgId,
            actorId,
            workspaceId == null ? Set.of() : Set.of(workspaceId));
        requireCurrentMutationAccess(orgId, actorId);
        dataSubjectRequestMapper.insert(request);
        auditService.record("appi.subject_request.create", "organization", orgId,
            requestLabel(request.getId()), "APPI data-subject request created", auditChanges(request));
        return DataSubjectRequestDto.from(findRequest(orgId, request.getId()));
    }

    @Transactional
    public DataSubjectRequestDto update(int orgId, long requestId, int actorId,
            DataSubjectRequest before, DataSubjectRequest request) {
        Set<Integer> workspaceIds = new TreeSet<>();
        if (before.getSubjectWorkspaceId() != null) {
            workspaceIds.add(before.getSubjectWorkspaceId());
        }
        if (request.getSubjectWorkspaceId() != null) {
            workspaceIds.add(request.getSubjectWorkspaceId());
        }
        lockMutationRoots(orgId, actorId, workspaceIds);
        requireCurrentMutationAccess(orgId, actorId);
        DataSubjectRequest locked = dataSubjectRequestMapper.findByIdForUpdate(orgId, requestId);
        if (locked == null) {
            throw new ResourceNotFoundException("Data-subject request not found: " + requestId);
        }
        if (!locked.equals(before)) {
            throw new ConflictException("Data-subject request changed; retry the update");
        }
        if (dataSubjectRequestMapper.update(request) != 1) {
            throw new ResourceNotFoundException("Data-subject request not found: " + requestId);
        }
        Map<String, Object> changes = new LinkedHashMap<>(auditChanges(request));
        Map<String, Object> diff = auditService.diff(locked, request, AUDIT_DIFF_FIELDS);
        if (diff != null && !diff.isEmpty()) {
            changes.put("fields", diff);
        }
        auditService.record("appi.subject_request.update", "organization", orgId,
            requestLabel(requestId), "APPI data-subject request updated", changes);
        return DataSubjectRequestDto.from(findRequest(orgId, requestId));
    }

    @Transactional(readOnly = true)
    public DisclosureControlData prepareDisclosure(int orgId, long requestId, int actorId) {
        requireMutationAccess(orgId, actorId);
        DataSubjectRequest request = findRequest(orgId, requestId);
        if (!"disclosure".equals(request.getRequestType())) {
            throw new BadRequestException("Disclosure can only be assembled for a disclosure-type request");
        }
        if (request.getIdentityVerifiedAt() == null) {
            throw new BadRequestException(
                "Identity verification must be recorded before disclosure can be assembled");
        }
        Integer workspaceId = request.getSubjectWorkspaceId();
        Integer personId = request.getSubjectPersonId();
        if (workspaceId == null || personId == null) {
            throw new BadRequestException("A linked subject person is required before disclosure can be assembled");
        }
        WorkspaceSnapshot workspaces = workspaceSnapshot(orgId);
        if (!workspaces.names().containsKey(workspaceId)) {
            throw new ResourceNotFoundException("Linked subject person not found: " + personId);
        }
        List<AuditEntryDto> auditTrail = dataSubjectRequestMapper.findDisclosureAudit(
            orgId, personId, workspaces.ids(), DataSubjectRequestService.DISCLOSURE_AUDIT_LIMIT);
        long auditTrailTotal = dataSubjectRequestMapper.countDisclosureAudit(
            orgId, personId, workspaces.ids());
        return new DisclosureControlData(request, workspaces, auditTrail, auditTrailTotal);
    }

    @Transactional
    public void recordDisclosureAudit(int orgId, long requestId, int personId, int workspaceId) {
        auditService.recordStrict("appi.subject_request.disclosure", "organization", orgId,
            requestLabel(requestId), "Subject-scoped disclosure export assembled",
            Map.of("requestId", requestId, "subjectPersonId", personId,
                "subjectWorkspaceId", workspaceId));
    }


    /** Reads the immutable organization workspace scope before acquiring its ordered roots. */
    @Transactional(readOnly = true)
    public List<Integer> missionPurgeWorkspaceIds(int orgId, int actorId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return workspaceMapper.findByOrgId(orgId).stream().map(Workspace::getId).sorted().toList();
    }

    /** Replays an accepted key under current administrator access without reusing its snapshot. */
    @Transactional(readOnly = true)
    public ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance missionPurgeByKey(
            int orgId, long requestId, int actorId, String key) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        findRequest(orgId, requestId);
        var row = missionPurgeMapper.findAcceptanceByKey(orgId, requestId, key);
        return row == null ? null : ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance.from(row, missionJson);
    }

    /** Commits C1 under the established linked-person proof and request serialization boundary. */
    @Transactional
    public ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance acceptMissionPurge(
            int orgId, long requestId, int actorId,
            ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest request,
            List<Integer> workspaceIds, int provenWorkspaceId, int provenPersonId) {
        lockMutationRoots(orgId, actorId, new TreeSet<>(workspaceIds));
        requireCurrentMutationAccess(orgId, actorId);
        DataSubjectRequest locked = dataSubjectRequestMapper.findByIdForUpdate(orgId, requestId);
        if (locked == null) {
            throw new ResourceNotFoundException("Data-subject request not found");
        }
        var existing = missionPurgeMapper.findAcceptanceByKey(orgId, requestId, request.idempotencyKey());
        if (existing != null) {
            return ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance.from(existing, missionJson);
        }
        if (!DataSubjectRequestSnapshot.token(locked).equals(request.snapshotToken())
                || !Integer.valueOf(provenWorkspaceId).equals(locked.getSubjectWorkspaceId())
                || !Integer.valueOf(provenPersonId).equals(locked.getSubjectPersonId())) {
            throw new ConflictException("dsr_snapshot_conflict");
        }
        if (locked.getIdentityVerifiedAt() == null || locked.getSubjectWorkspaceId() == null
                || locked.getSubjectPersonId() == null || !workspaceIds.contains(locked.getSubjectWorkspaceId())) {
            throw new BadRequestException("A verified linked subject is required");
        }
        List<Integer> current = workspaceMapper.findByOrgId(orgId).stream().map(Workspace::getId).sorted().toList();
        if (!current.equals(workspaceIds)) {
            throw new ConflictException("dsr_workspace_scope_conflict");
        }
        var accepted = new ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance(
                0, orgId, requestId, java.util.UUID.randomUUID().toString(), request.idempotencyKey(),
                workspaceIds, java.time.Instant.now().toString());
        auditService.recordStrictScoped(
                ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance.ACCEPTED,
                "organization", orgId, null, orgId, "Mission holdings purge",
                "Conservative mission holdings erasure accepted", accepted.metadata());
        var saved = missionPurgeMapper.findAcceptanceByKey(orgId, requestId, request.idempotencyKey());
        if (saved == null || !missionJson.readTree(saved.getChanges()).equals(missionJson.valueToTree(accepted.metadata()))) {
            throw new IllegalStateException("Mission purge acceptance audit did not preserve its contract");
        }
        return ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance.from(saved, missionJson);
    }

    /** Reads one accepted operation after checking current organization-administrator authority. */
    @Transactional(readOnly = true)
    public ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance missionPurgeOperation(
            int orgId, long requestId, int actorId, String operationId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        findRequest(orgId, requestId);
        var row = missionPurgeMapper.findAcceptance(orgId, requestId, operationId);
        if (row == null) {
            throw new ResourceNotFoundException("Mission holdings purge not found");
        }
        return ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance.from(row, missionJson);
    }

    /** Appends C2 once using the original C1 authority; this never edits the human DSR status. */
    @Transactional
    public void completeMissionPurge(ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance accepted) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof ooo.klae.connex.backend.beans.User actor) {
            userMapper.lockByIdForShare(actor.getId());
        }
        for (int workspaceId : accepted.workspaceIds()) {
            var workspace = tenantLifecycleControlMapper.lockWorkspaceForShare(workspaceId);
            if (workspace == null || workspace.orgId() != accepted.orgId()) {
                throw new ConflictException("dsr_lifecycle_evidence_required");
            }
        }
        if (tenantLifecycleControlMapper.lockActiveOrganizationForShare(accepted.orgId()) == null
                || dataSubjectRequestMapper.findByIdForUpdate(accepted.orgId(), accepted.requestId()) == null) {
            throw new ConflictException("dsr_lifecycle_evidence_required");
        }
        if (missionPurgeMapper.findCompletion(accepted.orgId(), accepted.requestId(), accepted.operationId()) != null) {
            return;
        }
        auditService.recordStrictScoped(
                ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance.COMPLETED,
                "organization", accepted.orgId(), null, accepted.orgId(), "Mission holdings purge",
                "Conservative mission holdings erasure verified", accepted.metadata());
        var completion = missionPurgeMapper.findCompletion(accepted.orgId(), accepted.requestId(), accepted.operationId());
        if (completion == null
                || !missionJson.readTree(completion.getChanges()).equals(missionJson.valueToTree(accepted.metadata()))) {
            throw new IllegalStateException("Mission purge completion audit did not preserve its contract");
        }
    }

    private void lockMutationRoots(int orgId, int actorId, Set<Integer> workspaceIds) {
        if (userMapper.lockByIdForShare(actorId) == null) {
            throw new ForbiddenException("Authenticated user is unavailable");
        }
        for (int workspaceId : workspaceIds) {
            WorkspaceLifecycleRef workspace =
                tenantLifecycleControlMapper.lockWorkspaceForShare(workspaceId);
            if (workspace == null
                    || workspace.orgId() != orgId
                    || !"active".equals(workspace.lifecycleState())) {
                throw new ConflictException(
                    "The subject person's workspace is being removed and cannot be linked");
            }
        }
        if (tenantLifecycleControlMapper.lockActiveOrganizationForShare(orgId) == null) {
            throw new ConflictException(
                "The organization is being removed and cannot record data-subject requests");
        }
    }

    private void requireCurrentMutationAccess(int orgId, int actorId) {
        if (tenantLifecycleControlMapper.lockOrgAdminMembershipForUpdate(orgId, actorId) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        sessionSecurityService.requireRecentAuthentication(actorId);
    }

    private WorkspaceSnapshot workspaceSnapshot(int orgId) {
        List<Workspace> workspaces = workspaceMapper.findByOrgId(orgId);
        List<Integer> ids = workspaces.stream().map(Workspace::getId).toList();
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Workspace workspace : workspaces) {
            names.put(workspace.getId(), workspace.getName());
        }
        return new WorkspaceSnapshot(ids, names);
    }

    private DataSubjectRequest findRequest(int orgId, long requestId) {
        DataSubjectRequest request = dataSubjectRequestMapper.findById(orgId, requestId);
        if (request == null) {
            throw new ResourceNotFoundException("Data-subject request not found: " + requestId);
        }
        return request;
    }

    private static String normalizeStatus(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new BadRequestException("Unknown status: " + value);
        }
        return normalized;
    }

    private static int cap(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static int normalizeOffset(int offset) {
        return Math.min(Math.max(0, offset), MAX_OFFSET);
    }

    private static String requestLabel(long requestId) {
        return "Subject request " + requestId;
    }

    private static Map<String, Object> auditChanges(DataSubjectRequest request) {
        return Map.of("requestId", request.getId(), "requestType", request.getRequestType(),
            "status", request.getStatus());
    }

    public record WorkspaceSnapshot(List<Integer> ids, Map<Integer, String> names) {
        public WorkspaceSnapshot {
            ids = List.copyOf(ids);
            names = Map.copyOf(names);
        }
    }

    public record DisclosureControlData(
            DataSubjectRequest request,
            WorkspaceSnapshot workspaces,
            List<AuditEntryDto> auditTrail,
            long auditTrailTotal) {
        public DisclosureControlData {
            auditTrail = List.copyOf(auditTrail);
        }
    }
}
