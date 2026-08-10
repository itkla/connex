package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.mappers.AiChatMapper;

/** Proves stale restriction epochs cannot commit assistant answers or read-tool payloads. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiChatRestrictionEpochCommitTest extends AbstractServiceTest {
    @Autowired private AiChatTurnPersistenceService persistenceService;
    @Autowired private AiRestrictionEpoch restrictionEpoch;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUpDurableFixtures() {
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                    "DELETE FROM ai_chat_session WHERE workspace_id = ? AND created_by_user_id = ?",
                    workspace.getId(), currentUser.getId());
            jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                    workspace.getId(), currentUser.getId());
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
    }

    @Test
    void epochBumpBeforeFinalCommitRejectsTheAnswerWithoutTerminalizingTheTurn() {
        AiChatSession session = session("Final restriction fence");
        AiChatQueuedTurn turn = queuedTurn(session, "Summarize my pipeline");
        assertTrue(persistenceService.markRunning(turn));
        restrictionEpoch.bump(workspace.getId());

        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> persistenceService.resolve(
                        turn,
                        "Pipeline is healthy.",
                        "{\"citations\":[],\"resources\":[]}",
                        3,
                        5));

        List<AiChatMessage> messages = chatMapper.listMessages(
                workspace.getId(), session.getId(), 100, 0);
        AiChatTurn stored = chatMapper.getTurnById(
                workspace.getId(), session.getId(), turn.turnId());
        assertEquals("restrictions_changed", failure.detailReason());
        assertEquals(1, messages.size());
        assertEquals("user", messages.getFirst().getAuthorKind());
        assertEquals("running", stored.getStatus());
        assertNull(stored.getTerminalReason());
    }

    @Test
    void epochBumpBeforeFinishToolRejectsAndRetainsTheProposedToolState() {
        AiChatSession session = session("Tool restriction fence");
        AiChatQueuedTurn turn = queuedTurn(session, "Read restricted content");
        assertTrue(persistenceService.markRunning(turn));
        int toolCallId = persistenceService.proposeTool(
                turn, 1, "get_record", "{\"handle\":\"r1\"}");
        restrictionEpoch.bump(workspace.getId());

        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> persistenceService.finishTool(
                        turn, toolCallId, "executed", "{\"restricted\":\"text\"}"));

        AiChatToolCall stored = chatMapper.getToolCallById(
                workspace.getId(), turn.userMessageId(), toolCallId);
        assertEquals("restrictions_changed", failure.detailReason());
        assertEquals("proposed", stored.getStatus());
        assertNull(stored.getResultJson());
    }

    private AiChatSession session(String title) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle(title + " " + unique());
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private AiChatQueuedTurn queuedTurn(AiChatSession session, String content) {
        long expectedEpoch = restrictionEpoch.current(workspace.getId());
        return persistenceService.queue(
                session.getId(),
                new AiChatTurnCreateRequest(content, List.of()),
                expectedEpoch);
    }
}
