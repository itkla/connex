package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler.ToolTurn;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult.Identifier;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import ooo.klae.connex.backend.beans.AiChatMessage;
import tools.jackson.databind.json.JsonMapper;

/** Captures actual invocation dispatches after every assistant source can discard member tokens. */
class AiAssistantMemberExposureTest {
    private static final String MEMBER = "AlixMember";
    private static final Map<String, Set<String>> CENSUS_REFUSALS = Map.of(
            "canonical-code-span", Set.of(
                    "HTML then local code <div>\n` stray\n</div>`:",
                    "HTML then local code <div>\n` stray\n</div>`&colon;",
                    "HTML then local code <div>\n` stray\n</div>`\\:",
                    "HTML then local code <div>\n` stray\n</div>``:",
                    "HTML then local code <div>\n` stray\n</div>``&colon;",
                    "HTML then local code <div>\n` stray\n</div>``\\:"),
            "b", Set.of("unclosed opaque construct <!--", "unclosed opaque construct <?",
                    "unclosed opaque construct <!DECL", "unclosed opaque construct <![CDATA["),
            "c", Set.of("container continuation - ", "container continuation * ", "container continuation + ",
                    "container continuation 1. ", "container continuation 2) ", "container continuation > - "),
            "final-2", Set.of("literal continuation marker -", "literal continuation marker >",
                    "continued container definition key > - ", "continued container definition key - ",
                    "continued container definition key 1. "),
            "definition-fallback", Set.of("invalid definition user:7 \"title\" trailing",
                    "invalid definition user:7 'title' trailing", "invalid definition user:7 (title) trailing",
                    "invalid definition user:7 \"unterminated", "invalid definition user:7 (unescaped (parenthesis))",
                    "invalid definition <user:7", "invalid definition user:7)"),
            "paragraph-context", Set.of("encoded reference key is a paragraph shortcut Al&#93;ice",
                    "encoded reference key is a paragraph shortcut Al&#91;ice",
                    "encoded reference key is a paragraph shortcut Alice &amp; Bob", "encoded titled definition in paragraph"));
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AiAssistantPromptAssembler assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
    private final AiAssistantMaskingTestSupport support = new AiAssistantMaskingTestSupport(7, 11);

