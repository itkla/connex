package ooo.klae.connex.backend.ai.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class OpenAiSseAccumulatorTest {
    @Test
    void reassemblesContentUsageAndFragmentedNativeToolFields() {
        List<String> deltas = new ArrayList<>();
        OpenAiSseAccumulator accumulator = accumulator(deltas);
        accumulator.accept("{\"choices\":[{\"delta\":{\"content\":\"Hi \"},\"finish_reason\":null}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"content\":\"😀\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}");
        accumulator.accept("[DONE]");

        AiCompletionResult result = accumulator.finish();

        assertEquals("Hi 😀", result.text());
        assertEquals(List.of("Hi ", "😀"), deltas);
        assertEquals(4, result.inputTokens());
        assertEquals(2, result.outputTokens());

        OpenAiSseAccumulator tools = accumulator(new ArrayList<>());
        tools.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_\","
                + "\"function\":{\"name\":\"sea\",\"arguments\":\"{\\\"q\\\":\"}}]},\"finish_reason\":null}]}");
        tools.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"1\","
                + "\"function\":{\"name\":\"rch\",\"arguments\":\"\\\"x\\\"}\"},"
                + "\"extra_content\":{\"google\":{\"thought_signature\":\"sig\"}}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        tools.accept("[DONE]");

        AiCompletionResult toolResult = tools.finish();
        assertEquals("call_1", toolResult.toolCalls().getFirst().id());
        assertEquals("search", toolResult.toolCalls().getFirst().name());
        assertEquals("{\"q\":\"x\"}", toolResult.toolCalls().getFirst().arguments());
        assertEquals("sig", toolResult.toolCalls().getFirst().thoughtSignature());
    }

    @Test
    void malformedEventFailsWithSanitizedProviderError() {
        OpenAiSseAccumulator accumulator = accumulator(new ArrayList<>());

        AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> accumulator.accept("{\"sensitive\":\"raw provider bytes\""));

        assertEquals("OpenAI-compatible streaming response was invalid", exception.getMessage());
        assertFalse(String.valueOf(exception).contains("raw provider bytes"));
    }

    @Test
    void malformedFieldAfterContentPublishesNoPartOfTheEvent() {
        List<String> deltas = new ArrayList<>();
        OpenAiSseAccumulator accumulator = accumulator(deltas);

        assertThrows(AiProviderException.class, () -> accumulator.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"" + "x".repeat(300)
                        + "\",\"tool_calls\":[{\"index\":0,\"function\":{\"name\":3}}]},"
                        + "\"finish_reason\":null}]}"));

        assertEquals(List.of(), deltas);
    }

    private static OpenAiSseAccumulator accumulator(List<String> deltas) {
        return new OpenAiSseAccumulator(
                JsonMapper.builder().build(),
                new AiProviderStreamObserver() {
                    @Override
                    public void onContentDelta(String text) {
                        deltas.add(text);
                    }
                },
                AiStructuredOutputEnforcement.JSON_SCHEMA,
                AiReasoningMode.NONE);
    }
}
