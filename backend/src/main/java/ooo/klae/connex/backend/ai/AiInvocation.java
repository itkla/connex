package ooo.klae.connex.backend.ai;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiInvocationProtocol;

/**
 * Request to the AI invocation choke point. The prompt and context are redacted from
 * {@link #toString()} because they may contain masked provider payloads or request-local
 * re-identification state.
 * @param feature AI feature being invoked
 * @param context request-local masking context
 * @param prompt masked prompt to send
 * @param images bounded embedded images to send with the first user turn
 * @param maxTokens provider output token cap
 * @param temperature provider sampling temperature
 * @param reasoningRequested whether the feature requests display-only model reasoning
 * @param callerDeadline absolute caller-owned deadline, or {@code null} for the provider default
 * @param protocol metadata-only provider protocol diagnostic
 * @param nativeToolsDegradedStatus sanitized native-tool rejection status when this invocation is
 *                                  a turn-local JSON-ReAct degradation retry
 */
public record AiInvocation(
        AiFeature feature,
        MaskingContext context,
        MaskedPrompt prompt,
        List<AiInputImage> images,
        int maxTokens,
        double temperature,
        boolean reasoningRequested,
        Instant callerDeadline,
        AiInvocationProtocol protocol,
        Integer nativeToolsDegradedStatus) {

    public AiInvocation(
            AiFeature feature,
            MaskingContext context,
            MaskedPrompt prompt,
            List<AiInputImage> images,
            int maxTokens,
            double temperature,
            boolean reasoningRequested,
            Instant callerDeadline,
            AiInvocationProtocol protocol) {
        this(feature, context, prompt, images, maxTokens, temperature, reasoningRequested,
                callerDeadline, protocol, null);
    }

    public AiInvocation(
            AiFeature feature,
            MaskingContext context,
            MaskedPrompt prompt,
            int maxTokens,
            double temperature) {
        this(feature, context, prompt, List.of(), maxTokens, temperature, false, null,
                AiInvocationProtocol.STANDARD);
    }

    public AiInvocation(
            AiFeature feature,
            MaskingContext context,
            MaskedPrompt prompt,
            int maxTokens,
            double temperature,
            boolean reasoningRequested) {
        this(feature, context, prompt, List.of(), maxTokens, temperature, reasoningRequested, null,
                AiInvocationProtocol.STANDARD);
    }

    public AiInvocation(
            AiFeature feature,
            MaskingContext context,
            MaskedPrompt prompt,
            int maxTokens,
            double temperature,
            boolean reasoningRequested,
            Instant callerDeadline) {
        this(feature, context, prompt, List.of(), maxTokens, temperature,
                reasoningRequested, callerDeadline, AiInvocationProtocol.STANDARD);
    }

    public AiInvocation(
            AiFeature feature,
            MaskingContext context,
            MaskedPrompt prompt,
            List<AiInputImage> images,
            int maxTokens,
            double temperature) {
        this(feature, context, prompt, images, maxTokens, temperature, false, null,
                AiInvocationProtocol.STANDARD);
    }

    public AiInvocation(
            AiFeature feature,
            MaskingContext context,
            MaskedPrompt prompt,
            List<AiInputImage> images,
            int maxTokens,
            double temperature,
            boolean reasoningRequested) {
        this(feature, context, prompt, images, maxTokens, temperature,
                reasoningRequested, null, AiInvocationProtocol.STANDARD);
    }

    public AiInvocation(
            AiFeature feature,
            MaskingContext context,
            MaskedPrompt prompt,
            List<AiInputImage> images,
            int maxTokens,
            double temperature,
            boolean reasoningRequested,
            Instant callerDeadline) {
        this(feature, context, prompt, images, maxTokens, temperature,
                reasoningRequested, callerDeadline, AiInvocationProtocol.STANDARD);
    }

    public AiInvocation {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(protocol, "protocol");
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        if (images.size() > 1) {
            throw new IllegalArgumentException("AI invocation accepts at most one image");
        }
        if ((feature.requiresImageInput() && images.isEmpty())
                || (!feature.acceptsImageInput() && !images.isEmpty())) {
            throw new IllegalArgumentException("AI invocation input does not match its feature");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0) {
            throw new IllegalArgumentException("temperature must be a finite non-negative number");
        }
        if (nativeToolsDegradedStatus != null
                && (nativeToolsDegradedStatus < 400 || nativeToolsDegradedStatus > 499)) {
            throw new IllegalArgumentException(
                    "nativeToolsDegradedStatus must be a client-error status");
        }
    }

    @Override
    public String toString() {
        return "AiInvocation[feature=" + feature.wireKey()
                + ", context=<redacted>"
                + ", prompt=<redacted>"
                + ", images=<redacted>"
                + ", maxTokens=" + maxTokens
                + ", temperature=" + temperature
                + ", reasoningRequested=" + reasoningRequested
                + ", protocol=" + protocol
                + ", nativeToolsDegraded=" + (nativeToolsDegradedStatus != null)
                + ", nativeToolsDegradedStatus=" + nativeToolsDegradedStatus + "]";
    }
}
