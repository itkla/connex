package ooo.klae.connex.backend.ai.provider.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import tools.jackson.databind.json.JsonMapper;

class VertexSseAccumulatorTest {
    @Test
    void reassemblesContentReasoningUsageAndFinishReason() {
        List<String> deltas = new ArrayList<>();
        VertexSseAccumulator accumulator = new VertexSseAccumulator(
                JsonMapper.builder().build(),
                new AiProviderStreamObserver() {
                    @Override
                    public void onContentDelta(String text) {
                        deltas.add(text);
                    }
                },
                AiStructuredOutputEnforcement.JSON_SCHEMA,
                AiReasoningMode.NATIVE);
        accumulator.accept("""
                {"candidates":[{"content":{"parts":[
                  {"thought":true,"text":"Check authorized signals."},
                  {"text":"Hello "}
                ]}}]}
                """);
        accumulator.accept("""
                {"candidates":[{"content":{"parts":[{"text":"😀"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":6,"candidatesTokenCount":2,
                 "thoughtsTokenCount":3}}
                """);

        AiCompletionResult result = accumulator.finish();

        assertEquals("Hello 😀", result.text());
        assertEquals("Check authorized signals.", result.reasoning());
        assertEquals(List.of("Hello ", "😀"), deltas);
        assertEquals(6, result.inputTokens());
        assertEquals(5, result.outputTokens());
        assertEquals("stop", result.stopReason());
    }

    @Test
    void malformedLaterPartPublishesNoPartOfTheEvent() {
        List<String> deltas = new ArrayList<>();
        VertexSseAccumulator accumulator = new VertexSseAccumulator(
                JsonMapper.builder().build(),
                deltas::add,
                AiStructuredOutputEnforcement.JSON_SCHEMA,
                AiReasoningMode.NONE);

        assertThrows(AiProviderException.class, () -> accumulator.accept(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\""
                        + "x".repeat(300) + "\"},{\"text\":3}]},\"finishReason\":\"STOP\"}]}"));

        assertEquals(List.of(), deltas);
    }

    @Test
    void malformedContentAndPartsShapesPublishNoPartOfTheEvent() {
        for (String event : List.of(
                "{\"candidates\":[{\"content\":[],\"finishReason\":\"STOP\"}]}",
                "{\"candidates\":[{\"content\":{\"parts\":\"invalid\"},"
                        + "\"finishReason\":\"STOP\"}]}")) {
            List<String> deltas = new ArrayList<>();
            VertexSseAccumulator accumulator = new VertexSseAccumulator(
                    JsonMapper.builder().build(),
                    deltas::add,
                    AiStructuredOutputEnforcement.JSON_SCHEMA,
                    AiReasoningMode.NONE);

            assertThrows(AiProviderException.class, () -> accumulator.accept(event));
            assertEquals(List.of(), deltas);
        }
    }
}
