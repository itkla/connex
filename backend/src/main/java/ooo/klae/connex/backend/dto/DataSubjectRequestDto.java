package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import ooo.klae.connex.backend.beans.DataSubjectRequest;

@Data
public class DataSubjectRequestDto {
    private long id;
    private int orgId;
    private String requestType;
    private String status;
    private String channel;
    private String requesterName;
    private String subjectName;
    private String subjectEmail;
    private Integer subjectWorkspaceId;
    private Integer subjectPersonId;
    private LocalDateTime receivedAt;
    private LocalDateTime identityVerifiedAt;
    private LocalDateTime dueAt;
    private LocalDateTime respondedAt;
    private LocalDateTime closedAt;
    private String summary;
    private String resolution;
    private Integer createdBy;
    private Integer updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DataSubjectRequestDto from(DataSubjectRequest request) {
        DataSubjectRequestDto dto = new DataSubjectRequestDto();
        dto.setId(request.getId());
        dto.setOrgId(request.getOrgId());
        dto.setRequestType(request.getRequestType());
        dto.setStatus(request.getStatus());
        dto.setChannel(request.getChannel());
        dto.setRequesterName(request.getRequesterName());
        dto.setSubjectName(request.getSubjectName());
        dto.setSubjectEmail(request.getSubjectEmail());
        dto.setSubjectWorkspaceId(request.getSubjectWorkspaceId());
        dto.setSubjectPersonId(request.getSubjectPersonId());
        dto.setReceivedAt(request.getReceivedAt());
        dto.setIdentityVerifiedAt(request.getIdentityVerifiedAt());
        dto.setDueAt(request.getDueAt());
        dto.setRespondedAt(request.getRespondedAt());
        dto.setClosedAt(request.getClosedAt());
        dto.setSummary(request.getSummary());
        dto.setResolution(request.getResolution());
        dto.setCreatedBy(request.getCreatedBy());
        dto.setUpdatedBy(request.getUpdatedBy());
        dto.setCreatedAt(request.getCreatedAt());
        dto.setUpdatedAt(request.getUpdatedAt());
        return dto;
    }
}
