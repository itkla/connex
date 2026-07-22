package ooo.klae.connex.backend.ai.riskrationale;

import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Masked prompt and request-local re-identification context for one deal-risk rationale.
 * @param context request-local masking context
 * @param prompt provider-ready masked prompt
 * @param atRisk whether at least one AI-eligible risk factor remains
 * @param contributorPersonIds exact directly contributing contact ids captured before invocation
 */
public record RationaleAssembly(
        MaskingContext context,
        MaskedPrompt prompt,
        boolean atRisk,
        List<Integer> contributorPersonIds) {

    public RationaleAssembly {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
        contributorPersonIds = List.copyOf(
                Objects.requireNonNull(contributorPersonIds, "contributorPersonIds"));
    }
}
