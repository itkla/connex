package ooo.klae.connex.backend.ai.masking;

/**
 * Public facade for assembling prompts after values have crossed the masking boundary. Callers
 * MUST pass values already masked with {@link MaskingEngine#maskField(EntityKind, String,
 * MaskingContext)} or {@link MaskingEngine#maskFreeText(String, MaskingContext)}. Runtime
 * enforcement remains {@link OutboundLeakScan}, which scans the serialized outbound payload before
 * it can leave Connex. Keeping construction here prevents code outside {@code ai.masking} from
 * bypassing the package-private {@link MaskedPrompt} builder.
 */
public final class PromptAssembly {

    private PromptAssembly() {
    }

    /**
     * Starts a masked prompt builder.
     * @return prompt builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for provider-ready masked prompts.
     */
    public static final class Builder {
        private final MaskedPrompt.Builder delegate = MaskedPrompt.builder();

        private Builder() {
        }

        /**
         * Sets the masked system prompt.
         * @param maskedSystemText masked system text, or null
         * @return this builder
         */
        public Builder system(String maskedSystemText) {
            delegate.systemPrompt(maskedSystemText);
            return this;
        }

        /**
         * Adds a masked user turn.
         * @param maskedContent masked message content
         * @return this builder
         */
        public Builder userTurn(String maskedContent) {
            delegate.addMessage("user", maskedContent);
            return this;
        }

        /**
         * Adds a masked assistant turn.
         * @param maskedContent masked message content
         * @return this builder
         */
        public Builder assistantTurn(String maskedContent) {
            delegate.addMessage("assistant", maskedContent);
            return this;
        }

        /**
         * Builds the masked prompt.
         * @return masked prompt
         */
        public MaskedPrompt build() {
            return delegate.build();
        }
    }
}
