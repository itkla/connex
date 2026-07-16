package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class DataSubjectRequestUpsertRequest {
    @Size(max = 32)
    private String requestType;

    @Size(max = 32)
    private String status;

    @Size(max = 64)
    private String channel;

    @NotBlank
    @Size(max = 160)
    private String requesterName;

    @NotBlank
    @Size(max = 160)
    private String subjectName;

    @Email
    @Size(max = 255)
    private String subjectEmail;

    private Integer subjectWorkspaceId;
    private Integer subjectPersonId;
    private LocalDateTime receivedAt;
    private LocalDateTime identityVerifiedAt;
    private LocalDateTime dueAt;
    private LocalDateTime respondedAt;
    private LocalDateTime closedAt;

    @Size(max = 10000)
    private String summary;

    @Size(max = 10000)
    private String resolution;

    /** Returns whether both subject-link identifiers are supplied or both are omitted. */
    @AssertTrue(message = "subjectWorkspaceId and subjectPersonId must be supplied together")
    @JsonIgnore
    public boolean isSubjectLinkComplete() {
        return (subjectWorkspaceId == null) == (subjectPersonId == null);
    }

    /** Returns whether every supplied timestamp fits the MySQL {@code DATETIME} year range. */
    @AssertTrue(message = "timestamps must use years from 1000 through 9999")
    @JsonIgnore
    public boolean isMysqlDateTimeRangeValid() {
        return supported(receivedAt)
            && supported(identityVerifiedAt)
            && supported(dueAt)
            && supported(respondedAt)
            && supported(closedAt);
    }

    private static boolean supported(LocalDateTime value) {
        if (value == null) {
            return true;
        }
        LocalDateTime stored = value.getNano() >= 500_000_000
            ? value.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
            : value;
        return stored.getYear() >= 1000 && stored.getYear() <= 9999;
    }
}
