package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Pins the cost of the fixed Ask Connex envelope against the declared minimum context window.
 *
 * <p>The declared tool catalog is serialized twice into every model step: once as the prompt
 * vocabulary inside the system instructions and once as the strict step response schema.
 * {@link AiAssistantPromptBudget} spends that envelope directly out of the output-token allocation,
 * whose conservative term reduces to {@code contextTokens - fixedEnvelopeBytes - 14,848}. On a 32k
 * window that is {@code 17,920 - fixedEnvelopeBytes}, which is why 32k is refused rather than
 * quietly starved: today's JSON-ReAct envelope would leave it 958 output tokens.
 *
 * <p>This test guards the relationship at the floor instead. At
 * {@link AiAssistantPromptBudget#ASSISTANT_MIN_CONTEXT_TOKENS} the envelope must still leave at
 * least twice the operator-configured output ceiling — {@value #MINIMUM_FLOOR_OUTPUT_TOKENS} tokens
 * — so the answer budget is funded with room rather than exactly. A new tool or a new sentence of
 * policy that eats that margin fails here, with the numbers printed, rather than silently reducing
 * what the floor was raised to protect.
 */
class AiAssistantPromptEnvelopeTest {

    private static final int FLOOR_CONTEXT_TOKENS =
            AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS;
    private static final int CONFIGURED_MAX_OUTPUT_TOKENS = 16_384;
    private static final int PROVIDER_MAX_OUTPUT_TOKENS = 8_192;

    /**
     * The floor-preserving output allocation the fixed envelope must leave at the declared floor.
     *
     * <p>Twice {@link #CONFIGURED_MAX_OUTPUT_TOKENS}: the floor exists so the envelope is absorbed
     * without competing with the answer, so it may consume at most half of what the window grants
     * beyond the configured ceiling. The JSON-ReAct envelope currently leaves 33,726 tokens, a
     * margin of 958 — the same figure that was a 32k model's entire answer budget.
     */
    private static final int MINIMUM_FLOOR_OUTPUT_TOKENS = 2 * CONFIGURED_MAX_OUTPUT_TOKENS;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiAssistantToolCatalog toolCatalog = new AiAssistantToolCatalog();
    private final AiAssistantPromptAssembler promptAssembler =
            new AiAssistantPromptAssembler(objectMapper, toolCatalog);
    private final AiAssistantStepSchema stepSchema =
            new AiAssistantStepSchema(objectMapper, toolCatalog);

    @Test
    void theFixedEnvelopeLeavesRoomToSpareAtTheDeclaredMinimumContextWindow() {
        int reactEnvelope = reactEnvelopeBytes();
        int nativeEnvelope = nativeEnvelopeBytes();
        int reactFloorOutputTokens = unclampedFloorOutputTokens(reactEnvelope);
        int nativeFloorOutputTokens = unclampedFloorOutputTokens(nativeEnvelope);
        AiAssistantPromptBudget reactBudget = budget(reactEnvelope);
        AiAssistantPromptBudget nativeBudget = budget(nativeEnvelope);
        System.out.println("[envelope] floor=" + FLOOR_CONTEXT_TOKENS
                + " minimumFloorOutputTokens=" + MINIMUM_FLOOR_OUTPUT_TOKENS);
        System.out.println("[envelope] react fixed=" + reactEnvelope
                + " floorOutputTokens=" + reactFloorOutputTokens
                + " outputTokens=" + reactBudget.maxOutputTokens()
                + " toolResultBytes=" + reactBudget.toolResultBytes()
                + " thirtyTwoKOutputTokens=" + (17_920 - reactEnvelope));
        System.out.println("[envelope] native fixed=" + nativeEnvelope
                + " floorOutputTokens=" + nativeFloorOutputTokens
                + " outputTokens=" + nativeBudget.maxOutputTokens()
                + " toolResultBytes=" + nativeBudget.toolResultBytes()
                + " thirtyTwoKOutputTokens=" + (17_920 - nativeEnvelope));
        assertTrue(reactFloorOutputTokens >= MINIMUM_FLOOR_OUTPUT_TOKENS,
                "The fixed JSON-ReAct envelope of " + reactEnvelope
                        + " bytes leaves only " + reactFloorOutputTokens
                        + " output tokens at the " + FLOOR_CONTEXT_TOKENS + "-token floor");
        assertTrue(nativeFloorOutputTokens >= MINIMUM_FLOOR_OUTPUT_TOKENS,
                "The fixed native-tool envelope of " + nativeEnvelope
                        + " bytes leaves only " + nativeFloorOutputTokens
                        + " output tokens at the " + FLOOR_CONTEXT_TOKENS + "-token floor");
        assertEquals(PROVIDER_MAX_OUTPUT_TOKENS, reactBudget.maxOutputTokens(),
                "The fixed JSON-ReAct envelope reduced the answer budget at the floor");
        assertEquals(PROVIDER_MAX_OUTPUT_TOKENS, nativeBudget.maxOutputTokens(),
                "The fixed native-tool envelope reduced the answer budget at the floor");
    }

    /**
     * States the measurement the floor was raised over: the real envelope on a real 32k model.
     *
     * <p>The refusal is the product decision, so the number behind it stays measured rather than
     * remembered. If a future envelope change moves it, this test says so instead of leaving the
     * declared rationale quietly stale.
     */
    @Test
    void theSameEnvelopeIsRefusedOnAThirtyTwoThousandTokenModel() {
        int reactEnvelope = reactEnvelopeBytes();
        int thirtyTwoKOutputTokens = 17_920 - reactEnvelope;
        System.out.println("[envelope] 32k react fixed=" + reactEnvelope
                + " outputTokens=" + thirtyTwoKOutputTokens
                + " cliffBytes=17919");
        assertTrue(thirtyTwoKOutputTokens > 0 && thirtyTwoKOutputTokens < 2_048,
                "A 32k model would retain " + thirtyTwoKOutputTokens
                        + " output tokens, which is no longer the starved budget the floor"
                        + " was raised over");

        AiAssistantLoopException refused = assertThrows(
                AiAssistantLoopException.class,
                () -> AiAssistantPromptBudget.from(
                        new AiProviderCapabilities(
                                AiStructuredOutputEnforcement.JSON_SCHEMA,
                                AiReasoningMode.TAGGED,
                                32_768,
                                PROVIDER_MAX_OUTPUT_TOKENS),
                        CONFIGURED_MAX_OUTPUT_TOKENS,
                        reactEnvelope));

        assertEquals(AiAssistantTerminalReasons.CONTEXT_WINDOW_TOO_SMALL,
                refused.terminalReason());
    }

    /**
     * Measures the same real envelope against a million-token window.
     *
     * <p>The envelope is a fixed cost, so widening the declared window must show up entirely as
     * variable input budget: the answer allocation is unchanged (it is still the operator ceiling)
     * while history and tool-result budgets grow by roughly the ratio of the windows. This is the
     * payoff of the catalog stated as an assertion — and it fails if a future derivation quietly
     * clamps a large window back toward the floor's allocations.
     */
    @Test
    void theSameFixedEnvelopeScalesIntoAMillionTokenWindow() {
        int reactEnvelope = reactEnvelopeBytes();
        AiAssistantPromptBudget atFloor = budget(reactEnvelope);
        AiAssistantPromptBudget atMillion = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        1_000_000,
                        128_000),
                CONFIGURED_MAX_OUTPUT_TOKENS,
                reactEnvelope);

        System.out.println("[envelope] 1M react fixed=" + reactEnvelope
                + " outputTokens=" + atMillion.maxOutputTokens()
                + " historyBytes=" + atMillion.historyBytes()
                + " toolResultBytes=" + atMillion.toolResultBytes()
                + " compactionSourceBytes=" + atMillion.compactionSourceBytes());
        assertEquals(CONFIGURED_MAX_OUTPUT_TOKENS, atMillion.maxOutputTokens());
        assertTrue(atMillion.historyBytes() > 10 * atFloor.historyBytes(),
                "a 15x wider window must fund a materially larger history budget");
        assertTrue(atMillion.toolResultBytes() > 10 * atFloor.toolResultBytes(),
                "a 15x wider window must fund a materially larger tool-result budget");
        assertTrue(atMillion.compactionSourceBytes() > 0);
        assertEquals(
                atMillion.compactionSourceBytes(),
                atMillion.historyBytes() + atMillion.attachmentContextBytes()
                        + atMillion.pageContextBytes() + atMillion.toolResultBytes());
    }

    private static AiAssistantPromptBudget budget(int fixedEnvelopeBytes) {
        return AiAssistantPromptBudget.from(
                capabilities(PROVIDER_MAX_OUTPUT_TOKENS),
                CONFIGURED_MAX_OUTPUT_TOKENS,
                fixedEnvelopeBytes);
    }

    /**
     * Returns the floor-preserving output allocation before any provider or operator ceiling clamps
     * it, by asking for more output than either ceiling would ever grant.
     */
    private static int unclampedFloorOutputTokens(int fixedEnvelopeBytes) {
        return AiAssistantPromptBudget.from(
                capabilities(FLOOR_CONTEXT_TOKENS - 1),
                FLOOR_CONTEXT_TOKENS - 1,
                fixedEnvelopeBytes).maxOutputTokens();
    }

    private static AiProviderCapabilities capabilities(int maxOutputTokens) {
        return new AiProviderCapabilities(
                AiStructuredOutputEnforcement.JSON_SCHEMA,
                AiReasoningMode.TAGGED,
                FLOOR_CONTEXT_TOKENS,
                maxOutputTokens);
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
