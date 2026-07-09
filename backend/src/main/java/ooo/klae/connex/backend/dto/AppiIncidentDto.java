package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import ooo.klae.connex.backend.beans.AppiIncident;

@Data
public class AppiIncidentDto {
    private long id;
    private int orgId;
    private String title;
    private String status;
    private String severity;
    private boolean reportable;
    private LocalDateTime occurredFrom;
    private LocalDateTime occurredTo;
    private LocalDateTime detectedAt;
    private LocalDateTime customerNotifiedAt;
    private LocalDateTime ppcReportedAt;
    private LocalDateTime individualsNotifiedAt;
    private String summary;
    private String containment;
    private Integer createdBy;
    private Integer updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AppiIncidentDto from(AppiIncident incident) {
        AppiIncidentDto dto = new AppiIncidentDto();
        dto.setId(incident.getId());
        dto.setOrgId(incident.getOrgId());
        dto.setTitle(incident.getTitle());
        dto.setStatus(incident.getStatus());
        dto.setSeverity(incident.getSeverity());
        dto.setReportable(incident.isReportable());
        dto.setOccurredFrom(incident.getOccurredFrom());
        dto.setOccurredTo(incident.getOccurredTo());
        dto.setDetectedAt(incident.getDetectedAt());
        dto.setCustomerNotifiedAt(incident.getCustomerNotifiedAt());
        dto.setPpcReportedAt(incident.getPpcReportedAt());
        dto.setIndividualsNotifiedAt(incident.getIndividualsNotifiedAt());
        dto.setSummary(incident.getSummary());
        dto.setContainment(incident.getContainment());
        dto.setCreatedBy(incident.getCreatedBy());
        dto.setUpdatedBy(incident.getUpdatedBy());
        dto.setCreatedAt(incident.getCreatedAt());
        dto.setUpdatedAt(incident.getUpdatedAt());
        return dto;
    }
}
