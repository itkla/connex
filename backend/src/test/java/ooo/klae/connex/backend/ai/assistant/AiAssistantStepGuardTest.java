package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class AiAssistantStepGuardTest {
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AiAssistantStepGuard guard = new AiAssistantStepGuard(
            new AiAssistantToolCatalog());

    @Test
    void acceptsExactlyOneKnownToolOrFinalAndRejectsUnknownFields() throws Exception {
        assertTrue(guard.permits(objectMapper.readTree(
                "{\"tool\":{\"name\":\"get_record\",\"args\":{\"handle\":\"r1\"}},\"final\":null}")));
        assertTrue(guard.permits(objectMapper.readTree(
                "{\"tool\":null,\"final\":{\"text\":\"Ready\",\"citations\":[\"r1\"]}}")));
        assertFalse(guard.permits(objectMapper.readTree(
                "{\"tool\":null,\"final\":null}")));
        assertFalse(guard.permits(objectMapper.readTree(
                "{\"tool\":{\"name\":\"get_record\",\"args\":{\"handle\":\"r1\"}},"
                        + "\"final\":{\"text\":\"Ready\",\"citations\":[]}}")));
        assertFalse(guard.permits(objectMapper.readTree(
                "{\"tool\":{\"name\":\"delete_record\",\"args\":{}},\"final\":null}")));
        assertFalse(guard.permits(objectMapper.readTree(
                "{\"tool\":{\"name\":\"get_record\",\"args\":{\"handle\":\"987654321\"}},"
                        + "\"final\":null}")));
        assertFalse(guard.permits(objectMapper.readTree(
                "{\"tool\":null,\"final\":{\"text\":\"Ready\",\"citations\":[],\"extra\":1}}")));
    }
}
