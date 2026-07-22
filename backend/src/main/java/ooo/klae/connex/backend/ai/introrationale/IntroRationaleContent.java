package ooo.klae.connex.backend.ai.introrationale;

/**
 * JSON content shape the model returns for an introduction rationale. Bound from provider output at
 * the invocation boundary before any feature-level validation.
 * @param rationale single-sentence plain-text rationale
 */
public record IntroRationaleContent(String rationale) {
}
