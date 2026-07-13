package ooo.klae.connex.backend.ai.report;

import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Provider-ready report prompt and its request-local re-identification context.
 * @param context request-local masking context
 * @param prompt provider-ready masked prompt
 */
public record AiReportAssembly(MaskingContext context, MaskedPrompt prompt) {

    public AiReportAssembly {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
    }
}
