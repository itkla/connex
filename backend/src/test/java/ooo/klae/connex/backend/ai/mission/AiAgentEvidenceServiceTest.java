package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult;
import tools.jackson.databind.ObjectMapper;

class AiAgentEvidenceServiceTest {
    private final AiAgentEvidenceService service = new AiAgentEvidenceService(new ObjectMapper());

    @Test
    void canonicalVersionIgnoresHandlesMapOrderAndBulkReadEnvelope() {
        var first = new AiAssistantToolResult(Map.of("name", "Record label", "handle", "r1"), List.of());
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("handle", "r99");
        reordered.put("name", "Record label");
        var bulk = new AiAssistantToolResult(Map.of("records", List.of(reordered)), List.of());
        assertEquals(service.reference("person", 7, "record", 0, first),
                service.reference("person", 7, "record", 0, bulk));
    }

    @Test
    void canonicalVersionIncludesActualBoundedDataAndIdentifierProvenance() {
        var first = new AiAssistantToolResult(Map.of("subject", "Reviewed"), List.of());
        var changed = new AiAssistantToolResult(Map.of("subject", "Changed"), List.of());
        var provenance = new AiAssistantToolResult(first.data(), List.of(
                new AiAssistantToolResult.Identifier("member", "Member label", "/subject")));
        assertNotEquals(service.reference("person", 7, "activities", 4, first),
                service.reference("person", 7, "activities", 4, changed));
        assertNotEquals(service.reference("person", 7, "activities", 4, first),
                service.reference("person", 7, "activities", 4, provenance));
        assertNotEquals(service.reference("person", 7, "activities", 4, first),
                service.reference("person", 7, "activities", 5, first));
    }

    @Test
    void changedAndInaccessibleEvidenceStopBeforeContextCanDispatch() {
        var first = new AiAssistantToolResult(Map.of("name", "Original"), List.of());
        var reference = service.reference("person", 7, "record", 0, first);
        assertEquals("source_changed", assertThrows(AiAssistantLoopException.class,
                () -> service.revalidate(List.of(reference), ignored ->
                        new AiAssistantToolResult(Map.of("name", "Changed"), List.of()))).terminalReason());
        assertEquals("access_lost", assertThrows(AiAssistantLoopException.class,
                () -> service.revalidate(List.of(reference), ignored -> {
                    throw AiAssistantLoopException.accessRevoked("unavailable");
                })).terminalReason());
        assertEquals(List.of(first), service.revalidate(List.of(reference), ignored -> first));
    }
}
