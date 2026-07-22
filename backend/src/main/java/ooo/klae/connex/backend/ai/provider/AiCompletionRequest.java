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
 * @param maxTokens provider output token cap
 * @param temperature provider sampling temperature
 */
public record AiCompletionRequest(
        AiProviderTarget target,
        AiCredentials credentials,
        String systemPrompt,
        List<AiMessage> messages,
        List<AiInputImage> images,
        int maxTokens,
        double temperature) {

    public AiCompletionRequest(
            AiProviderTarget target,
            AiCredentials credentials,
            String systemPrompt,
            List<AiMessage> messages,
            int maxTokens,
            double temperature) {
        this(target, credentials, systemPrompt, messages, List.of(), maxTokens, temperature);
    }

    public AiCompletionRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(credentials, "credentials");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        if (images.size() > 1) {
            throw new IllegalArgumentException("AI completion accepts at most one image");
        }
    }

    @Override
    public String toString() {
        return "AiCompletionRequest[target=" + target
                + ", credentials=<redacted>, systemPrompt=<redacted>, messages=<redacted>, maxTokens="
                + maxTokens + ", images=<redacted>, temperature=" + temperature + "]";
    }
}
