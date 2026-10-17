package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.exceptions.AiAgentUnavailableException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import tools.jackson.databind.ObjectMapper;

class AiAgentPrivacyInitializationIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAgentRunService missions;
    @Autowired private AiAgentRunPersistenceService persistence;
    @Autowired private AiAgentPrivacyInitializer initializer;
    @Autowired private AiRestrictionEpoch epochs;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private AiAgentCapabilityGate capability;

    @Test
    void existingWorkspaceBarrierInitializationIsIdempotent() {
        String operation = invalidate();
        long generation = privacyMapper.getBarrier(workspace.getId()).getGeneration();
        privacyService.initialize(workspace.getId());
        assertEquals(generation, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        finish(operation);
        privacyService.initialize(workspace.getId());
        assertEquals(generation, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    @Test
    void missingBarrierWithMissionEvidenceIsNotReinitialized() {
        jdbc.update("INSERT INTO ai_chat_session (workspace_id,created_by_user_id,title,message_seq_high_water,mission_taint_generation,mission_taint_seq) VALUES (?,?,?,1,1,1)",
                workspace.getId(), currentUser.getId(), "Tainted fixture");
        jdbc.update("DELETE FROM ai_agent_privacy_barrier WHERE workspace_id=?", workspace.getId());
        assertThrows(ServiceUnavailableException.class, () -> privacyService.initialize(workspace.getId()));
        assertNull(privacyMapper.getBarrier(workspace.getId()));
    }

    @Test
    void newWorkspaceBarrierInitializationRecoversControlCommitCrash() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Barrier admission fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        doReturn(AiAgentCapabilityGate.hash("configured provider fixture")).when(capability).requireReady();
        jdbc.update("DELETE FROM ai_agent_privacy_barrier WHERE workspace_id=?", workspace.getId());
        assertNull(privacyMapper.getBarrier(workspace.getId()));
        assertThrows(AiAgentUnavailableException.class, () -> missions.create(session.getId(), request));
        assertEquals(0, chatMapper.getPrivacySession(workspace.getId(), session.getId()).getMessageSeqHighWater());
        assertNull(chatMapper.getPrivacySession(workspace.getId(), session.getId()).getMissionTaintSeq());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_run WHERE workspace_id=?",
                Integer.class, workspace.getId()));
        initializer.initialize(workspace.getId());
        assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        missions.requirePrivacyReady();
        var admitted = persistence.create(session.getId(), request, "en",
                AiAgentCapabilityGate.hash(json.writeValueAsString(request)), json.writeValueAsString(request.scope()),
                epochs.current(workspace.getId()));
        assertEquals("queued", admitted.getStatus());
        assertEquals(1, chatMapper.getPrivacySession(workspace.getId(), session.getId()).getMessageSeqHighWater());
    }
}
