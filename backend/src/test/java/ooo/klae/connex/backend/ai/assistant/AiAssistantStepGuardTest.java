package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

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
        assertEquals("exclusive_step", guard.rejectionReason(objectMapper.readTree(
                "{\"tool\":null,\"final\":null}")));
        assertEquals("tool_arguments", guard.rejectionReason(objectMapper.readTree(
                "{\"tool\":{\"name\":\"get_record\",\"args\":{\"handle\":\"raw-id\"}},"
                        + "\"final\":null}")));
    }

    @Test
    void issuedPlaceholderGuardRejectsBareBodiesButAcceptsBracedTokens() throws Exception {
        var issuedGuard = guard.forIssuedPlaceholders(Set.of("{{P1}}"));

        assertEquals("bare_placeholder", issuedGuard.rejectionReason(objectMapper.readTree(
                "{\"tool\":null,\"final\":{\"text\":\"Ask P1\",\"citations\":[]}}")));
        assertTrue(issuedGuard.permits(objectMapper.readTree(
                "{\"tool\":null,\"final\":{\"text\":\"Ask {{ P1 }}\",\"citations\":[]}}")));
        assertTrue(issuedGuard.permits(objectMapper.readTree(
                "{\"tool\":null,\"final\":{\"text\":\"Ask P10\",\"citations\":[]}}")));
    }
}
