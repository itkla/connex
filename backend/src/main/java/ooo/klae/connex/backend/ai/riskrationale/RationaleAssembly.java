package ooo.klae.connex.backend.ai.riskrationale;

import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Masked prompt and request-local re-identification context for one deal-risk rationale.
 * @param context request-local masking context
 * @param prompt provider-ready masked prompt
 */
public record RationaleAssembly(MaskingContext context, MaskedPrompt prompt) {

    public RationaleAssembly {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
    }
}
