package ooo.klae.connex.backend.ai.riskrationale;

import java.util.List;

/**
 * JSON content shape the model returns for a deal-risk rationale. Bound from provider output at the
 * invocation boundary before any feature-level validation.
 * @param narrative plain-text "before you act" narrative
 * @param narrativeFactorCodes deterministic factors supporting the narrative
 * @param recommendedActions ordered factor-bound next actions
 * @param actions untyped action-text fallback populated by Connex after validation
 */
public record DealRiskRationaleContent(
        String narrative,
        List<String> narrativeFactorCodes,
        List<RecommendedAction> recommendedActions,
        List<String> actions) {

    /**
     * One recommended action bound to its deterministic risk factors.
     * @param text action text
     * @param factorCodes deterministic factors supporting the action
     */
    public record RecommendedAction(String text, List<String> factorCodes) {
    }
}
