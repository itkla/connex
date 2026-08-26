package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for creating one typed watch.
 *
 * <p>Every field is typed and validated at the boundary. A natural-language request never reaches
 * this endpoint directly: the client shows the member a typed preview of exactly these values and
 * they apply it, so there is no path where prose creates a watch whose trigger was never displayed.
 *
 * @param watchType durable watch type key
 * @param subjectKind record kind the watch is about
 * @param subjectId record the watch is about
 * @param thresholdBand declared warmth band, required by the cooling type
 * @param thresholdDays declared day count, required by the no-interaction type
 * @param thresholdLevel declared risk level, required by the deal-risk type
 * @param cooldownDays minimum days between two firings of the same condition
 * @param expiresOn ISO-8601 local date the watch stops evaluating, or null
 */
public record AiWatchCreateRequest(
        @NotBlank
        @Pattern(regexp = "relationship_cooling|no_interaction"
                + "|commitment_overdue|deal_risk_threshold")
        String watchType,
        @NotBlank
        @Pattern(regexp = "person|company|deal")
        String subjectKind,
        @Min(1)
        int subjectId,
        @Pattern(regexp = "warm|cool|cold")
        String thresholdBand,
        @Min(1)
        @Max(365)
        Integer thresholdDays,
        @Pattern(regexp = "medium|high")
        String thresholdLevel,
        @Min(1)
        @Max(90)
        Integer cooldownDays,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
        String expiresOn) {
}
