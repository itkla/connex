package ooo.klae.connex.backend.ai.introrationale;

import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Masked prompt and request-local re-identification context for one introduction rationale.
 * @param context request-local masking context
 * @param prompt provider-ready masked prompt
 * @param reasonCodes exact deterministic suggestion reasons available for output binding
 */
public record IntroRationaleAssembly(
        MaskingContext context, MaskedPrompt prompt, Set<String> reasonCodes) {

    public IntroRationaleAssembly {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
        reasonCodes = Set.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
    }
}
