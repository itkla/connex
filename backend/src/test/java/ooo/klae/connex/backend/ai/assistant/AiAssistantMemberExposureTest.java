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
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
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
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AiAssistantPromptAssembler assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
    private final AiAssistantMaskingTestSupport support = new AiAssistantMaskingTestSupport(7, 11);

    @AfterEach
    void releaseMocks() {
        support.releaseFixtureMocks();
    }

    static Stream<Arguments> guesses() {
        return Stream.of(false, true).flatMap(nativeProtocol ->
                Stream.of("unused_directory", "dropped_row", "oversized_attachment", "redaction", "omitted_replay")
                        .flatMap(source -> Stream.of("final", "stream", "assign_owner")
                                .map(output -> Arguments.of(nativeProtocol, source, output))));
    }

    @ParameterizedTest
    @MethodSource("guesses")
    void undispatchedMembersNeverDemaskInAnswersStreamsOrOwnerArguments(
            boolean nativeProtocol, String source, String outputKind) throws Exception {
        MaskingContext context = new MaskingContext();
        context.registerMemberCandidate(MEMBER);
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
                    Map.of("records", rows), List.of(new Identifier("member", MEMBER)))));
        } else if ("oversized_attachment".equals(source)) {
            attachments.add(Map.of("content", "Attached text ".repeat(1_000) + MEMBER));
        } else if ("redaction".equals(source)) {
            String redacted = MaskingEngine.maskFreeText("https://" + MEMBER + ".example", context);
            assertEquals(MaskingEngine.REDACTED, redacted);
            attachments.add(Map.of("content", redacted));
        } else if ("omitted_replay".equals(source)) {
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
        MaskedPrompt prompt = nativeProtocol
                ? assembler.assembleNative(history, empty, turns, context, resources, attachments, budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(history, empty, turns, context, resources, attachments, budget, null, AiAssistantToolCatalog.ALL);
        Map<AiAssistantToolCallRef, AiToolCall> calls = turns.isEmpty() ? Map.of()
                : Map.of(turns.getFirst().ref(), new AiToolCall("read-result", "get_record", "{\"handle\":\"r1\"}"));
        List<AiToolExchange> exchanges = nativeProtocol
                ? assembler.nativeReplay(turns, calls, context, budget, null).exchanges() : List.of();
        String finalText = "{\"text\":\"{{M1}}\",\"citations\":[],\"suggestions\":[],\"title\":null}";
        boolean assignment = "assign_owner".equals(outputKind);
        String output = assignment
                ? "{\"tool\":{\"name\":\"assign_owner\",\"args\":{\"handle\":\"r1\",\"owner\":\"{{M1}}\"}},\"final\":null}"
                : nativeProtocol ? finalText : "{\"tool\":null,\"final\":" + finalText + "}";
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(call ->
                (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
        AiChatStreamingProgress.Observer observer = "stream".equals(outputKind)
                ? new AiChatStreamingProgress(new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 23L, true,
                        List.of(), List.of()), persistence, context).observer(nativeProtocol) : null;
        ooo.klae.connex.backend.ai.provider.AiCompletionRequest dispatched;
        if (observer != null) {
            AiAssistantLoopException refused = org.junit.jupiter.api.Assertions.assertThrows(AiAssistantLoopException.class, () ->
                    support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges, output, false, observer));
            assertEquals("malformed_output", refused.terminalReason());
            assertEquals("invented_placeholder", refused.detailReason());
            dispatched = org.mockito.Mockito.mockingDetails(support.providerHarness(mapper).provider()).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("completeStreaming"))
                    .map(call -> (ooo.klae.connex.backend.ai.provider.AiCompletionRequest) call.getArgument(0))
                    .findFirst().orElseThrow();
        } else {
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges, output, assignment, null);
            assertEquals(1, capture.warnings(), source + "/" + outputKind);
            dispatched = capture.request();
        }
        String payload = mapper.writeValueAsString(dispatched.messages()) + mapper.writeValueAsString(exchanges);
        assertFalse(payload.contains(MEMBER));
        assertFalse(payload.contains("{{M1}}"), source);
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
        assertFalse(Demasker.demask("{{M1}}", context).text().contains(MEMBER));
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
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void memberTokensRoundTripOnlyAfterTheActualProviderDispatch(boolean nativeProtocol, boolean streamed) throws Exception {
        MaskingContext context = new MaskingContext();
        var result = new AiAssistantToolResult(Map.of("owner", MEMBER), List.of(new Identifier("member", MEMBER)));
        var resources = new AiChatResourceRegistry(context);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
        var persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(call ->
                (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
        var observer = streamed ? new AiChatStreamingProgress(new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 23L, true,
                List.of(), List.of()), persistence, context).observer(nativeProtocol) : null;
        String answer = "{\"text\":\"{{M1}}\",\"citations\":[],\"suggestions\":[],\"title\":null}";
        String output = nativeProtocol ? answer : "{\"tool\":null,\"final\":" + answer + "}";
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(), output, false, observer);
        if (observer != null) {
            assertEquals(MEMBER, observer.finish(MEMBER));
            var batches = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(persistence).appendPartialBatch(any(), anyInt(), batches.capture());
            assertEquals(MEMBER, batches.getValue());
        }
        assertTrue(mapper.writeValueAsString(capture.request().messages()).contains("{{M1}}"));
        assertFalse(mapper.writeValueAsString(capture.request().messages()).contains(MEMBER));
        assertEquals(0, capture.warnings());
        assertEquals(MEMBER, Demasker.demask("{{M1}}", context).text());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unexposedMemberGuessesCannotEarnASchemaRepairOrRepairExposure(boolean nativeProtocol) throws Exception {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, MEMBER, context);
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var resources = new AiChatResourceRegistry(context);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), empty, List.of(), context, resources, List.of(),
                        new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        for (String token : List.of("{{M1}}", "{{[M1](person:1)}}", "\\u007b\\u007bM1\\u007d\\u007d")) {
            String invalidAnswer = "{\"text\":\"" + token + "\"}";
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                    nativeProtocol ? invalidAnswer : "{\"tool\":null,\"final\":" + invalidAnswer + "}", false, null);
            assertEquals("invented_placeholder", capture.refusalReason());
            assertFalse(capture.repairPresent());
            assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
            assertFalse(mapper.writeValueAsString(capture.request().messages()).contains("{{M1}}"));
        }
        org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> MaskingEngine.maskFreeTextPreservingIssuedPlaceholders("{\"text\":\"{{M1}}\"}", context));
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
        var context = new MaskingContext();
        var result = new AiAssistantToolResult(Map.of("owner", "Alice"), List.of(new Identifier("member", "Alice")));
        var resources = new AiChatResourceRegistry(context);
        resources.register("person", 31);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var prompt = nativeProtocol
                ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
        var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "{{M1}}_team"), false, null);
        assertEquals(0, capture.warnings());
        String answer = Demasker.demask("{{M1}}_team", context).text();
        assertEquals("Alice_team", answer);
        var labels = context.referencedMemberValues(answer);
        assertEquals(List.of("Alice"), labels);
        var history = new AiChatMessage();
        history.setAuthorKind("assistant");
        history.setContent(answer);
        history.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), resources.snapshot(), Map.of(),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), labels));
        var fresh = new MaskingContext();
        fresh.registerMemberCandidate("Renamed Alice");
        var freshResources = new AiChatResourceRegistry(fresh);
        freshResources.register("person", 31);
        var plainHistory = new AiChatMessage();
        plainHistory.setAuthorKind("assistant");
        plainHistory.setContent("Alice followed up");
        plainHistory.setStructuredJson(history.getStructuredJson());
        var empty = new AiAssistantToolResult(Map.of(), List.of());
        var replay = nativeProtocol
                ? assembler.assembleNative(List.of(history, plainHistory), empty, List.of(), fresh, freshResources, List.of(), budget, AiAssistantToolCatalog.ALL)
                : assembler.assemble(List.of(history, plainHistory), empty, List.of(), fresh, freshResources, AiAssistantToolCatalog.ALL);
        var replayCapture = support.captureAssistantPayload(replay, fresh, mapper, nativeProtocol, List.of(),
                AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
        assertFalse(mapper.writeValueAsString(replayCapture.request().messages()).contains("Alice"));
        assertEquals(0, replayCapture.warnings());
    }

}
