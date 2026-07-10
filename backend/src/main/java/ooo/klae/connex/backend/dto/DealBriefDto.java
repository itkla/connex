package ooo.klae.connex.backend.dto;

import java.util.Objects;
import java.util.Set;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Presentation-only AI brief for a deal, or a graceful unavailability result.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DealBriefDto {
    private static final Set<String> UNAVAILABLE_REASONS = Set.of("not_configured", "provider_error");

    private final int dealId;
    private final boolean available;
    private final String brief;
    private final String generatedAt;
    private final int warnings;
    private final String reason;

    /**
     * Creates an available brief response.
     * @param dealId summarized deal
     * @param brief demasked brief prose
     * @param generatedAt ISO generation instant
     * @param warnings demasking warning count
     * @return available response
     */
    public static DealBriefDto of(int dealId, String brief, String generatedAt, int warnings) {
        return new DealBriefDto(
                dealId,
                true,
                Objects.requireNonNull(brief, "brief"),
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
    public static DealBriefDto unavailable(int dealId, String reason) {
        if (!UNAVAILABLE_REASONS.contains(reason)) {
            throw new IllegalArgumentException("Unsupported deal brief unavailability reason");
        }
        return new DealBriefDto(dealId, false, null, null, 0, reason);
    }
}
