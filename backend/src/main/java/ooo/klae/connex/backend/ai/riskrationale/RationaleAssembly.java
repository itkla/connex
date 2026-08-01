package ooo.klae.connex.backend.ai.riskrationale;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Masked prompt and request-local re-identification context for one deal-risk rationale.
 * @param context request-local masking context
 * @param prompt provider-ready masked prompt
 * @param atRisk whether at least one AI-eligible risk factor remains
 * @param factorCodes exact deterministic factor codes available for output binding
 * @param contributorPersonIds exact directly contributing contact ids captured before invocation
 */
public record RationaleAssembly(
        MaskingContext context,
        MaskedPrompt prompt,
        boolean atRisk,
        Set<String> factorCodes,
        List<Integer> contributorPersonIds) {

    public RationaleAssembly {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
        factorCodes = Set.copyOf(Objects.requireNonNull(factorCodes, "factorCodes"));
        contributorPersonIds = List.copyOf(
                Objects.requireNonNull(contributorPersonIds, "contributorPersonIds"));
    }
}
