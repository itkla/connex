package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Evidence-specific request to dismiss or reopen one ordered duplicate pair.
 *
 * @param recordType {@code person} or {@code company}
 * @param kind lowercase canonical identity kind
 * @param recordIdA first member identifier in either order
 * @param recordIdB second member identifier in either order
 * @param evidenceFingerprint opaque fingerprint returned by the list endpoint
 * @param note optional bounded dismissal note; ignored by reopen
 */
public record DuplicateReviewDecisionRequest(
        @NotBlank @Pattern(regexp = "person|company") String recordType,
        @NotBlank @Pattern(regexp = "email|phone|domain|external_id") String kind,
        @Positive int recordIdA,
        @Positive int recordIdB,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String evidenceFingerprint,
        @Size(max = 500) String note) {
}
