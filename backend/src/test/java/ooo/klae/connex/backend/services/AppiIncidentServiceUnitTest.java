package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.AppiIncident;
import ooo.klae.connex.backend.dto.AppiIncidentDto;
import ooo.klae.connex.backend.dto.AppiIncidentRequest;
import ooo.klae.connex.backend.dto.AppiIncidentScopeDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.AppiIncidentMapper;

@ExtendWith(MockitoExtension.class)
class AppiIncidentServiceUnitTest {
    @Mock private AppiIncidentMapper appiIncidentMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;

    private AppiIncidentService service;

    @BeforeEach
    void setUp() {
        service = new AppiIncidentService(
            appiIncidentMapper, orgMemberService, auditService, sessionSecurityService);
    }

    @Test
    void updateClearsOmittedNullableFields() {
        LocalDateTime occurredFrom = LocalDateTime.of(2026, 1, 2, 3, 0);
        LocalDateTime occurredTo = occurredFrom.plusHours(2);
        AppiIncident incident = incident(occurredFrom, occurredTo);
        when(appiIncidentMapper.findById(3, 9)).thenReturn(incident);
        when(appiIncidentMapper.update(incident)).thenReturn(1);
        AppiIncidentRequest request = new AppiIncidentRequest();
        request.setTitle("Retitled");

        AppiIncidentDto updated = service.update(3, 9, 7, request);

        assertEquals("closed", updated.getStatus());
        assertEquals("critical", updated.getSeverity());
        assertTrue(updated.isReportable());
        assertNull(updated.getOccurredFrom());
        assertNull(updated.getOccurredTo());
        assertNull(updated.getSummary());
        assertNull(updated.getContainment());
    }

    @Test
    void updateValidatesTheReplacementWindowWithoutStoredValues() {
        LocalDateTime occurredFrom = LocalDateTime.of(2026, 1, 2, 3, 0);
        LocalDateTime occurredTo = occurredFrom.plusHours(2);
        AppiIncident incident = incident(occurredFrom, occurredTo);
        when(appiIncidentMapper.findById(3, 9)).thenReturn(incident);
        when(appiIncidentMapper.update(incident)).thenReturn(1);
        AppiIncidentRequest request = new AppiIncidentRequest();
        request.setTitle("Invalid");
        LocalDateTime replacementStart = occurredTo.plusSeconds(1);
        request.setOccurredFrom(replacementStart);

        AppiIncidentDto updated = service.update(3, 9, 7, request);

        assertEquals(replacementStart, updated.getOccurredFrom());
        assertNull(updated.getOccurredTo());
    }

    @Test
    void scopeRejectsAnUnboundedWindow() {
        AppiIncident incident = incident(null, null);
        when(appiIncidentMapper.findById(3, 9)).thenReturn(incident);

        assertThrows(BadRequestException.class, () -> service.scope(3, 9, 7, 1, 50));

        verify(appiIncidentMapper, never()).scopeFromAudit(3, null, null, 50, 0);
    }

    @Test
    void scopeReturnsBoundedPaginationMetadata() {
        LocalDateTime occurredFrom = LocalDateTime.of(2026, 1, 2, 3, 0);
        LocalDateTime occurredTo = occurredFrom.plusHours(2);
        AppiIncident incident = incident(occurredFrom, occurredTo);
        AppiIncidentScopeDto row = new AppiIncidentScopeDto();
        when(appiIncidentMapper.findById(3, 9)).thenReturn(incident);
        when(appiIncidentMapper.scopeFromAudit(3, occurredFrom, occurredTo, 25, 25))
            .thenReturn(List.of(row));
        when(appiIncidentMapper.countScopeFromAudit(3, occurredFrom, occurredTo)).thenReturn(125L);

        PageResponse<AppiIncidentScopeDto> response = service.scope(3, 9, 7, 2, 25);

        assertEquals(List.of(row), response.items());
        assertEquals(125, response.total());
    }

    private static AppiIncident incident(LocalDateTime occurredFrom, LocalDateTime occurredTo) {
        AppiIncident incident = new AppiIncident();
        incident.setId(9);
        incident.setOrgId(3);
        incident.setTitle("Initial");
        incident.setStatus("closed");
        incident.setSeverity("critical");
        incident.setReportable(true);
        incident.setOccurredFrom(occurredFrom);
        incident.setOccurredTo(occurredTo);
        incident.setDetectedAt(LocalDateTime.of(2026, 1, 2, 4, 0));
        incident.setSummary("Summary");
        incident.setContainment("Containment");
        return incident;
    }
}
