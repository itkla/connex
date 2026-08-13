package ooo.klae.connex.backend.ai.provider;

import java.util.List;
import java.util.Objects;

/**
 * Internal completion request passed across the provider seam.
 * @param target the configured provider target
 * @param credentials the decrypted provider credentials
 * @param systemPrompt optional system instruction text
 * @param messages ordered conversation messages
 * @param images bounded embedded images attached to the first user message
 * @param outputMode requested provider response shape
 * @param responseSchema optional provider-neutral JSON Schema for structured enforcement
 * @param nativeTools optional native function definitions and completed exchanges
 * @param reasoningMode provider reasoning protocol selected for this request
 * @param providerAttemptExecutor provider-send egress boundary
 * @param maxTokens provider output token cap
 * @param temperature provider sampling temperature
 */
public record AiCompletionRequest(
        AiProviderTarget target,
        AiCredentials credentials,
        String systemPrompt,
        List<AiMessage> messages,
        List<AiInputImage> images,
        AiOutputMode outputMode,
        AiResponseSchema responseSchema,
        AiNativeToolRequest nativeTools,
        AiReasoningMode reasoningMode,
        AiProviderAttemptExecutor providerAttemptExecutor,
        int maxTokens,
        double temperature) {

    public AiCompletionRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(credentials, "credentials");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(reasoningMode, "reasoningMode");
        Objects.requireNonNull(providerAttemptExecutor, "providerAttemptExecutor");
        if (outputMode == AiOutputMode.TEXT && responseSchema != null) {
            throw new IllegalArgumentException("AI text completion cannot declare a response schema");
        }
        if (nativeTools != null
                && (outputMode != AiOutputMode.JSON || responseSchema == null)) {
            throw new IllegalArgumentException(
                    "AI native tools require a structured terminal response schema");
        }
        if (images.size() > 1) {
            throw new IllegalArgumentException("AI completion accepts at most one image");
        }
    }

    public AiCompletionRequest(
            AiProviderTarget target,
            AiCredentials credentials,
            String systemPrompt,
            List<AiMessage> messages,
            List<AiInputImage> images,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            AiProviderAttemptExecutor providerAttemptExecutor,
            int maxTokens,
            double temperature) {
        this(target, credentials, systemPrompt, messages, images, outputMode, responseSchema, null,
                AiReasoningMode.NONE, providerAttemptExecutor, maxTokens, temperature);
    }

    public AiCompletionRequest(
            AiProviderTarget target,
            AiCredentials credentials,
            String systemPrompt,
            List<AiMessage> messages,
            List<AiInputImage> images,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            AiReasoningMode reasoningMode,
            AiProviderAttemptExecutor providerAttemptExecutor,
            int maxTokens,
            double temperature) {
        this(target, credentials, systemPrompt, messages, images, outputMode, responseSchema, null,
                reasoningMode, providerAttemptExecutor, maxTokens, temperature);
    }

    public AiCompletionRequest(
            AiProviderTarget target,
            AiCredentials credentials,
            String systemPrompt,
            List<AiMessage> messages,
            List<AiInputImage> images,
            AiOutputMode outputMode,
            AiResponseSchema responseSchema,
            int maxTokens,
            double temperature) {
        this(target, credentials, systemPrompt, messages, images, outputMode, responseSchema, null,
                AiReasoningMode.NONE, AiProviderAttemptExecutor.DIRECT, maxTokens, temperature);
    }

    public AiCompletionRequest(
            AiProviderTarget target,
            AiCredentials credentials,
            String systemPrompt,
            List<AiMessage> messages,
            List<AiInputImage> images,
            AiOutputMode outputMode,
            int maxTokens,
            double temperature) {
        this(target, credentials, systemPrompt, messages, images, outputMode, null, null,
                AiReasoningMode.NONE, AiProviderAttemptExecutor.DIRECT, maxTokens, temperature);
    }

    @Override
    public String toString() {
        return "AiCompletionRequest[target=" + target
                + ", credentials=<redacted>, systemPrompt=<redacted>, messages=<redacted>, maxTokens="
                + maxTokens + ", images=<redacted>, outputMode=" + outputMode
                + ", responseSchema=" + (responseSchema == null ? "none" : responseSchema.name())
                + ", nativeTools=" + (nativeTools == null ? "none" : "<redacted>")
                + ", reasoningMode=" + reasoningMode
                + ", temperature=" + temperature + "]";
    }
}
