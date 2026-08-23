package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the cost of the fixed Ask Connex envelope on the smallest supported context window.
 *
 * <p>The declared tool catalog is serialized twice into every model step: once as the prompt
 * vocabulary inside the system instructions and once as the strict step response schema. On a 32k
 * model {@link AiAssistantPromptBudget} spends the envelope directly out of the output-token
 * allocation, so each added byte removes roughly one token the model has left to write its answer
 * with, until the allocation reaches zero and the turn cannot start at all.
 *
 * <p>{@code AiInvocationServiceTest} proves the real first-tool-result prompt is still admitted.
 * This test guards the margin behind that admission so a new tool or a new sentence of policy fails
 * here, with the numbers printed, rather than silently starving the answer budget.
 */
class AiAssistantPromptEnvelopeTest {

    private static final int SMALLEST_SUPPORTED_CONTEXT_TOKENS = 32_768;
    private static final int CONFIGURED_MAX_OUTPUT_TOKENS = 16_384;

    /**
     * The minimum output-token allocation a 32k model must retain after the fixed envelope is paid.
     * Below this the assistant cannot reliably emit a complete answer document.
     */
    private static final int MINIMUM_RETAINED_OUTPUT_TOKENS = 512;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiAssistantToolCatalog toolCatalog = new AiAssistantToolCatalog();
    private final AiAssistantPromptAssembler promptAssembler =
            new AiAssistantPromptAssembler(objectMapper, toolCatalog);
    private final AiAssistantStepSchema stepSchema =
            new AiAssistantStepSchema(objectMapper, toolCatalog);

    @Test
    void theFixedEnvelopeLeavesAUsableOutputBudgetOnTheSmallestSupportedContextWindow() {
        int reactEnvelope = reactEnvelopeBytes();
        int nativeEnvelope = nativeEnvelopeBytes();
        AiAssistantPromptBudget reactBudget = budget(reactEnvelope);
        AiAssistantPromptBudget nativeBudget = budget(nativeEnvelope);
        System.out.println("[envelope] react fixed=" + reactEnvelope
                + " outputTokens=" + reactBudget.maxOutputTokens()
                + " toolResultBytes=" + reactBudget.toolResultBytes());
        System.out.println("[envelope] native fixed=" + nativeEnvelope
                + " outputTokens=" + nativeBudget.maxOutputTokens()
                + " toolResultBytes=" + nativeBudget.toolResultBytes());
        assertTrue(reactBudget.maxOutputTokens() >= MINIMUM_RETAINED_OUTPUT_TOKENS,
                "The fixed JSON-ReAct envelope of " + reactEnvelope
                        + " bytes leaves only " + reactBudget.maxOutputTokens()
                        + " output tokens on a 32k model");
        assertTrue(nativeBudget.maxOutputTokens() >= MINIMUM_RETAINED_OUTPUT_TOKENS,
                "The fixed native-tool envelope of " + nativeEnvelope
                        + " bytes leaves only " + nativeBudget.maxOutputTokens()
                        + " output tokens on a 32k model");
    }

    private static AiAssistantPromptBudget budget(int fixedEnvelopeBytes) {
        return AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        SMALLEST_SUPPORTED_CONTEXT_TOKENS,
                        8_192),
                CONFIGURED_MAX_OUTPUT_TOKENS,
                fixedEnvelopeBytes);
    }

    private int reactEnvelopeBytes() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", taggedSystemPrompt(
                promptAssembler.fixedPrompt().getSystemPrompt()));
        payload.put("messages", List.of());
        payload.put("responseSchema", stepSchema.responseSchema().schema());
        return serializedBytes(payload);
    }

    private int nativeEnvelopeBytes() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", taggedSystemPrompt(
                promptAssembler.fixedNativePrompt().getSystemPrompt()));
        payload.put("messages", List.of());
        payload.put("responseSchema", stepSchema.finalResponseSchema().schema());
        payload.put("tools", promptAssembler.nativeToolDefinitions().stream()
                .map(definition -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", definition.name());
                    tool.put("description", definition.description());
                    tool.put("parameters", definition.parametersSchema());
                    return tool;
                })
                .toList());
        payload.put("toolExchanges", List.of());
        return serializedBytes(payload);
    }

    private static String taggedSystemPrompt(String systemPrompt) {
        return systemPrompt + "\n\n" + AiInvocationService.TAGGED_REASONING_INSTRUCTION;
    }

    private int serializedBytes(Map<String, Object> payload) {
        return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length;
    }
}
