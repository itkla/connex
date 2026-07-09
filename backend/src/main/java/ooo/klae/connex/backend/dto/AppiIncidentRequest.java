package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class AppiIncidentRequest {
    @NotBlank
    @Size(max = 160)
    private String title;

    @Size(max = 32)
    private String status;

    @Size(max = 32)
    private String severity;

    private Boolean reportable;
    private LocalDateTime occurredFrom;
    private LocalDateTime occurredTo;
    private LocalDateTime detectedAt;
    private LocalDateTime customerNotifiedAt;
    private LocalDateTime ppcReportedAt;
    private LocalDateTime individualsNotifiedAt;

    @Size(max = 10000)
    private String summary;

    @Size(max = 10000)
    private String containment;
}
