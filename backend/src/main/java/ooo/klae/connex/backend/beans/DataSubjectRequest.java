package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DataSubjectRequest {
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
}
