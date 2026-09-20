package ooo.klae.connex.backend.ai.provider.scripted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor;
import ooo.klae.connex.backend.ai.provider.AiProviderCallerDeadlineExceededException;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderIdleTimeoutException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the scripted provider against the whole provider SPI, and against the one accounting rule a
 * hand-written adapter is most likely to miss.
 *
 * <p>{@code beforeSend()} is the only route to the organization budget lease's dispatched mark;
 * {@code execute(...)} never touches the lease. An adapter that produced its output inside
 * {@code execute} without calling {@code beforeSend} would pass every functional test while
 * silently under-counting real sends, which is exactly the trap the older private
 * {@code DeterministicProvider} in the injection golden falls into. Every assertion below therefore
 * checks both calls, including on the declared-failure paths, because a transport failure in
 * production happens after the bytes have left.
 */
class ScriptedAiProviderTest {

    private static final String SELECTOR = "connex_script_unit";
    private static final String FINAL_TEXT =
            "{\"tool\":null,\"final\":{\"text\":\"done\",\"citations\":[]}}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completesABufferedNativeToolCallThroughTheDispatchSeam(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, nativeScript());
        RecordingExecutor executor = new RecordingExecutor();

        AiCompletionResult result = provider.complete(nativeRequest(executor, List.of(), null));

