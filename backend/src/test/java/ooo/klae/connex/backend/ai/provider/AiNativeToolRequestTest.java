package ooo.klae.connex.backend.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the replay invariants an adapter depends on before it serializes an assistant message.
 *
 * <p>The record is the last place a partially replayed step can be refused: an adapter emits one
 * assistant message per run of exchanges sharing a step, so a run with a gap in it would quietly
 * become a message carrying fewer {@code tool_calls} than the model emitted.
 */
class AiNativeToolRequestTest {

    private static final AiToolDefinition DEFINITION = new AiToolDefinition(
            "get_record",
            "Load one visible CRM record.",
            JsonMapper.builder().build().createObjectNode().put("type", "object"));

    @Test
    void groupsAStepsExchangesIntoOneContiguousAscendingRun() {
        AiNativeToolRequest request = new AiNativeToolRequest(
                List.of(DEFINITION),
                List.of(
                        exchange("call_1", 1, 0),
                        exchange("call_2", 2, 1),
                        exchange("call_3", 2, 2),
                        exchange("call_4", 3, 0)));

        assertEquals(4, request.exchanges().size());
    }

    @Test
    void refusesAStepWhoseCallOrdinalsSkipTheDroppedMiddleCall() {
        List<AiToolExchange> withAGap = List.of(
                exchange("call_1", 1, 1),
                exchange("call_2", 1, 3));

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> new AiNativeToolRequest(List.of(DEFINITION), withAGap));

        assertEquals("AI native tool exchange grouping is invalid", refused.getMessage());
    }

    @Test
    void refusesAStepThatMixesTheSoleCallOrdinalWithABatchedOne() {
        List<AiToolExchange> mixed = List.of(
                exchange("call_1", 1, 0),
                exchange("call_2", 1, 1));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AiNativeToolRequest(List.of(DEFINITION), mixed));
    }

    @Test
    void refusesAStepWhoseBatchedOrdinalsDoNotStartAtOne() {
        List<AiToolExchange> startingLate = List.of(
                exchange("call_1", 1, 2),
                exchange("call_2", 1, 3));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AiNativeToolRequest(List.of(DEFINITION), startingLate));
    }

    @Test
    void refusesExchangesWhoseStepsGoBackwards() {
        List<AiToolExchange> outOfOrder = List.of(
                exchange("call_1", 2, 0),
                exchange("call_2", 1, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AiNativeToolRequest(List.of(DEFINITION), outOfOrder));
    }

    @Test
    void refusesOneStepSplitAcrossTwoRuns() {
        List<AiToolExchange> split = List.of(
                exchange("call_1", 1, 0),
                exchange("call_2", 2, 0),
                exchange("call_3", 1, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AiNativeToolRequest(List.of(DEFINITION), split));
    }

    @Test
    void keepsRefusingADuplicateCallIdentifierAndAnUndeclaredToolName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AiNativeToolRequest(
                        List.of(DEFINITION),
                        List.of(exchange("call_1", 1, 0), exchange("call_1", 2, 0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AiNativeToolRequest(
                        List.of(DEFINITION),
                        List.of(new AiToolExchange(
                                new AiToolCall("call_1", "list_tasks", "{}"),
                                "CRM_DATA_BEGIN\n{}\nCRM_DATA_END",
                                1,
                                0))));
    }

    @Test
    void refusesAnExchangeWithoutAStepOrWithAnOrdinalOutsideTheCallCeiling() {
        AiToolCall call = new AiToolCall("call_1", "get_record", "{}");

        assertThrows(
                IllegalArgumentException.class,
                () -> new AiToolExchange(call, "CRM_DATA_BEGIN\n{}\nCRM_DATA_END", 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AiToolExchange(call, "CRM_DATA_BEGIN\n{}\nCRM_DATA_END", 1, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AiToolExchange(
                        call,
                        "CRM_DATA_BEGIN\n{}\nCRM_DATA_END",
                        1,
                        AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS + 1));
    }

    /**
     * The per-step call bound is the request's own, not something each reader re-derives.
     *
     * <p>Both the adapter that serializes {@code parallel_tool_calls} and the parser that refuses
     * an over-delivering response read it here, so a request carrying an impossible bound is
     * refused where it is built rather than acted on twice.
     */
    @Test
    void boundsTheParallelCallCeilingToWhatOneStepCanEverCarry() {
        assertEquals(
                1,
                new AiNativeToolRequest(List.of(DEFINITION), List.of()).maxParallelCalls());
        assertEquals(
                AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS,
                new AiNativeToolRequest(
                        List.of(DEFINITION),
                        List.of(),
                        null,
                        false,
                        AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS).maxParallelCalls());

        for (int invalid : new int[] {
                0, -1, AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS + 1}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AiNativeToolRequest(
                            List.of(DEFINITION), List.of(), null, false, invalid),
                    "expected a refusal for maxParallelCalls " + invalid);
        }
    }

    private static AiToolExchange exchange(String id, int step, int callOrdinal) {
        return new AiToolExchange(
                new AiToolCall(id, "get_record", "{\"handle\":\"r1\"}"),
                "CRM_DATA_BEGIN\n{\"kind\":\"tool_result\"}\nCRM_DATA_END",
                step,
                callOrdinal);
    }
}
