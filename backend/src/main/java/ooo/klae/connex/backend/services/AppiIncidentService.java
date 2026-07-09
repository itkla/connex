package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AppiIncident;
import ooo.klae.connex.backend.dto.AppiIncidentDto;
import ooo.klae.connex.backend.dto.AppiIncidentRequest;
import ooo.klae.connex.backend.dto.AppiIncidentScopeDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AppiIncidentMapper;

@Service
@RequiredArgsConstructor
public class AppiIncidentService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_OFFSET = 100_000;
    private static final int SCOPE_LIMIT = 200;
    private static final Set<String> STATUSES = Set.of("triage", "contained", "notifiable", "notified", "closed");
    private static final Set<String> SEVERITIES = Set.of("undetermined", "low", "medium", "high", "critical");

    private final AppiIncidentMapper appiIncidentMapper;
    private final OrgMemberService orgMemberService;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;

    public List<AppiIncidentDto> list(int orgId, int actorId, int limit, int offset) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return appiIncidentMapper.findByOrg(orgId, cap(limit), offset(offset)).stream()
            .map(AppiIncidentDto::from)
            .toList();
    }

    public AppiIncidentDto get(int orgId, long incidentId, int actorId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return AppiIncidentDto.from(findIncident(orgId, incidentId));
    }

    @Transactional
    public AppiIncidentDto create(int orgId, int actorId, AppiIncidentRequest request) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        AppiIncident incident = new AppiIncident();
        apply(incident, orgId, actorId, request, true);
        appiIncidentMapper.insert(incident);
        auditService.record("appi.incident.create", "organization", orgId, incidentLabel(incident.getId()),
            "APPI incident record created", Map.of("incidentId", incident.getId(), "status", incident.getStatus()));
        return AppiIncidentDto.from(findIncident(orgId, incident.getId()));
    }

    @Transactional
    public AppiIncidentDto update(int orgId, long incidentId, int actorId, AppiIncidentRequest request) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        AppiIncident incident = findIncident(orgId, incidentId);
        apply(incident, orgId, actorId, request, false);
        if (appiIncidentMapper.update(incident) == 0) {
            throw new ResourceNotFoundException("APPI incident not found: " + incidentId);
        }
        auditService.record("appi.incident.update", "organization", orgId, incidentLabel(incident.getId()),
            "APPI incident record updated", Map.of("incidentId", incident.getId(), "status", incident.getStatus()));
        return AppiIncidentDto.from(findIncident(orgId, incidentId));
    }

    public List<AppiIncidentScopeDto> scope(int orgId, long incidentId, int actorId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        AppiIncident incident = findIncident(orgId, incidentId);
        return appiIncidentMapper.scopeFromAudit(orgId, incident.getOccurredFrom(), incident.getOccurredTo(), SCOPE_LIMIT);
    }

    private AppiIncident findIncident(int orgId, long incidentId) {
        AppiIncident incident = appiIncidentMapper.findById(orgId, incidentId);
        if (incident == null) {
            throw new ResourceNotFoundException("APPI incident not found: " + incidentId);
        }
        return incident;
    }

    private void apply(AppiIncident incident, int orgId, int actorId, AppiIncidentRequest request, boolean create) {
        LocalDateTime occurredFrom = request.getOccurredFrom();
        LocalDateTime occurredTo = request.getOccurredTo();
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw new BadRequestException("occurredFrom must be before occurredTo");
        }
        incident.setOrgId(orgId);
        incident.setTitle(requiredTitle(request.getTitle()));
        incident.setStatus(normalize(request.getStatus(), "triage", STATUSES, "status"));
        incident.setSeverity(normalize(request.getSeverity(), "undetermined", SEVERITIES, "severity"));
        incident.setReportable(Boolean.TRUE.equals(request.getReportable()));
        incident.setOccurredFrom(occurredFrom);
        incident.setOccurredTo(occurredTo);
        incident.setDetectedAt(request.getDetectedAt());
        incident.setCustomerNotifiedAt(request.getCustomerNotifiedAt());
        incident.setPpcReportedAt(request.getPpcReportedAt());
        incident.setIndividualsNotifiedAt(request.getIndividualsNotifiedAt());
        incident.setSummary(blankToNull(request.getSummary()));
        incident.setContainment(blankToNull(request.getContainment()));
        if (create) {
            incident.setCreatedBy(actorId);
        }
        incident.setUpdatedBy(actorId);
    }

    private String normalize(String value, String fallback, Set<String> allowed, String field) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BadRequestException("Unknown " + field + ": " + value);
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requiredTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("title is required");
        }
        return value.trim();
    }

    private static int cap(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static int offset(int offset) {
        return Math.min(Math.max(0, offset), MAX_OFFSET);
    }

    private static String incidentLabel(long incidentId) {
        return "Incident " + incidentId;
    }
}
