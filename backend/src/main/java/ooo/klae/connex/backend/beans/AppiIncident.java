package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AppiIncident {
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
}
