package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the cost of the fixed Ask Connex envelope against the declared minimum context window.
 *
 * <p>The loaded tool vocabulary is serialized into every model step: on JSON-ReAct once as the
 * prompt vocabulary inside the system instructions and once as the strict step response schema,
 * and on native function calling as the system instructions plus the tool definitions. A turn is
 * budgeted once, from the reservation ({@link AiAssistantToolCatalog#reservationToolsets()}: core
 * plus {@link #RESERVED_LOADABLE_TOOLSETS} loadable toolsets), and {@link AiAssistantPromptBudget}
 * spends that envelope directly out of the output-token allocation, whose conservative term
 * reduces to {@code contextTokens - fixedEnvelopeBytes - 14,848}. On a 32k window that is
 * {@code 17,920 - fixedEnvelopeBytes}, which
 * {@link #theSameEnvelopeIsRefusedOnAThirtyTwoThousandTokenModel} holds under half the configured
 * answer budget, so 32k stays refused rather than quietly starved.
 * The measured reservation, core and margins are printed as {@code [envelope]} lines rather than
 * restated here.
 *
 * <p>This test guards the relationship at the floor instead. At
 * {@link AiAssistantPromptBudget#ASSISTANT_MIN_CONTEXT_TOKENS} the envelope must still leave at
 * least twice the operator-configured output ceiling — {@link #MINIMUM_FLOOR_OUTPUT_TOKENS} — so
 * the answer budget is funded with room rather than exactly. That admits a fixed envelope of at
 * most {@link #FLOOR_ADMISSIBLE_ENVELOPE_BYTES} bytes on either protocol, pinned by
 * {@link #everyPinnedCeilingFitsTheShareTheFloorMarginFundsPerReservedToolset}. A new tool or a new
 * sentence of policy that eats that margin fails here, with the numbers printed, rather than
 * silently reducing what the floor was raised to protect.
 *
 * <p>Growth is budgeted in advance by literal ceilings: {@link #CORE_CEILING} for the core and
 * {@link #TOOLSET_CEILINGS} for every loadable toolset, including toolsets that are planned but
 * not yet declared. Every ceiling must fit the share of the floor margin one reserved toolset is
 * funded, computed against the core ceiling rather than today's core, so spending the core's
 * allowance cannot silently invalidate a toolset's.
 */
class AiAssistantPromptEnvelopeTest {

    private static final int FLOOR_CONTEXT_TOKENS =
            AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS;

    /**
     * The production default for the assistant's per-step output ceiling.
     *
     * <p>Read from {@link AiProperties} rather than restated, and checked against
     * {@code application.yml} by {@link #theConfiguredOutputCeilingIsTheProductionDefault}, so a
     * changed default moves the floor budget every ceiling below is certified against.
     */
    private static final int CONFIGURED_MAX_OUTPUT_TOKENS =
            new AiProperties().getAssistantMaxOutputTokens();
    private static final int PROVIDER_MAX_OUTPUT_TOKENS = 8_192;

    /**
     * The floor-preserving output allocation the fixed envelope must leave at the declared floor.
     *
     * <p>Twice {@link #CONFIGURED_MAX_OUTPUT_TOKENS}: the floor exists so the envelope is absorbed
     * without competing with the answer, so it may consume at most half of what the window grants
     * beyond the configured ceiling.
     */
    private static final int MINIMUM_FLOOR_OUTPUT_TOKENS = 2 * CONFIGURED_MAX_OUTPUT_TOKENS;

    /**
     * The largest fixed envelope that still leaves {@link #MINIMUM_FLOOR_OUTPUT_TOKENS} at the
     * floor, on either protocol, as {@link AiAssistantPromptBudget#from} derives it today.
     *
     * <p>Pinned so that a change to the budget's repair or variable-input reserves, to the floor,
     * or to the configured output default fails loudly instead of silently re-certifying every
     * ceiling below against a different margin.
     */
    private static final int FLOOR_ADMISSIBLE_ENVELOPE_BYTES = 17_920;

    /** Loadable toolsets the reservation carries, and therefore the ceiling's divisor. */
    private static final int RESERVED_LOADABLE_TOOLSETS =
            AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN
                    + AiAssistantToolCatalog.RESERVATION_HEADROOM_TOOLSETS;

    /**
     * The most the core envelope may ever cost, in serialized bytes per protocol.
     *
     * <p>Each literal is the core as measured when it was pinned (issue #1817) plus an itemized
     * allowance for everything the action surface adds to the core, JSON-ReAct / native:
     * <ul>
     *   <li>Declaring {@link #PLANNED_TOOLSET_KEYS}, 410 / 350 (403 / 346 itemized below). Every loadable
     *       toolset costs the core whether or not it is loaded: a directory line
     *       ({@code "\n" + key + " - " + summary + " - available"}, JSON-escaped) on both
     *       protocols, and a value in the closed {@code find_tools} enum, which JSON-ReAct
     *       serializes twice ({@code "|" + key} in the prompt vocabulary and {@code ,"key"} in the
     *       step schema) and native once ({@code ,"key"} in the tool parameters). Per toolset,
     *       directory + enum:
     *       {@code write_followup} 80 + 32 / 80 + 17, {@code write_fields} 69 + 28 / 69 + 15,
     *       {@code write_create} 66 + 28 / 66 + 15, {@code write_workspace} 66 + 34 / 66 + 18.</li>
     *   <li>The task-handle rule, 300 on either protocol. On native it can live in the
     *       {@code list_tasks} description. JSON-ReAct never serializes tool descriptions (the
     *       prompt vocabulary carries only name, tier and arguments, and the step schema no
     *       description), so on that protocol the rule costs nothing through the description and
     *       reaches the model only as a system-prompt sentence, which this allowance funds.</li>
     * </ul>
     * The planned rewording of the {@code write_content} and {@code write_pipeline} summaries saves
     * 4 and 6 bytes on both protocols; that saving is left unclaimed. Every toolset ceiling is
     * checked against this value rather than today's core, so spending the allowance cannot
     * invalidate a toolset's.
     */
    private static final EnvelopeCeiling CORE_CEILING =
            new EnvelopeCeiling("core", 9_912 + 410 + 300, 8_711 + 350 + 300);

    /**
     * Wire keys of toolsets the action surface plans but has not yet declared.
     *
     * <p>Their ceilings in {@link #TOOLSET_CEILINGS} are committed before their first tool exists.
     * Declaring one must move its key out of this set, and dropping or renaming one must delete or
     * rename its ceiling, so the ledger never budgets a toolset that is neither declared nor
     * planned.
     */
    private static final Set<String> PLANNED_TOOLSET_KEYS =
            Set.of("write_followup", "write_fields", "write_create", "write_workspace");

    /**
     * The most each loadable toolset may add over the core envelope, per protocol.
     *
     * <p>Declared toolsets are pinned at their bytes as measured for issue #1817, plus the
     * allocation the action surface commits to the tools it adds to them: 900 and 700 bytes for
     * {@code log_activities}, 450 and 350 for {@code remove_tag} and for {@code draft_document}.
     * The last four entries are {@link #PLANNED_TOOLSET_KEYS}; their allocations are committed here
     * so each is proven to fit the floor before its first tool exists. A toolset's cost to the core
     * (its directory line and {@code find_tools} value) is not part of its entry here; it is
     * funded by {@link #CORE_CEILING}. Raising any entry is a budget decision, not a
     * re-measurement.
     */
    private static final List<EnvelopeCeiling> TOOLSET_CEILINGS = List.of(
            new EnvelopeCeiling("analytics", 1_316, 669),
            new EnvelopeCeiling("schedule", 506, 388),
            new EnvelopeCeiling("write_activity", 1_351 + 900, 1_085 + 700),
            new EnvelopeCeiling("write_content", 1_097 + 450, 828 + 350),
            new EnvelopeCeiling("write_pipeline", 859 + 450, 640 + 350),
            new EnvelopeCeiling("write_followup", 950, 750),
            new EnvelopeCeiling("write_fields", 1_000, 800),
            new EnvelopeCeiling("write_create", 1_800, 1_400),
            new EnvelopeCeiling("write_workspace", 1_700, 1_350));

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
        int reactFloorOutputTokens =
                unclampedFloorOutputTokens(reactEnvelope, AiToolCallingMode.NONE);
        int nativeFloorOutputTokens =
                unclampedFloorOutputTokens(nativeEnvelope, AiToolCallingMode.NATIVE_FUNCTIONS);
        AiAssistantPromptBudget reactBudget = budget(reactEnvelope, AiToolCallingMode.NONE);
        AiAssistantPromptBudget nativeBudget =
                budget(nativeEnvelope, AiToolCallingMode.NATIVE_FUNCTIONS);
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
        AiAssistantPromptBudget atFloor = budget(reactEnvelope, AiToolCallingMode.NONE);
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
        assertTrue(unclampedFloorOutputTokens(reservationReact, AiToolCallingMode.NONE)
                        >= MINIMUM_FLOOR_OUTPUT_TOKENS,
                "the reserved JSON-ReAct envelope of " + reservationReact
                        + " bytes starves the answer budget at the "
                        + FLOOR_CONTEXT_TOKENS + "-token floor");
        assertTrue(unclampedFloorOutputTokens(
                        reservationNative, AiToolCallingMode.NATIVE_FUNCTIONS)
                        >= MINIMUM_FLOOR_OUTPUT_TOKENS,
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
        int reactAdmissible = floorAdmissibleEnvelopeBytes(AiToolCallingMode.NONE);
        int nativeAdmissible = floorAdmissibleEnvelopeBytes(AiToolCallingMode.NATIVE_FUNCTIONS);
        int reactCeiling = (reactAdmissible - coreReact) / RESERVED_LOADABLE_TOOLSETS;
        int nativeCeiling = (nativeAdmissible - coreNative) / RESERVED_LOADABLE_TOOLSETS;
        System.out.println("[envelope] core react=" + coreReact + " native=" + coreNative
                + " reactFloorAdmissible=" + reactAdmissible
                + " nativeFloorAdmissible=" + nativeAdmissible
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
     * Holds the core and every declared toolset within the literal ceilings pinned above.
     *
     * <p>The ceilings are literal so that growth is a reviewed budget change: a new tool or a longer
     * description that pushes a toolset past its allocation fails here with the measured bytes
     * printed. Every declared loadable toolset must have a ceiling, so a new toolset cannot ship
     * without one.
     */
    @Test
    void theCoreAndEveryDeclaredToolsetStayWithinTheirPinnedCeilings() {
        int coreReact = reactEnvelopeBytes(AiAssistantToolCatalog.CORE);
        int coreNative = nativeEnvelopeBytes(AiAssistantToolCatalog.CORE);
        System.out.println("[envelope] core react=" + coreReact + "/" + CORE_CEILING.reactBytes()
                + " native=" + coreNative + "/" + CORE_CEILING.nativeBytes());
        assertTrue(coreReact <= CORE_CEILING.reactBytes(),
                "the core costs " + coreReact + " JSON-ReAct bytes, past its "
                        + CORE_CEILING.reactBytes() + "-byte ceiling");
        assertTrue(coreNative <= CORE_CEILING.nativeBytes(),
                "the core costs " + coreNative + " native bytes, past its "
                        + CORE_CEILING.nativeBytes() + "-byte ceiling");

        assertEquals(TOOLSET_CEILINGS.size(),
                TOOLSET_CEILINGS.stream().map(EnvelopeCeiling::key).distinct().count(),
                "a toolset key is pinned twice");
        for (Toolset toolset : AiAssistantToolCatalog.LOADABLE) {
            EnvelopeCeiling ceiling = TOOLSET_CEILINGS.stream()
                    .filter(candidate -> candidate.key().equals(toolset.key()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            toolset.key() + " is declared without a pinned envelope ceiling"));
            int reactDelta = reactEnvelopeBytes(loaded(toolset)) - coreReact;
            int nativeDelta = nativeEnvelopeBytes(loaded(toolset)) - coreNative;
            System.out.println("[envelope] " + toolset.key()
                    + " reactDelta=" + reactDelta + "/" + ceiling.reactBytes()
                    + " nativeDelta=" + nativeDelta + "/" + ceiling.nativeBytes());
            assertTrue(reactDelta <= ceiling.reactBytes(),
                    () -> toolset.key() + " costs " + reactDelta
                            + " JSON-ReAct bytes over the core, past its "
                            + ceiling.reactBytes() + "-byte ceiling");
            assertTrue(nativeDelta <= ceiling.nativeBytes(),
                    () -> toolset.key() + " costs " + nativeDelta
                            + " native bytes over the core, past its "
                            + ceiling.nativeBytes() + "-byte ceiling");
        }
    }

    /**
     * Holds the ceiling ledger to the toolsets that exist or are planned.
     *
     * <p>Every entry must name a declared loadable toolset or a {@link #PLANNED_TOOLSET_KEYS} key,
     * every planned key must have an entry, and no planned key may already be declared. Renaming,
     * folding or deferring a planned toolset therefore fails here until its ceiling is renamed,
     * moved or deleted, instead of leaving an orphan that budgets a toolset nobody will ship.
     */
    @Test
    void everyPinnedCeilingNamesADeclaredOrPlannedToolset() {
        Set<String> declared = new LinkedHashSet<>();
        for (Toolset toolset : AiAssistantToolCatalog.LOADABLE) {
            declared.add(toolset.key());
        }
        Set<String> pinned = new LinkedHashSet<>();
        for (EnvelopeCeiling ceiling : TOOLSET_CEILINGS) {
            pinned.add(ceiling.key());
            assertTrue(declared.contains(ceiling.key())
                            || PLANNED_TOOLSET_KEYS.contains(ceiling.key()),
                    () -> ceiling.key() + " is pinned but neither declared nor planned; delete or"
                            + " rename its ceiling");
        }
        for (String planned : PLANNED_TOOLSET_KEYS) {
            assertFalse(declared.contains(planned),
                    () -> planned + " is declared now; move it out of PLANNED_TOOLSET_KEYS");
            assertTrue(pinned.contains(planned),
                    () -> planned + " is planned without a pinned envelope ceiling");
        }
    }

    /**
     * Ties the output ceiling every floor figure here derives from to the one production ships.
     *
     * <p>{@link #CONFIGURED_MAX_OUTPUT_TOKENS} is read from the {@link AiProperties} field default;
     * this proves {@code application.yml} binds the same default, so raising it in either place
     * moves the floor budget and fails the pinned admissible envelope rather than leaving every
     * ceiling certified against a stale margin.
     */
    @Test
    void theConfiguredOutputCeilingIsTheProductionDefault() throws IOException {
        String yaml = new String(
                new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertTrue(yaml.contains("assistant-max-output-tokens: "
                        + "${CONNEX_AI_ASSISTANT_MAX_OUTPUT_TOKENS:"
                        + CONFIGURED_MAX_OUTPUT_TOKENS + "}"),
                "application.yml binds a different assistant output default than AiProperties; the"
                        + " floor budget must be re-derived against the default production ships");
    }

    /**
     * Proves every pinned ceiling, declared or planned, fits the floor before any tool spends it.
     *
     * <p>The cap is {@code (floor-admissible envelope - CORE_CEILING) / RESERVED_LOADABLE_TOOLSETS}
     * on each protocol, with the admissible envelope found through
     * {@link AiAssistantPromptBudget#from} exactly as a turn derives its budget. If every toolset
     * stays within its ceiling and every ceiling within the cap, then the core plus any
     * {@link #RESERVED_LOADABLE_TOOLSETS} toolsets fits the floor, whichever toolsets the weight
     * proxy reserves. Raising a ceiling past the cap therefore fails here, before the reservation
     * check could.
     */
    @Test
    void everyPinnedCeilingFitsTheShareTheFloorMarginFundsPerReservedToolset() {
        int reactAdmissible = floorAdmissibleEnvelopeBytes(AiToolCallingMode.NONE);
        int nativeAdmissible = floorAdmissibleEnvelopeBytes(AiToolCallingMode.NATIVE_FUNCTIONS);
        int reactCap = (reactAdmissible - CORE_CEILING.reactBytes()) / RESERVED_LOADABLE_TOOLSETS;
        int nativeCap =
                (nativeAdmissible - CORE_CEILING.nativeBytes()) / RESERVED_LOADABLE_TOOLSETS;
        System.out.println("[envelope] ceilings reactFloorAdmissible=" + reactAdmissible
                + " nativeFloorAdmissible=" + nativeAdmissible
                + " coreCeilingReact=" + CORE_CEILING.reactBytes()
                + " coreCeilingNative=" + CORE_CEILING.nativeBytes()
                + " reservedLoadableToolsets=" + RESERVED_LOADABLE_TOOLSETS
                + " reactCap=" + reactCap + " nativeCap=" + nativeCap);
        assertEquals(FLOOR_ADMISSIBLE_ENVELOPE_BYTES, reactAdmissible,
                "the JSON-ReAct floor-admissible envelope moved; AiAssistantPromptBudget, the floor"
                        + " or the configured output default changed, so re-derive the floor budget"
                        + " and every ceiling certified against it before re-pinning");
        assertEquals(FLOOR_ADMISSIBLE_ENVELOPE_BYTES, nativeAdmissible,
                "the native floor-admissible envelope moved; AiAssistantPromptBudget, the floor"
                        + " or the configured output default changed, so re-derive the floor budget"
                        + " and every ceiling certified against it before re-pinning");
        assertTrue(reactCap > 0 && nativeCap > 0,
                "the core ceiling leaves the floor margin nothing to fund toolsets with");

        for (EnvelopeCeiling ceiling : TOOLSET_CEILINGS) {
            System.out.println("[envelope] ceiling " + ceiling.key()
                    + " react=" + ceiling.reactBytes() + "/" + reactCap
                    + " native=" + ceiling.nativeBytes() + "/" + nativeCap);
            assertTrue(ceiling.reactBytes() > 0 && ceiling.nativeBytes() > 0,
                    () -> ceiling.key() + " is pinned at no cost at all");
            assertTrue(ceiling.reactBytes() <= reactCap,
                    () -> ceiling.key() + " is allotted " + ceiling.reactBytes()
                            + " JSON-ReAct bytes, past the " + reactCap
                            + "-byte share the floor margin funds per reserved toolset");
            assertTrue(ceiling.nativeBytes() <= nativeCap,
                    () -> ceiling.key() + " is allotted " + ceiling.nativeBytes()
                            + " native bytes, past the " + nativeCap
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
     * declared floor for a provider speaking the given tool protocol, found by bisection because
     * the budget derivation is not invertible.
     */
    private static int floorAdmissibleEnvelopeBytes(AiToolCallingMode toolCalling) {
        int admissible = 0;
        int refused = 1 << 20;
        while (refused - admissible > 1) {
            int candidate = admissible + (refused - admissible) / 2;
            if (clearsFloor(candidate, toolCalling)) {
                admissible = candidate;
            } else {
                refused = candidate;
            }
        }
        return admissible;
    }

    private static boolean clearsFloor(int fixedEnvelopeBytes, AiToolCallingMode toolCalling) {
        try {
            return unclampedFloorOutputTokens(fixedEnvelopeBytes, toolCalling)
                    >= MINIMUM_FLOOR_OUTPUT_TOKENS;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    private static AiAssistantPromptBudget budget(
            int fixedEnvelopeBytes, AiToolCallingMode toolCalling) {
        return AiAssistantPromptBudget.from(
                capabilities(PROVIDER_MAX_OUTPUT_TOKENS, toolCalling),
                CONFIGURED_MAX_OUTPUT_TOKENS,
                fixedEnvelopeBytes);
    }

    /**
     * Returns the floor-preserving output allocation before any provider or operator ceiling clamps
     * it, by asking for more output than either ceiling would ever grant, for a provider speaking
     * the given tool protocol exactly as a turn's capabilities would declare it.
     */
    private static int unclampedFloorOutputTokens(
            int fixedEnvelopeBytes, AiToolCallingMode toolCalling) {
        return AiAssistantPromptBudget.from(
                capabilities(FLOOR_CONTEXT_TOKENS - 1, toolCalling),
                FLOOR_CONTEXT_TOKENS - 1,
                fixedEnvelopeBytes).maxOutputTokens();
    }

    private static AiProviderCapabilities capabilities(
            int maxOutputTokens, AiToolCallingMode toolCalling) {
        return new AiProviderCapabilities(
                AiStructuredOutputEnforcement.JSON_SCHEMA,
                AiReasoningMode.TAGGED,
                FLOOR_CONTEXT_TOKENS,
                maxOutputTokens,
                toolCalling);
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

    /**
     * The most one envelope component may cost, in serialized UTF-8 bytes per protocol.
     *
     * @param key the toolset wire key, or {@code core}
     * @param reactBytes the JSON-ReAct ceiling
     * @param nativeBytes the native function-calling ceiling
     */
    private record EnvelopeCeiling(String key, int reactBytes, int nativeBytes) {
    }
}
