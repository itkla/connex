package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiChatToolCall;

class AiAssistantProposalExpiryTest {
    @Test
    void reviewDeadlineUsesOriginalCreationAndStrictBoundary() {
        AiChatToolCall call = new AiChatToolCall();
        call.setCreatedAt("2026-10-01 10:11:12.123456");
        call.setUpdatedAt("2026-10-02 10:11:12.654321");
        Instant deadline = AiAssistantProposalExpiry.reviewExpiresAt(call.getCreatedAt());
        assertEquals(Instant.parse("2026-10-04T10:11:12.123456Z"), deadline);
        assertFalse(AiAssistantProposalExpiry.isExpired(deadline, deadline.minusNanos(1_000)));
        assertTrue(AiAssistantProposalExpiry.isExpired(deadline, deadline));
        assertTrue(AiAssistantProposalExpiry.isExpired(deadline, deadline.plusNanos(1_000)));
        call.setUpdatedAt("2026-10-08 10:11:12.123456");
        call.setExecutedAt("2026-10-08 10:11:12.123456");
        for (int read = 0; read < 3; read++) {
            assertEquals(deadline, AiAssistantProposalExpiry.reviewExpiresAt(call.getCreatedAt()));
        }
        AiChatToolCall reread = new AiChatToolCall();
        reread.setCreatedAt(call.getCreatedAt());
        assertEquals(deadline, AiAssistantProposalExpiry.reviewExpiresAt(reread.getCreatedAt()));
    }

    @Test
    void pendingCutoffRetainsMicrosecondsAndUsesTheSameStrictBoundary() {
        Instant now = Instant.parse("2026-10-04T10:11:12.123456999Z");
        assertEquals(LocalDateTime.parse("2026-10-01T10:11:12.123456"),
                AiAssistantProposalExpiry.pendingCreatedAfter(now));
    }

    @Test
    void missingOrMalformedCreationCannotArmAProposal() {
        for (String createdAt : new String[] {null, "", "2026-02-31 01:00:00", "invalid"}) {
            assertNull(AiAssistantProposalExpiry.reviewExpiresAt(createdAt));
        }
    }
}
