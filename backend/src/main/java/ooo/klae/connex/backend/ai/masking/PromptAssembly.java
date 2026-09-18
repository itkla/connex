package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;

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
     * Starts a masked prompt builder bound to the request's masking context.
     *
     * @param ctx request-local masking context the prompt's identifiers were seeded into
     * @return prompt builder
     */
    public static Builder builder(MaskingContext ctx) {
        return new Builder(Objects.requireNonNull(ctx, "ctx"));
    }

    /**
     * Builder for provider-ready masked prompts.
     */
    public static final class Builder {
        private final MaskedPrompt.Builder delegate = MaskedPrompt.builder();
        private final MaskingContext ctx;

        private Builder(MaskingContext ctx) {
            this.ctx = ctx;
        }

        /**
         * Sets the masked system prompt and registers it as server-authored text.
         *
         * <p>A system prompt is written by this codebase, so every word in it is emitted whatever
         * the tenant's records are called. Registering it here — rather than leaving each caller
         * to remember — is what stops a record named after one of those words (a company named
         * {@code Tokens} against a directive mentioning "placeholder tokens") from making the
         * outbound leak scan refuse every request that seeds it.
         *
         * @param maskedSystemText masked system text, or null
         * @return this builder
         */
        public Builder system(String maskedSystemText) {
            delegate.systemPrompt(maskedSystemText);
            ctx.addTrustedStaticText(maskedSystemText);
            return this;
        }

        /**
         * Adds a masked user turn.
         * @param maskedContent masked message content
         * @return this builder
         */
        public Builder userTurn(String maskedContent) {
            delegate.addMessage(MaskedMessage.ROLE_USER, maskedContent);
            return this;
        }

        /**
         * Adds a masked assistant turn.
         * @param maskedContent masked message content
         * @return this builder
         */
        public Builder assistantTurn(String maskedContent) {
            delegate.addMessage(MaskedMessage.ROLE_ASSISTANT, maskedContent);
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
