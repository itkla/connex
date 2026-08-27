package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.dto.AiChatProgressItemDto;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatTurnDto;

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
                summary, List.of(), List.of(), null);

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
                true);

        assertTrue(projected.isContentWithheld());
        assertEquals("", projected.getContent());
        assertEquals(List.of(), projected.getCitations());
        assertEquals(List.of(), projected.getSuggestions());
    }

    @Test
    void streamedPartialContentIsVisibleOnlyToTheTurnRequester() {
        AiChatTurn turn = new AiChatTurn();
        turn.setId(17);
        turn.setSessionId(9);
        turn.setRequestedByUserId(11);
        turn.setStatus("running");
        turn.setStreamed(true);
        turn.setPartialContent("Private partial answer");

        List<AiChatProgressItemDto> progress = List.of(
                new AiChatProgressItemDto(1, "records", "complete", 4, true));

        AiChatTurnDto requester = AiChatTurnDto.from(turn, progress, 11);
        AiChatTurnDto collaborator = AiChatTurnDto.from(turn, progress, 12);
        assertEquals(
                "Private partial answer",
                requester.partialContent());
        assertEquals(4, requester.progress().getFirst().count());
        assertTrue(requester.progress().getFirst().truncated());
        assertNull(collaborator.partialContent());
        assertNull(collaborator.progress().getFirst().count());
        assertFalse(collaborator.progress().getFirst().truncated());
        assertTrue(requester.requestedByCurrentUser());
        assertFalse(collaborator.requestedByCurrentUser());
    }

    @Test
    void anAnonymousReaderIsNeverTreatedAsTheTurnRequester() {
        AiChatTurn turn = new AiChatTurn();
        turn.setId(17);
        turn.setSessionId(9);
        turn.setRequestedByUserId(11);
        turn.setStatus("running");

        assertFalse(AiChatTurnDto.from(turn, List.of(), null).requestedByCurrentUser());
    }

    @Test
    void aFailedTurnKeepsItsPartialAnswerForTheRequesterOnly() {
        AiChatTurn turn = new AiChatTurn();
        turn.setId(17);
        turn.setSessionId(9);
        turn.setRequestedByUserId(11);
        turn.setStatus("failed");
        turn.setTerminalReason("provider_error");
        turn.setStreamed(true);
        turn.setPartialContent("Atlas renewal is slipping");

        assertEquals(
                "Atlas renewal is slipping",
                AiChatTurnDto.from(turn, List.of(), 11).partialContent());
        assertEquals("failed", AiChatTurnDto.from(turn, List.of(), 11).status());
        assertNull(AiChatTurnDto.from(turn, List.of(), 12).partialContent());

        turn.setStatus("resolved");
        turn.setTerminalReason(null);
        assertNull(AiChatTurnDto.from(turn, List.of(), 11).partialContent());
    }
}
