package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
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
 * quietly starved: today's JSON-ReAct envelope would leave it 350 output tokens.
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
     * margin of 350 — the same figure that was a 32k model's entire answer budget.
     */
    private static final int MINIMUM_FLOOR_OUTPUT_TOKENS = 2 * CONFIGURED_MAX_OUTPUT_TOKENS;

    /** Loadable toolsets the reservation carries, and therefore the ceiling's divisor. */
    private static final int RESERVED_LOADABLE_TOOLSETS =
            AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN
                    + AiAssistantToolCatalog.RESERVATION_HEADROOM_TOOLSETS;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiAssistantToolCatalog toolCatalog = new AiAssistantToolCatalog();
    private final AiAssistantPromptAssembler promptAssembler =
            new AiAssistantPromptAssembler(objectMapper, toolCatalog);
    private final AiAssistantStepSchema stepSchema =
            new AiAssistantStepSchema(objectMapper, toolCatalog);

    @Test
    void theFixedEnvelopeLeavesRoomToSpareAtTheDeclaredMinimumContextWindow() {
        int reactEnvelope = reactEnvelopeBytes(toolCatalog.reservationToolsets());
        int nativeEnvelope = nativeEnvelopeBytes(toolCatalog.reservationToolsets());
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
        int reactEnvelope = reactEnvelopeBytes(toolCatalog.reservationToolsets());
        int thirtyTwoKOutputTokens = 17_920 - reactEnvelope;
        System.out.println("[envelope] 32k react fixed=" + reactEnvelope
                + " outputTokens=" + thirtyTwoKOutputTokens
                + " cliffBytes=17919");
        assertTrue(thirtyTwoKOutputTokens < CONFIGURED_MAX_OUTPUT_TOKENS / 2,
                "A 32k model would retain " + thirtyTwoKOutputTokens
                        + " output tokens, which can fund at least half the configured answer"
                        + " budget; revisit whether the 64k floor is still the right refusal");

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
        int reactEnvelope = reactEnvelopeBytes(toolCatalog.reservationToolsets());
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

    /**
     * Pins the two ends of the loaded-set range by the names they actually carry.
     *
     * <p>A byte threshold alone would not notice a wiring that quietly reverted to the whole
     * catalog while still fitting the floor. Asserting the definition names, and that a core-only
     * ReAct prompt never mentions a non-core tool, fails on the defect rather than on its size.
     *
     * <p>Both expectations are literal for the same reason: deriving either side from the catalog
     * call under test would make the assertion move with the defect instead of catching it.
     */
    @Test
    void theCoreAndReservationEnvelopesCarryExactlyTheToolsTheyDeclare() {
        assertEquals(
                List.of("search_records", "get_record", "get_records", "set_todos",
                        "list_activities", "list_tasks", "list_scope_activities", "find_tools"),
                promptAssembler.nativeToolDefinitions(AiAssistantToolCatalog.CORE).stream()
                        .map(definition -> definition.name())
                        .toList());
        assertEquals(
                List.of("search_records", "get_record", "get_records", "set_todos",
                        "list_activities", "list_tasks", "list_scope_activities", "find_tools",
                        "aggregate_metric", "create_activity", "create_task",
                        "create_note", "add_tag"),
                promptAssembler.nativeToolDefinitions(toolCatalog.reservationToolsets()).stream()
                        .map(definition -> definition.name())
                        .toList(),
                "the reservation is core plus the "
                        + RESERVED_LOADABLE_TOOLSETS + " weightiest loadable toolsets; if the"
                        + " weight ranking legitimately moved, re-measure and re-pin this list"
                        + " rather than deriving it from the call under test");
        assertEquals(
                Set.of(Toolset.CORE, Toolset.ANALYTICS,
                        Toolset.WRITE_ACTIVITY, Toolset.WRITE_CONTENT),
                Set.copyOf(toolCatalog.reservationToolsets()));

        String coreReactPrompt =
                promptAssembler.fixedPrompt(AiAssistantToolCatalog.CORE).getSystemPrompt();
        for (var spec : toolCatalog.tools(AiAssistantToolCatalog.ALL)) {
            if (AiAssistantToolCatalog.CORE.contains(spec.toolset())) {
                assertTrue(coreReactPrompt.contains(spec.name()),
                        spec.name() + " is core and must stay in the core vocabulary");
                continue;
            }
            assertFalse(coreReactPrompt.contains(spec.name()),
                    spec.name() + " leaked into the core-only prompt vocabulary");
        }
    }

    /**
     * The turn gets one budget, measured once from {@code reservationToolsets()}, so that
     * measurement has to dominate every loaded set a turn can actually reach.
     *
     * <p>{@code reservationToolsets()} picks its toolsets by a pure-catalog weight proxy, because
     * the catalog cannot serialize a prompt. This measures the real envelope for every reachable
     * loaded set on both protocols instead of trusting the proxy: if a future toolset makes the
     * proxy pick the wrong combination, the budget would silently under-reserve, and this fails
     * with the numbers printed rather than starving a turn.
     *
     * <p>The enumeration is derived from {@code MAX_ACTIVE_TOOLSETS_PER_TURN} rather than written
     * out, so raising the cap widens what is measured instead of quietly narrowing the guarantee
     * to the combination sizes someone happened to type. The core-only set is enumerated too,
     * because every turn starts there and the toolset directory marks an unloaded set with a
     * longer word than a loaded one.
     */
    @Test
    void theReservationEnvelopeDominatesEveryReachableLoadedSet() {
        int reservationReact = reactEnvelopeBytes(toolCatalog.reservationToolsets());
        int reservationNative = nativeEnvelopeBytes(toolCatalog.reservationToolsets());

        List<Set<Toolset>> reachable = reachableLoadedSets();
        assertFalse(reachable.isEmpty(), "no reachable loaded set was enumerated");
        for (Set<Toolset> candidate : reachable) {
            int react = reactEnvelopeBytes(candidate);
            int nativeBytes = nativeEnvelopeBytes(candidate);
            System.out.println("[envelope] loaded=" + candidate
                    + " react=" + react + " native=" + nativeBytes);
            assertTrue(react <= reservationReact,
                    () -> "loaded set " + candidate + " serializes " + react
                            + " JSON-ReAct bytes, more than the reserved "
                            + reservationReact);
            assertTrue(nativeBytes <= reservationNative,
                    () -> "loaded set " + candidate + " serializes " + nativeBytes
                            + " native bytes, more than the reserved " + reservationNative);
        }

        System.out.println("[envelope] reservation=" + toolCatalog.reservationToolsets()
                + " react=" + reservationReact + " native=" + reservationNative
                + " fullCatalogReact=" + reactEnvelopeBytes()
                + " fullCatalogNative=" + nativeEnvelopeBytes());
        assertTrue(reservationReact < reactEnvelopeBytes(),
                "the reservation must cost less than today's whole-catalog envelope");
        assertTrue(reservationNative < nativeEnvelopeBytes(),
                "the reservation must cost less than today's whole-catalog envelope");
        assertTrue(unclampedFloorOutputTokens(reservationReact) >= MINIMUM_FLOOR_OUTPUT_TOKENS,
                "the reserved JSON-ReAct envelope of " + reservationReact
                        + " bytes starves the answer budget at the "
                        + FLOOR_CONTEXT_TOKENS + "-token floor");
        assertTrue(unclampedFloorOutputTokens(reservationNative) >= MINIMUM_FLOOR_OUTPUT_TOKENS,
                "the reserved native envelope of " + reservationNative
                        + " bytes starves the answer budget at the "
                        + FLOOR_CONTEXT_TOKENS + "-token floor");
    }

    /**
     * Bounds what one toolset may cost the envelope, at the value that actually keeps the floor.
     *
     * <p>The ceiling is not a chosen number. The reservation is core plus
     * {@link #RESERVED_LOADABLE_TOOLSETS} loadable toolsets, so if every one of them stayed within
     * {@code (largest floor-admissible envelope - core envelope) / RESERVED_LOADABLE_TOOLSETS} the
     * reservation cannot breach the floor assertion in
     * {@link #theReservationEnvelopeDominatesEveryReachableLoadedSet}. That makes this an advance
     * warning for the assertion it protects rather than a second, unrelated threshold: a family
     * that outgrows it must be split before the floor check is the thing that fails.
     */
    @Test
    void noSingleToolsetCostsMoreThanTheFloorMarginFundsPerToolset() {
        int coreReact = reactEnvelopeBytes(AiAssistantToolCatalog.CORE);
        int coreNative = nativeEnvelopeBytes(AiAssistantToolCatalog.CORE);
        int admissible = floorAdmissibleEnvelopeBytes();
        int reactCeiling = (admissible - coreReact) / RESERVED_LOADABLE_TOOLSETS;
        int nativeCeiling = (admissible - coreNative) / RESERVED_LOADABLE_TOOLSETS;
        System.out.println("[envelope] core react=" + coreReact + " native=" + coreNative
                + " floorAdmissible=" + admissible
                + " reservedLoadableToolsets=" + RESERVED_LOADABLE_TOOLSETS
                + " reactCeiling=" + reactCeiling + " nativeCeiling=" + nativeCeiling);
        assertTrue(reactCeiling > 0 && nativeCeiling > 0,
                "the floor margin funds no per-toolset growth at all; the core envelope itself is"
                        + " already at the floor");

        for (Toolset toolset : AiAssistantToolCatalog.LOADABLE) {
            int reactDelta = reactEnvelopeBytes(loaded(toolset)) - coreReact;
            int nativeDelta = nativeEnvelopeBytes(loaded(toolset)) - coreNative;
            System.out.println("[envelope] " + toolset.key()
                    + " reactDelta=" + reactDelta + " nativeDelta=" + nativeDelta);
            assertTrue(reactDelta > 0,
                    () -> toolset.key() + " adds no tools to the JSON-ReAct vocabulary");
            assertTrue(reactDelta <= reactCeiling,
                    () -> toolset.key() + " costs " + reactDelta
                            + " JSON-ReAct bytes, past the " + reactCeiling
                            + "-byte share the floor margin funds per reserved toolset");
            assertTrue(nativeDelta <= nativeCeiling,
                    () -> toolset.key() + " costs " + nativeDelta
                            + " native bytes, past the " + nativeCeiling
                            + "-byte share the floor margin funds per reserved toolset");
        }
    }

    /**
     * Enumerates every loaded set a turn can reach: core plus any combination of up to
     * {@code MAX_ACTIVE_TOOLSETS_PER_TURN} loadable toolsets.
     */
    private static List<Set<Toolset>> reachableLoadedSets() {
        List<Toolset> loadable = AiAssistantToolCatalog.LOADABLE;
        List<Set<Toolset>> reachable = new ArrayList<>();
        for (int mask = 0; mask < (1 << loadable.size()); mask++) {
            if (Integer.bitCount(mask) > AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN) {
                continue;
            }
            Set<Toolset> candidate = new LinkedHashSet<>(AiAssistantToolCatalog.CORE);
            for (int index = 0; index < loadable.size(); index++) {
                if ((mask & (1 << index)) != 0) {
                    candidate.add(loadable.get(index));
                }
            }
            reachable.add(candidate);
        }
        return reachable;
    }

    /**
     * The largest fixed envelope that still leaves {@link #MINIMUM_FLOOR_OUTPUT_TOKENS} at the
     * declared floor, found by bisection because the budget derivation is not invertible.
     */
    private static int floorAdmissibleEnvelopeBytes() {
        int admissible = 0;
        int refused = 1 << 20;
        while (refused - admissible > 1) {
            int candidate = admissible + (refused - admissible) / 2;
            if (clearsFloor(candidate)) {
                admissible = candidate;
            } else {
                refused = candidate;
            }
        }
        return admissible;
    }

    private static boolean clearsFloor(int fixedEnvelopeBytes) {
        try {
            return unclampedFloorOutputTokens(fixedEnvelopeBytes) >= MINIMUM_FLOOR_OUTPUT_TOKENS;
        } catch (RuntimeException refused) {
            return false;
        }
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
        return reactEnvelopeBytes(AiAssistantToolCatalog.ALL);
    }

    private int nativeEnvelopeBytes() {
        return nativeEnvelopeBytes(AiAssistantToolCatalog.ALL);
    }

    private int reactEnvelopeBytes(Set<Toolset> loadedToolsets) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", taggedSystemPrompt(
                promptAssembler.fixedPrompt(loadedToolsets).getSystemPrompt()));
        payload.put("messages", List.of());
        payload.put("responseSchema", stepSchema.responseSchema(loadedToolsets).schema());
        return serializedBytes(payload);
    }

    private int nativeEnvelopeBytes(Set<Toolset> loadedToolsets) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", taggedSystemPrompt(
                promptAssembler.fixedNativePrompt(loadedToolsets).getSystemPrompt()));
        payload.put("messages", List.of());
        payload.put("responseSchema", stepSchema.finalResponseSchema().schema());
        payload.put("tools", promptAssembler.nativeToolDefinitions(loadedToolsets).stream()
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

    private static Set<Toolset> loaded(Toolset... loadable) {
        Set<Toolset> toolsets = new LinkedHashSet<>(AiAssistantToolCatalog.CORE);
        toolsets.addAll(List.of(loadable));
        return toolsets;
    }

    private static String taggedSystemPrompt(String systemPrompt) {
        return systemPrompt + "\n\n" + AiInvocationService.TAGGED_REASONING_INSTRUCTION;
    }

    private int serializedBytes(Map<String, Object> payload) {
        return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length;
    }
}
