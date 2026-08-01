package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Presentation-only AI risk rationale for a deal, or a graceful unavailability result. The
 * structured {@link #narrative} and {@link #actions} are the source of truth; {@link #rationale} is
 * a plain-text flattening retained for one release so older clients keep rendering.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DealRationaleDto {
    private static final Set<String> UNAVAILABLE_REASONS = Set.of(
            "not_configured", "provider_error", "not_at_risk", "rate_limited");

    private final int dealId;
    private final boolean available;
    private final String narrative;
    private final List<String> actions;
    private final String rationale;
    private final String generatedAt;
    private final int warnings;
    private final String reason;

    /**
     * Creates an available rationale response.
     * @param dealId assessed deal
     * @param narrative demasked narrative
     * @param actions demasked recommended next actions
     * @param generatedAt ISO generation instant
     * @param warnings demasking warning count
     * @return available response
     */
    public static DealRationaleDto of(
            int dealId, String narrative, List<String> actions, String generatedAt, int warnings) {
        String safeNarrative = Objects.requireNonNull(narrative, "narrative");
        List<String> safeActions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        return new DealRationaleDto(
                dealId,
                true,
                safeNarrative,
                safeActions,
                flatten(safeNarrative, safeActions),
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
        return new DealRationaleDto(dealId, false, null, null, null, null, 0, reason);
    }

    private static String flatten(String narrative, List<String> actions) {
        StringBuilder flattened = new StringBuilder(narrative);
        for (String action : actions) {
            flattened.append("\n• ").append(action);
        }
        return flattened.toString();
    }
}
