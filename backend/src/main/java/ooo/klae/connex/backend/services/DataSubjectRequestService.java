package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;

@Service
@RequiredArgsConstructor
public class DataSubjectRequestService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_OFFSET = 100_000;
    private static final int DISCLOSURE_AUDIT_LIMIT = 1_000;
    private static final Set<String> REQUEST_TYPES = Set.of(
        "disclosure", "correction", "cease_use", "cease_provision");
    private static final Set<String> STATUSES = Set.of(
        "received", "verifying", "in_progress", "responded", "refused", "closed");
    private static final Set<String> AUDIT_DIFF_FIELDS = Set.of(
        "requestType", "status", "subjectWorkspaceId", "subjectPersonId", "receivedAt",
        "identityVerifiedAt", "dueAt", "respondedAt", "closedAt");

    private final DataSubjectRequestMapper dataSubjectRequestMapper;
    private final OrgMemberService orgMemberService;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;

    public List<DataSubjectRequestDto> list(int orgId, int actorId, String status, int limit, int offset) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        String normalizedStatus = status == null || status.isBlank()
            ? null
            : normalize(status, null, STATUSES, "status");
        return dataSubjectRequestMapper.findByOrg(orgId, normalizedStatus, cap(limit), offset(offset)).stream()
            .map(DataSubjectRequestDto::from)
            .toList();
    }

    public DataSubjectRequestDto get(int orgId, long requestId, int actorId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return DataSubjectRequestDto.from(findRequest(orgId, requestId));
    }

    @Transactional
    public DataSubjectRequestDto create(int orgId, int actorId, DataSubjectRequestUpsertRequest request) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        DataSubjectRequest subjectRequest = new DataSubjectRequest();
        apply(subjectRequest, orgId, actorId, request, true);
        dataSubjectRequestMapper.insert(subjectRequest);
        auditService.record("appi.subject_request.create", "organization", orgId,
            requestLabel(subjectRequest.getId()), "APPI data-subject request created",
            auditChanges(subjectRequest));
        return DataSubjectRequestDto.from(findRequest(orgId, subjectRequest.getId()));
    }

    /**
     * Merges the supplied representation into the stored request: omitted ({@code null}) fields
     * keep their stored values, so a status-only update can never silently strip
     * identity-verification evidence or the subject link from a compliance record. Text fields
     * are cleared by sending a blank string. Field-level changes to the workflow timestamps and
     * the subject link are recorded in the audit log.
     */
    @Transactional
    public DataSubjectRequestDto update(int orgId, long requestId, int actorId,
            DataSubjectRequestUpsertRequest request) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        DataSubjectRequest subjectRequest = findRequest(orgId, requestId);
        DataSubjectRequest before = auditSnapshot(subjectRequest);
        apply(subjectRequest, orgId, actorId, request, false);
        dataSubjectRequestMapper.update(subjectRequest);
        Map<String, Object> changes = new LinkedHashMap<>(auditChanges(subjectRequest));
        Map<String, Object> diff = auditService.diff(before, subjectRequest, AUDIT_DIFF_FIELDS);
        if (diff != null && !diff.isEmpty()) {
            changes.put("fields", diff);
        }
        auditService.record("appi.subject_request.update", "organization", orgId,
            requestLabel(subjectRequest.getId()), "APPI data-subject request updated", changes);
        return DataSubjectRequestDto.from(findRequest(orgId, requestId));
    }

    /**
     * Assembles the operator-facing disclosure record for the linked subject person (APPI Art. 33).
     * The result is raw assembly material for the handling operator — it may contain third-party
     * personal data and confidential business information, so statutory redaction (Art. 33(2))
     * must be applied by the operator before anything is released to the data subject. Attachments
     * are listed as metadata only; binaries stay behind the attachment endpoints. The audit-trail
     * section is capped at {@link #DISCLOSURE_AUDIT_LIMIT} entries with the uncapped total exposed
     * for truncation detection.
     */
    @Transactional
    public DataSubjectDisclosureDto disclosure(int orgId, long requestId, int actorId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        DataSubjectRequest subjectRequest = findRequest(orgId, requestId);
        if (!"disclosure".equals(subjectRequest.getRequestType())) {
            throw new BadRequestException("Disclosure can only be assembled for a disclosure-type request");
        }
        if (subjectRequest.getIdentityVerifiedAt() == null) {
            throw new BadRequestException(
                "Identity verification must be recorded before disclosure can be assembled");
        }
        Integer workspaceId = subjectRequest.getSubjectWorkspaceId();
        Integer personId = subjectRequest.getSubjectPersonId();
        if (workspaceId == null || personId == null) {
            throw new BadRequestException("A linked subject person is required before disclosure can be assembled");
        }
        PersonDto person = dataSubjectRequestMapper.findDisclosurePerson(orgId, workspaceId, personId);
        if (person == null) {
            throw new ResourceNotFoundException("Linked subject person not found: " + personId);
        }

        DataSubjectDisclosureDto disclosure = new DataSubjectDisclosureDto();
        disclosure.setRequestId(requestId);
        disclosure.setSubjectWorkspaceId(workspaceId);
        disclosure.setSubjectPersonId(personId);
        disclosure.setPerson(person);
        disclosure.setTags(dataSubjectRequestMapper.findDisclosureTags(orgId, workspaceId, personId));
        disclosure.setCustomFieldValues(
            dataSubjectRequestMapper.findDisclosureCustomFields(orgId, workspaceId, personId));
        disclosure.setActivities(dataSubjectRequestMapper.findDisclosureActivities(orgId, workspaceId, personId));
        disclosure.setNotes(dataSubjectRequestMapper.findDisclosureNotes(orgId, workspaceId, personId));
        disclosure.setTasks(dataSubjectRequestMapper.findDisclosureTasks(orgId, workspaceId, personId));
        disclosure.setAttachments(dataSubjectRequestMapper.findDisclosureAttachments(orgId, workspaceId, personId));
        disclosure.setEmploymentHistory(
            dataSubjectRequestMapper.findDisclosureEmployment(orgId, workspaceId, personId));
        disclosure.setRelationshipEdges(dataSubjectRequestMapper.findDisclosureEdges(orgId, workspaceId, personId));
        disclosure.setDealAssociations(dataSubjectRequestMapper.findDisclosureDeals(orgId, workspaceId, personId));
        disclosure.setIntroductions(
            dataSubjectRequestMapper.findDisclosureIntroductions(orgId, workspaceId, personId));
        disclosure.setThirdPartyProvisions(
            dataSubjectRequestMapper.findDisclosureProvisions(orgId, workspaceId, personId));
        disclosure.setAuditTrail(
            dataSubjectRequestMapper.findDisclosureAudit(orgId, workspaceId, personId, DISCLOSURE_AUDIT_LIMIT));
        disclosure.setAuditTrailTotal(dataSubjectRequestMapper.countDisclosureAudit(orgId, workspaceId, personId));
        disclosure.setGeneratedAt(LocalDateTime.now());

        try {
            auditService.recordStrict("appi.subject_request.disclosure", "organization", orgId,
                requestLabel(subjectRequest.getId()), "Subject-scoped disclosure export assembled",
                Map.of("requestId", subjectRequest.getId(), "subjectPersonId", personId,
                    "subjectWorkspaceId", workspaceId));
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException(
                "Disclosure requires a durable audit record and none could be written", e);
        }
        return disclosure;
    }

    private DataSubjectRequest findRequest(int orgId, long requestId) {
        DataSubjectRequest request = dataSubjectRequestMapper.findById(orgId, requestId);
        if (request == null) {
            throw new ResourceNotFoundException("Data-subject request not found: " + requestId);
        }
        return request;
    }

    private void apply(DataSubjectRequest target, int orgId, int actorId,
            DataSubjectRequestUpsertRequest request, boolean create) {
        validateRequestShape(request);
        LocalDateTime receivedAt = mysqlPrecision(request.getReceivedAt() == null
            ? create ? LocalDateTime.now() : target.getReceivedAt()
            : request.getReceivedAt());
        Integer subjectWorkspaceId = merge(create, request.getSubjectWorkspaceId(), target.getSubjectWorkspaceId());
        Integer subjectPersonId = merge(create, request.getSubjectPersonId(), target.getSubjectPersonId());
        LocalDateTime identityVerifiedAt =
            mysqlPrecision(merge(create, request.getIdentityVerifiedAt(), target.getIdentityVerifiedAt()));
        LocalDateTime dueAt = mysqlPrecision(merge(create, request.getDueAt(), target.getDueAt()));
        LocalDateTime respondedAt = mysqlPrecision(merge(create, request.getRespondedAt(), target.getRespondedAt()));
        LocalDateTime closedAt = mysqlPrecision(merge(create, request.getClosedAt(), target.getClosedAt()));
        validateChronology(receivedAt, identityVerifiedAt, respondedAt, closedAt);
        validateSubjectLink(orgId, subjectWorkspaceId, subjectPersonId);

        target.setOrgId(orgId);
        target.setRequestType(normalize(request.getRequestType(), create ? null : target.getRequestType(),
            REQUEST_TYPES, "requestType"));
        target.setStatus(normalize(request.getStatus(), create ? "received" : target.getStatus(), STATUSES, "status"));
        target.setChannel(mergeText(create, request.getChannel(), target.getChannel()));
        target.setRequesterName(required(request.getRequesterName(), "requesterName"));
        target.setSubjectName(required(request.getSubjectName(), "subjectName"));
        target.setSubjectEmail(mergeText(create, request.getSubjectEmail(), target.getSubjectEmail()));
        target.setSubjectWorkspaceId(subjectWorkspaceId);
        target.setSubjectPersonId(subjectPersonId);
        target.setReceivedAt(receivedAt);
        target.setIdentityVerifiedAt(identityVerifiedAt);
        target.setDueAt(dueAt);
        target.setRespondedAt(respondedAt);
        target.setClosedAt(closedAt);
        target.setSummary(mergeText(create, request.getSummary(), target.getSummary()));
        target.setResolution(mergeText(create, request.getResolution(), target.getResolution()));
        if (create) {
            target.setCreatedBy(actorId);
        }
        target.setUpdatedBy(actorId);
    }

    private static <T> T merge(boolean create, T supplied, T stored) {
        return create || supplied != null ? supplied : stored;
    }

    private static String mergeText(boolean create, String supplied, String stored) {
        return create || supplied != null ? blankToNull(supplied) : stored;
    }

    private static LocalDateTime mysqlPrecision(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        LocalDateTime truncated = value.truncatedTo(ChronoUnit.SECONDS);
        return value.getNano() >= 500_000_000 ? truncated.plusSeconds(1) : truncated;
    }

    private void validateRequestShape(DataSubjectRequestUpsertRequest request) {
        if (!request.isSubjectLinkComplete()) {
            throw new BadRequestException("subjectWorkspaceId and subjectPersonId must be supplied together");
        }
        if (!request.isMysqlDateTimeRangeValid()) {
            throw new BadRequestException("timestamps must use years from 1000 through 9999");
        }
    }

    private void validateSubjectLink(int orgId, Integer workspaceId, Integer personId) {
        if (workspaceId == null && personId == null) {
            return;
        }
        if (workspaceId == null || personId == null
                || !dataSubjectRequestMapper.subjectPersonInOrg(orgId, workspaceId, personId)) {
            throw new BadRequestException("Subject person must exist in a workspace belonging to the organization");
        }
    }

    private String normalize(String value, String fallback, Set<String> allowed, String field) {
        String normalized = value == null || value.isBlank()
            ? fallback
            : value.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw new BadRequestException("Unknown " + field + ": " + value);
        }
        return normalized;
    }

    private static void validateChronology(LocalDateTime receivedAt, LocalDateTime identityVerifiedAt,
            LocalDateTime respondedAt, LocalDateTime closedAt) {
        if (identityVerifiedAt != null && receivedAt.isAfter(identityVerifiedAt)) {
            throw new BadRequestException(
                "receivedAt (" + receivedAt + ") must be before or equal to identityVerifiedAt");
        }
        if (respondedAt != null && receivedAt.isAfter(respondedAt)) {
            throw new BadRequestException(
                "receivedAt (" + receivedAt + ") must be before or equal to respondedAt");
        }
        if (closedAt != null && receivedAt.isAfter(closedAt)) {
            throw new BadRequestException(
                "receivedAt (" + receivedAt + ") must be before or equal to closedAt");
        }
        if (respondedAt != null && closedAt != null && respondedAt.isAfter(closedAt)) {
            throw new BadRequestException("respondedAt must be before or equal to closedAt");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int cap(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static int offset(int offset) {
        return Math.min(Math.max(0, offset), MAX_OFFSET);
    }

    private static String requestLabel(long requestId) {
        return "Subject request " + requestId;
    }

    private static DataSubjectRequest auditSnapshot(DataSubjectRequest source) {
        DataSubjectRequest snapshot = new DataSubjectRequest();
        snapshot.setId(source.getId());
        snapshot.setOrgId(source.getOrgId());
        snapshot.setRequestType(source.getRequestType());
        snapshot.setStatus(source.getStatus());
        snapshot.setChannel(source.getChannel());
        snapshot.setRequesterName(source.getRequesterName());
        snapshot.setSubjectName(source.getSubjectName());
        snapshot.setSubjectEmail(source.getSubjectEmail());
        snapshot.setSubjectWorkspaceId(source.getSubjectWorkspaceId());
        snapshot.setSubjectPersonId(source.getSubjectPersonId());
        snapshot.setReceivedAt(source.getReceivedAt());
        snapshot.setIdentityVerifiedAt(source.getIdentityVerifiedAt());
        snapshot.setDueAt(source.getDueAt());
        snapshot.setRespondedAt(source.getRespondedAt());
        snapshot.setClosedAt(source.getClosedAt());
        snapshot.setSummary(source.getSummary());
        snapshot.setResolution(source.getResolution());
        snapshot.setCreatedBy(source.getCreatedBy());
        snapshot.setUpdatedBy(source.getUpdatedBy());
        snapshot.setCreatedAt(source.getCreatedAt());
        snapshot.setUpdatedAt(source.getUpdatedAt());
        return snapshot;
    }

    private static Map<String, Object> auditChanges(DataSubjectRequest request) {
        return Map.of("requestId", request.getId(), "requestType", request.getRequestType(),
            "status", request.getStatus());
    }
}
