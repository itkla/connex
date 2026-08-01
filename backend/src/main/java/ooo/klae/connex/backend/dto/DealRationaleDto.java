package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Presentation-only AI risk rationale for a deal, or a graceful unavailability result. The
 * structured {@link #narrative}, {@link #narrativeFactorCodes}, and {@link #recommendedActions} are
 * the source of truth; {@link #actions} and {@link #rationale} remain compatibility fallbacks.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DealRationaleDto {
    private static final Set<String> UNAVAILABLE_REASONS = Set.of(
            "not_configured", "provider_error", "not_at_risk", "rate_limited");

    private final int dealId;
    private final boolean available;
    private final String narrative;
    private final List<String> narrativeFactorCodes;
    private final List<RecommendedAction> recommendedActions;
    private final List<String> actions;
    private final String rationale;
    private final String generatedAt;
    private final int warnings;
    private final String reason;

    /**
     * Creates an available rationale response.
     * @param dealId assessed deal
     * @param narrative demasked narrative
     * @param narrativeFactorCodes deterministic factors supporting the narrative
     * @param recommendedActions demasked factor-bound next actions
     * @param generatedAt ISO generation instant
     * @param warnings demasking warning count
     * @return available response
     */
    public static DealRationaleDto of(
            int dealId,
            String narrative,
            List<String> narrativeFactorCodes,
            List<RecommendedAction> recommendedActions,
            String generatedAt,
            int warnings) {
        String safeNarrative = Objects.requireNonNull(narrative, "narrative");
        List<String> safeNarrativeCodes = List.copyOf(
                Objects.requireNonNull(narrativeFactorCodes, "narrativeFactorCodes"));
        List<RecommendedAction> safeRecommendedActions = List.copyOf(
                Objects.requireNonNull(recommendedActions, "recommendedActions"));
        List<String> safeActions = safeRecommendedActions.stream()
                .map(RecommendedAction::text)
                .toList();
        return new DealRationaleDto(
                dealId,
                true,
                safeNarrative,
                safeNarrativeCodes,
                safeRecommendedActions,
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
        return new DealRationaleDto(
                dealId, false, null, null, null, null, null, null, 0, reason);
    }

    private static String flatten(String narrative, List<String> actions) {
        StringBuilder flattened = new StringBuilder(narrative);
        for (String action : actions) {
            flattened.append("\n• ").append(action);
        }
        return flattened.toString();
    }

    /**
     * One recommended action with its deterministic risk-factor binding.
     * @param text action text
     * @param factorCodes deterministic factors supporting the action
     */
    public record RecommendedAction(String text, List<String> factorCodes) {

        public RecommendedAction {
            Objects.requireNonNull(text, "text");
            factorCodes = List.copyOf(Objects.requireNonNull(factorCodes, "factorCodes"));
        }
    }
}
