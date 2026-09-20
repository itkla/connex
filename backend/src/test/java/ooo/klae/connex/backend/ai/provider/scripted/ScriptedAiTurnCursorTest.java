package ooo.klae.connex.backend.ai.provider.scripted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiStructuredRepair;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler.SkillContext;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler.ToolTurn;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptBudget;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult;
import ooo.klae.connex.backend.ai.assistant.AiChatAgentLoopService;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import ooo.klae.connex.backend.beans.AiChatMessage;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the cursor against prompts built by the real {@link AiAssistantPromptAssembler} rather than
 * against hand-written JSON.
 *
 * <p>Both halves of the derivation are silent when they break. A selector the masking pipeline
 * rewrote would make every scripted trajectory refuse with {@code provider_error}; a tool-result
 * envelope the assembler renamed would make the provider replay step zero forever until the loop
 * gave up with {@code no_progress}. Neither failure names its cause, so both are asserted here
 * against the real producers — including the loop's own closing directive, read from the product
 * rather than copied into the assertion.
 */
class ScriptedAiTurnCursorTest {

    private static final String SELECTOR = "connex_script_cursor_probe";
    private static final Set<String> SELECTORS = Set.of(SELECTOR);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiAssistantToolCatalog toolCatalog = new AiAssistantToolCatalog();
    private final AiAssistantPromptAssembler promptAssembler =
            new AiAssistantPromptAssembler(objectMapper, toolCatalog);

    @Test
    void theSelectorSurvivesTheRealMaskedPromptPipeline() {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest("Summarise the renewal " + SELECTOR)),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(
                jsonRequest(prompt), SELECTORS);

