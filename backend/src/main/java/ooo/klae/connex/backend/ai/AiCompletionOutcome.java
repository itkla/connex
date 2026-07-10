package ooo.klae.connex.backend.ai;

import java.util.Objects;

/**
 * Completion returned by the AI invocation choke point after provider output is demasked.
 * @param text demasked completion text
 * @param demaskWarnings number of unknown placeholder references in provider output
 * @param inputTokens provider-reported input token count
 * @param outputTokens provider-reported output token count
 * @param stopReason provider stop reason
 */
public record AiCompletionOutcome(
        String text,
        int demaskWarnings,
        int inputTokens,
        int outputTokens,
        String stopReason) {

    public AiCompletionOutcome {
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String toString() {
        return "AiCompletionOutcome[text=<redacted>"
                + ", demaskWarnings=" + demaskWarnings
                + ", inputTokens=" + inputTokens
                + ", outputTokens=" + outputTokens
                + ", stopReason=" + stopReason + "]";
    }
}
