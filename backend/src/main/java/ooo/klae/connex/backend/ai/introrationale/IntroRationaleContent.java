package ooo.klae.connex.backend.ai.introrationale;

import java.util.List;

/**
 * JSON content shape the model returns for an introduction rationale. Bound from provider output at
 * the invocation boundary before any feature-level validation.
 * @param rationale single-sentence plain-text rationale
 * @param reasonCodes deterministic suggestion reasons supporting the rationale
 */
public record IntroRationaleContent(String rationale, List<String> reasonCodes) {
}
