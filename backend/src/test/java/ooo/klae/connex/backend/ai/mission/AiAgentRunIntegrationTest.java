package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import tools.jackson.databind.ObjectMapper;

/** Real transaction fixtures exercise the mission persistence boundary without a model provider. */
class AiAgentRunIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired protected AiAgentRunPersistenceService persistence;
    @Autowired protected AiAgentRunMapper runs;
    @Autowired protected AiAgentCapabilityGate capability;
    @Autowired protected AiProperties properties;
    @Autowired protected AiRestrictionEpoch epoch;
    @Autowired protected AiChatTurnPersistenceService turns;
    @Autowired protected ObjectMapper json;

    @Test
    void createsOneDurableReadOnlyMissionAndOneReport() {
        AiAgentRun run = create(session(), request());
        AiAgentExecutionPolicy policy = claim(run);
        var dispatch = persistence.reserveDispatch(policy, 1000);
        persistence.recordUsage(policy, dispatch, 50, 20);
        assertTrue(persistence.resolve(policy, "The selected record was reviewed.", "{\"citations\":[],\"resources\":[]}"));
        AiAgentRun done = runs.getRun(workspace.getId(), run.getId());
        assertEquals("done", done.getStatus());
        assertNull(done.getActiveSegmentId());
        assertNotNull(done.getReportMessageId());
        assertEquals(1, done.getConsumedSteps());
        assertEquals(70, done.getConsumedTokens());
        assertEquals(0, done.getReservedTokens());
        assertEquals(1, runs.listSegments(workspace.getId(), run.getId()).size());
        assertEquals("completed", runs.listSegments(workspace.getId(), run.getId()).getFirst().getStatus());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND mission_run_id=?", run.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND mission_run_id=? AND server_proposal=TRUE", run.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND subject_kind='agent_run' AND subject_id=? AND released_at IS NULL", run.getId()));
        assertThrows(ConflictException.class, () -> persistence.resolve(policy, "Duplicate", "{}"));
    }

    @Test
    void replaysCreateWithoutAnotherGoalOrDeadline() {
        int session = session();
        var request = request();
        AiAgentRun first = create(session, request);
        AiAgentRun replay = create(session, request);
        assertEquals(first.getId(), replay.getId());
        assertEquals(first.getExecutionDeadlineAt(), replay.getExecutionDeadlineAt());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND mission_run_id=?", first.getId()));
        var changed = new AiAgentRunCreateRequest(request.clientRequestKey(), request.missionType(),
                "Different goal", request.scope(), request.limits());
        assertThrows(ConflictException.class, () -> create(session, changed));
    }

    @Test
    void purgedCreateKeyCannotCreateAgain() {
        int session = session();
        var request = request();
        AiAgentRun run = create(session, request);
        finish(invalidate());
        AiAgentRun tombstone = runs.getRun(workspace.getId(), run.getId());
        assertNotNull(tombstone.getPurgedAt());
        assertNull(tombstone.getRequestHash());
        assertThrows(AiAgentGoneException.class, () -> create(session, request));
    }

    @Test
    void oldAttemptCannotPublishOrReleaseSuccessor() {
        AiAgentRun run = create(session(), request());
        AiAgentExecutionPolicy first = claim(run);
        AiAgentRun paused = persistence.control(runs.getRun(workspace.getId(), run.getId()), 2, "pause");
        AiAgentRun queued = persistence.control(paused, paused.getStateVersion(), "resume");
        AiAgentExecutionPolicy successor = claim(queued);
        assertThrows(ConflictException.class, () -> persistence.resolve(first, "Stale report", "{}"));
        persistence.interrupt(runWithGeneration(run.getId(), first.executionGeneration()), "interrupted");
        AiAgentRun current = runs.getRun(workspace.getId(), run.getId());
        assertEquals("running", current.getStatus());
        assertEquals(successor.segmentId(), current.getActiveSegmentId());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND subject_kind='agent_run' AND subject_id=? AND released_at IS NULL", run.getId()));
        assertNull(chatMapper.getMissionMessage(workspace.getId(), run.getId(), "report"));
    }

    @Test
    void pauseResumeKeepsOriginalCapsAndDeadline() {
        AiAgentRun run = create(session(), request());
        var deadline = run.getExecutionDeadlineAt();
        for (int attempt = 1; attempt <= 3; attempt++) {
            AiAgentExecutionPolicy policy = claim(run);
            var dispatch = persistence.reserveDispatch(policy, 100);
            persistence.recordUsage(policy, dispatch, 1, 1);
            AiAgentRun live = runs.getRun(workspace.getId(), run.getId());
            run = persistence.control(live, live.getStateVersion(), "pause");
            assertEquals(deadline, run.getExecutionDeadlineAt());
            assertEquals(attempt, run.getConsumedSteps());
            assertEquals(attempt * 2L, run.getConsumedTokens());
            assertEquals(8, run.getMaxSteps());
            if (attempt < 3) {
                run = persistence.control(run, run.getStateVersion(), "resume");
            }
        }
        AiAgentRun finalRun = run;
        assertThrows(ConflictException.class, () -> persistence.control(finalRun, finalRun.getStateVersion(), "resume"));
    }

    @Test
    void legacyRoutesCannotControlMissionTurn() {
        AiAgentRun run = create(session(), request());
        var policy = claim(run);
        assertThrows(ConflictException.class, () -> turns.cancel(policy.turn().sessionId(), policy.turn().turnId()));
        assertThrows(ConflictException.class, () -> turns.readTurn(policy.turn().sessionId(), policy.turn().turnId()));
        assertFalse(turns.expireUnleasedTurn(workspace.getId(), policy.turn().sessionId(), policy.turn().turnId(), 0));
        assertEquals("running", runs.getRun(workspace.getId(), run.getId()).getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND subject_kind='chat_turn' AND subject_id=?", policy.turn().turnId()));
    }

    @Test
    void firstMissionAndReactiveAdmissionSerialize() {
        int firstSession = session();
        var reactive = turns.queue(firstSession, new AiChatTurnCreateRequest("Earlier input", List.of()), epoch.current(workspace.getId()));
        assertThrows(ConflictException.class, () -> create(firstSession, request()));
        assertNull(chatMapper.getPrivacySession(workspace.getId(), firstSession).getMissionTaintSeq());
        turns.cancel(firstSession, reactive.turnId());
        int secondSession = session();
        AiAgentRun mission = create(secondSession, request());
        var following = turns.queue(secondSession, new AiChatTurnCreateRequest("Following input", List.of()), epoch.current(workspace.getId()));
        assertEquals(chatMapper.getPrivacySession(workspace.getId(), secondSession).getMissionTaintSeq(),
                following.privacyToken().taintSeqAtAdmission());
        assertEquals(mission.getPrivacyGeneration(), following.privacyToken().privacyGeneration());
    }

    @Test
    void expiredQueuedRunDoesNotStarveNextRun() {
        AiAgentRun expired = create(session(), request());
        AiAgentRun next = create(session(), request());
        jdbc.update("UPDATE ai_agent_run SET execution_deadline_at=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE workspace_id=? AND id=?", workspace.getId(), expired.getId());
        assertEquals(next.getId(), runs.dueRuns(workspace.getId(), 1).getFirst().getId());
        assertTrue(persistence.expire(runs.getRun(workspace.getId(), expired.getId())));
        assertEquals("failed", runs.getRun(workspace.getId(), expired.getId()).getStatus());
    }

    protected int session() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Mission fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session.getId();
    }

    protected AiAgentRunCreateRequest request() {
        return new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review this record",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
    }

    protected AiAgentRun create(int session, AiAgentRunCreateRequest request) {
        return persistence.create(session, request, "en", AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
    }

    protected AiAgentExecutionPolicy claim(AiAgentRun run) {
        return persistence.claim(run, AiAgentCapabilityGate.hash("fixture declaration"),
                new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
    }

    private AiAgentRun runWithGeneration(int id, long generation) {
        AiAgentRun run = runs.getRun(workspace.getId(), id);
        run.setExecutionGeneration(generation);
        return run;
    }

    private int count(String query, int id) {
        Integer count = jdbc.queryForObject(query, Integer.class, workspace.getId(), id);
        assertNotNull(count);
        return count;
    }
}
