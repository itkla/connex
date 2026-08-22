package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.dto.AiChatSessionDto;

class AiChatTranscriptProjectionTest {

    @Test
    void durableSummaryProjectsOnlyVisibleMarkerWithoutSummaryText() {
        AiChatMessage summary = new AiChatMessage();
        summary.setId(17);
        summary.setSessionId(9);
        summary.setSeq(41);
        summary.setAuthorKind("system");
        summary.setContent("Early facts that must remain server-only");

        AiChatMessageDto projected = AiChatMessageDto.from(
                summary, List.of(), List.of(), null, null);

        assertTrue(projected.isHistorySummarized());
        assertEquals("", projected.getContent());
    }

    @Test
    void sessionProjectionKeepsSummaryMarkerVisibleOutsideMessagePage() {
        AiChatSession session = new AiChatSession();
        session.setId(9);
        session.setHistorySummarized(true);

        assertTrue(AiChatSessionDto.from(session).isHistorySummarized());
    }

    @Test
    void withheldAssistantProjectionDropsEveryGeneratedContentSurface() {
        AiChatMessage message = new AiChatMessage();
        message.setId(17);
        message.setSessionId(9);
        message.setSeq(41);
        message.setAuthorKind("assistant");
        message.setContent("Restricted generated answer");

        AiChatMessageDto projected = AiChatMessageDto.from(
                message,
                List.of(),
                List.of("Review recent activity"),
                null,
                "Compared restricted signals",
                true);

        assertTrue(projected.isContentWithheld());
        assertEquals("", projected.getContent());
        assertNull(projected.getReasoning());
        assertEquals(List.of(), projected.getCitations());
        assertEquals(List.of(), projected.getSuggestions());
    }
}