    @AfterEach
    void releaseMocks() {
        support.releaseFixtureMocks();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void structuredToolResultKeysAreStaticButValuesAndJsonShapedUserTextRemainProtected(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        for (String label : List.of("Status", "Name", "Tool", "Result", "Step")) {
            context.registerMemberCandidate(label);
        }
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var result = new AiAssistantToolResult(Map.of("status", "done", "name", "Ask Status to follow up"), List.of());
        var turn = ToolTurn.soleCall(1, "get_record", result);
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("{\"Status\":\"follow up\"}");
        var resources = new AiChatResourceRegistry(context);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), empty, List.of(turn), context, resources, List.of(), budget,
                        AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), empty, List.of(turn), context, resources, List.of(), budget, null,
                        AiAssistantToolCatalog.ALL);
        var exchanges = nativeProtocol ? assembler.nativeReplay(List.of(turn),
                Map.of(turn.ref(), new AiToolCall("call-1", "get_record", "{\"handle\":\"r1\"}")),
                context, budget, null).exchanges() : List.<AiToolExchange>of();
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages())
                + (nativeProtocol ? mapper.writeValueAsString(capture.request().nativeTools().exchanges()) : "");
        assertFalse(payload.contains("Ask Status"));
        assertTrue(payload.contains("Ask {{M1}}"));
        assertFalse(capture.request().messages().getFirst().content().contains("Status"));
        assertTrue(payload.contains("status"));
        assertEquals(0, capture.warnings());
        var rawJson = ooo.klae.connex.backend.ai.masking.PromptAssembly.builder(context)
                .system("Use concise analysis").userTurn("{\"Status\":\"follow up\"}").build();
        org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> support.captureAssistantPayload(rawJson, context, mapper, nativeProtocol, List.of(),
                        AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null));
    }

    @org.junit.jupiter.api.Test
    void nativeReplayRetainsTheAdmissionCandidateSetWhenItsResultDiscoversAFormerMember() throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate("Li");
        var call = new AiToolCall("call-1", "search_records", "{\"query\":\"Former Name\"}", "/Li+");
        org.junit.jupiter.api.Assertions.assertInstanceOf(ooo.klae.connex.backend.ai.AiNativeToolCompletion.Tool.class,
                support.completeNativeCall(context, mapper, call));
        var result = new AiAssistantToolResult(Map.of("note", "[Former Name](user:42)"),
                List.of(new Identifier("member", "Former Name", "/note")));
        var turn = ToolTurn.soleCall(1, "search_records", result);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = assembler.assembleNative(List.of(), new AiAssistantToolResult(Map.of(), List.of()), List.of(turn),
                context, new AiChatResourceRegistry(context), List.of(), budget, AiAssistantToolCatalog.ALL);
        var exchanges = assembler.nativeReplay(List.of(turn), Map.of(turn.ref(), call), context, budget, null).exchanges();
        assertEquals(List.of("Former Name"), context.memberCandidatesIn("Former Name"));
        var capture = support.captureAssistantPayload(prompt, context, mapper, true, exchanges,
                AiAssistantMaskingTestSupport.finalOutput(true, "Ready"), false, null);
        assertEquals(0, capture.warnings());
        assertTrue(capture.request().nativeTools().exchanges().getFirst().call().arguments().contains("Former Name"));
        assertFalse(capture.request().nativeTools().exchanges().getFirst().maskedResult().contains("Former Name"));
        assertEquals("/Li+", capture.request().nativeTools().exchanges().getFirst().call().thoughtSignature());
    }

    static Stream<Arguments> sharedValueGuesses() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of("final", "stream", "arguments")
                .flatMap(field -> Stream.of(false, true).map(exposed -> Arguments.of(nativeProtocol, field, exposed))));
    }

    @ParameterizedTest
    @MethodSource("sharedValueGuesses")
    void memberExposureFollowsTheRawValueAcrossOtherTokenNamespaces(
            boolean nativeProtocol, String field, boolean exposed) throws Exception {
        var context = new MaskingContext();
        assertEquals("{{P1}}", context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.PERSON, MEMBER));
        if (exposed) {
            context.exposeDispatchedPayload("Discuss {{P1}}");
        }
        context.registerMemberCandidate(MEMBER);
        String args = "{\"handle\":\"r1\",\"owner\":\"{{P1}}\"}";
        if (nativeProtocol && field.equals("arguments")) {
            var completion = support.completeNativeCall(context, mapper, new AiToolCall("call-1", "assign_owner", args));
            if (exposed) {
                var admitted = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        ooo.klae.connex.backend.ai.AiNativeToolCompletion.Tool.class, completion);
                assertEquals(MEMBER, admitted.arguments().path("owner").asString());
            } else {
                var refused = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        ooo.klae.connex.backend.ai.AiNativeToolCompletion.Malformed.class, completion);
                assertEquals("invented_placeholder", refused.repairRule());
                assertEquals(1, refused.demaskWarnings());
            }
        } else {
            var prompt = ooo.klae.connex.backend.ai.masking.PromptAssembly.builder(context)
                    .system("Use concise analysis").userTurn("Follow up").build();
            String output = field.equals("arguments")
                    ? "{\"tool\":{\"name\":\"assign_owner\",\"args\":" + args + "},\"final\":null}"
                    : AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ask {{P1}}");
            var persistence = mock(AiChatTurnPersistenceService.class);
            when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(call ->
                    (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
            var observer = field.equals("stream") ? new AiChatStreamingProgress(new AiChatQueuedTurn(
                    7, 11, 13, 17, 19, 1, 23L, true, List.of(), List.of()), persistence, context).observer(nativeProtocol) : null;
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                    output, false, observer);
            assertEquals(exposed ? 0 : 1, capture.warnings());
            if (observer != null && !exposed) {
                var refused = org.junit.jupiter.api.Assertions.assertThrows(AiAssistantLoopException.class,
                        () -> observer.finish("Ask {{P1}}"));
                assertEquals("invented_placeholder", refused.detailReason());
            }
        }
        assertEquals(exposed ? MEMBER : "[unknown reference]", Demasker.demask("{{P1}}", context).text());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void catalogToolResultsAreStaticWhileTheSameMemberNameInTenantProseStillRequiresExposure(
            boolean nativeProtocol, boolean tenantMention) throws Exception {
        var context = new MaskingContext();
        context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "create_task");
        var catalog = new AiAssistantToolCatalog();
        var loaded = new AiAssistantToolsetLoader(catalog).load(mapper.readTree("{\"toolset\":\"write_activity\"}"),
                java.util.Set.of(AiAssistantToolCatalog.Toolset.CORE), AiAssistantToolCatalog.ALL);
        assertTrue(loaded.result().data().get("tools").toString().contains("create_task"));
        var turns = new ArrayList<ToolTurn>();
        turns.add(ToolTurn.soleCall(1, "find_tools", loaded.result()));
        if (tenantMention) {
            turns.add(ToolTurn.soleCall(2, "get_record",
                    new AiAssistantToolResult(Map.of("description", "Ask create_task"), List.of())));
        }
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var resources = new AiChatResourceRegistry(context);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, turns, context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, turns, context, resources, List.of(), budget, null, AiAssistantToolCatalog.ALL);
        var calls = new java.util.LinkedHashMap<AiAssistantToolCallRef, AiToolCall>();
        calls.put(turns.getFirst().ref(), new AiToolCall("catalog", "find_tools", "{\"toolset\":\"write_activity\"}"));
        if (tenantMention) {
            calls.put(turns.getLast().ref(), new AiToolCall("record", "get_record", "{\"handle\":\"r1\"}"));
        }
        var exchanges = nativeProtocol ? assembler.nativeReplay(turns, calls, context, budget, null).exchanges()
                : List.<AiToolExchange>of();
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "{{M1}}"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages()) + mapper.writeValueAsString(exchanges);
        assertTrue(payload.contains("create_task"));
        assertFalse(payload.contains("Ask create_task"));
        assertEquals(tenantMention, payload.contains("Ask {{M1}}"));
        assertEquals(tenantMention ? 0 : 1, capture.warnings());
        assertEquals(tenantMention ? "create_task" : "[unknown reference]", Demasker.demask("{{M1}}", context).text());
    }

    static Stream<Arguments> repairInsertedMarkers() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(
                Arguments.of(nativeProtocol, "Redacted", "{\"unexpected\":\"alice@example.test\"}", "[redacted]"),
                Arguments.of(nativeProtocol, "Redacted", "{\"unexpected\":\"Ask Redacted\"}", "{{M1}}"),
                Arguments.of(nativeProtocol, "Omitted by policy", "{\"unexpected\":\"The patient has a diagnosis.\"}",
                        "[omitted by policy]")));
    }

    @ParameterizedTest
    @MethodSource("repairInsertedMarkers")
    void schemaRepairRetainsMaskerInsertionsWithoutTrustingProviderText(
            boolean nativeProtocol, String member, String malformed, String expected) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate(member);
        var resources = new AiChatResourceRegistry(context);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var repair = ooo.klae.connex.backend.ai.AiStructuredRepair.from("final_schema", malformed);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(), context, resources, List.of(), budget,
                        AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(), context, resources, List.of(), budget, repair,
                        AiAssistantToolCatalog.ALL);
        var replay = assembler.nativeReplay(List.of(), Map.of(), context, budget, repair);
        var nativeRequest = new ooo.klae.connex.backend.ai.provider.AiNativeToolRequest(
                assembler.nativeToolDefinitions(AiAssistantToolCatalog.ALL), replay.exchanges(), replay.repairMessage(),
                false, 1, replay.repairSegments());
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null, nativeRequest);
        String text = nativeProtocol ? capture.request().nativeTools().repairMessage()
                : capture.request().messages().getLast().content();
        String output = mapper.readTree(text.substring(text.indexOf("MODEL_OUTPUT_BEGIN\n")
                + "MODEL_OUTPUT_BEGIN\n".length(), text.lastIndexOf("\nMODEL_OUTPUT_END"))).path("output").asString();
        assertTrue(output.contains(expected));
        assertFalse(output.contains("alice@example.test"));
        assertFalse(output.contains("Ask Redacted"));
        assertEquals(0, capture.warnings());
        if (!expected.equals("{{M1}}")) {
            assertTrue(context.tokenBindings().isEmpty());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void providerAuthoredLiteralMarkersRemainStrictlyScannedWithoutInsertionProvenance(boolean nativeProtocol) {
        var context = new MaskingContext();
        context.registerMemberCandidate("Redacted");
        var prompt = ooo.klae.connex.backend.ai.masking.PromptAssembly.builder(context)
                .system("Use concise analysis").segmentedUserTurn(List.of(
                        new ooo.klae.connex.backend.ai.masking.OutboundSegment("{\"query\":\"[redacted]\"}",
                                ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.PROVIDER_AUTHORED))).build();
        org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                        AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null));
    }

    static Stream<Arguments> staticRepairCollisions() {
        return Stream.of("Return", "Output", "JSON").flatMap(label -> Stream.of(
                Arguments.of(false, false, label), Arguments.of(true, false, label), Arguments.of(true, true, label)));
    }

    @ParameterizedTest
    @MethodSource("staticRepairCollisions")
    void repairOriginsExcludeStaticInstructionsAndKeysButMaskTenantText(
            boolean nativeProtocol, boolean toolRepair, String label) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate(label);
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("Ask " + label + " to follow up");
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var resources = new AiChatResourceRegistry(context);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var repair = ooo.klae.connex.backend.ai.AiStructuredRepair.from(
                toolRepair ? "native_invalid_arguments" : "final_citations", "Ask " + label + " again");
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), empty, List.of(), context, resources, List.of(), budget,
                        AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), empty, List.of(), context, resources, List.of(), budget, repair,
                        AiAssistantToolCatalog.ALL);
        var replay = assembler.nativeReplay(List.of(), Map.of(), context, budget, repair);
        var nativeRequest = new ooo.klae.connex.backend.ai.provider.AiNativeToolRequest(
                assembler.nativeToolDefinitions(AiAssistantToolCatalog.ALL), replay.exchanges(), replay.repairMessage(),
                false, 1, replay.repairSegments());
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null, nativeRequest);
        assertEquals(0, capture.warnings());
        assertTrue(mapper.writeValueAsString(capture.request().messages()).contains("Ask {{M1}} to follow up"));
        String repairText = nativeProtocol ? capture.request().nativeTools().repairMessage()
                : capture.request().messages().getLast().content();
        assertTrue(repairText.contains("Return"));
        if (!toolRepair) {
            assertTrue(repairText.contains("JSON"));
            assertTrue(repairText.contains("\"output\""));
            assertFalse(repairText.contains("Ask " + label + " again"));
            assertTrue(repairText.contains("Ask {{M1}} again"));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void validatedNativeToolNamesAreStaticButTheSameMemberNameInArgumentsIsProviderText(boolean rawArgument) {
        var context = new MaskingContext();
        context.registerMemberCandidate("search_records");
        String masked = MaskingEngine.maskFreeText("Ask search_records to follow up", context);
        var prompt = ooo.klae.connex.backend.ai.masking.PromptAssembly.builder(context)
                .system("Use concise analysis").userTurn(masked).build();
        var exchange = new AiToolExchange(new AiToolCall("call-1", "search_records",
                rawArgument ? "{\"query\":\"search_records\"}" : "{\"query\":\"relationships\"}"),
                "{\"records\":[]}", 1, 0);
        if (rawArgument) {
            org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                    () -> support.captureAssistantPayload(prompt, context, mapper, true, List.of(exchange),
                            AiAssistantMaskingTestSupport.finalOutput(true, "Ready"), false, null));
        } else {
            var capture = support.captureAssistantPayload(prompt, context, mapper, true, List.of(exchange),
                    AiAssistantMaskingTestSupport.finalOutput(true, "Ready"), false, null);
            assertEquals(0, capture.warnings());
            assertEquals("search_records", capture.request().nativeTools().exchanges().getFirst().call().name());
            assertTrue(mapper.writeValueAsString(capture.request().messages()).contains("Ask {{M1}} to follow up"));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"id,false", "signature,false", "arguments,false", "id,true", "signature,true", "arguments,true"})
    void providerCallReplayNeverGrantsMemberExposureButServerResultsDo(String field, boolean inResult) throws Exception {
        var context = new MaskingContext();
        context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, MEMBER);
        var prompt = ooo.klae.connex.backend.ai.masking.PromptAssembly.builder(context)
                .system("Use concise analysis").userTurn("Find the relationship").build();
        var call = new AiToolCall(field.equals("id") ? "call-{{M1}}" : "call-1", "search_records",
                field.equals("arguments") ? "{\"query\":\"{{M1}}\"}" : "{\"query\":\"relationship\"}",
                field.equals("signature") ? "sig-{{M1}}" : null);
        var exchange = new AiToolExchange(call, inResult ? "{\"note\":\"Discuss {{M1}}\"}" : "{\"records\":[]}", 1, 0);
        if (!inResult) {
            org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                    () -> support.captureAssistantPayload(prompt, context, mapper, true, List.of(exchange),
                            AiAssistantMaskingTestSupport.finalOutput(true, "Ask {{M1}}"), false, null));
            assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
            return;
        }
        context.exposeDispatchedPayload("Discuss {{M1}}");
        var capture = support.captureAssistantPayload(prompt, context, mapper, true, List.of(exchange),
                AiAssistantMaskingTestSupport.finalOutput(true, "Ask {{M1}}"), false, null);
        assertEquals(call, capture.request().nativeTools().exchanges().get(0).call());
        assertFalse(mapper.writeValueAsString(capture.request().nativeTools().exchanges()).contains(MEMBER));
        assertEquals(inResult ? 0 : 1, capture.warnings());
        assertEquals(!inResult, "invented_placeholder".equals(capture.refusalReason()));
        assertFalse(capture.repairPresent());
        var demasked = Demasker.demask("{{M1}}", context);
        assertEquals(inResult ? 0 : 1, demasked.warnings());
        assertEquals(inResult ? MEMBER : "[unknown reference]", demasked.text());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void suggestionProvenanceStaysWithItsOwnFieldAndExactClick(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate("Li");
        context.registerMemberCandidate("Alice");
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("Ask Li and Alice");
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), empty, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        String memberToken = context.tokenBindings().stream().filter(binding -> "Li".equals(binding.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String answer = "{\"text\":\"Ask " + memberToken + "_team and Alice_team\",\"citations\":[],"
                + "\"suggestions\":[\"Open client pipeline\",\"Ask x" + memberToken + "_team to follow up.\"],\"title\":null}";
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                nativeProtocol ? answer : "{\"tool\":null,\"final\":" + answer + "}", false, null);
        assertEquals(0, capture.warnings());
        String prefix = nativeProtocol ? "" : "/final";
        assertEquals(List.of("Li", "Alice"), capture.memberIdentifiers().get(prefix + "/text"));
        assertFalse(capture.memberIdentifiers().containsKey(prefix + "/suggestions/0"));
        assertEquals(List.of("Li"), capture.memberIdentifiers().get(prefix + "/suggestions/1"));
        var suggestions = List.of("Open client pipeline", "Ask xLi_team to follow up.");
        var stored = mapper.readTree(assembler.finalMetadata(1, List.of(), suggestions, Map.of(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), Map.of(
                        "/text", capture.memberIdentifiers().get(prefix + "/text"),
                        "/suggestions/1", capture.memberIdentifiers().get(prefix + "/suggestions/1"))));
        for (int index = 0; index < suggestions.size(); index++) {
            String clicked = suggestions.get(index);
            var provenance = AiAssistantMemberIdentifiers.suggestionIdentifiers(stored, clicked);
            assertEquals(index == 0 ? List.of() : List.of(new Identifier("member", "Li")), provenance);
            var fresh = new MaskingContext();
            fresh.registerMemberCandidate("Renamed Member");
            var message = new AiChatMessage();
            message.setAuthorKind("user");
            message.setContent(clicked);
            message.setStructuredJson(mapper.writeValueAsString(Map.of("kind", "user_message", "resources", List.of(),
                    "identifiers", List.of(), "memberIdentifiers", provenance)));
            var freshResources = new AiChatResourceRegistry(fresh);
            var replay = nativeProtocol
                    ? assembler.assembleNative(List.of(message), empty, List.of(), fresh, freshResources, List.of(), budget, AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(message), empty, List.of(), fresh, freshResources, AiAssistantToolCatalog.ALL);
            var replayCapture = support.captureAssistantPayload(replay, fresh, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
            String payload = mapper.writeValueAsString(replayCapture.request().messages());
            assertEquals(0, replayCapture.warnings());
            if (index == 0) {
                assertTrue(payload.contains("Open client pipeline"));
                assertFalse(fresh.identifierDictionary().contains("Li"));
            } else {
                assertFalse(payload.contains("xLi_team"));
                assertTrue(payload.contains("x{{M1}}_team"));
            }
        }
        assertTrue(AiAssistantMemberIdentifiers.suggestionIdentifiers(stored, "Open client pipeline ").isEmpty());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unmaskedMemberMetadataReplaysAndCompactsThroughActualDispatch(boolean nativeProtocol) throws Exception {
        support.allowUnmaskedDisclosure(mapper);
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("Discuss Former Member");
        user.setStructuredJson("{\"kind\":\"user_message\",\"resources\":[],\"identifiers\":[],\"memberIdentifiers\":[{\"kind\":\"member\",\"value\":\"Former Member\"}]}");
        var answer = new AiChatMessage();
        answer.setAuthorKind("assistant");
        answer.setContent("Former Member handled it.");
        answer.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), Map.of(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), List.of("Former Member")));
        var summary = new AiChatMessage();
        summary.setAuthorKind("system");
        summary.setContent("Former Member discussed next steps.");
        summary.setStructuredJson("{\"kind\":\"history_summary\",\"resources\":[],\"identifiers\":[],\"memberIdentifiers\":[{\"kind\":\"member\",\"value\":\"Former Member\"}]}");
        var context = new MaskingContext(ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED);
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var history = List.of(summary, user, answer);
        var prompt = nativeProtocol
                ? assembler.assembleNative(history, empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(history, empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        assertTrue(mapper.writeValueAsString(capture.request().messages()).contains("Former Member"));
        assertEquals(0, capture.warnings());
        assertTrue(context.tokenBindings().isEmpty());
        assertTrue(context.identifierDictionary().isEmpty());
        var compacted = assembler.assembleSummary(summary, List.of(user, answer), context, resources);
        var compactCapture = support.captureAssistantPayload(compacted, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        assertTrue(mapper.writeValueAsString(compactCapture.request().messages()).contains("Former Member"));
        assertEquals(0, compactCapture.warnings());
        assertTrue(context.tokenBindings().isEmpty());
        assertTrue(context.identifierDictionary().isEmpty());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,Redacted", "true,Redacted", "false,[redacted]", "true,[redacted]",
            "false,Omitted by policy", "true,Omitted by policy"})
    void maskingSentinelsCannotPoisonDispatchOrExemptSourceMemberMentions(boolean nativeProtocol, String label) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate(label);
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("Ask " + label + " to follow up. Contact alice@example.test");
        var result = new AiAssistantToolResult(Map.of("description", "The note includes a diagnosis from last week.",
                "notes", "Call +1 202 555 0199 or https://example.test/secret"), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertTrue(payload.contains("Ask {{M1}} to follow up."));
        assertTrue(payload.contains("[redacted]"));
        assertTrue(payload.contains("[omitted by policy]"));
        assertFalse(payload.contains("Ask " + label + " to follow up."));
        assertFalse(payload.contains("alice@example.test"));
        assertEquals(label, Demasker.demask("{{M1}}", context).text());
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void exactSuggestionClickMasksFormerMemberAfterRenameInAFreshContext(boolean nativeProtocol) throws Exception {
        var member = new ooo.klae.connex.backend.beans.User();
        member.setDisplayName("Former Member");
        var context = new MaskingContext();
        AiAssistantMemberIdentifiers.seed(List.of(member), context);
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("Discuss Former Member");
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var resources = new AiChatResourceRegistry(context);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), empty, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        String finalAnswer = "{\"text\":\"Ready\",\"suggestions\":[\"Ask {{M1}} to follow up.\"],\"citations\":[],\"title\":null}";
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                nativeProtocol ? finalAnswer : "{\"tool\":null,\"final\":" + finalAnswer + "}", false, null);
        assertEquals(0, capture.warnings());
        var provenance = capture.memberIdentifiers().get(nativeProtocol ? "/suggestions/0" : "/final/suggestions/0");
        assertEquals(List.of("Former Member"), provenance);
        String suggestion = Demasker.demask("Ask {{M1}} to follow up.", context).text();
        var stored = mapper.readTree(assembler.finalMetadata(1, List.of(), List.of(suggestion), Map.of(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), Map.of("/suggestions/0", provenance)));
        member.setDisplayName("Renamed Member");
        var fresh = new MaskingContext();
        AiAssistantMemberIdentifiers.seed(List.of(member), fresh);
        assertFalse(fresh.identifierDictionary().contains("Former Member"));
        assertTrue(AiAssistantMemberIdentifiers.suggestionIdentifiers(stored, suggestion + " ").isEmpty());
        assertTrue(AiAssistantMemberIdentifiers.suggestionIdentifiers(stored, "A different request").isEmpty());
        var clickedIdentifiers = AiAssistantMemberIdentifiers.suggestionIdentifiers(stored, suggestion);
        assertEquals(List.of(new Identifier("member", "Former Member")), clickedIdentifiers);
        var click = new AiChatMessage();
        click.setAuthorKind("user");
        click.setContent(suggestion);
        click.setStructuredJson(mapper.writeValueAsString(Map.of("kind", "user_message", "resources", List.of(),
                "identifiers", List.of(), "memberIdentifiers", clickedIdentifiers)));
        var freshResources = new AiChatResourceRegistry(fresh);
        var replay = nativeProtocol
                ? assembler.assembleNative(List.of(click), empty, List.of(), fresh, freshResources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(click), empty, List.of(), fresh, freshResources, AiAssistantToolCatalog.ALL);
        var replayCapture = support.captureAssistantPayload(replay, fresh, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(replayCapture.request().messages());
        assertFalse(payload.contains("Former Member"));
        assertTrue(payload.contains("Ask {{M1}} to follow up."));
        assertEquals("Former Member", Demasker.demask("{{M1}}", fresh).text());
        assertEquals(0, replayCapture.warnings());
    }

    static Stream<Arguments> kindProtocols() {
        return Stream.of(false, true).flatMap(nativeProtocol ->
                Stream.of(ooo.klae.connex.backend.ai.masking.EntityKind.values())
                        .map(kind -> Arguments.of(nativeProtocol, kind)));
    }

    static Stream<Arguments> kindStreams() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(false, true)
                .flatMap(streamed -> Stream.of(ooo.klae.connex.backend.ai.masking.EntityKind.values())
                        .map(kind -> Arguments.of(nativeProtocol, streamed, kind))));
    }

    static Stream<Arguments> guesses() {
        return Stream.of(false, true).flatMap(nativeProtocol ->
                Stream.of("unused_directory", "dropped_row", "oversized_attachment", "redaction", "omitted_replay", "skill_evidence")
                        .flatMap(source -> Stream.of("final", "stream", "assign_owner")
                                .flatMap(output -> Stream.of(ooo.klae.connex.backend.ai.masking.EntityKind.values())
                                        .map(kind -> Arguments.of(nativeProtocol, source, output, kind)))));
    }

    @ParameterizedTest
    @MethodSource("guesses")
    void undispatchedReferencesNeverDemaskInAnswersStreamsOrOwnerArguments(
            boolean nativeProtocol, String source, String outputKind, ooo.klae.connex.backend.ai.masking.EntityKind kind) throws Exception {
        MaskingContext context = new MaskingContext();
        if (kind == ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER) {
            context.registerMemberCandidate(MEMBER);
        }
        String token = "{{" + kind.tokenPrefix() + "1}}";
        if (!"unused_directory".equals(source)) {
            context.tokenFor(kind, MEMBER);
        }
        List<ToolTurn> turns = new ArrayList<>();
        List<Map<String, Object>> attachments = new ArrayList<>();
        List<AiChatMessage> history = new ArrayList<>();
        if ("dropped_row".equals(source)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                rows.add(Map.of("text", "Ordinary evidence ".repeat(40)));
            }
            rows.add(Map.of("owner", MEMBER));
            turns.add(ToolTurn.soleCall(1, "get_record", new AiAssistantToolResult(
                    Map.of("records", rows), List.of(new Identifier(kind.name().toLowerCase(java.util.Locale.ROOT), MEMBER)))));
        } else if ("oversized_attachment".equals(source)) {
            attachments.add(Map.of("content", "Attached text ".repeat(1_000) + MEMBER));
        } else if ("redaction".equals(source)) {
            String redacted = MaskingEngine.maskFreeText("https://" + MEMBER + ".example", context);
            assertEquals(MaskingEngine.REDACTED, redacted);
            attachments.add(Map.of("content", redacted));
        } else if ("omitted_replay".equals(source)) {
            context.tokenFor(kind, MEMBER);
            AiChatMessage omitted = new AiChatMessage();
            omitted.setAuthorKind("assistant");
            omitted.setContent("t1 remembers " + MEMBER);
            omitted.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), Map.of(), Map.of(),
                    AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), List.of(MEMBER)));
            history.add(omitted);
        }
        AiAssistantPromptBudget budget = new AiAssistantPromptBudget(64, 4_096, 256, 256, 600, 8_000);
        AiAssistantToolResult empty = new AiAssistantToolResult(Map.of(), List.of());
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
        var skill = "skill_evidence".equals(source)
                ? new AiAssistantPromptAssembler.SkillContext("Summarize the supplied evidence",
                        Map.of("note", "Skill evidence ".repeat(1_000) + MEMBER))
                : AiAssistantPromptAssembler.SkillContext.NONE;
        MaskedPrompt prompt = nativeProtocol
                ? assembler.assembleNative(history, empty, turns, context, resources, attachments, budget, skill, AiAssistantToolCatalog.ALL)
                : assembler.assemble(history, empty, turns, context, resources, attachments, budget, null, skill, AiAssistantToolCatalog.ALL);
        Map<AiAssistantToolCallRef, AiToolCall> calls = turns.isEmpty() ? Map.of()
                : Map.of(turns.getFirst().ref(), new AiToolCall("read-result", "get_record", "{\"handle\":\"r1\"}"));
        List<AiToolExchange> exchanges = nativeProtocol
                ? assembler.nativeReplay(turns, calls, context, budget, null).exchanges() : List.of();
        String finalText = "{\"text\":\"" + token + "\",\"citations\":[],\"suggestions\":[],\"title\":null}";
        boolean assignment = "assign_owner".equals(outputKind);
        String output = assignment
                ? "{\"tool\":{\"name\":\"assign_owner\",\"args\":{\"handle\":\"r1\",\"owner\":\"" + token + "\"}},\"final\":null}"
                : nativeProtocol ? finalText : "{\"tool\":null,\"final\":" + finalText + "}";
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(call ->
                (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
        AiChatStreamingProgress.Observer observer = "stream".equals(outputKind)
                ? new AiChatStreamingProgress(new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 23L, true,
                        List.of(), List.of()), persistence, context).observer(nativeProtocol) : null;
        ooo.klae.connex.backend.ai.provider.AiCompletionRequest dispatched;
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges, output, assignment, observer);
        assertEquals(1, capture.warnings(), source + "/" + outputKind);
        dispatched = capture.request();
        if (observer != null) {
            var refused = org.junit.jupiter.api.Assertions.assertThrows(AiAssistantLoopException.class,
                    () -> observer.finish(token));
            assertEquals("invented_placeholder", refused.detailReason());
        }
        String payload = mapper.writeValueAsString(dispatched.messages()) + mapper.writeValueAsString(exchanges);
        assertFalse(payload.contains(MEMBER));
        assertFalse(payload.contains(token), source);
        assertEquals(1, Demasker.demask(token, context).warnings());
        assertFalse(Demasker.demask(token, context).text().contains(MEMBER));
        assertEquals("unused_directory".equals(source) ? 0 : 1, context.tokenBindings().size(), source);
        assertTrue(org.mockito.Mockito.mockingDetails(persistence).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("appendPartialBatch"))
                .noneMatch(call -> ((String) call.getArgument(2)).contains(MEMBER)));
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void approvedUnmaskedDisclosureStillDispatchesMemberVocabulary(boolean nativeProtocol) throws Exception {
        support.allowUnmaskedDisclosure(mapper);
        MaskingContext context = new MaskingContext(ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED);
        context.registerMemberCandidate(MEMBER);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        AiChatMessage request = new AiChatMessage();
        request.setAuthorKind("user");
        request.setContent("Ask " + MEMBER);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(request), empty, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(request), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        assertTrue(mapper.writeValueAsString(capture.request().messages()).contains(MEMBER));
        assertEquals(0, context.tokenBindings().size());
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @MethodSource("kindStreams")
    void tokensRoundTripOnlyAfterTheActualProviderDispatch(boolean nativeProtocol, boolean streamed, ooo.klae.connex.backend.ai.masking.EntityKind kind) throws Exception {
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(kind, MEMBER, context);
        var prompt = ooo.klae.connex.backend.ai.masking.PromptAssembly.builder(context)
                .system("Use concise analysis").userTurn("Discuss " + token).build();
        assertEquals(1, Demasker.demask(token, context).warnings());
        var persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(call ->
                (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
        var observer = streamed ? new AiChatStreamingProgress(new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 23L, true,
                List.of(), List.of()), persistence, context).observer(nativeProtocol) : null;
        String answer = "{\"text\":\"" + token + "\",\"citations\":[],\"suggestions\":[],\"title\":null}";
        String output = nativeProtocol ? answer : "{\"tool\":null,\"final\":" + answer + "}";
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(), output, false, observer);
        if (observer != null) {
            assertEquals(MEMBER, observer.finish(MEMBER));
            var batches = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(persistence).appendPartialBatch(any(), anyInt(), batches.capture());
            assertEquals(MEMBER, batches.getValue());
        }
        assertTrue(mapper.writeValueAsString(capture.request().messages()).contains(token));
        assertFalse(mapper.writeValueAsString(capture.request().messages()).contains(MEMBER));
        assertEquals(0, capture.warnings());
        assertEquals(MEMBER, Demasker.demask(token, context).text());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void phoneLikeMemberOutcomeRedactsWithoutExposingItsAllocatedToken(boolean nativeProtocol) throws Exception {
        String member = "Second Actor 12345678";
        var context = new MaskingContext();
        context.registerMemberCandidate("First Actor 12345678");
        context.registerMemberCandidate("Renamed Assignment Member");
        var result = new AiAssistantToolResult(Map.of("owner", member), List.of(new Identifier("member", member)));
        var turn = ToolTurn.soleCall(1, "assign_owner", result);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var resources = new AiChatResourceRegistry(context);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(turn), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(turn), context, resources, AiAssistantToolCatalog.ALL);
        var exchanges = nativeProtocol
                ? assembler.nativeReplay(List.of(turn), Map.of(turn.ref(),
                        new AiToolCall("numeric-owner", "assign_owner", "{}")), context, budget, null).exchanges()
                : List.<AiToolExchange>of();
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages())
                + mapper.writeValueAsString(capture.request().nativeTools());
        assertFalse(payload.contains(member));
        assertTrue(payload.contains(MaskingEngine.REDACTED));
        assertFalse(payload.contains("{{M1}}"));
        assertEquals(List.of(Map.entry("{{M1}}", member)), context.tokenBindings());
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
        assertEquals(0, capture.warnings());
        var guessed = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "{{M1}}"), false, null);
        assertEquals("invented_placeholder", guessed.refusalReason());
        assertFalse(guessed.repairPresent());
    }

    @ParameterizedTest
    @MethodSource("kindProtocols")
    void unexposedMemberGuessesCannotEarnASchemaRepairOrRepairExposure(boolean nativeProtocol, ooo.klae.connex.backend.ai.masking.EntityKind kind) throws Exception {
        MaskingContext context = new MaskingContext();
        String reference = "{{" + kind.tokenPrefix() + "1}}";
        MaskingEngine.maskField(kind, MEMBER, context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        for (String token : List.of(reference, "{{[" + kind.tokenPrefix() + "1](person:1)}}", "\\u007b\\u007b" + kind.tokenPrefix() + "1\\u007d\\u007d")) {
            String invalidAnswer = "{\"text\":\"" + token + "\"}";
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                    nativeProtocol ? invalidAnswer : "{\"tool\":null,\"final\":" + invalidAnswer + "}", false, null);
            assertEquals("invented_placeholder", capture.refusalReason());
            assertFalse(capture.repairPresent());
            assertEquals(1, Demasker.demask(reference, context).warnings());
            assertFalse(mapper.writeValueAsString(capture.request().messages()).contains(reference));
        }
        org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> MaskingEngine.maskFreeTextPreservingIssuedPlaceholders("{\"text\":\"" + reference + "\"}", context));
    }

    static Stream<Arguments> escapedMalformedMembers() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(
                "\\u007b\\u007bM1\\u007d\\u007d", "\\\\u007b\\\\u007bM1\\\\u007d\\\\u007d",
                "\\{\\{M1\\}\\}", "\\u005cu007b\\u005cu007bM1\\u005cu007d\\u005cu007d",
                "\\u005c\\u0075\\u0030\\u0030\\u0037\\u0062"
                        + "\\u005c\\u0075\\u0030\\u0030\\u0037\\u0062M1"
                        + "\\u005c\\u0075\\u0030\\u0030\\u0037\\u0064"
                        + "\\u005c\\u0075\\u0030\\u0030\\u0037\\u0064")
                .map(token -> Arguments.of(nativeProtocol, token)));
    }

    @ParameterizedTest
    @MethodSource("escapedMalformedMembers")
    void malformedEscapedMemberTokensNeverEarnASecondDispatch(boolean nativeProtocol, String token) throws Exception {
        var context = new MaskingContext();
        context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, MEMBER);
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        String malformed = "{\"text\":\"" + token + "\"";
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                nativeProtocol ? malformed : "{\"tool\":null,\"final\":" + malformed, false, null);
        assertEquals("invented_placeholder", capture.refusalReason());
        assertFalse(capture.repairPresent());
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
    }

    static Stream<Arguments> completeProviderEscapeCases() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(
                "{{M1}}", "\\u007b\\u007bM1\\u007d\\u007d", "\\\\u007b\\\\u007bM1\\\\u007d\\\\u007d",
                "\\{\\{M1\\}\\}").flatMap(token -> Stream.of(
                        "{} {\"text\":\"" + token + "\"",
                        "{} trailing garbage \"" + token + "\"",
                        AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready") + " \"" + token + "\"",
                        "{} {\"tool\":{\"name\":\"assign_owner\",\"args\":{\"owner\":\"" + token + "\"}}",
                        "<think>" + token + "</think>" + AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"))
                        .map(output -> Arguments.of(nativeProtocol, output))));
    }

    @ParameterizedTest
    @MethodSource("completeProviderEscapeCases")
    void completeProviderTextCannotHideEscapedMembersBehindAValidPrefixOrReasoning(
            boolean nativeProtocol, String output) throws Exception {
        var context = new MaskingContext();
        context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, MEMBER);
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                output, false, null);
        assertEquals("invented_placeholder", capture.refusalReason());
        assertFalse(capture.repairPresent());
        assertEquals(1, capture.warnings());
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void memberCollisionWithRealStaticDirectiveMasksTheTenantMentionOnly(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate("May");
        AiChatMessage user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("Ask May to follow up");
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        assertTrue(prompt.getSystemPrompt().contains("may"));
        assertFalse(prompt.getSystemPrompt().contains("{{M1}}"));
        assertFalse(mapper.writeValueAsString(prompt.getMessages()).contains("May"));
        assertTrue(mapper.writeValueAsString(prompt.getMessages()).contains("{{M1}}"));
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        assertEquals(prompt.getSystemPrompt(), capture.request().systemPrompt());
        assertEquals(0, capture.warnings());
        org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> ooo.klae.connex.backend.ai.masking.OutboundLeakScan.assertNoLeakInServerEnvelope(
                        mapper.writeValueAsString(Map.of("messages", List.of(Map.of("role", "user", "content", "Ask May")))),
                        context, mapper));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void joinedMemberOutputRetainsProvenanceAfterRenameAndFreshReplay(boolean nativeProtocol) throws Exception {
        assertJoinedMemberReplay(nativeProtocol, "Alice", "{{M1}}_team");
    }

    static Stream<Arguments> shortJoinedMemberCases() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of("{{M1}}_team", "x{{M1}}")
                .map(text -> Arguments.of(nativeProtocol, text)));
    }

    @ParameterizedTest
    @MethodSource("shortJoinedMemberCases")
    void shortJoinedMemberOutputRetainsExactDemaskProvenanceAfterRenameAndFreshReplay(
            boolean nativeProtocol, String output) throws Exception {
        assertJoinedMemberReplay(nativeProtocol, "Bob", output);
    }

    private void assertJoinedMemberReplay(boolean nativeProtocol, String member, String output) throws Exception {
        var context = new MaskingContext();
        var result = new AiAssistantToolResult(Map.of("owner", member), List.of(new Identifier("member", member)));
        var resources = new AiChatResourceRegistry(context);
        resources.register("person", 31);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, output), false, null);
        assertEquals(0, capture.warnings());
        String answer = Demasker.demask(output, context).text();
        assertEquals(output.replace("{{M1}}", member), answer);
        var labels = capture.memberIdentifiers().getOrDefault(nativeProtocol ? "/text" : "/final/text", List.of());
        assertEquals(List.of(member), labels);
        var history = new AiChatMessage();
        history.setAuthorKind("assistant");
        history.setContent(answer);
        history.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), resources.snapshot(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), labels));
        var fresh = new MaskingContext();
        fresh.registerMemberCandidate("Renamed " + member);
        var freshResources = new AiChatResourceRegistry(fresh);
        freshResources.register("person", 31);
        var plainHistory = new AiChatMessage();
        plainHistory.setAuthorKind("assistant");
        plainHistory.setContent(member + " followed up");
        plainHistory.setStructuredJson(history.getStructuredJson());
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var replay = nativeProtocol
                ? assembler.assembleNative(List.of(history, plainHistory), empty, List.of(), fresh, freshResources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(history, plainHistory), empty, List.of(), fresh, freshResources, AiAssistantToolCatalog.ALL);
        var replayCapture = support.captureAssistantPayload(replay, fresh, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(replayCapture.request().messages());
        assertFalse(payload.contains(member));
        assertTrue(payload.contains(output));
        assertEquals(0, replayCapture.warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void replayContainmentDoesNotSpreadToOtherMessagesOrUnrelatedShortLabels(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate("Renamed Bob");
        context.registerMemberCandidate("Li");
        var history = new AiChatMessage();
        history.setAuthorKind("assistant");
        history.setContent("Bob_team xBob Little xLi Li_team");
        history.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), Map.of(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), List.of("Bob")));
        var unrelated = new AiChatMessage();
        unrelated.setAuthorKind("user");
        unrelated.setContent("Bob_team xBob Little xLi Li_team");
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(history, unrelated), empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(history, unrelated), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String messages = mapper.writeValueAsString(capture.request().messages());
        String bob = context.tokenBindings().stream().filter(binding -> "Bob".equals(binding.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String li = context.tokenBindings().stream().filter(binding -> "Li".equals(binding.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        assertTrue(messages.contains(bob + "_team x" + bob + " Little xLi " + li + "_team"));
        assertTrue(messages.contains(bob + "_team xBob Little xLi " + li + "_team"));
        assertEquals(0, capture.warnings());
    }

    static Stream<Arguments> compatibilityReplayCases() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(
                Arguments.of(nativeProtocol, "Li x\uFB02i", "{{M1}} x[redacted]"),
                Arguments.of(nativeProtocol, "Li xLi Li_team LiLi", "{{M1}} x{{M1}} {{M1}}_team {{M1}}{{M1}}")));
    }

    @ParameterizedTest
    @MethodSource("compatibilityReplayCases")
    void memberReplayRedactsPartialCompatibilityExpansionsAndRoundTripsAlignedMatches(
            boolean nativeProtocol, String text, String expected) throws Exception {
        var history = new AiChatMessage();
        history.setAuthorKind("assistant");
        history.setContent(text + " xBob Bob_team");
        history.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), Map.of(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), List.of("Li")));
        var context = new MaskingContext();
        context.registerMemberCandidate("Renamed Li");
        context.registerMemberCandidate("Bob");
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(history), empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(history), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        var replayed = capture.request().messages().stream().filter(message -> "assistant".equals(message.role())).toList();
        assertEquals(1, replayed.size());
        String masked = mapper.readTree(replayed.getFirst().content()).path("content").asString();
        String li = context.tokenBindings().stream().filter(binding -> "Li".equals(binding.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String bob = context.tokenBindings().stream().filter(binding -> "Bob".equals(binding.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        assertEquals(expected.replace("{{M1}}", li) + " xBob " + bob + "_team", masked);
        assertFalse(mapper.writeValueAsString(capture.request().messages()).contains("Li"));
        assertEquals(expected.replace("{{M1}}", "Li") + " xBob Bob_team", Demasker.demask(masked, context).text());
        assertEquals(0, capture.warnings());
    }

    static Stream<Arguments> provenancedReplayForms() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of("assistant", "user", "system")
                .map(author -> Arguments.of(nativeProtocol, author)));
    }

    @ParameterizedTest
    @MethodSource("provenancedReplayForms")
    void recordedShortMemberProvenanceCoversUserAnswerAndSummaryReplayAndCompaction(
            boolean nativeProtocol, String author) throws Exception {
        var message = new AiChatMessage();
        message.setAuthorKind(author);
        message.setContent("Bob_team xBob");
        message.setStructuredJson("assistant".equals(author)
                ? assembler.finalMetadata(1, List.of(), List.of(), Map.of(), Map.of(),
                        AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), List.of("Bob"))
                : mapper.writeValueAsString(Map.of(
                        "kind", "user".equals(author) ? "user_message" : "history_summary",
                        "resources", List.of(), "identifiers", List.of(),
                        "memberIdentifiers", List.of(Map.of("kind", "member", "value", "Bob")))));
        var context = new MaskingContext();
        context.registerMemberCandidate("Renamed Bob");
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(message), empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(message), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("Bob"));
        assertTrue(payload.contains("{{M1}}_team x{{M1}}"));
        var compactionContext = new MaskingContext();
        compactionContext.registerMemberCandidate("Renamed Bob");
        var compaction = assembler.assembleSummary("system".equals(author) ? message : null,
                "system".equals(author) ? List.of() : List.of(message), compactionContext,
                new AiChatResourceRegistry(compactionContext));
        String compactionPayload = mapper.writeValueAsString(compaction.getMessages());
        assertFalse(compactionPayload.contains("Bob"));
        assertTrue(compactionPayload.contains("{{M1}}_team x{{M1}}"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unissuedRawMemberAnswerKeepsProvenanceAfterRenameAndFreshReplay(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate("Bob");
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Bob_team"), false, null);
        assertEquals(List.of(), context.tokenBindings());
        var provenance = capture.memberIdentifiers().getOrDefault(nativeProtocol ? "/text" : "/final/text", List.of());
        assertEquals(List.of("Bob"), provenance);
        var stored = new AiChatMessage();
        stored.setAuthorKind("assistant");
        stored.setContent("Bob_team");
        stored.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), Map.of(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), provenance));
        var fresh = new MaskingContext();
        fresh.registerMemberCandidate("Renamed Bob");
        var freshResources = new AiChatResourceRegistry(fresh);
        var replay = nativeProtocol
                ? assembler.assembleNative(List.of(stored), empty, List.of(), fresh, freshResources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(stored), empty, List.of(), fresh, freshResources, AiAssistantToolCatalog.ALL);
        var replayed = support.captureAssistantPayload(replay, fresh, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(replayed.request().messages());
        assertFalse(payload.contains("Bob"));
        assertTrue(payload.contains("{{M1}}_team"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unmaskedAnswerProvenanceMasksOldNamesAfterRenameAndPolicyChange(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext(ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED);
        context.registerMemberCandidate("Bob");
        context.registerMemberCandidate("Li");
        support.allowUnmaskedDisclosure(mapper);
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setSessionId(1);
        user.setContent("Ask Bob about the client");
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), empty, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        String text = "Bob_team can open client; xBob is deliberately not a raw mention.";
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, text), false, null);
        assertTrue(mapper.writeValueAsString(capture.request().messages()).contains("Ask Bob about the client"));
        assertEquals(text, Demasker.demask(text, context).text());
        assertEquals(0, capture.warnings());
        assertTrue(context.tokenBindings().isEmpty());
        assertTrue(context.identifierDictionary().isEmpty());
        var provenance = capture.memberIdentifiers().getOrDefault(nativeProtocol ? "/text" : "/final/text", List.of());
        assertEquals(List.of("Bob"), provenance);
        var stored = new AiChatMessage();
        stored.setAuthorKind("assistant");
        stored.setSessionId(1);
        stored.setContent(text);
        stored.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), Map.of(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), provenance));
        var fresh = new MaskingContext();
        fresh.registerMemberCandidate("Renamed Bob");
        var freshResources = new AiChatResourceRegistry(fresh);
        var replay = nativeProtocol
                ? assembler.assembleNative(List.of(stored), empty, List.of(), fresh, freshResources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(stored), empty, List.of(), fresh, freshResources, AiAssistantToolCatalog.ALL);
        var maskedSupport = new AiAssistantMaskingTestSupport(7, 11);
        try {
            var replayed = maskedSupport.captureAssistantPayload(replay, fresh, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
            String payload = mapper.writeValueAsString(replayed.request().messages());
            assertFalse(payload.contains("Bob"));
            assertTrue(payload.contains("{{M1}}_team"));
            assertEquals(0, replayed.warnings());
        } finally {
            maskedSupport.releaseFixtureMocks();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void letterAdjacentRawLatinContainmentIsDeliberatelyNotAMemberMention(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate("Bob");
        context.registerMemberCandidate("Li");
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "xBob client"), false, null);
        assertTrue(capture.memberIdentifiers().isEmpty());
        assertTrue(context.tokenBindings().isEmpty());
        var stored = new AiChatMessage();
        stored.setAuthorKind("assistant");
        stored.setContent("xBob client");
        stored.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), Map.of(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), List.of()));
        var fresh = new MaskingContext();
        fresh.registerMemberCandidate("Renamed Bob");
        fresh.registerMemberCandidate("Renamed Li");
        var freshResources = new AiChatResourceRegistry(fresh);
        var replay = nativeProtocol
                ? assembler.assembleNative(List.of(stored), empty, List.of(), fresh, freshResources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(stored), empty, List.of(), fresh, freshResources, AiAssistantToolCatalog.ALL);
        var replayed = support.captureAssistantPayload(replay, fresh, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        assertTrue(mapper.writeValueAsString(replayed.request().messages()).contains("xBob client"));
        assertTrue(fresh.tokenBindings().isEmpty());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,{{m1}}", "true,{{m1}}", "false,｛｛Ｍ１｝｝", "true,｛｛Ｍ１｝｝"})
    void noncanonicalMemberOutputIsNeverSilentlyRetained(boolean nativeProtocol, String token) throws Exception {
        var context = new MaskingContext();
        var result = new AiAssistantToolResult(Map.of("owner", MEMBER), List.of(new Identifier("member", MEMBER)));
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, token), false, null);
        assertEquals(1, capture.warnings());
        assertEquals("invented_placeholder", capture.refusalReason());
        assertFalse(capture.repairPresent());
        assertEquals(1, Demasker.demask(token, context).warnings());
        assertFalse(Demasker.demask(token, context).text().contains(token));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void encodedReaderVisibleReferenceLabelsAreMaskedInActualPayload(boolean nativeProtocol) throws Exception {
        String text = "[Alice &amp; Bob](user&#58;42) [Pending &lt;Name&gt;][invite]\n\n[invite]: user:43";
        var context = new MaskingContext();
        AiAssistantMemberIdentifiers.seedReferenceText(text, context);
        var result = new AiAssistantToolResult(Map.of("description", text),
                AiAssistantMemberIdentifiers.referenceIdentifiers(text));
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("Alice"));
        assertFalse(payload.contains("Pending"));
        assertEquals(0, capture.warnings());
    }

    @Test
    void anEncodedDefinitionInsideAParagraphRetainsCandidatesButRefusesBeforeAdmission() {
        String text = "[Alice &amp; Bob](user&#58;42) [Pending &lt;Name&gt;][invite]\n[invite]: user:43";
        var references = ooo.klae.connex.backend.services.ReferenceService.memberReferencesInContent(text);
        assertTrue(references.contains(new ooo.klae.connex.backend.services.ReferenceService.MemberReference(
                "Alice & Bob", Set.of(42))));
        assertTrue(references.contains(new ooo.klae.connex.backend.services.ReferenceService.MemberReference(
                "Pending <Name>", Set.of(43))));
        var context = new MaskingContext();
        var refused = org.junit.jupiter.api.Assertions.assertThrows(
                ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> AiAssistantMemberIdentifiers.seedReferenceText(text, context));
        assertEquals("Assistant member reference content is incomplete", refused.getMessage());
        org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> AiAssistantMemberIdentifiers.referenceIdentifiers(text));
        assertTrue(context.identifierDictionary().isEmpty());
        assertTrue(context.tokenBindings().isEmpty());
    }

    static Stream<Arguments> encodedReferenceDelimiters() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(
                Map.entry("Al&#93;ice", "Al]ice"),
                Map.entry("Al&#91;ice", "Al[ice"),
                Map.entry("Alice &amp; Bob", "Alice & Bob"))
                .flatMap(label -> Stream.of(false, true).map(referenceStyle ->
                        Arguments.of(nativeProtocol, referenceStyle, label.getKey(), label.getValue()))));
    }

    @ParameterizedTest
    @MethodSource("encodedReferenceDelimiters")
    void encodedLabelDelimitersAreMaskedInActualPayload(
            boolean nativeProtocol, boolean referenceStyle, String sourceLabel, String decodedLabel) throws Exception {
        String text = referenceStyle
                ? "[" + sourceLabel + "][member&#93;key]\n\n[member&#93;key]: user&#58;42"
                : "[" + sourceLabel + "](user&#58;42)";
        var context = new MaskingContext();
        AiAssistantMemberIdentifiers.seedReferenceText(text, context);
        assertTrue(context.tokenBindings().isEmpty());
        var result = new AiAssistantToolResult(Map.of("description", text),
                AiAssistantMemberIdentifiers.referenceIdentifiers(text));
        assertEquals(List.of(new Identifier("member", decodedLabel), new Identifier("member", sourceLabel)),
                result.identifiers());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains(sourceLabel));
        assertFalse(payload.contains(decodedLabel));
        String token = context.tokenBindings().stream().filter(binding -> sourceLabel.equals(binding.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        assertTrue(payload.contains(token));
        assertEquals(sourceLabel, Demasker.demask(token, context).text());
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void encodedDefinitionTitleLineBreaksCannotExposeMemberLabels(boolean nativeProtocol) throws Exception {
        String text = "[Alice &amp; Bob][m]\n\n[m]: user:42 \"a&#10;b\"\n\n"
                + "[Pending &amp; Member][pending]\n\n[pending]: <user:43> (a&#13;b)";
        var context = new MaskingContext();
        AiAssistantMemberIdentifiers.seedReferenceText(text, context);
        assertTrue(context.tokenBindings().isEmpty());
        var result = new AiAssistantToolResult(Map.of("description", text),
                AiAssistantMemberIdentifiers.referenceIdentifiers(text));
        assertEquals(List.of(new Identifier("member", "Alice & Bob"), new Identifier("member", "Pending & Member"),
                new Identifier("member", "Alice &amp; Bob"), new Identifier("member", "Pending &amp; Member")),
                result.identifiers());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("Alice"));
        assertFalse(payload.contains("Pending"));
        for (String sourceLabel : List.of("Alice &amp; Bob", "Pending &amp; Member")) {
            String token = context.tokenBindings().stream().filter(binding -> sourceLabel.equals(binding.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            assertTrue(payload.contains(token));
            assertEquals(sourceLabel, Demasker.demask(token, context).text());
        }
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void userMemberProvenanceAdmitsMentionsButLeavesUnrelatedShortSubstringsUnchanged(boolean nativeProtocol) throws Exception {
        var directory = org.mockito.Mockito.mock(ooo.klae.connex.backend.services.WorkspaceService.class);
        var scope = org.mockito.Mockito.mock(ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess.class);
        var member = new ooo.klae.connex.backend.beans.User();
        member.setDisplayName("Li");
        org.mockito.Mockito.when(directory.getCurrentWorkspaceId()).thenReturn(7);
        var emoji = new ooo.klae.connex.backend.beans.User();
        emoji.setDisplayName("😀");
        var punctuation = new ooo.klae.connex.backend.beans.User();
        punctuation.setDisplayName(".");
        org.mockito.Mockito.when(directory.getMembers(7)).thenReturn(List.of(member, emoji, punctuation));
        org.mockito.Mockito.when(scope.getForWorkspace(7)).thenReturn(
                new ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope(1, List.of(7), "[7]"));
        var resolver = new AiAssistantIdentifierResolver(
                org.mockito.Mockito.mock(ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper.class), directory, scope,
                org.mockito.Mockito.mock(ooo.klae.connex.backend.services.ReferenceService.class));
        for (String text : List.of("show the client pipeline", "Ask Li to follow up", "Ask 😀 to follow up.")) {
            var resolution = resolver.resolve(text);
            assertEquals(text.startsWith("Ask") ? 1 : 0, resolution.identifiers().size());
            var metadata = mapper.createObjectNode().put("kind", "user_message");
            metadata.putArray("resources");
            metadata.putArray("identifiers");
            var provenance = metadata.putArray("memberIdentifiers");
            resolution.identifiers().forEach(identifier -> provenance.addObject()
                    .put("kind", "member").put("value", identifier.value()));
            var user = new AiChatMessage();
            user.setAuthorKind("user");
            user.setContent(text);
            user.setStructuredJson(mapper.writeValueAsString(metadata));
            var context = new MaskingContext();
            context.registerMemberCandidate("Renamed Member");
            var resources = new AiChatResourceRegistry(context);
            var empty = new AiAssistantToolResult(Map.of(), List.of());
            var prompt = nativeProtocol
                    ? assembler.assembleNative(List.of(user), empty, List.of(), context, resources, List.of(),
                            new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(user), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
            String payload = mapper.writeValueAsString(capture.request().messages());
            String expected = text.contains("😀") ? "Ask [redacted] to follow up."
                    : text.startsWith("Ask") ? "Ask {{M1}} to follow up" : text;
            assertTrue(payload.contains(expected));
            assertFalse(payload.contains("😀"));
            assertEquals(text.contains("Li") ? 1 : 0, context.tokenBindings().size());
            assertEquals(0, capture.warnings());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void toolMemberContainmentBelongsOnlyToItsOriginatingTextualField(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        var legacy = mapper.readTree("{\"request\":{\"description\":\"[Li](user:42) Li_team xLi\"},"
                + "\"memberIdentifiers\":[{\"kind\":\"member\",\"value\":\"Li\"}]}");
        var provenance = AiAssistantMemberIdentifiers.stored(legacy);
        assertEquals(List.of(new Identifier("member", "Li", "/description")), provenance);
        var result = new AiAssistantToolResult(Map.of("description", "[Li](user:42) Li_team xLi",
                "company", "Little Co", "query", "client", "siblings", List.of("Little Co", "client")), provenance);
        var turns = List.of(ToolTurn.soleCall(1, "get_record", result));
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, turns, context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, turns, context, resources, AiAssistantToolCatalog.ALL);
        var exchanges = nativeProtocol ? assembler.nativeReplay(turns,
                Map.of(new AiAssistantToolCallRef(1, 0), new AiToolCall("get", "get_record", "{\"handle\":\"r1\"}")),
                context, budget, null).exchanges() : List.<AiToolExchange>of();
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages())
                + mapper.writeValueAsString(capture.request().nativeTools());
        assertTrue(payload.contains("{{M1}}_team x{{M1}}"));
        assertTrue(payload.contains("Little Co"));
        assertTrue(payload.contains("client"));
        assertFalse(payload.contains("Li_team"));
        assertFalse(payload.contains("{{M1}}ttle"));
        assertFalse(payload.contains("c{{M1}}ent"));
        assertEquals(0, capture.warnings());
        assertEquals("Li_team xLi", Demasker.demask("{{M1}}_team x{{M1}}", context).text());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void recordedContainmentPreservesFixedToolResultVocabularyByPosition(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        var result = new AiAssistantToolResult(Map.of("tool", "create_task", "tier", "auto", "status", "executed",
                "outcome", Map.of("recordType", "task", "description", "Ask Ed and Tas")),
                List.of(new Identifier("member", "Ed"), new Identifier("member", "Tas")));
        var turns = List.of(ToolTurn.soleCall(1, "create_task", result));
        var resources = new AiChatResourceRegistry(context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, turns, context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, turns, context, resources, AiAssistantToolCatalog.ALL);
        var exchanges = nativeProtocol ? assembler.nativeReplay(turns, Map.of(new AiAssistantToolCallRef(1, 0),
                new AiToolCall("create", "create_task", "{\"handle\":\"r1\",\"description\":\"Ask {{M1}} and {{M2}}\"}")),
                context, budget, null).exchanges() : List.<AiToolExchange>of();
        if (nativeProtocol) {
            context.exposeDispatchedPayload("Ask {{M1}} and {{M2}}");
        }
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages()) + mapper.writeValueAsString(exchanges);
        assertTrue(payload.contains("executed"));
        assertTrue(payload.contains("create_task"));
        assertTrue(payload.contains("Ask {{M1}} and {{M2}}"));
        assertFalse(payload.contains("execut{{M"));
        assertFalse(payload.contains("create_{{M"));
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void recordedMemberProvenanceCannotOverrideAnotherKindsResidualRedaction(
            boolean nativeProtocol, boolean sharedValue) throws Exception {
        var context = new MaskingContext();
        if (sharedValue) {
            context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.COMPANY, "Acme");
        }
        context.registerMemberCandidate("Acme");
        var result = new AiAssistantToolResult(Map.of("description", "éAcmeé"),
                List.of(new Identifier("member", "Acme", "/description")));
        var turn = ToolTurn.soleCall(1, "get_record", result);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(turn), context, resources, List.of(), budget,
                        AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(turn), context, resources, List.of(), budget, null,
                        AiAssistantToolCatalog.ALL);
        var exchanges = nativeProtocol ? assembler.nativeReplay(List.of(turn), Map.of(turn.ref(),
                new AiToolCall("read", "get_record", "{\"handle\":\"r1\"}")), context, budget, null).exchanges()
                : List.<AiToolExchange>of();
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages())
                + mapper.writeValueAsString(exchanges);
        assertFalse(payload.contains("Acme"));
        String token = context.tokenBindings().getFirst().getKey();
        if (sharedValue) {
            assertTrue(payload.contains("é[redacted]é"));
            assertFalse(payload.contains(token));
            assertEquals(1, Demasker.demask(token, context).warnings());
        } else {
            assertTrue(payload.contains("é" + token + "é"));
            assertEquals("éAcmeé", Demasker.demask("é" + token + "é", context).text());
        }
        assertEquals(0, capture.warnings());
    }

    static Stream<Arguments> collidingMemberPayloads() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(
                ooo.klae.connex.backend.ai.masking.EntityKind.values())
                .filter(kind -> kind != ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER)
                .flatMap(kind -> Stream.of(false, true).map(memberFirst -> Arguments.of(nativeProtocol, kind, memberFirst))));
    }

    @ParameterizedTest
    @MethodSource("collidingMemberPayloads")
    void collidingMemberKindsPreserveResidualRedactionInActualPayloads(
            boolean nativeProtocol, ooo.klae.connex.backend.ai.masking.EntityKind kind, boolean memberFirst) throws Exception {
        var context = new MaskingContext();
        if (memberFirst) {
            context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Acme");
        }
        String token = context.tokenFor(kind, "Acme");
        context.registerMemberCandidate("Acme");
        context.registerMemberCandidate("Li");
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("Open AcmeCorp client");
        var result = new AiAssistantToolResult(Map.of("description", "Open AcmeCorp client"), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("Acme"));
        assertTrue(payload.contains("Open [redacted] client"));
        assertTrue(payload.indexOf("Open [redacted] client") != payload.lastIndexOf("Open [redacted] client"));
        assertEquals(List.of(Map.entry(token, "Acme")), context.tokenBindings());
        if (memberFirst) {
            assertEquals(1, Demasker.demask(token, context).warnings());
        }
        assertEquals(0, capture.warnings());
    }

    static Stream<Arguments> memberScriptBoundaryPayloads() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(
                new String[]{"Иван", "Иван пришёл; Иванов вернулся.", "{{M1}} пришёл; Иванов вернулся."},
                new String[]{"Μαρία", "Μαρία ήρθε; Μαρίανος έφυγε.", "{{M1}} ήρθε; Μαρίανος έφυγε."},
                new String[]{"Élodie", "Élodie arrived; Élodienne left.", "{{M1}} arrived; Élodienne left."},
                new String[]{"Alice", "Alice arrived; Malice remains.", "{{M1}} arrived; Malice remains."},
                new String[]{"a a", "xa a a", "xa {{M1}}"},
                new String[]{"李", "联系李处理", "联系{{M1}}处理"},
                new String[]{"山田", "请山田处理", "请{{M1}}处理"})
                .map(values -> Arguments.of(nativeProtocol, values[0], values[1], values[2])));
    }

    @ParameterizedTest
    @MethodSource("memberScriptBoundaryPayloads")
    void memberScriptBoundariesPreserveUnrelatedWordsInUserAndToolPayloads(
            boolean nativeProtocol, String member, String text, String expected) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate(member);
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent(text);
        var result = new AiAssistantToolResult(Map.of("description", text), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertTrue(payload.contains(expected));
        assertTrue(payload.indexOf(expected) != payload.lastIndexOf(expected));
        assertFalse(payload.contains(text));
        assertEquals(0, capture.warnings());
        assertEquals(List.of(Map.entry("{{M1}}", member)), context.tokenBindings());
        assertEquals(text, Demasker.demask(expected, context).text());
    }

    static Stream<Arguments> memberReferenceReadings() {
        return Stream.of(false, true).flatMap(nativeProtocol ->
                ooo.klae.connex.backend.services.MemberReferenceReadings.all().stream()
                        .map(reading -> Arguments.of(nativeProtocol, reading, censusRefuses(reading))));
    }

    private static boolean censusRefuses(ooo.klae.connex.backend.services.MemberReferenceReadings.Reading reading) {
        return CENSUS_REFUSALS.getOrDefault(reading.rule(), Set.of()).contains(reading.name());
    }

    @Test
    void theExplicitCensusRefusalGoldenCoversEveryReviewedLegacyCase() {
        assertEquals(32, CENSUS_REFUSALS.values().stream().mapToInt(Set::size).sum());
        assertEquals(32, ooo.klae.connex.backend.services.MemberReferenceReadings.all().stream()
                .filter(AiAssistantMemberExposureTest::censusRefuses).count());
    }

    static Stream<Arguments> tableDisagreementReadings() {
        return Stream.of(false, true).flatMap(nativeProtocol -> Stream.of(
                Arguments.of(nativeProtocol, true,
                        "| h |\n| - |\n|\nprefix `\n[Former `]` Member](user:42 \"title\")\nsuffix `", "Former `]` Member"),
                Arguments.of(nativeProtocol, true,
                        "| h |\n| - |\n|\nprefix `\n[Former `]` Member](user:42 \"title\")\nsuffix `\n\n[unused]: user:42",
                        "Former `]` Member"),
                Arguments.of(nativeProtocol, true,
                        "| h |\n| - |\n|\nprefix `\n[Former `]` Member](u&#115;er:42 \"title\")\nsuffix `",
                        "Former `]` Member"),
                Arguments.of(nativeProtocol, false,
                        "prefix `\n\nh|i\n--|--|--\n[Former|Member](user:42 \"title\")\n`", "Former|Member")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"a", "😀"})
    void oversizedResolvedMemberLabelsRefuseBeforeProviderAdmission(String character) {
        for (String prefix : List.of("", "!")) {
            String content = prefix + "[" + character.repeat(1_000) + "](user:42)";
            var context = new MaskingContext();
            var refused = org.junit.jupiter.api.Assertions.assertThrows(
                    ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                    () -> AiAssistantMemberIdentifiers.seedReferenceText(content, context));
            assertEquals("Assistant member reference content is incomplete", refused.getMessage());
            org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                    () -> AiAssistantMemberIdentifiers.referenceIdentifiers(content));
            assertTrue(context.identifierDictionary().isEmpty());
            assertTrue(context.tokenBindings().isEmpty());
        }
    }

    @ParameterizedTest
    @MethodSource("tableDisagreementReadings")
    void tableDisagreementsEitherRefuseBeforeDispatchOrMaskTheActualProviderPayload(
            boolean nativeProtocol, boolean refuse, String content, String label) throws Exception {
        var context = new MaskingContext();
        if (refuse) {
            var refused = org.junit.jupiter.api.Assertions.assertThrows(
                    ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                    () -> AiAssistantMemberIdentifiers.seedReferenceText(content, context));
            assertEquals("Assistant member reference content is incomplete", refused.getMessage());
            org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                    () -> AiAssistantMemberIdentifiers.referenceIdentifiers(content));
            assertTrue(context.identifierDictionary().isEmpty());
            assertTrue(context.tokenBindings().isEmpty());
            return;
        }
        AiAssistantMemberIdentifiers.seedReferenceText(content, context);
        assertTrue(context.identifierDictionary().contains(label));
        assertTrue(context.tokenBindings().isEmpty());
        var result = new AiAssistantToolResult(Map.of("description", content),
                AiAssistantMemberIdentifiers.referenceIdentifiers(content));
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("Former"));
        assertTrue(payload.contains("{{M"));
        assertTrue(context.tokenBindings().stream().anyMatch(binding -> label.equals(binding.getValue())));
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void emojiMembersAreRedactedInUserAndToolProseWhilePeriodsRemain(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        context.registerMemberCandidate("😀");
        context.registerMemberCandidate(".");
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent("Ask 😀. Keep ordinary periods.");
        var result = new AiAssistantToolResult(Map.of("description", "Follow up with 😀. Then finish."),
                List.of(new Identifier("member", "😀"), new Identifier("member", ".")));
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(user), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(user), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("😀"));
        assertTrue(payload.contains("Ask [redacted]. Keep ordinary periods."));
        assertTrue(payload.contains("Follow up with [redacted]. Then finish."));
        assertTrue(context.tokenBindings().isEmpty());
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedDefinitionsMaskTheirShortcutWhileValidKeysRemainOrdinary(boolean nativeProtocol) throws Exception {
        String text = "[Alice Former]: welcome back.\n\n[Alice Former]: user:42\n\n"
                + "[Pending Former]: <invalid\n\n[Pending Former]: user:43\n\n"
                + "[Public Label][m]\n\n[m]: user:44 \"first\nsecond\"\n\nm remains ordinary";
        var context = new MaskingContext();
        AiAssistantMemberIdentifiers.seedReferenceText(text, context);
        var identifiers = AiAssistantMemberIdentifiers.referenceIdentifiers(text);
        assertEquals(List.of(new Identifier("member", "Alice Former"), new Identifier("member", "Pending Former"),
                new Identifier("member", "Public Label")), identifiers);
        var result = new AiAssistantToolResult(Map.of("description", text), identifiers);
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("Alice"));
        assertFalse(payload.contains("Pending"));
        assertFalse(payload.contains("Public Label"));
        assertTrue(payload.contains("m remains ordinary"));
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void paragraphContinuationShortcutsAreMaskedWhileConsecutiveDefinitionKeysRemainOrdinary(boolean nativeProtocol) throws Exception {
        String text = "Hello\n[Alice Former]: welcome.\n\n[Alice Former]: user:42\n\n"
                + "> Hello\n> [Pending Former]: welcome.\n\n[Pending Former]: user:43\n\n"
                + "[Public Label][m]\n\n[m]: user:44\n[n]: user:45\n\nm and n remain ordinary";
        var context = new MaskingContext();
        AiAssistantMemberIdentifiers.seedReferenceText(text, context);
        var identifiers = AiAssistantMemberIdentifiers.referenceIdentifiers(text);
        assertEquals(List.of(new Identifier("member", "Alice Former"), new Identifier("member", "Pending Former"),
                new Identifier("member", "Public Label")), identifiers);
        var result = new AiAssistantToolResult(Map.of("description", text), identifiers);
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("Alice"));
        assertFalse(payload.contains("Pending"));
        assertFalse(payload.contains("Public Label"));
        assertTrue(payload.contains("m and n remain ordinary"));
        assertEquals(0, capture.warnings());
    }

    @ParameterizedTest
    @MethodSource("memberReferenceReadings")
    void everyConservativeMemberReadingIsMaskedOrRefusedBeforeProviderDispatch(
            boolean nativeProtocol, ooo.klae.connex.backend.services.MemberReferenceReadings.Reading reading,
            boolean refuse) throws Exception {
        var context = new MaskingContext();
        assertTrue(ooo.klae.connex.backend.services.ReferenceService.memberReferencesInContent(reading.content()).stream()
                .anyMatch(reference -> reference.label().equals(reading.label()) && reference.memberIds().contains(42)),
                reading.rule() + ": " + reading.name());
        if (refuse) {
            var refused = org.junit.jupiter.api.Assertions.assertThrows(
                    ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                    () -> AiAssistantMemberIdentifiers.seedReferenceText(reading.content(), context));
            assertEquals("Assistant member reference content is incomplete", refused.getMessage());
            org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                    () -> AiAssistantMemberIdentifiers.referenceIdentifiers(reading.content()));
            assertTrue(context.identifierDictionary().isEmpty());
            assertTrue(context.tokenBindings().isEmpty());
            return;
        }
        AiAssistantMemberIdentifiers.seedReferenceText(reading.content(), context);
        assertTrue(context.tokenBindings().isEmpty());
        var result = new AiAssistantToolResult(Map.of("description", reading.content()),
                AiAssistantMemberIdentifiers.referenceIdentifiers(reading.content()));
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains(reading.label().replace("\\", "\\\\")), reading.rule() + ": " + reading.name());
        assertFalse(payload.contains("Alice"), reading.rule() + ": " + reading.name());
        assertEquals(0, capture.warnings());
        assertTrue(context.tokenBindings().stream().anyMatch(binding -> reading.label().equals(binding.getValue())),
                reading.rule() + ": " + reading.name());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,1", "true,1", "false,2", "true,2"})
    void placeholderShapedMemberLabelsAreIrreversiblyRedactedAtDispatch(boolean nativeProtocol, int ordinal) throws Exception {
        var context = new MaskingContext();
        String label = "{{M" + ordinal + "}}";
        if (ordinal == 2) {
            context.registerMemberCandidate("Prior Member");
        }
        context.registerMemberCandidate(label);
        String text = (ordinal == 2 ? "Prior Member then " : "") + label;
        var result = new AiAssistantToolResult(Map.of("description", text), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, label), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains(label));
        assertTrue(payload.contains(MaskingEngine.REDACTED));
        assertTrue(context.tokenBindings().stream().noneMatch(binding -> label.equals(binding.getValue())));
        assertEquals("invented_placeholder", capture.refusalReason());
        assertFalse(capture.repairPresent());
        assertEquals(1, Demasker.demask(label, context).warnings());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void literalMemberCannotExposeAnotherMembersPreviouslyAllocatedToken(boolean nativeProtocol) throws Exception {
        var context = new MaskingContext();
        assertEquals("{{M1}}", context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Unsent Member"));
        context.registerMemberCandidate("{{M1}}");
        var result = new AiAssistantToolResult(Map.of("description", "{{M1}}"), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "{{M1}}"), false, null);
        String payload = mapper.writeValueAsString(capture.request().messages());
        assertFalse(payload.contains("{{M1}}"));
        assertFalse(payload.contains("Unsent Member"));
        assertTrue(payload.contains(MaskingEngine.REDACTED));
        assertEquals("invented_placeholder", capture.refusalReason());
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
    }

}
