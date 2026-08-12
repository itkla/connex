package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AiAssistantSummaryGuardTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiAssistantSummaryGuard guard = new AiAssistantSummaryGuard();

    @Test
    void acceptsBoundedSafeSummary() throws Exception {
        assertNull(guard.rejectionReason(objectMapper.readTree(
                "{\"summary\":\"The user prefers quarterly planning.\"}")));
    }

    @Test
    void rejectsContactDetailsRawIdsAndBarePlaceholders() throws Exception {
        assertEquals("summary_email_address", guard.rejectionReason(objectMapper.readTree(
                "{\"summary\":\"Contact ada@example.com.\"}")));
        assertEquals("summary_raw_record_id", guard.rejectionReason(objectMapper.readTree(
                "{\"summary\":\"Person id: 42.\"}")));
        assertEquals("summary_placeholder", guard.rejectionReason(objectMapper.readTree(
                "{\"summary\":\"The user discussed P1.\"}")));
    }
}
