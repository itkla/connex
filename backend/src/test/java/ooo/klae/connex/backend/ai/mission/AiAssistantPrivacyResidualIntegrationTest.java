package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper;
import ooo.klae.connex.backend.services.AiAssistantService;

class AiAssistantPrivacyResidualIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAssistantPrivacyService organizationPrivacy;
    @Autowired private AiAssistantPrivacyControlMapper control;
    @Autowired private AiAssistantService assistant;

    @Test
    void repairsRowsAboveFrozenWatermark() {
        AiAssistantPrivacyOperation operation = invalidateAll();
        var frozen = privacyMapper.getWork(workspace.getId(), operation.getOperationKey());
        assertNotNull(frozen);
        int restored = restoreHistoricalSession();
        assertTrue(restored > frozen.getMaxSessionId());
        finish(operation.getOperationKey());
        assertEquals(frozen.getMaxSessionId(), privacyMapper.getWork(workspace.getId(), operation.getOperationKey()).getMaxSessionId());
        assertEquals(0, privacyMapper.allChatResidualCount(workspace.getId(), operation.getCutoffGeneration(), operation.getOperationKey()));
    }

    @Test
    void repairsRowsBehindCursor() {
        int restored = restoreHistoricalSession();
        AiAssistantPrivacyOperation operation = invalidateAll();
        jdbc.update("UPDATE ai_agent_privacy_work SET phase='verify',last_session_id=? WHERE workspace_id=? AND operation_key=?", restored + 1, workspace.getId(), operation.getOperationKey());
        assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        assertEquals("sessions", privacyMapper.getWork(workspace.getId(), operation.getOperationKey()).getPhase());
        finish(operation.getOperationKey());
        assertEquals(0, privacyMapper.allChatResidualCount(workspace.getId(), operation.getCutoffGeneration(), operation.getOperationKey()));
    }

    @Test
    void ownedOrphanLeasePreventsCompletion() {
        AiAssistantPrivacyOperation operation = invalidateAll();
        jdbc.update("INSERT INTO ai_run_lease (workspace_id,subject_kind,subject_id,owner,epoch,expires_at) VALUES (?,'chat_turn',987654321,?,1,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR))", workspace.getId(), UUID.randomUUID().toString());
        for (int attempt = 0; attempt < 6; attempt++) {
            assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        }
        var work = privacyMapper.getWork(workspace.getId(), operation.getOperationKey());
        assertNotNull(work);
        assertEquals("verify", work.getPhase());
        assertNull(work.getCompletedAt());
        assertTrue(java.util.Objects.requireNonNull(work.getLastResidualCount()) > 0);
        jdbc.update("DELETE FROM ai_run_lease WHERE workspace_id=? AND subject_id=987654321", workspace.getId());
        finish(operation.getOperationKey());
    }

    @Test
    void terminalTurnLeaseIsReleasedBeforeItsIdentityIsDeleted() {
        int historical = restoreHistoricalSession();
        jdbc.update("INSERT INTO ai_chat_turn (workspace_id,session_id,requested_by_user_id,status,organization_privacy_generation) VALUES (?,?,?,'resolved',0)",
                workspace.getId(), historical, currentUser.getId());
        Integer turn = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_turn WHERE workspace_id=?", Integer.class, workspace.getId());
        jdbc.update("INSERT INTO ai_run_lease (workspace_id,subject_kind,subject_id,owner,epoch,expires_at) VALUES (?,'chat_turn',?,?,1,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR))",
                workspace.getId(), java.util.Objects.requireNonNull(turn), UUID.randomUUID().toString());
        AiAssistantPrivacyOperation operation = invalidateAll();
        finish(operation.getOperationKey());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND subject_kind='chat_turn' AND subject_id=? AND owner IS NOT NULL", Integer.class, workspace.getId(), turn));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE workspace_id=? AND id=?", Integer.class, workspace.getId(), turn));
    }

    @Test
    void restoredGenerationZeroRemainsUnavailable() {
        String bootstrap = java.util.Objects.requireNonNull(control.getFence(workspace.getOrgId())).getBootstrapOperationId();
        finish(bootstrap);
        assertNotNull(control.getFence(workspace.getOrgId()).getBootstrapCompletedAt());
        int restored = restoreHistoricalSession();
        assertThrows(RuntimeException.class, () -> assistant.get(restored, 1, 100));
        privacyService.initialize(workspace.getId());
        assertNotEquals("complete", privacyMapper.getWork(workspace.getId(), bootstrap).getPhase());
        assertNull(control.getFence(workspace.getOrgId()).getBootstrapCompletedAt());
        assertEquals(0, jdbc.queryForObject("SELECT organization_privacy_generation FROM ai_chat_session WHERE workspace_id=? AND id=?", Long.class, workspace.getId(), restored));
        finish(bootstrap);
        assertEquals(0, privacyMapper.allChatResidualCount(workspace.getId(), 0, bootstrap));
    }

    @ParameterizedTest
    @CsvSource({"bootstrap,missing", "bootstrap,runs", "bootstrap,verify",
            "restriction,missing", "restriction,runs", "restriction,verify"})
    void restoreBeforeCleanupReopensVerifiedControlEvidence(String reason, String phase) {
        String key = "bootstrap".equals(reason)
                ? control.getFence(workspace.getOrgId()).getBootstrapOperationId()
                : invalidateAll().getOperationKey();
        finish(key);
        var accepted = control.getOperation(workspace.getOrgId(), key);
        assertNotNull(accepted.getCompletedAt());
        assertEquals(0, control.countUnverified(workspace.getOrgId(), key));
        if ("bootstrap".equals(reason)) {
            assertNotNull(control.getFence(workspace.getOrgId()).getBootstrapCompletedAt());
        }
        int restored = restoreHistoricalSession();
        if ("missing".equals(phase)) {
            jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id=? AND operation_key=?",
                    workspace.getId(), key);
        } else {
            jdbc.update("UPDATE ai_agent_privacy_work SET phase=?,completed_at=NULL,verified_at=NULL,last_residual_count=NULL"
                    + " WHERE workspace_id=? AND operation_key=?", phase, workspace.getId(), key);
        }
        assertNotNull(chatMapper.getRetainedSessionById(workspace.getId(), currentUser.getId(), restored,
                List.of(currentUser.getId()), 0));
        assertEquals("AI assistant session is not accessible",
                assertThrows(ForbiddenException.class, () -> assistant.getRetained(restored, 1, 100)).getMessage());

        privacyService.initialize(workspace.getId());

        assertEquals(1, control.countUnverified(workspace.getOrgId(), key));
        assertNull(control.getOperation(workspace.getOrgId(), key).getCompletedAt());
        if ("bootstrap".equals(reason)) {
            assertNull(control.getFence(workspace.getOrgId()).getBootstrapCompletedAt());
        }
        assertEquals(Boolean.FALSE,
                transaction().execute(status -> organizationPrivacy.reconcileCompletion(workspace.getOrgId(), key)));
        assertEquals("AI assistant session is not accessible",
                assertThrows(ForbiddenException.class, () -> assistant.getRetained(restored, 1, 100)).getMessage());
        var resumed = privacyMapper.getWork(workspace.getId(), key);
        assertNotEquals("complete", resumed.getPhase());
        assertEquals(accepted.getCutoffGeneration(), resumed.getCutoffGeneration());
        assertEquals(accepted.getInvalidatedGeneration(), resumed.getInvalidatedGeneration());

        finish(key);

        assertEquals(0, privacyMapper.allChatResidualCount(workspace.getId(), accepted.getCutoffGeneration(), key));
        assertNull(chatMapper.getPrivacySession(workspace.getId(), restored));
        assertEquals(0, control.countUnverified(workspace.getOrgId(), key));
        assertNotNull(control.getOperation(workspace.getOrgId(), key).getCompletedAt());
        if ("bootstrap".equals(reason)) {
            assertNotNull(control.getFence(workspace.getOrgId()).getBootstrapCompletedAt());
        }
    }

    @Test
    void orphanBriefProjectionPreventsCompletionUntilItsSourceIsRepaired() {
        AiAssistantPrivacyOperation operation = invalidateAll();
        jdbc.update("INSERT INTO ai_brief_schedule (workspace_id,user_id,last_delivered_session_id,last_delivered_kind,last_delivered_at) VALUES (?,?,987654320,'daily',UTC_TIMESTAMP(6))",
                workspace.getId(), currentUser.getId());
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
            }
            assertNull(privacyMapper.getWork(workspace.getId(), operation.getOperationKey()).getCompletedAt());
            assertTrue(privacyMapper.allChatResidualCount(workspace.getId(), operation.getCutoffGeneration(), operation.getOperationKey()) > 0);
            jdbc.update("UPDATE ai_brief_schedule SET last_delivered_session_id=NULL,last_delivered_kind=NULL,last_delivered_at=NULL WHERE workspace_id=?", workspace.getId());
            finish(operation.getOperationKey());
        } finally {
            jdbc.update("DELETE FROM ai_brief_schedule WHERE workspace_id=?", workspace.getId());
        }
    }

    @Test
    void erasureClearsPendingAndDeliveredBriefSources() {
        int historical = restoreHistoricalSession();
        jdbc.update("INSERT INTO ai_brief_schedule (workspace_id,user_id,pending_kind,pending_session_id,pending_turn_id,pending_started_at,last_delivered_session_id,last_delivered_kind,last_delivered_at) VALUES (?,?,'daily',?,987654319,UTC_TIMESTAMP(6),?,'daily',UTC_TIMESTAMP(6))",
                workspace.getId(), currentUser.getId(), historical, historical);
        try {
            AiAssistantPrivacyOperation operation = invalidateAll();
            finish(operation.getOperationKey());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_brief_schedule WHERE workspace_id=? AND (pending_session_id IS NOT NULL OR pending_turn_id IS NOT NULL OR pending_kind IS NOT NULL OR pending_started_at IS NOT NULL OR last_delivered_session_id IS NOT NULL OR last_delivered_kind IS NOT NULL OR last_delivered_at IS NOT NULL)",
                    Integer.class, workspace.getId()));
        } finally {
            jdbc.update("DELETE FROM ai_brief_schedule WHERE workspace_id=?", workspace.getId());
        }
    }

    @Test
    void orphanSetResidualPreventsCompletion() {
        AiAssistantPrivacyOperation operation = invalidateAll();
        jdbc.update("INSERT INTO ai_chat_session (workspace_id,created_by_user_id,title,organization_privacy_generation) VALUES (?,?,'Current session with restored inconsistent child',?)",
                workspace.getId(), currentUser.getId(), operation.getInvalidatedGeneration());
        Integer session = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session WHERE workspace_id=?", Integer.class, workspace.getId());
        int sessionId = java.util.Objects.requireNonNull(session);
        jdbc.update("INSERT INTO ai_chat_message (workspace_id,session_id,seq,author_kind,content,organization_privacy_generation) VALUES (?,?,1,'user','Current-generation input',?)",
                workspace.getId(), sessionId, operation.getInvalidatedGeneration());
        Integer message = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_message WHERE workspace_id=?", Integer.class, workspace.getId());
        jdbc.update("INSERT INTO ai_chat_turn (workspace_id,session_id,requested_by_user_id,status,organization_privacy_generation) VALUES (?,?,?,'resolved',?)",
                workspace.getId(), sessionId, currentUser.getId(), operation.getInvalidatedGeneration());
        Integer turn = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_turn WHERE workspace_id=?", Integer.class, workspace.getId());
        jdbc.update("INSERT INTO ai_assistant_proposal_set (workspace_id,proposal_key,session_id,origin_turn_id,message_id,step_number,call_ordinal,tool_name,invocation_mode,row_count,intent_fingerprint,scope_revision,privacy_generation,organization_privacy_generation) VALUES (?,'restored-inconsistent-parent',?,?,?,1,0,'add_tag','scalar',1,REPEAT('a',64),'scope-1',1,?)",
                workspace.getId(), sessionId, java.util.Objects.requireNonNull(turn), java.util.Objects.requireNonNull(message), operation.getInvalidatedGeneration());
        jdbc.update("INSERT INTO ai_chat_tool_call (workspace_id,message_id,tool_name,server_proposal,proposal_key,proposal_ordinal,proposal_prepared_at,privacy_generation,organization_privacy_generation) VALUES (?,?,'add_tag',TRUE,'restored-inconsistent-parent',1,UTC_TIMESTAMP(6),1,?)",
                workspace.getId(), message, operation.getCutoffGeneration());
        for (int pass = 0; pass < 5; pass++) {
            assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        }
        assertNull(privacyMapper.getWork(workspace.getId(), operation.getOperationKey()).getCompletedAt());
        assertTrue(privacyMapper.allChatResidualCount(workspace.getId(), operation.getCutoffGeneration(), operation.getOperationKey()) > 0);
        assertNotNull(assistant.get(sessionId, 1, 20));
        jdbc.update("DELETE FROM ai_chat_tool_call WHERE workspace_id=? AND proposal_key='restored-inconsistent-parent'", workspace.getId());
        finish(operation.getOperationKey());
        assertNotNull(assistant.get(sessionId, 1, 20));
    }

    private AiAssistantPrivacyOperation invalidateAll() {
        var operation = transaction().execute(status -> organizationPrivacy.invalidateOrganization(workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
        assertNotNull(operation);
        transaction().executeWithoutResult(status -> privacyService.enrollOperation(workspace.getId(), operation));
        return operation;
    }

    private int restoreHistoricalSession() {
        jdbc.update("INSERT INTO ai_chat_session (workspace_id,created_by_user_id,title,organization_privacy_generation) VALUES (?,NULL,'Restored private title',0)", workspace.getId());
        Integer sessionId = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session WHERE workspace_id=?", Integer.class, workspace.getId());
        int id = java.util.Objects.requireNonNull(sessionId);
        jdbc.update("INSERT INTO ai_chat_message (workspace_id,session_id,seq,author_kind,content,organization_privacy_generation) VALUES (?,?,1,'user','Restored private free text',0)", workspace.getId(), id);
        return id;
    }
}