        assertEquals(1, executor.executes);
        assertEquals(1, executor.beforeSends);
        assertEquals(1, result.toolCalls().size());
        AiToolCall call = result.toolCalls().getFirst();
        assertEquals("search_records", call.name());
        assertEquals("{\"query\":\"renewal\"}", call.arguments());
        assertEquals("tool_calls", result.stopReason());
        assertEquals("", result.text());
        assertEquals("looking it up", result.reasoning());
    }

    @Test
    void advancesToTheNextStepAsCompletedToolCallsAccumulate(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, nativeScript());
        RecordingExecutor executor = new RecordingExecutor();

        AiCompletionResult result = provider.complete(
                nativeRequest(executor, List.of(exchange("call-1", "search_records")), null));

        assertEquals(FINAL_TEXT, result.text());
        assertTrue(result.toolCalls().isEmpty());
        assertEquals("stop", result.stopReason());
        assertEquals(1, executor.beforeSends);
    }

    @Test
    void selectsTheRepairStepWhenTheRequestCarriesARepairMessage(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, """
                {
                  "id": "unit",
                  "selector": "connex_script_unit",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "malformed", "text": "not json"}},
                    {
                      "afterToolCalls": 0,
                      "onRepair": true,
                      "emit": {"kind": "final", "text": "%s"}
                    }
                  ]
                }
                """.formatted(FINAL_TEXT.replace("\"", "\\\"")));
        RecordingExecutor executor = new RecordingExecutor();

        assertEquals("not json",
                provider.complete(nativeRequest(executor, List.of(), null)).text());
        assertEquals(FINAL_TEXT,
                provider.complete(nativeRequest(executor, List.of(), "fix it")).text());
        assertEquals(2, executor.beforeSends);
    }

    @Test
    void selectsTheClosingStepWhenTheLoopForbidsFurtherToolCalls(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, """
                {
                  "id": "unit",
                  "selector": "connex_script_unit",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {
                      "afterToolCalls": 0,
                      "closing": false,
                      "emit": {
                        "kind": "tool_call",
                        "toolName": "search_records",
                        "arguments": "{\\"query\\":\\"renewal\\"}"
                      }
                    },
                    {
                      "afterToolCalls": 0,
                      "closing": true,
                      "emit": {"kind": "final", "text": "%s"}
                    }
                  ]
                }
                """.formatted(FINAL_TEXT.replace("\"", "\\\"")));
        RecordingExecutor executor = new RecordingExecutor();

        AiCompletionResult closing = provider.complete(
                nativeRequest(executor, List.of(), null, true));

        assertEquals(FINAL_TEXT, closing.text());
        assertEquals(1, executor.beforeSends);
    }

    @Test
    void refusesARequestCarryingNoKnownSelectorBeforeAnyDispatch(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, nativeScript());
        RecordingExecutor executor = new RecordingExecutor();

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> provider.complete(request(
                        executor, "scripted-native", "no selector here", List.of(), null, false)));

        assertTrue(exception.getMessage().contains("matched no script selector"),
                exception.getMessage());
        assertEquals(0, executor.executes);
        assertEquals(0, executor.beforeSends);
    }

    @Test
    void refusesARequestWhoseStepIsNotScriptedRatherThanImprovising(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, nativeScript());
        RecordingExecutor executor = new RecordingExecutor();

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> provider.complete(nativeRequest(
                        executor,
                        List.of(
                                exchange("call-1", "search_records"),
                                exchange("call-2", "search_records")),
                        null)));

        assertTrue(exception.getMessage().contains("no step for this request"),
                exception.getMessage());
        assertEquals(0, executor.beforeSends);
    }

    @Test
    void refusesAnUnknownConfiguredModelIdRatherThanDefaulting(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, nativeScript());
        RecordingExecutor executor = new RecordingExecutor();

        assertThrows(AiProviderException.class, () -> provider.complete(
                request(executor, "gpt-4o", SELECTOR, List.of(), null, false)));
        assertEquals(0, executor.beforeSends);
    }

    @Test
    void everyDeclaredFailureDispatchesBeforeItFails(@TempDir Path directory) throws IOException {
        assertFailureDispatches(directory, "transport", AiProviderException.class);
        assertFailureDispatches(directory, "idle_timeout", AiProviderIdleTimeoutException.class);
        assertFailureDispatches(directory, "deadline",
                AiProviderCallerDeadlineExceededException.class);
    }

    @Test
    void aDeclaredClientErrorRejectionDispatchesBeforeItFails(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, """
                {
                  "id": "unit",
                  "selector": "connex_script_unit",
                  "capabilityClass": "scripted-native",
                  "expectsNativeDegradation": true,
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "failure", "failureKind": "rejected"}}
                  ]
                }
                """);
        RecordingExecutor executor = new RecordingExecutor();

        AiProviderRequestRejectedException exception = assertThrows(
                AiProviderRequestRejectedException.class,
                () -> provider.complete(nativeRequest(executor, List.of(), null)));

        assertTrue(exception.isClientError());
        assertEquals(1, executor.executes);
        assertEquals(1, executor.beforeSends);
    }

    @Test
    void streamsOrderedDeltasThroughTheObserverAndTheStreamDispatchSeam(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, streamingScript());
        List<String> events = new ArrayList<>();
        RecordingExecutor executor = new RecordingExecutor(events);
        RecordingObserver observer = new RecordingObserver(events, -1);

        AiCompletionResult result = provider.completeStreaming(
                request(executor, "scripted-native-stream", SELECTOR, List.of(), null, false),
                observer);

        assertEquals("hello world", result.text());
        assertEquals(List.of("hello", " world"), observer.deltas);
        assertEquals(
                List.of("open", "beforeSend", "delta", "chunk", "delta", "chunk", "closed"),
                events,
                "the streamed order must mirror OpenAiCompatibleClient.sendStream: the transport "
                        + "opens, and only then is the budget lease marked dispatched. The reasoning "
                        + "mode is announced by AiInvocationService, never by an adapter, so an "
                        + "adapter that announced it too would deliver it twice");
        assertEquals(1, executor.streamExecutes);
        assertEquals(1, executor.beforeSends);
    }

    @Test
    void refusesAnAlreadyExpiredAttemptBeforeMarkingTheLeaseDispatched(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, nativeScript());
        RecordingExecutor executor = new RecordingExecutor();
        executor.pinnedDeadline = expiredDeadline();

        assertThrows(AiProviderCallerDeadlineExceededException.class,
                () -> provider.complete(nativeRequest(executor, List.of(), null)));

        assertEquals(1, executor.executes);
        assertEquals(0, executor.beforeSends,
                "an attempt whose caller deadline has elapsed must not mark a dispatch");
    }

    @Test
    void refusesAStreamCancelledAsTheTransportOpensRatherThanDispatching(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, streamingScript());
        List<String> events = new ArrayList<>();
        RecordingExecutor executor = new RecordingExecutor(events);
        RecordingObserver observer = new RecordingObserver(events, 0);

        assertThrows(AiProviderCallerDeadlineExceededException.class,
                () -> provider.completeStreaming(
                        request(executor, "scripted-native-stream", SELECTOR, List.of(), null,
                                false),
                        observer));

        assertEquals(List.of("open", "closed"), events);
        assertEquals(0, executor.beforeSends);
        assertTrue(observer.deltas.isEmpty());
    }

    @Test
    void abortsAStreamCancelledMidEmissionInsteadOfFinishingIt(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, streamingScript());
        List<String> events = new ArrayList<>();
        RecordingExecutor executor = new RecordingExecutor(events);
        RecordingObserver observer = new RecordingObserver(events, 1);

        assertThrows(AiProviderCallerDeadlineExceededException.class,
                () -> provider.completeStreaming(
                        request(executor, "scripted-native-stream", SELECTOR, List.of(), null,
                                false),
                        observer));

        assertEquals(List.of("hello"), observer.deltas,
                "the registered cancellation must actually abort the scripted emission");
        assertEquals(1, executor.beforeSends);
        assertEquals("closed", events.getLast());
    }

    @Test
    void refusesStreamingForACapabilityClassThatDoesNotDeclareIt(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, nativeScript());
        RecordingExecutor executor = new RecordingExecutor();

        assertThrows(AiProviderException.class, () -> provider.completeStreaming(
                nativeRequest(executor, List.of(), null), new RecordingObserver()));
        assertEquals(0, executor.beforeSends);
    }

    @Test
    void answersTheJsonProtocolWithATextualToolStep(@TempDir Path directory) throws IOException {
        ScriptedAiProvider provider = provider(directory, """
                {
                  "id": "unit",
                  "selector": "connex_script_unit",
                  "capabilityClass": "scripted-json",
                  "steps": [
                    {
                      "afterToolCalls": 0,
                      "emit": {
                        "kind": "tool_call",
                        "toolName": "search_records",
                        "arguments": "{\\"query\\":\\"renewal\\"}"
                      }
                    }
                  ]
                }
                """);
        RecordingExecutor executor = new RecordingExecutor();

        AiCompletionResult result = provider.complete(
                request(executor, "scripted-json", SELECTOR, List.of(), null, false));

        assertEquals(
                "{\"tool\":{\"name\":\"search_records\",\"args\":{\"query\":\"renewal\"}},"
                        + "\"final\":null}",
                result.text());
        assertEquals("stop", result.stopReason());
        assertTrue(result.toolCalls().isEmpty());
        assertEquals(1, executor.beforeSends);
    }

    @Test
    void reportsTheCapabilityClassDeclaredByTheConfiguredModelId(@TempDir Path directory)
            throws IOException {
        ScriptedAiProvider provider = provider(directory, nativeScript());

        assertEquals("openai_compatible", provider.providerId());
        assertEquals(AiToolCallingMode.NATIVE_FUNCTIONS,
                provider.toolCallingCapability(target("scripted-native")));
        assertEquals(AiToolCallingMode.NONE,
                provider.toolCallingCapability(target("scripted-json")));
        assertEquals(AiStructuredOutputEnforcement.JSON_SCHEMA,
                provider.structuredOutputCapability(target("scripted-native")));
        assertEquals(AiReasoningMode.TAGGED,
                provider.reasoningCapability(target("scripted-native")));
        assertTrue(provider.supportsStreaming(target("scripted-native-stream")));
        assertEquals(32_768, provider.contextWindowTokens(target("scripted-small-context")));
        assertEquals(4_096, provider.maxOutputTokens(target("scripted-small-context")));
    }

    @Test
    void journalsEveryRequestItReceivedAndMarksOnlyTheDispatchedOnes(@TempDir Path directory)
            throws IOException {
        ScriptedAiRequestJournal journal = new ScriptedAiRequestJournal();
        ScriptedAiProvider provider = provider(directory, nativeScript(), journal);
        RecordingExecutor executor = new RecordingExecutor();
        AiCompletionRequest first = nativeRequest(executor, List.of(), null);

        provider.complete(first);
        assertThrows(AiProviderException.class, () -> provider.complete(nativeRequest(
                executor,
                List.of(
                        exchange("call-1", "search_records"),
                        exchange("call-2", "search_records")),
                null)));

        assertEquals(2, journal.recorded().size());
        assertTrue(journal.recorded().getFirst().dispatched());
        assertFalse(journal.recorded().getLast().dispatched(),
                "a request the provider refused before its send point never left");
        assertEquals(1, journal.dispatched().size());
        journal.clear();
        assertTrue(journal.recorded().isEmpty());
    }

    @Test
    void marksNoDispatchWhenTheEgressSeamRefusesTheAttempt(@TempDir Path directory)
            throws IOException {
        ScriptedAiRequestJournal journal = new ScriptedAiRequestJournal();
        ScriptedAiProvider provider = provider(directory, nativeScript(), journal);
        AiProviderAttemptExecutor refusing = new AiProviderAttemptExecutor() {
            @Override
            public String execute(Supplier<String> attempt) {
                throw new AiProviderException("refused at the egress seam");
            }
        };

        assertThrows(AiProviderException.class,
                () -> provider.complete(nativeRequest(refusing, List.of(), null)));

        assertEquals(1, journal.recorded().size(),
                "the journal records what the provider was handed, dispatched or not");
        assertFalse(journal.recorded().getFirst().dispatched());
        assertTrue(journal.dispatched().isEmpty(),
                "an attempt the restriction epoch, the feature gate or the provider guard "
                        + "refused inside execute must not appear as egress");
    }

    @Test
    void journalsARedactionThatCarriesNoCredentialValues(@TempDir Path directory)
            throws IOException {
        ScriptedAiRequestJournal journal = new ScriptedAiRequestJournal();
        ScriptedAiProvider provider = provider(directory, nativeScript(), journal);
        RecordingExecutor executor = new RecordingExecutor();
        AiCompletionRequest live = new AiCompletionRequest(
                target("scripted-native"),
                AiCredentials.of(Map.of("apiKey", "live-provider-secret-value")),
                "system",
                List.of(new AiMessage("user", SELECTOR)),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step", objectMapper.createObjectNode()),
                new AiNativeToolRequest(
                        List.of(new AiToolDefinition(
                                "search_records", "search", objectMapper.createObjectNode())),
                        List.of(),
                        null,
                        false),
                AiReasoningMode.TAGGED,
                executor,
                256,
                0.1);

        provider.complete(live);

        AiCompletionRequest recorded = journal.recorded().getFirst().request();
        assertNotSame(live, recorded, "the live request must not be retained");
        assertTrue(recorded.credentials().values().isEmpty(),
                "decrypted BYOP credentials must never sit in a process-lifetime buffer");
        assertEquals(List.of(new AiMessage("user", SELECTOR)), recorded.messages(),
                "the prompt material the assertions are about is retained verbatim");
    }

    private void assertFailureDispatches(
            Path directory, String failureKind, Class<? extends RuntimeException> expected)
            throws IOException {
        Path scoped = Files.createDirectories(directory.resolve(failureKind));
        ScriptedAiProvider provider = provider(scoped, """
                {
                  "id": "unit",
                  "selector": "connex_script_unit",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {"afterToolCalls": 0, "emit": {"kind": "failure", "failureKind": "%s"}}
                  ]
                }
                """.formatted(failureKind));
        RecordingExecutor executor = new RecordingExecutor();

        assertThrows(expected, () -> provider.complete(nativeRequest(executor, List.of(), null)));

        assertEquals(1, executor.executes, failureKind);
        assertEquals(1, executor.beforeSends, failureKind);
    }

    private ScriptedAiProvider provider(Path directory, String script) throws IOException {
        return provider(directory, script, new ScriptedAiRequestJournal());
    }

    private ScriptedAiProvider provider(
            Path directory, String script, ScriptedAiRequestJournal journal) throws IOException {
        Files.writeString(directory.resolve("unit.json"), script, StandardCharsets.UTF_8);
        return new ScriptedAiProvider(
                new ScriptedAiScriptLoader(directory.toString(), objectMapper),
                journal,
                List.of(),
                new AiProperties());
    }

    private static String nativeScript() {
        return """
                {
                  "id": "unit",
                  "selector": "connex_script_unit",
                  "capabilityClass": "scripted-native",
                  "steps": [
                    {
                      "afterToolCalls": 0,
                      "emit": {
                        "kind": "tool_call",
                        "toolName": "search_records",
                        "arguments": "{\\"query\\":\\"renewal\\"}",
                        "reasoning": "looking it up"
                      }
                    },
                    {
                      "afterToolCalls": 1,
                      "emit": {"kind": "final", "text": "%s"}
                    }
                  ]
                }
                """.formatted(FINAL_TEXT.replace("\"", "\\\""));
    }

    private static String streamingScript() {
        return """
                {
                  "id": "unit",
                  "selector": "connex_script_unit",
                  "capabilityClass": "scripted-native-stream",
                  "steps": [
                    {
                      "afterToolCalls": 0,
                      "emit": {
                        "kind": "final",
                        "text": "hello world",
                        "deltas": ["hello", " world"]
                      }
                    }
                  ]
                }
                """;
    }

    private static AiRequestDeadline expiredDeadline() {
        AiRequestDeadline deadline = AiRequestDeadline.afterNanos(1);
        while (!deadline.isExpired()) {
            Thread.onSpinWait();
        }
        return deadline;
    }

    private AiCompletionRequest nativeRequest(
            AiProviderAttemptExecutor executor,
            List<AiToolExchange> exchanges,
            String repairMessage) {
        return nativeRequest(executor, exchanges, repairMessage, false);
    }

    private AiCompletionRequest nativeRequest(
            AiProviderAttemptExecutor executor,
            List<AiToolExchange> exchanges,
            String repairMessage,
            boolean finalOnly) {
        return request(
                executor,
                "scripted-native",
                SELECTOR,
                exchanges,
                repairMessage,
                finalOnly);
    }

    private AiCompletionRequest request(
            AiProviderAttemptExecutor executor,
            String modelId,
            String userText,
            List<AiToolExchange> exchanges,
            String repairMessage,
            boolean finalOnly) {
        AiNativeToolRequest nativeTools = modelId.startsWith("scripted-native")
                ? new AiNativeToolRequest(
                        List.of(new AiToolDefinition(
                                "search_records",
                                "search",
                                objectMapper.createObjectNode())),
                        exchanges,
                        repairMessage,
                        finalOnly)
                : null;
        return new AiCompletionRequest(
                target(modelId),
                AiCredentials.of(java.util.Map.of()),
                "system",
                List.of(new AiMessage("user", userText)),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step", objectMapper.createObjectNode()),
                nativeTools,
                AiReasoningMode.TAGGED,
                executor,
                256,
                0.1);
    }

    private static AiToolExchange exchange(String id, String name) {
        return new AiToolExchange(
                new AiToolCall(id, name, "{\"query\":\"renewal\"}"),
                "CRM_DATA_BEGIN\n{\"type\":\"tool_result\",\"data\":{}}\nCRM_DATA_END");
    }

    private static AiProviderTarget target(String modelId) {
        return new AiProviderTarget(
                "openai_compatible", null, modelId, "https://scripted.invalid/v1",
                null, null, null, false);
    }

    /**
     * Records the executor callbacks into the same ordered list the observer writes to.
     *
     * <p>Counting {@code beforeSend} on its own cannot fail on a dispatch-point regression: moving
     * the call anywhere inside the supplier leaves the total at one. The relative order against
     * {@code onTransportOpen} is the property that matters, because opening the transport is a
     * gate in production, so it has to be observable here.
     */
    private static final class RecordingExecutor implements AiProviderAttemptExecutor {
        private final List<String> events;
        private AiRequestDeadline pinnedDeadline;
        private int executes;
        private int streamExecutes;
        private int beforeSends;

        private RecordingExecutor() {
            this(new ArrayList<>());
        }

        private RecordingExecutor(List<String> events) {
            this.events = events;
        }

        @Override
        public AiRequestDeadline deadline(long requestTimeoutMillis) {
            return pinnedDeadline == null
                    ? AiProviderAttemptExecutor.super.deadline(requestTimeoutMillis)
                    : pinnedDeadline;
        }

        @Override
        public String execute(Supplier<String> attempt) {
            executes++;
            return attempt.get();
        }

        @Override
        public AiCompletionResult executeStream(Supplier<AiCompletionResult> attempt) {
            streamExecutes++;
            return attempt.get();
        }

        @Override
        public void beforeSend() {
            beforeSends++;
            events.add("beforeSend");
        }
    }

    private static final class RecordingObserver implements AiProviderStreamObserver {
        private final List<String> deltas = new ArrayList<>();
        private final List<String> events;
        private final int cancelAfterDeltas;
        private Runnable cancellation;

        private RecordingObserver() {
            this(new ArrayList<>(), -1);
        }

        private RecordingObserver(List<String> events, int cancelAfterDeltas) {
            this.events = events;
            this.cancelAfterDeltas = cancelAfterDeltas;
        }

        @Override
        public void onReasoningMode(AiReasoningMode reasoningMode) {
            events.add("reasoningMode");
        }

        @Override
        public void onTransportOpen(Runnable cancellation) {
            events.add("open");
            this.cancellation = cancellation;
            if (cancelAfterDeltas == 0) {
                cancellation.run();
            }
        }

        @Override
        public void onTransportClosed() {
            events.add("closed");
        }

        @Override
        public void onNetworkChunk() {
            events.add("chunk");
        }

        @Override
        public void onContentDelta(String text) {
            events.add("delta");
            deltas.add(text);
            if (cancellation != null && deltas.size() == cancelAfterDeltas) {
                cancellation.run();
            }
        }
    }
}
