package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AuditEntryDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Executes data-subject request lifecycle work against the control catalog. */
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
    private final OrgMemberService orgMemberService;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;

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
        orgMemberService.requireOrgAdmin(orgId, actorId);
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

    @Transactional
    public DataSubjectRequestDto create(int orgId, int actorId, DataSubjectRequest request) {
        requireMutationAccess(orgId, actorId);
        dataSubjectRequestMapper.insert(request);
        auditService.record("appi.subject_request.create", "organization", orgId,
            requestLabel(request.getId()), "APPI data-subject request created", auditChanges(request));
        return DataSubjectRequestDto.from(findRequest(orgId, request.getId()));
    }

    @Transactional
    public DataSubjectRequestDto update(int orgId, long requestId, int actorId,
            DataSubjectRequest before, DataSubjectRequest request) {
        requireMutationAccess(orgId, actorId);
        if (dataSubjectRequestMapper.update(request) != 1) {
            throw new ResourceNotFoundException("Data-subject request not found: " + requestId);
        }
        Map<String, Object> changes = new LinkedHashMap<>(auditChanges(request));
        Map<String, Object> diff = auditService.diff(before, request, AUDIT_DIFF_FIELDS);
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
