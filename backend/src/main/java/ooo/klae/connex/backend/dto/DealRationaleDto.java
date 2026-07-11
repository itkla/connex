package ooo.klae.connex.backend.dto;

import java.util.Objects;
import java.util.Set;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Presentation-only AI risk rationale for a deal, or a graceful unavailability result.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DealRationaleDto {
    private static final Set<String> UNAVAILABLE_REASONS = Set.of(
            "not_configured", "provider_error", "not_at_risk");

    private final int dealId;
    private final boolean available;
    private final String rationale;
    private final String generatedAt;
    private final int warnings;
    private final String reason;

    /**
     * Creates an available rationale response.
     * @param dealId assessed deal
     * @param rationale demasked rationale prose
     * @param generatedAt ISO generation instant
     * @param warnings demasking warning count
     * @return available response
     */
    public static DealRationaleDto of(int dealId, String rationale, String generatedAt, int warnings) {
        return new DealRationaleDto(
                dealId,
                true,
                Objects.requireNonNull(rationale, "rationale"),
                Objects.requireNonNull(generatedAt, "generatedAt"),
                warnings,
                null);
    }

    /**
     * Creates a graceful unavailability response.
     * @param dealId requested deal
     * @param reason stable unavailability reason
     * @return unavailable response
     */
    public static DealRationaleDto unavailable(int dealId, String reason) {
        if (reason == null || !UNAVAILABLE_REASONS.contains(reason)) {
            throw new IllegalArgumentException("Unsupported deal rationale unavailability reason");
        }
        return new DealRationaleDto(dealId, false, null, null, 0, reason);
    }
}
