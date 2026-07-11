package ooo.klae.connex.backend.ai.riskrationale;

import java.util.List;

/**
 * JSON content shape the model returns for a deal-risk rationale. Bound from provider output at the
 * invocation boundary before any feature-level validation.
 * @param narrative plain-text "before you act" narrative
 * @param actions ordered recommended next actions
 */
public record DealRiskRationaleContent(String narrative, List<String> actions) {
}
