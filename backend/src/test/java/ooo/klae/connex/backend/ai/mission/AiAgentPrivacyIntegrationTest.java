package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import tools.jackson.databind.ObjectMapper;

class AiAgentPrivacyIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiChatTurnPersistenceService turns;
    @Autowired private AiRestrictionEpoch epoch;
    @Autowired private AiProperties properties;
    @Autowired private ooo.klae.connex.backend.services.AiAssistantService assistant;
    @Autowired private AiAgentRunPersistenceService missions;
    @Autowired private ObjectMapper json;

    @Test
    void secondPurgeErasesPostPurgeOrdinaryInput() {
        AiChatSession session = session();
        completedTurn(session.getId(), "Earlier input", "Earlier answer");
        assertEquals(2, counter(session.getId()));
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1",
                "Review this record", new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        var mission = missions.create(session.getId(), request, "en",
                AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
        assertEquals("queued", mission.getStatus());
        AiChatMessage goal = chatMapper.getMissionMessage(workspace.getId(), mission.getId(), "goal");
        assertNotNull(goal);
        assertEquals(3, goal.getSeq());
        assertEquals(3, chatMapper.getPrivacySession(workspace.getId(), session.getId()).getMissionTaintSeq());
        assertEquals(3, counter(session.getId()));
        String first = invalidate();
        finish(first);
        assertEquals(List.of(1), chatMapper.listMessages(workspace.getId(), session.getId(), 100, 0)
                .stream().map(AiChatMessage::getSeq).toList());
        assertEquals("Earlier input", chatMapper.listMessages(workspace.getId(), session.getId(), 100, 0).getFirst().getContent());
        assertEquals(3, chatMapper.getPrivacySession(workspace.getId(), session.getId()).getMissionTaintSeq());
        assertEquals(3, counter(session.getId()));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        privacyService.initialize(workspace.getId());
        assertEquals(3, counter(session.getId()));
        completedTurn(session.getId(), "Later input", "Later answer");
        List<AiChatMessage> after = chatMapper.listMessages(workspace.getId(), session.getId(), 100, 0);
        assertEquals(List.of(1, 4, 5), after.stream().map(AiChatMessage::getSeq).toList());
        long currentGeneration = privacyMapper.getBarrier(workspace.getId()).getGeneration();
        assertEquals(currentGeneration, after.get(1).getPrivacyGeneration());
        assertEquals(currentGeneration, after.get(2).getPrivacyGeneration());
        assertEquals(5, counter(session.getId()));
        String second = invalidate();
        advanceUntilSessionPhase(second, "messages");
        jdbc.update("UPDATE ai_agent_privacy_work SET last_message_id=? WHERE workspace_id=? AND operation_key=?",
                after.get(1).getId(), workspace.getId(), second);
        advanceUntilSessionPhase(second, "title");
        assertEquals(List.of(1, 4), jdbc.queryForList(
                "SELECT seq FROM ai_chat_message WHERE workspace_id=? AND session_id=? ORDER BY seq",
                Integer.class, workspace.getId(), session.getId()));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), currentGeneration - 1));
        assertEquals(1, privacyMapper.residualCount(workspace.getId(), currentGeneration));
        jdbc.update("UPDATE ai_agent_privacy_work SET phase='verify',current_session_id=NULL,session_phase=NULL WHERE workspace_id=? AND operation_key=?",
                workspace.getId(), second);
        assertFalse(privacyService.batch(workspace.getId(), second));
        var refused = privacyMapper.getWork(workspace.getId(), second);
        assertNotNull(refused);
        assertEquals("verify", refused.getPhase());
        assertEquals(1L, refused.getLastResidualCount());
        assertNull(refused.getCompletedAt());
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        jdbc.update("UPDATE ai_agent_privacy_work SET phase='sessions',current_session_id=?,session_phase='messages',last_message_id=0 WHERE workspace_id=? AND operation_key=?",
                session.getId(), workspace.getId(), second);
        finish(second);
        List<AiChatMessage> remaining = chatMapper.listMessages(workspace.getId(), session.getId(), 100, 0);
        assertEquals(1, remaining.size());
        assertEquals("Earlier input", remaining.getFirst().getContent());
        assertEquals(1, remaining.getFirst().getSeq());
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), currentGeneration));
        assertEquals(3, chatMapper.getPrivacySession(workspace.getId(), session.getId()).getMissionTaintSeq());
        assertEquals(5, counter(session.getId()));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        var append = new ooo.klae.connex.backend.dto.AiChatMessageCreateRequest();
        append.setContent("Direct appended later input");
        assistant.appendMessage(session.getId(), append);
        assertEquals(6, counter(session.getId()));
        String third = invalidate();
        finish(third);
        assertEquals(List.of(1), chatMapper.listMessages(workspace.getId(), session.getId(), 100, 0)
                .stream().map(AiChatMessage::getSeq).toList());
        assertEquals(6, counter(session.getId()));
        assertEquals(3, chatMapper.getPrivacySession(workspace.getId(), session.getId()).getMissionTaintSeq());
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), currentGeneration + 1));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    private void advanceUntilSessionPhase(String operation, String phase) {
        for (int count = 0; count < 30; count++) {
            var work = privacyMapper.getWork(workspace.getId(), operation);
            assertNotNull(work);
            if ("sessions".equals(work.getPhase()) && phase.equals(work.getSessionPhase())) {
                return;
            }
            assertFalse(privacyService.batch(workspace.getId(), operation));
        }
        throw new AssertionError("Privacy work did not reach its expected session phase");
    }

    @Test
    void taintSequencePreservesEarlierUserInput() {
        AiChatSession session = session();
        AiChatQueuedTurn earlier = runningTurn(session.getId(), "Preserve this exact input");
        int earlierTool = turns.proposeTool(earlier, 1, 0, "get_record", "{\"id\":1}");
        turns.finishTool(earlier, earlierTool, "executed", "{\"text\":\"Earlier evidence\"}");
        turns.resolve(earlier, "Earlier generated answer", "{}", 1, 1);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        missions.create(session.getId(), request, "en", AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
        AiChatQueuedTurn later = runningTurn(session.getId(), "Erase this later input");
        int laterTool = turns.proposeTool(later, 1, 0, "get_record", "{\"id\":2}");
        turns.finishTool(later, laterTool, "executed", "{\"text\":\"Later evidence\"}");
        turns.upsertHistorySummary(later, null, 0, "Generated summary of both inputs",
                "{\"kind\":\"history_summary\",\"throughSeq\":4}", 1, 1);
        turns.resolve(later, "Later generated answer", "{}", 1, 1);
        assertEquals(earlier.privacyToken().privacyGeneration(), later.privacyToken().privacyGeneration());
        assertEquals(1, earlier.userMessageSeq());
        assertEquals(4, later.userMessageSeq());
        assertEquals(6, counter(session.getId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND id IN (?,?)",
                Integer.class, workspace.getId(), earlierTool, laterTool));

        finish(invalidate());

        assertEquals(List.of("Preserve this exact input"), chatMapper.listMessages(workspace.getId(), session.getId(), 100, 0)
                .stream().map(AiChatMessage::getContent).toList());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND id IN (?,?)",
                Integer.class, workspace.getId(), earlierTool, laterTool));
        assertNull(chatMapper.getHistorySummary(workspace.getId(), session.getId()));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), 1));
        AiChatQueuedTurn fresh = runningTurn(session.getId(), "Fresh post-purge input");
        int freshTool = turns.proposeTool(fresh, 1, 0, "get_record", "{\"id\":3}");
        turns.finishTool(fresh, freshTool, "executed", "{\"text\":\"Fresh evidence\"}");
        turns.resolve(fresh, "Fresh answer", "{}", 1, 1);
        assertEquals(7, fresh.userMessageSeq());
        finish(invalidate());
        assertEquals(List.of(1), chatMapper.listMessages(workspace.getId(), session.getId(), 100, 0)
                .stream().map(AiChatMessage::getSeq).toList());
        assertEquals("Preserve this exact input", chatMapper.listMessages(workspace.getId(), session.getId(), 100, 0).getFirst().getContent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=?",
                Integer.class, workspace.getId()));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), 2));
        assertEquals(3, chatMapper.getPrivacySession(workspace.getId(), session.getId()).getMissionTaintSeq());
        assertEquals(8, counter(session.getId()));
    }

    @Test
    void residualVerificationIgnoresDiscoveryWatermarkLimits() {
        AiChatSession session = session();
        jdbc.update("UPDATE ai_chat_session SET message_seq_high_water=1,mission_taint_generation=1,mission_taint_seq=1 WHERE workspace_id=? AND id=?",
                workspace.getId(), session.getId());
        String operation = invalidate();
        var work = privacyMapper.getWork(workspace.getId(), operation);
        assertNotNull(work);
        jdbc.update("INSERT INTO ai_chat_message (workspace_id,session_id,seq,author_kind,content,privacy_generation) VALUES (?,?,1,'user','Late enrolled fixture',1)",
                workspace.getId(), session.getId());
        Integer id = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_message WHERE workspace_id=?", Integer.class, workspace.getId());
        assertNotNull(id);
        assertTrue(id > work.getMaxMessageId());
        assertTrue(privacyMapper.residualCount(workspace.getId(), work.getCutoffGeneration()) > 0);
        jdbc.update("UPDATE ai_agent_privacy_work SET phase='verify' WHERE workspace_id=? AND operation_key=?", workspace.getId(), operation);
        assertFalse(privacyService.batch(workspace.getId(), operation));
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        assertEquals("verify", privacyMapper.getWork(workspace.getId(), operation).getPhase());
    }

    private AiChatSession session() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Mission privacy fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private void completedTurn(int sessionId, String input, String output) {
        AiChatQueuedTurn turn = runningTurn(sessionId, input);
        turns.resolve(turn, output, "{\"citations\":[],\"resources\":[]}", 1, 1);
    }

    private AiChatQueuedTurn runningTurn(int sessionId, String input) {
        AiChatQueuedTurn turn = turns.queue(sessionId, new AiChatTurnCreateRequest(input, List.of()),
                epoch.current(workspace.getId()));
        turns.markRunning(turn, new AiRunLeaseGuard(properties.getRunLeaseTtl()));
        return turn;
    }

    private int counter(int sessionId) {
        Integer value = jdbc.queryForObject("SELECT message_seq_high_water FROM ai_chat_session WHERE workspace_id=? AND id=?",
                Integer.class, workspace.getId(), sessionId);
        assertNotNull(value);
        return value;
    }
}
