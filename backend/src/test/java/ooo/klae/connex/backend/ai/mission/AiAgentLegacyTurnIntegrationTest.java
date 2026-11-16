package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.services.AiAssistantService;
import tools.jackson.databind.ObjectMapper;

/** Captured ordinary callbacks must remain stale after durable cleanup completes and unblocks. */
class AiAgentLegacyTurnIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiChatTurnPersistenceService turns;
    @Autowired private AiAgentRunPersistenceService missions;
    @Autowired private AiAssistantService sessions;
    @Autowired private AiProperties properties;
    @Autowired private AiRestrictionEpoch epoch;
    @Autowired private ObjectMapper json;

    @Test
    void ordinarySummaryCannotReappearAfterCompletedDsrPurge() {
        AiChatQueuedTurn captured = runningTainted();
        finish(invalidate());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        AiChatQueuedTurn fresh = running(captured.sessionId());
        AiChatQueuedTurn stale = withOriginalPrivacy(fresh, captured);
        assertEquals("restrictions_changed", assertThrows(AiAssistantLoopException.class,
                () -> turns.upsertHistorySummary(stale, null, 0,
                        "Captured summary", "{\"kind\":\"history_summary\",\"throughSeq\":1}", 1, 1)).detailReason());
        assertNull(chatMapper.getHistorySummary(workspace.getId(), captured.sessionId(), 1L));
        assertEquals(2, residuals(captured.sessionId()));
        assertDoesNotThrow(() -> turns.upsertHistorySummary(fresh, null, 0, "Current summary",
                "{\"kind\":\"history_summary\",\"throughSeq\":1}", 1, 1));
    }

    @Test
    void ordinaryAnswerCannotReappearAfterCompletedDsrPurge() {
        AiChatQueuedTurn captured = runningTainted();
        finish(invalidate());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        AiChatQueuedTurn fresh = running(captured.sessionId());
        AiChatQueuedTurn stale = withOriginalPrivacy(fresh, captured);
        assertEquals("restrictions_changed", assertThrows(AiAssistantLoopException.class,
                () -> turns.resolve(stale, "Captured answer", "{}", 1, 1)).detailReason());
        assertNull(chatMapper.getLatestAnswer(workspace.getId(), captured.sessionId(), 1L));
        assertEquals(2, residuals(captured.sessionId()));
        assertTrue(turns.resolve(fresh, "Current answer", "{}", 1, 1));
    }

    @ParameterizedTest
    @MethodSource("derivedWriters")
    void allDerivedWritersRejectStaleTokens(String writer) {
        AiChatQueuedTurn captured = runningTainted();
        finish(invalidate());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        AiChatQueuedTurn fresh = running(captured.sessionId());
        AiChatQueuedTurn stale = withOriginalPrivacy(fresh, captured);
        int toolId = turns.proposeTool(fresh, 1, 0, "get_record", "{}");
        Consumer<AiChatQueuedTurn> callback = switch (writer) {
            case "title" -> turn -> turns.applyGeneratedTitle(turn, "Captured title");
            case "partial" -> turn -> turns.appendPartialBatch(turn, 0, "Captured partial");
            case "reset" -> turn -> turns.resetPartialContent(turn, 0);
            case "progress" -> turn -> turns.applySkill(turn, "record-review", "1");
            case "arguments" -> turn -> turns.proposeTool(turn, 2, 0, "get_record", "{}");
            case "result" -> turn -> turns.finishTool(turn, toolId, "executed", "{\"text\":\"Captured result\"}");
            case "failure" -> turn -> turns.failTool(turn, toolId, "{\"text\":\"Captured error\"}");
            default -> throw new AssertionError(writer);
        };
        if ("title".equals(writer)) {
            turns.resolve(fresh, "Current answer", "{}", 1, 1);
        }
        assertEquals("restrictions_changed", assertThrows(AiAssistantLoopException.class,
                () -> callback.accept(stale)).detailReason());
        assertEquals("proposed", chatMapper.getToolCallBySession(
                workspace.getId(), captured.sessionId(), toolId, 1L).getStatus());
        assertFalse(chatMapper.getPrivacySession(workspace.getId(), captured.sessionId()).getTitle()
                .contains("Captured"));
        assertEquals("title".equals(writer) ? "Current answer" : null,
                turns.readTurn(fresh.sessionId(), fresh.turnId()).getPartialContent());
        assertDoesNotThrow(() -> callback.accept(fresh));
    }

    @Test
    void preTaintDerivedJobCannotPublishAfterTaint() {
        int session = session();
        var captured = running(session);
        turns.resolve(captured, "Earlier answer", "{}", 1, 1);
        assertNull(captured.privacyToken().taintSeqAtAdmission());
        createMission(session);
        assertThrows(RuntimeException.class, () -> turns.applyGeneratedTitle(captured, "Stale generated title"));
        assertEquals("Original title", chatMapper.getPrivacySession(workspace.getId(), session).getTitle());
    }

    @Test
    void readersStayPrivateDuringPurgeAndAfterRestart() {
        var captured = runningTainted();
        int toolId = turns.proposeTool(captured, 1, 0, "get_record", "{}");
        turns.appendPartialBatch(captured, 0, "Partial evidence");
        turns.upsertHistorySummary(captured, null, 0, "Summary evidence",
                "{\"kind\":\"history_summary\",\"throughSeq\":1}", 1, 1);
        String operation = invalidate();
        privacyService.initialize(workspace.getId());
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        assertTrue(chatMapper.listMessages(workspace.getId(), captured.sessionId(), 100, 0, 1L).isEmpty());
        assertNull(chatMapper.getHistorySummary(workspace.getId(), captured.sessionId(), 1L));
        assertNull(chatMapper.getLatestAnswer(workspace.getId(), captured.sessionId(), 1L));
        assertNull(chatMapper.getToolCallById(workspace.getId(), captured.userMessageId(), toolId, 1L));
        assertNull(chatMapper.getToolCallBySession(workspace.getId(), captured.sessionId(), toolId, 1L));
        assertNull(sessions.get(captured.sessionId(), 1, 50).activeTurn());
        var turnProjection = turns.readTurn(captured.sessionId(), captured.turnId());
        assertNull(turnProjection.getPartialContent());
        assertNull(turnProjection.getScopeJson());
        finish(operation);
    }

    @Test
    void directAppendNeverReusesDeletedMissionSequences() {
        int session = session();
        createMission(session);
        finish(invalidate());
        AiChatMessageCreateRequest input = new AiChatMessageCreateRequest();
        input.setContent("Later direct input");
        var appended = sessions.appendMessage(session, input);
        assertEquals(2, appended.getSeq());
        finish(invalidate());
        assertTrue(chatMapper.listMessages(workspace.getId(), session, 100, 0, 1L).isEmpty());
        assertEquals(2, chatMapper.getPrivacySession(workspace.getId(), session).getMessageSeqHighWater());
    }

    private static Stream<String> derivedWriters() {
        return Stream.of("title", "partial", "reset", "progress", "arguments", "result", "failure");
    }

    private AiChatQueuedTurn withOriginalPrivacy(AiChatQueuedTurn fresh, AiChatQueuedTurn captured) {
        return new AiChatQueuedTurn(fresh.workspaceId(), fresh.userId(), fresh.sessionId(),
                fresh.turnId(), fresh.userMessageId(), fresh.userMessageSeq(), fresh.restrictionEpoch(),
                fresh.includePrivateNotes(), fresh.pageContext(), fresh.attachmentIds(), fresh.privacyMode(),
                fresh.streamed(), fresh.scope(), captured.privacyToken());
    }

    private AiChatQueuedTurn runningTainted() {
        int session = session();
        createMission(session);
        return running(session);
    }

    private AiChatQueuedTurn running(int session) {
        var turn = turns.queue(session, new AiChatTurnCreateRequest("Later input", List.of()),
                epoch.current(workspace.getId()), AiPrivacyMode.MASKED, true);
        turns.markRunning(turn, new AiRunLeaseGuard(properties.getRunLeaseTtl()));
        return turn;
    }

    private int session() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(1L);
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Original title");
        session.setTitleUserSet(false);
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session.getId();
    }

    private void createMission(int session) {
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        missions.create(session, request, "en", AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
    }

    private int residuals(int session) {
        Integer count = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND session_id=?) + (SELECT COUNT(*) FROM ai_chat_turn WHERE workspace_id=? AND session_id=?)",
                Integer.class, workspace.getId(), session, workspace.getId(), session);
        assertNotNull(count);
        return count;
    }
}
