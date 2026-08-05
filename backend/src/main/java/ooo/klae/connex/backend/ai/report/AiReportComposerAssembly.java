package ooo.klae.connex.backend.ai.report;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Masked report-composer prompt and request-local demasking context.
 * @param context request-local masking context
 * @param prompt masked prompt
 */
public record AiReportComposerAssembly(
        MaskingContext context,
        MaskedPrompt prompt) {
}
