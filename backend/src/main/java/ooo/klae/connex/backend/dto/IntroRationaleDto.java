package ooo.klae.connex.backend.dto;

import java.util.Objects;
import java.util.Set;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Presentation-only AI rationale for an introduction suggestion, or a graceful unavailability result.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class IntroRationaleDto {
    private static final Set<String> UNAVAILABLE_REASONS = Set.of(
            "not_configured", "provider_error", "not_a_suggestion", "rate_limited");

    private final int personAId;
    private final int personBId;
    private final boolean available;
    private final String rationale;
    private final String generatedAt;
    private final int warnings;
    private final String reason;

    /**
     * Creates an available rationale response.
     * @param personAId canonical lower person id
     * @param personBId canonical higher person id
     * @param rationale demasked rationale prose
     * @param generatedAt ISO generation instant
     * @param warnings demasking warning count
     * @return available response
     */
    public static IntroRationaleDto of(
            int personAId, int personBId, String rationale, String generatedAt, int warnings) {
        return new IntroRationaleDto(
                personAId,
                personBId,
                true,
                Objects.requireNonNull(rationale, "rationale"),
                Objects.requireNonNull(generatedAt, "generatedAt"),
                warnings,
                null);
    }

    /**
     * Creates a graceful unavailability response.
     * @param personAId canonical lower person id
     * @param personBId canonical higher person id
     * @param reason stable unavailability reason
     * @return unavailable response
     */
    public static IntroRationaleDto unavailable(int personAId, int personBId, String reason) {
        if (reason == null || !UNAVAILABLE_REASONS.contains(reason)) {
            throw new IllegalArgumentException("Unsupported introduction rationale unavailability reason");
        }
        return new IntroRationaleDto(personAId, personBId, false, null, null, 0, reason);
    }
}
