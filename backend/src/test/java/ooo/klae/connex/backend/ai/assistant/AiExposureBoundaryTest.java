package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ooo.klae.connex.backend.ai.AiNativeToolCompletion;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import tools.jackson.databind.json.JsonMapper;

/** Exercises all reversible namespaces at actual invocation and durable-stream boundaries. */
class AiExposureBoundaryTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AiAssistantMaskingTestSupport support = new AiAssistantMaskingTestSupport(7, 11);

    @AfterEach
    void releaseMocks() {
        support.releaseFixtureMocks();
    }

    @ParameterizedTest
    @EnumSource(EntityKind.class)
    void completeValidationRefusesAnswersAndSuffixesAfterLongHiddenFields(EntityKind kind) {
        for (boolean nativeProtocol : List.of(false, true)) {
            for (boolean suffix : List.of(false, true)) {
                var context = new MaskingContext();
                String token = context.tokenFor(kind, "Unsent label");
                var progress = new AiChatStreamingProgress(new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 23L,
                        true, List.of(), List.of(), AiPrivacyMode.MASKED, true),
                        mock(AiChatTurnPersistenceService.class), context);
                var observer = progress.observer(nativeProtocol);
                observer.onProviderField("reasoning", "safe ".repeat(4_000));
                var prompt = PromptAssembly.builder(context).system("Use concise analysis")
                        .userTurn("Describe the relationship").build();
                String output = AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, suffix ? "Safe answer" : token)
                        + (suffix ? token : "");

                var refused = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                        output, false, observer);

                assertTrue(observer.screenedCharacters() >= 20_000);
                assertEquals("invented_placeholder", refused.refusalReason());
                assertFalse(refused.repairPresent());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(EntityKind.class)
    void nativeFieldsCannotAdmitBoundOrUnissuedGuessesEvenWhenEscaped(EntityKind kind) {
        for (boolean bound : List.of(false, true)) {
            for (String field : List.of("id", "name", "signature", "arguments", "malformed_arguments", "array_arguments")) {
                for (String representation : List.of("{{K1}}", "\\u007b\\u007bK1\\u007d\\u007d", "{{\\nK1}}", "{{K1\\t}}")) {
                    var context = new MaskingContext();
                    if (bound) {
                        context.tokenFor(kind, "Unsent label");
                    }
                    String token = representation.replace("K1", kind.tokenPrefix() + "1");
                    if (field.equals("name")) {
                        assertThrows(IllegalArgumentException.class,
                                () -> new AiToolCall("call-1", token, "{}"));
                        continue;
                    }
                    var call = new AiToolCall(field.equals("id") ? token : "call-1",
                            field.equals("name") ? token : "search_records",
                            field.equals("arguments") ? "{\"query\":\"" + token + "\"}"
                                    : field.equals("malformed_arguments") ? token + " invalid JSON"
                                    : field.equals("array_arguments") ? "[\"" + token + "\"]"
                                    : "{\"query\":\"relationship\"}",
                            field.equals("signature") ? token : null);

                    var refusal = assertInstanceOf(AiNativeToolCompletion.Malformed.class,
                            support.completeNativeCall(context, mapper, call));

                    assertEquals("invented_placeholder", refusal.repairRule());
                    assertTrue(refusal.demaskWarnings() > 0);
                    assertEquals(1, Demasker.demask("{{" + kind.tokenPrefix() + "1}}", context).warnings());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(EntityKind.class)
    void escapedWhitespaceCannotEarnStructuredRepair(EntityKind kind) {
        for (boolean nativeProtocol : List.of(false, true)) {
            for (String spelling : List.of("{{\\nK1}}", "{{K1\\t}}", "{{\\rK1\\f}}")) {
                var context = new MaskingContext();
                context.tokenFor(kind, "Unsent label");
                var prompt = PromptAssembly.builder(context).system("Use concise analysis")
                        .userTurn("Describe the relationship").build();
                String answer = "{\"text\":\"" + spelling.replace("K1", kind.tokenPrefix() + "1") + "\"}";
                var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                        nativeProtocol ? answer : "{\"tool\":null,\"final\":" + answer + "}", false, null);
                assertEquals("invented_placeholder", capture.refusalReason());
                assertFalse(capture.repairPresent());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(EntityKind.class)
    void earlierDispatchSurvivesEvictionAndClosingWhileFreshRunsStartEmpty(EntityKind kind) {
        for (boolean nativeProtocol : List.of(false, true)) {
            var context = new MaskingContext();
            String raw = "A quoted \"label\" with \\ separators";
            String token = context.tokenFor(kind, raw);
            var first = PromptAssembly.builder(context).system("Use concise analysis")
                    .userTurn("Discuss " + token).build();
            var exposed = support.captureAssistantPayload(first, context, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, token), false, null);
            assertEquals(0, exposed.warnings());
            assertEquals(raw, Demasker.demask(token, context).text());
            var closing = PromptAssembly.builder(context).system("Finish from earlier evidence")
                    .userTurn("The earlier context was evicted").build();
            var retained = support.captureAssistantPayload(closing, context, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, token), false, null);
            assertEquals(0, retained.warnings());
            assertFalse(mapper.writeValueAsString(retained.request().messages()).contains(token));

            var fresh = new MaskingContext();
            assertEquals(token, fresh.tokenFor(kind, raw));
            var undispatched = PromptAssembly.builder(fresh).system("Use concise analysis")
                    .userTurn("Finish the summary").build();
            var refused = support.captureAssistantPayload(undispatched, fresh, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, token), false, null);
            assertEquals("invented_placeholder", refused.refusalReason());
            assertFalse(refused.repairPresent());
        }
    }

    @ParameterizedTest
    @EnumSource(EntityKind.class)
    void remaskedHistoryExposesFreshTokensOnlyWhenDispatched(EntityKind kind) {
        for (boolean nativeProtocol : List.of(false, true)) {
            var context = new MaskingContext();
            String raw = "Earlier display label";
            String token = context.tokenFor(kind, raw);
            var history = new ooo.klae.connex.backend.beans.AiChatMessage();
            history.setAuthorKind("assistant");
            history.setContent("Earlier answer about " + raw);
            var assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
            var empty = new AiAssistantToolResult(Map.of(), List.of());
            var resources = new AiChatResourceRegistry(context);
            var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
            var prompt = nativeProtocol
                    ? assembler.assembleNative(List.of(history), empty, List.of(), context, resources, List.of(),
                            budget, AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(history), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
            assertEquals(1, Demasker.demask(token, context).warnings());

            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, token), false, null);

            assertEquals(0, capture.warnings());
            assertTrue(mapper.writeValueAsString(capture.request().messages()).contains(token));
            assertFalse(mapper.writeValueAsString(capture.request().messages()).contains(raw));
            assertEquals(raw, Demasker.demask(token, context).text());
        }
    }

    @ParameterizedTest
    @EnumSource(EntityKind.class)
    void fragmentedGuessesWithdrawBeforeFinishEvenAfterStreamTruncation(EntityKind kind) {
        for (boolean nativeProtocol : List.of(false, true)) {
            String token = "{{" + kind.tokenPrefix() + "1}}";
            for (String spelling : List.of(token, token.toLowerCase(Locale.ROOT),
                    "｛｛" + kind.tokenPrefix() + "１｝｝", "\\u007b\\u007b" + kind.tokenPrefix() + "1\\u007d\\u007d",
                    "{{\\n" + kind.tokenPrefix() + "1}}", "{{" + kind.tokenPrefix() + "1\\t}}")) {
                for (int split = 1; split < spelling.length(); split++) {
                    var context = new MaskingContext();
                    context.tokenFor(kind, "Bound but unsent");
                    var persistence = mock(AiChatTurnPersistenceService.class);
                    when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(call ->
                            (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
                    var turn = new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 23L, true,
                            List.of(), List.of(), AiPrivacyMode.MASKED, true);
                    var progress = new AiChatStreamingProgress(turn, persistence, context);
                    var observer = progress.observer(nativeProtocol);
                    String prefix = nativeProtocol ? "{\"text\":\"" : "{\"tool\":null,\"final\":{\"text\":\"";
                    observer.onContentDelta(prefix + "Safe words. ".repeat(40));
                    boolean truncated = !nativeProtocol && split == spelling.length() / 2;
                    if (truncated) {
                        observer.onContentDelta("x".repeat(17_000));
                    }
                    observer.onContentDelta(spelling.substring(0, split));
                    observer.onContentDelta(spelling.substring(split));

                    verify(persistence).resetPartialContent(org.mockito.ArgumentMatchers.eq(turn), anyInt());
                    var refused = assertThrows(AiAssistantLoopException.class, () -> observer.finish("unused"));
                    assertEquals("invented_placeholder", refused.detailReason());
                    progress.reset();
                    var reconnected = progress.observer(nativeProtocol);
                    reconnected.onContentDelta(AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"));
                    assertEquals("Ready", reconnected.finish("Ready"));
                    org.mockito.Mockito.framework().clearInlineMock(persistence);
                }
            }
        }
    }
    @ParameterizedTest
    @EnumSource(EntityKind.class)
    void nativeAndReasoningStreamFieldsWithdrawWithoutEnteringTheAnswerProjection(EntityKind kind) {
        for (String field : List.of("id", "name", "signature", "arguments", "reasoning", "thought", "vertex_thought")) {
            var context = new MaskingContext();
            context.tokenFor(kind, "Unsent label");
            var persistence = mock(AiChatTurnPersistenceService.class);
            when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(call ->
                    (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
            var turn = new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 23L, true,
                    List.of(), List.of(), AiPrivacyMode.MASKED, true);
            var progress = new AiChatStreamingProgress(turn, persistence, context);
            var observer = progress.observer(false);
            observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + "Safe words. ".repeat(40));
            if (field.equals("vertex_thought")) {
                var accumulator = new ooo.klae.connex.backend.ai.provider.vertex.VertexSseAccumulator(mapper, observer,
                        ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement.PROMPT_ONLY,
                        ooo.klae.connex.backend.ai.provider.AiReasoningMode.NONE);
                for (String fragment : List.of("{{", kind.tokenPrefix() + "1}}")) {
                    accumulator.accept(mapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                            "content", Map.of("parts", List.of(Map.of("text", fragment, "thought", true))))))));
                }
            } else {
                var accumulator = new ooo.klae.connex.backend.ai.provider.openai.OpenAiSseAccumulator(mapper, observer,
                        ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement.PROMPT_ONLY,
                        ooo.klae.connex.backend.ai.provider.AiReasoningMode.NONE);
                for (String fragment : List.of("{{", kind.tokenPrefix() + "1}}")) {
                    Map<String, Object> delta;
                    if (field.equals("reasoning")) {
                        delta = Map.of("reasoning_content", fragment);
                    } else if (field.equals("thought")) {
                        delta = Map.of("content", fragment, "extra_content", Map.of("google", Map.of("thought", true)));
                    } else {
                        var call = new java.util.LinkedHashMap<String, Object>();
                        call.put("index", 0);
                        call.put("id", field.equals("id") ? fragment : "call");
                        call.put("function", Map.of("name", field.equals("name") ? fragment : "search_records",
                                "arguments", field.equals("arguments") ? fragment : ""));
                        if (field.equals("signature")) {
                            call.put("extra_content", Map.of("google", Map.of("thought_signature", fragment)));
                        }
                        delta = Map.of("tool_calls", List.of(call));
                    }
                    accumulator.accept(mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", delta)))));
                }
            }

            verify(persistence).resetPartialContent(org.mockito.ArgumentMatchers.eq(turn),
                    org.mockito.ArgumentMatchers.intThat(offset -> offset > 0));
            var refusal = assertThrows(AiAssistantLoopException.class, () -> observer.finish("unused"));
            assertEquals("invented_placeholder", refusal.detailReason());
            org.mockito.Mockito.framework().clearInlineMock(persistence);
        }
    }

}