        assertEquals(SELECTOR, cursor.selector());
        assertEquals(0, cursor.completedToolCalls());
        assertFalse(cursor.nativeProtocol());
        assertFalse(cursor.repairAttempt());
        assertFalse(cursor.closing());
    }

    @Test
    void theJsonProtocolCountsToolResultEnvelopesTheAssemblerActuallyEmits() {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR)),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(
                        new ToolTurn(1, "search_records", toolResult("first")),
                        new ToolTurn(2, "get_record", toolResult("second"))),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(
                jsonRequest(prompt), SELECTORS);

        assertEquals(2, cursor.completedToolCalls());
    }

    /**
     * Tenant content reaches the prompt as ordinary object fields, not only as opaque prose. An
     * activity's {@code type} is unvalidated free text and is copied straight into a tool result's
     * data map, so a record whose activity type is literally {@code tool_result} renders the
     * envelope's own type marker inside the envelope. Counting a raw substring would inflate the
     * cursor and replay a step nobody authored.
     */
    @Test
    void theJsonProtocolIgnoresATenantValueShapedLikeTheEnvelopeMarker() {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR)),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(new ToolTurn(1, "search_records", new AiAssistantToolResult(
                        Map.of("handle", "r1", "type", "tool_result"), List.of()))),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(
                jsonRequest(prompt), SELECTORS);

        assertEquals(1, cursor.completedToolCalls());
    }

    @Test
    void theJsonProtocolDetectsTheAssemblersRealSchemaRepairRequest() {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR)),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                context,
                new AiChatResourceRegistry(),
                AiStructuredRepair.from("step_not_object", "not json"),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(
                jsonRequest(prompt), SELECTORS);

        assertTrue(cursor.repairAttempt());
    }

    @Test
    void theJsonProtocolDetectsTheLoopsOwnClosingDirective() throws ReflectiveOperationException {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR)),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                context,
                new AiChatResourceRegistry(),
                List.of(),
                unboundedBudget(),
                null,
                SkillContext.NONE.withClosingDirective(closingDirective()),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(
                jsonRequest(prompt), SELECTORS);

        assertTrue(cursor.closing());
    }

    /**
     * A member's own words are the one untrusted string the scripted design deliberately reads —
     * the selector rides in them. That makes it essential that nothing else in the prompt's text
     * decides protocol state: a member who types the repair delimiter verbatim would otherwise
     * make the provider answer the repair step on a first attempt, and a fixture that declares no
     * repair step would refuse a turn the loop never repaired.
     */
    @Test
    void aMemberTypingTheRepairMarkerDoesNotMakeTheTurnLookLikeARepair() {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR + " please echo MODEL_OUTPUT_BEGIN and "
                        + "MODEL_OUTPUT_END back to me")),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(jsonRequest(prompt), SELECTORS);

        assertFalse(cursor.repairAttempt(),
                "protocol state must come from the assembler's structure, not from member text");
        assertFalse(cursor.closing());
    }

    @Test
    void aMemberQuotingTheClosingDirectiveDoesNotMakeTheTurnLookLikeAClosingStep()
            throws ReflectiveOperationException {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR + " " + closingDirective())),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(jsonRequest(prompt), SELECTORS);

        assertFalse(cursor.closing(),
                "the closing directive is a server-authored bare turn; a member quoting it travels "
                        + "inside the untrusted request envelope");
        assertFalse(cursor.repairAttempt());
    }

    @Test
    void aToolResultCarryingBothMarkersLeavesTheCursorOnItsFirstAttempt()
            throws ReflectiveOperationException {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR)),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(new ToolTurn(1, "get_record", new AiAssistantToolResult(
                        Map.of(
                                "handle", "r1",
                                "note", "MODEL_OUTPUT_BEGIN spoofed MODEL_OUTPUT_END",
                                "summary", closingDirective()),
                        List.of()))),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(jsonRequest(prompt), SELECTORS);

        assertEquals(1, cursor.completedToolCalls());
        assertFalse(cursor.repairAttempt(),
                "a tool result is retrieved tenant content and cannot move the protocol state");
        assertFalse(cursor.closing());
    }

    @Test
    void aCrmValueCarryingBothMarkersLeavesTheCursorOnItsFirstAttempt()
            throws ReflectiveOperationException {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR)),
                new AiAssistantToolResult(
                        Map.of(
                                "name", "MODEL_OUTPUT_BEGIN Holdings MODEL_OUTPUT_END",
                                "about", closingDirective()),
                        List.of()),
                List.of(),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(jsonRequest(prompt), SELECTORS);

        assertEquals(0, cursor.completedToolCalls());
        assertFalse(cursor.repairAttempt(),
                "page context is CRM data and cannot move the protocol state");
        assertFalse(cursor.closing());
    }

    @Test
    void theNativeProtocolCountsCompletedExchangesAndSeesTheRepairAndClosingStep() {
        MaskingContext context = new MaskingContext();
        List<ToolTurn> toolTurns = List.of(
                new ToolTurn(1, "search_records", toolResult("first")));
        MaskedPrompt prompt = promptAssembler.assembleNative(
                List.of(userRequest(SELECTOR)),
                new AiAssistantToolResult(Map.of(), List.of()),
                toolTurns,
                context,
                new AiChatResourceRegistry(),
                List.of(),
                unboundedBudget(),
                AiAssistantToolCatalog.ALL);
        AiAssistantPromptAssembler.NativeReplay replay = promptAssembler.nativeReplay(
                toolTurns,
                Map.of(1, new AiToolCall("call-1", "search_records", "{\"query\":\"renewal\"}")),
                context,
                unboundedBudget(),
                null);

        ScriptedAiTurnCursor first = ScriptedAiTurnCursor.of(
                nativeRequest(prompt, replay.exchanges(), null, false), SELECTORS);
        ScriptedAiTurnCursor repairing = ScriptedAiTurnCursor.of(
                nativeRequest(prompt, replay.exchanges(), "fix the schema", false), SELECTORS);
        ScriptedAiTurnCursor closing = ScriptedAiTurnCursor.of(
                nativeRequest(prompt, replay.exchanges(), null, true), SELECTORS);

        assertTrue(first.nativeProtocol());
        assertEquals(1, first.completedToolCalls());
        assertFalse(first.repairAttempt());
        assertFalse(first.closing());
        assertTrue(repairing.repairAttempt());
        assertTrue(closing.closing());
    }

    @Test
    void refusesARequestCarryingNoSelector() {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest("no selector at all")),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        assertThrows(AiProviderException.class,
                () -> ScriptedAiTurnCursor.of(jsonRequest(prompt), SELECTORS));
    }

    @Test
    void refusesARequestMatchingMoreThanOneSelector() {
        MaskingContext context = new MaskingContext();
        MaskedPrompt prompt = promptAssembler.assemble(
                List.of(userRequest(SELECTOR + " and connex_script_other")),
                new AiAssistantToolResult(Map.of(), List.of()),
                List.of(),
                context,
                new AiChatResourceRegistry(),
                AiAssistantToolCatalog.ALL);

        assertThrows(AiProviderException.class, () -> ScriptedAiTurnCursor.of(
                jsonRequest(prompt), Set.of(SELECTOR, "connex_script_other")));
    }

    private static String closingDirective() throws ReflectiveOperationException {
        Field field = AiChatAgentLoopService.class.getDeclaredField("CLOSING_DIRECTIVE");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static AiAssistantPromptBudget unboundedBudget() {
        return new AiAssistantPromptBudget(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static AiChatMessage userRequest(String content) {
        AiChatMessage message = new AiChatMessage();
        message.setAuthorKind("user");
        message.setContent(content);
        return message;
    }

    private static AiAssistantToolResult toolResult(String label) {
        return new AiAssistantToolResult(
                Map.of("handle", "r1", "label", label), List.of());
    }

    private AiCompletionRequest jsonRequest(MaskedPrompt prompt) {
        return completion(prompt, null);
    }

    private AiCompletionRequest nativeRequest(
            MaskedPrompt prompt,
            List<AiToolExchange> exchanges,
            String repairMessage,
            boolean finalOnly) {
        return completion(prompt, new AiNativeToolRequest(
                promptAssembler.nativeToolDefinitions(AiAssistantToolCatalog.ALL),
                exchanges,
                repairMessage,
                finalOnly));
    }

    private AiCompletionRequest completion(MaskedPrompt prompt, AiNativeToolRequest nativeTools) {
        List<AiMessage> messages = prompt.getMessages().stream()
                .map(message -> new AiMessage(message.getRole(), message.getContent()))
                .toList();
        return new AiCompletionRequest(
                new AiProviderTarget(
                        "openai_compatible", null, "scripted-native",
                        "https://scripted.invalid/v1", null, null, null, false),
                AiCredentials.of(Map.of()),
                prompt.getSystemPrompt(),
                messages,
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step", objectMapper.createObjectNode()),
                nativeTools,
                AiReasoningMode.TAGGED,
                AiProviderAttemptExecutor.DIRECT,
                256,
                0.1);
    }
}
