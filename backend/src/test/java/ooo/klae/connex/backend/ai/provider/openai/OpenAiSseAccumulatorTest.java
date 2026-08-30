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

    @Test
    void acceptsAWholeToolCallThatArrivedWithoutAnIndex() {
        OpenAiSseAccumulator accumulator = accumulator(new ArrayList<>());

        accumulator.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"id\":\"call_446465\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Osaka\\\"}\"},"
                + "\"extra_content\":{\"google\":{\"thought_signature\":\"sig\"}}}]},"
                + "\"finish_reason\":null}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\"},"
                + "\"finish_reason\":\"stop\"}]}");
        accumulator.accept("[DONE]");

        AiCompletionResult result = accumulator.finish();

        assertEquals(1, result.toolCalls().size());
        assertEquals("call_446465", result.toolCalls().getFirst().id());
        assertEquals("get_weather", result.toolCalls().getFirst().name());
        assertEquals("{\"city\":\"Osaka\"}", result.toolCalls().getFirst().arguments());
        assertEquals("sig", result.toolCalls().getFirst().thoughtSignature());
    }

    @Test
    void keepsUnnumberedParallelToolCallsApartInsteadOfMergingThem() {
        OpenAiSseAccumulator accumulator = accumulator(new ArrayList<>());

        accumulator.accept(unnumberedCall("call_1", "Osaka"));
        accumulator.accept(unnumberedCall("call_2", "Kyoto"));
        accumulator.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\"},"
                + "\"finish_reason\":\"stop\"}]}");
        accumulator.accept("[DONE]");

        AiCompletionResult result = accumulator.finish();

        assertEquals(2, result.toolCalls().size());
        assertEquals(List.of("call_1", "call_2"),
                result.toolCalls().stream().map(call -> call.id()).toList());
        assertEquals("{\"city\":\"Osaka\"}", result.toolCalls().getFirst().arguments());
        assertEquals("{\"city\":\"Kyoto\"}", result.toolCalls().get(1).arguments());
    }

    @Test
    void rejoinsUnnumberedFragmentsThatShareOneCallIdentifier() {
        OpenAiSseAccumulator accumulator = accumulator(new ArrayList<>());

        accumulator.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                + "\"arguments\":\"{\\\"city\\\":\"}}]},\"finish_reason\":null}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_1\","
                + "\"function\":{\"arguments\":\"\\\"Osaka\\\"}\"}}]},"
                + "\"finish_reason\":\"stop\"}]}");
        accumulator.accept("[DONE]");

        AiCompletionResult result = accumulator.finish();

        assertEquals(1, result.toolCalls().size());
        assertEquals("{\"city\":\"Osaka\"}", result.toolCalls().getFirst().arguments());
    }

    @Test
    void takesALateThoughtSignatureOntoTheCallItIdentifies() {
        OpenAiSseAccumulator accumulator = accumulator(new ArrayList<>());

        accumulator.accept(unnumberedCall("call_1", "Osaka"));
        accumulator.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_1\","
                + "\"extra_content\":{\"google\":{\"thought_signature\":\"sig\"}}}]},"
                + "\"finish_reason\":\"stop\"}]}");
        accumulator.accept("[DONE]");

        AiCompletionResult result = accumulator.finish();

        assertEquals(1, result.toolCalls().size());
        assertEquals("sig", result.toolCalls().getFirst().thoughtSignature());
    }

    @Test
    void refusesAnUnnumberedToolCallThatIdentifiesNothing() {
        OpenAiSseAccumulator accumulator = accumulator(new ArrayList<>());

        assertThrows(AiProviderException.class, () -> accumulator.accept(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":null}]}"));
    }

    @Test
    void refusesAResponseThatChangesItsToolNumberingMidStream() {
        OpenAiSseAccumulator numberedFirst = accumulator(new ArrayList<>());
        numberedFirst.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"numbered\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":null}]}");

        assertThrows(AiProviderException.class,
                () -> numberedFirst.accept(unnumberedCall("implied", "Osaka")));

        OpenAiSseAccumulator impliedFirst = accumulator(new ArrayList<>());
        impliedFirst.accept(unnumberedCall("implied", "Osaka"));

        assertThrows(AiProviderException.class, () -> impliedFirst.accept(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"numbered\","
                        + "\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":null}]}"));
    }

    @Test
    void refusesMoreUnnumberedCallsThanAPositionExistsFor() {
        OpenAiSseAccumulator accumulator = accumulator(new ArrayList<>());
        for (int call = 0; call < 64; call++) {
            accumulator.accept(unnumberedCall("call_" + call, "Osaka"));
        }

        assertThrows(AiProviderException.class,
                () -> accumulator.accept(unnumberedCall("call_64", "Osaka")));
    }

    @Test
    void publishesNoPartOfAnEventRefusedForChangingItsNumbering() {
        List<String> deltas = new ArrayList<>();
        OpenAiSseAccumulator accumulator = accumulator(deltas);
        accumulator.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"numbered\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":null}]}");

        assertThrows(AiProviderException.class, () -> accumulator.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"words the reader must never see\","
                        + "\"tool_calls\":[{\"id\":\"implied\",\"function\":"
                        + "{\"name\":\"b\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":null}]}"));

        assertEquals(List.of(), deltas);
    }

    @Test
    void publishesNoPartOfAnEventWhoseToolCallsAreRefused() {
        List<String> deltas = new ArrayList<>();
        OpenAiSseAccumulator accumulator = accumulator(deltas);

        assertThrows(AiProviderException.class, () -> accumulator.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"words the reader must never see\","
                        + "\"tool_calls\":[{\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":null}]}"));

        assertEquals(List.of(), deltas);
    }

    private static String unnumberedCall(String id, String city) {
        return "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"" + city
                + "\\\"}\"}}]},\"finish_reason\":null}]}";
    }

    /**
     * The captured Gemini thought stream: flagged deltas carry the reasoning with an opening tag
     * on the first, and the closing tag arrives glued to the front of the first answer chunk.
     * The reasoning must come out whole and untagged, the text must hold only the answer, and the
     * requester's stream must never have seen a word of the thought.
     */
    @Test
    void routesFlaggedThoughtDeltasToReasoningAndPublishesNoneOfThem() {
        List<String> deltas = new ArrayList<>();
        OpenAiSseAccumulator accumulator = accumulator(deltas);

        accumulator.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\","
                + "\"content\":\"<thought>Compare the \","
                + "\"extra_content\":{\"google\":{\"thought\":true}}},\"finish_reason\":null}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\","
                + "\"content\":\"two deals.\","
                + "\"extra_content\":{\"google\":{\"thought\":true}}},\"finish_reason\":null}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\","
                + "\"content\":\"</thought>The first deal\"},\"finish_reason\":null}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"role\":\"assistant\","
                + "\"content\":\" is colder.\"},\"finish_reason\":\"stop\"}]}");
        accumulator.accept("[DONE]");

        AiCompletionResult result = accumulator.finish();

        assertEquals("The first deal is colder.", result.text());
        assertEquals("Compare the two deals.", result.reasoning());
        assertEquals(List.of("The first deal", " is colder."), deltas);
    }

    /**
     * A thought flag that is not a boolean must refuse the event before anything publishes: read
     * as false it would route the model's private reasoning into the requester's answer stream.
     */
    @Test
    void refusesANonBooleanThoughtFlagBeforePublishingAnything() {
        List<String> deltas = new ArrayList<>();
        OpenAiSseAccumulator accumulator = accumulator(deltas);

        assertThrows(AiProviderException.class, () -> accumulator.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"private thought text\","
                        + "\"extra_content\":{\"google\":{\"thought\":\"true\"}}},"
                        + "\"finish_reason\":null}]}"));

        assertEquals(List.of(), deltas);
    }

    /** A stream with no thought flags anywhere behaves exactly as it always did. */
    @Test
    void anUnflaggedStreamIsUntouchedByThoughtRouting() {
        List<String> deltas = new ArrayList<>();
        OpenAiSseAccumulator accumulator = accumulator(deltas);

        accumulator.accept("{\"choices\":[{\"delta\":{\"content\":\"Plain \"},"
                + "\"finish_reason\":null}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"content\":\"answer.\"},"
                + "\"finish_reason\":\"stop\"}]}");
        accumulator.accept("[DONE]");

        AiCompletionResult result = accumulator.finish();

        assertEquals("Plain answer.", result.text());
        assertEquals("", result.reasoning());
        assertEquals(List.of("Plain ", "answer."), deltas);
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
