package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Authority;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Execution;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Inverse;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Outcome;
import tools.jackson.databind.node.ObjectNode;

/**
 * A declared tool's undo receives the inverse extras its write recorded, exactly.
 *
 * <p>The framework persists {@code Inverse.extra} into the stored undo record while it executes the
 * write and rebuilds it from that record before calling {@code undo}, offering the tool none of the
 * keys the framework owns. The shapes an extra may hold are the ones that survive the stored JSON
 * unchanged, and anything else is refused when the inverse is built, so what a tool puts in is what
 * it gets back.
 */
class AiAssistantWriteInverseRoundTripTest extends AbstractAiAssistantWriteToolTest {

    @Test
    void undoReceivesTheExtrasTheWriteRecorded() throws Exception {
        Map<String, Object> recorded = Map.of("tagId", 7, "tag", "Priority");
        AtomicReference<Inverse> undone = new AtomicReference<>();
        AiAssistantWriteToolService service = service(List.of(
                recordingExtras(recorded, undone), stageTool()));
        executeAndStore(service);

        service.undo(TURN.sessionId(), TOOL_CALL_ID);

        assertEquals(recorded, undone.get().extra());
        assertEquals("task", undone.get().entityKind());
        assertEquals(74, undone.get().entityId());
    }

    @Test
    void undoReceivesNestedAndBooleanExtrasButNoKeyTheFrameworkOwns() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "tag");
        source.put("id", 5);
        source.put("status", "kept");
        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("tagId", 7);
        recorded.put("pinned", true);
        recorded.put("label", "");
        recorded.put("source", source);
        AtomicReference<Inverse> undone = new AtomicReference<>();
        AiAssistantWriteToolService service = service(List.of(
                recordingExtras(recorded, undone), stageTool()));
        executeAndStore(service);
        ObjectNode stored = (ObjectNode) objectMapper.readTree(storedToolCall.getResultJson());
        ((ObjectNode) stored.get("undo")).put("undoneAt", "2026-03-06T14:59:30Z");
        storedToolCall.setResultJson(objectMapper.writeValueAsString(stored));

        service.undo(TURN.sessionId(), TOOL_CALL_ID);

        assertEquals(recorded, undone.get().extra());
    }

    @Test
    void aStoredExtraOutsideTheAdmittedShapesIsRefusedBeforeTheToolRuns() throws Exception {
        AtomicReference<Inverse> undone = new AtomicReference<>();
        AiAssistantWriteToolService service = service(List.of(
                recordingExtras(Map.of("tagId", 7), undone), stageTool()));
        executeAndStore(service);
        String executed = storedToolCall.getResultJson();

        List<String> tamperedValues =
                List.of("7.5", "7.0", "9999999999", "null", "[7]", "{\"id\":[7]}");
        for (String tampered : tamperedValues) {
            ObjectNode stored = (ObjectNode) objectMapper.readTree(executed);
            ((ObjectNode) stored.get("undo")).set("tagId", objectMapper.readTree(tampered));
            storedToolCall.setResultJson(objectMapper.writeValueAsString(stored));

            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> service.undo(TURN.sessionId(), TOOL_CALL_ID),
                    tampered);
            assertEquals("Assistant tool metadata is invalid", refused.getMessage(), tampered);
        }
        assertNull(undone.get());
        verify(chatMapper, never())
                .updateExecutedToolResult(anyInt(), anyInt(), anyString(), anyInt());
    }

    @Test
    void anInverseRefusesAnExtraTheStoredRecordCouldNotHandBackExactly() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("tagId", null);
        Map<Object, Object> numericKey = new LinkedHashMap<>();
        numericKey.put(7, "tag");
        List<Map<String, Object>> refused = List.of(
                Map.of("tagId", 7L),
                Map.of("tagId", 7.0),
                Map.of("tagId", 7.0f),
                Map.of("tagId", (short) 7),
                Map.of("tagId", new BigDecimal("7")),
                Map.of("tagId", List.of(7)),
                withNull,
                Map.of("source", Map.of("id", 5L)));
        for (Map<String, Object> extra : refused) {
            IllegalStateException refusal = assertThrows(
                    IllegalStateException.class,
                    () -> new Inverse("tag", 31, "present:5", true, extra),
                    extra.toString());
            assertEquals(
                    "An assistant inverse value must be a string, an integer, a boolean or a map"
                            + " of them: " + (extra.containsKey("source") ? "source.id" : "tagId"),
                    refusal.getMessage());
        }
        IllegalStateException refusal = assertThrows(
                IllegalStateException.class,
                () -> new Inverse("tag", 31, "present:5", true, Map.of("source", numericKey)));
        assertEquals("An assistant inverse key must be a string: source.7", refusal.getMessage());
    }

    @Test
    void anAdmittedExtraIsCopiedSoTheToolCannotChangeItAfterRecording() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", 5);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", source);
        Inverse inverse = new Inverse("tag", 31, "present:5", true, extra);

        source.put("id", 6);
        extra.put("tagId", 7);

        assertEquals(Map.of("source", Map.of("id", 5)), inverse.extra());
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((Map<?, ?>) inverse.extra().get("source")).clear());
    }

    /**
     * A test-only create_task whose inverse also records {@code extra} and whose undo captures the
     * inverse the framework hands back.
     */
    private AiAssistantCreateTaskWriteTool recordingExtras(
            Map<String, Object> extra, AtomicReference<Inverse> undone) {
        return new AiAssistantCreateTaskWriteTool(taskService, dateResolver, objectMapper) {
            @Override
            public Outcome apply(Execution execution) {
                Outcome applied = super.apply(execution);
                Inverse inverse = applied.inverse();
                return new Outcome(
                        applied.data(),
                        new Inverse(
                                inverse.entityKind(),
                                inverse.entityId(),
                                inverse.fingerprint(),
                                inverse.available(),
                                extra),
                        applied.readBack());
            }

            @Override
            public void undo(Authority authority, Inverse inverse) {
                undone.set(inverse);
            }
        };
    }

    /** Executes one create_task as tool call 29 and stores its result as the executed row. */
    private void executeAndStore(AiAssistantWriteToolService service) throws Exception {
        createdTasksGetId74();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "person", 31);
        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });
        storedToolCall.setStatus("executed");
        storedToolCall.setResultJson(capturedExecutedResult());
    }
}
