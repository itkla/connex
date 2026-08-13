package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/** One ephemeral assistant function call followed by its masked tool-role result. */
public record AiToolExchange(AiToolCall call, String maskedResult) {
    public AiToolExchange {
        Objects.requireNonNull(call, "call");
        if (maskedResult == null || maskedResult.isBlank()) {
            throw new IllegalArgumentException("AI tool result is required");
        }
    }

    @Override
    public String toString() {
        return "AiToolExchange[call=" + call + ", maskedResult=<redacted>]";
    }
}
