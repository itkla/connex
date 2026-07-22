package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import ooo.klae.connex.backend.util.DateTimes;

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

    /** Returns whether every supplied timestamp fits the MySQL {@code DATETIME} year range. */
    @AssertTrue(message = "timestamps must use years from 1000 through 9999")
    @JsonIgnore
    public boolean isMysqlDateTimeRangeValid() {
        return supported(occurredFrom)
            && supported(occurredTo)
            && supported(detectedAt)
            && supported(customerNotifiedAt)
            && supported(ppcReportedAt)
            && supported(individualsNotifiedAt);
    }

    private static boolean supported(LocalDateTime value) {
        return DateTimes.fitsMysqlDateTimeRange(value);
    }
}
