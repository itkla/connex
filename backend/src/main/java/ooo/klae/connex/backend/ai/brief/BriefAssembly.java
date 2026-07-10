package ooo.klae.connex.backend.ai.brief;

import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Masked prompt and request-local re-identification context for one deal brief.
 * @param context request-local masking context
 * @param prompt provider-ready masked prompt
 */
public record BriefAssembly(MaskingContext context, MaskedPrompt prompt) {

    public BriefAssembly {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
    }
}
