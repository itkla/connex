package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper;
import ooo.klae.connex.backend.services.AiAssistantService;

class AiAssistantPrivacyBootstrapIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAssistantPrivacyService organizationPrivacy;
    @Autowired private AiAssistantPrivacyControlMapper control;
    @Autowired private AiAssistantService assistant;

    @Test
    void preMigrationRestrictionCannotLeakAfterBootstrap() {
        int historical = historicalSession("Private user-set title", "archived", "Copied private summary without citations");
        int current = fixtureSession("Current user-set title", "archived", "Readable current free text", organizationPrivacy.current(workspace.getOrgId()));
        assertNotNull(assistant.get(current, 0, 100));
        assertThrows(RuntimeException.class, () -> assistant.get(historical, 1, 100));
        assertEquals("Private user-set title", jdbc.queryForObject("SELECT title FROM ai_chat_session WHERE workspace_id=? AND id=?", String.class, workspace.getId(), historical));
        assertEquals(0, jdbc.queryForObject("SELECT organization_privacy_generation FROM ai_chat_session WHERE workspace_id=? AND id=?", Long.class, workspace.getId(), historical));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    @Test
    void bootstrapInvalidatesUncitedFreeText() {
        int historical = historicalSession("Free text", "active", "Subject information only present in free text");
        assertThrows(RuntimeException.class, () -> assistant.get(historical, 1, 100));
        String operation = bootstrapKey();
        finish(operation);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND id=?", Integer.class, workspace.getId(), historical));
        assertEquals(0, privacyMapper.allChatResidualCount(workspace.getId(), 0, operation));
    }

    @Test
    void bootstrapRetryNeverRelabelsOrReissues() {
        int historical = historicalSession("Retry fixture", "active", "Historical free text");
        String key = bootstrapKey();
        var before = privacyMapper.getWork(workspace.getId(), key);
        assertNotNull(before);
        privacyService.initialize(workspace.getId());
        privacyService.initialize(workspace.getId());
        var after = privacyMapper.getWork(workspace.getId(), key);
        assertNotNull(after);
        assertEquals(before.getCutoffGeneration(), after.getCutoffGeneration());
        assertEquals(before.getInvalidatedGeneration(), after.getInvalidatedGeneration());
        assertArrayEquals(before.getOperationFingerprint(), after.getOperationFingerprint());
        assertEquals(key, bootstrapKey());
        assertEquals(0, jdbc.queryForObject("SELECT organization_privacy_generation FROM ai_chat_session WHERE workspace_id=? AND id=?", Long.class, workspace.getId(), historical));
        assertEquals(1, organizationPrivacy.current(workspace.getOrgId()));
    }

    @Test
    void unavailablePlacementKeepsBootstrapPending() {
        String key = bootstrapKey();
        jdbc.update("INSERT INTO ai_assistant_privacy_operation_workspace (organization_id,operation_key,workspace_id) VALUES (?,?,?)",
                workspace.getOrgId(), key, workspace.getId() + 1000000);
        finish(key);
        assertFalse(Boolean.TRUE.equals(transaction().execute(status -> organizationPrivacy.reconcileCompletion(workspace.getOrgId(), key))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_assistant_privacy_operation_workspace WHERE organization_id=? AND operation_key=? AND state='pending'",
                Integer.class, workspace.getOrgId(), key));
    }

    @Test
    void newSessionUsesCurrentFenceDuringPendingPurge() {
        historicalSession("Pending historical chat", "active", "Old free text");
        AiChatSessionCreateRequest request = new AiChatSessionCreateRequest();
        request.setTitle("New current chat");
        var fresh = assistant.create(request);
        assertNotNull(fresh);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND title='New current chat' AND organization_privacy_generation=1", Integer.class, workspace.getId()));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        finish(bootstrapKey());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND title='New current chat'", Integer.class, workspace.getId()));
    }

    @Test
    void newOrganizationProvisioningRecoversAfterControlCommit() {
        jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id=?", workspace.getId());
        jdbc.update("DELETE FROM ai_assistant_privacy_operation_workspace WHERE organization_id=?", workspace.getOrgId());
        jdbc.update("DELETE FROM ai_assistant_privacy_operation WHERE organization_id=?", workspace.getOrgId());
        jdbc.update("DELETE FROM ai_assistant_privacy_fence WHERE organization_id=?", workspace.getOrgId());
        transaction().executeWithoutResult(status -> organizationPrivacy.provision(workspace.getOrgId()));
        String key = bootstrapKey();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_privacy_work WHERE workspace_id=? AND operation_key=?", Integer.class, workspace.getId(), key));
        privacyService.initialize(workspace.getId());
        privacyService.initialize(workspace.getId());
        assertEquals(key, bootstrapKey());
        finish(key);
        assertTrue(Boolean.TRUE.equals(transaction().execute(status -> organizationPrivacy.reconcileCompletion(workspace.getOrgId(), key))));
        assertNotNull(control.getFence(workspace.getOrgId()).getBootstrapCompletedAt());
    }

    @Test
    void v1WorkKeepsOriginalScopeAndFingerprint() throws Exception {
        String key = invalidate();
        var original = privacyMapper.getWork(workspace.getId(), key);
        assertNotNull(original);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(
                ("1|" + workspace.getId() + "|" + key + "|restriction|null|null").getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, original.getOperationFingerprint());
        assertEquals("organization_mission_holdings_v1", original.getContentScope());
        var allChats = transaction().execute(status -> organizationPrivacy.invalidateOrganization(
                workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
        assertNotNull(allChats);
        transaction().executeWithoutResult(status -> privacyService.enrollOperation(workspace.getId(), allChats));
        transaction().executeWithoutResult(status -> privacyService.invalidateWorkspaces(
                List.of(workspace.getId()), UUID.fromString(key), "restriction", null, null));
        var replay = privacyMapper.getWork(workspace.getId(), key);
        assertNotNull(replay);
        assertArrayEquals(expected, replay.getOperationFingerprint());
        assertEquals(original.getCutoffGeneration(), replay.getCutoffGeneration());
        assertEquals(original.getInvalidatedGeneration(), replay.getInvalidatedGeneration());
        assertEquals(original.getMaxSessionId(), replay.getMaxSessionId());
        assertEquals("organization_mission_holdings_v1", replay.getContentScope());
        assertEquals("organization_assistant_holdings_v2", privacyMapper.getWork(workspace.getId(), allChats.getOperationKey()).getContentScope());
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    @Test
    void v2RetiresEntireSession() {
        int affected = fixtureSession("Retired shared chat", "active", "Uncited retained free text",
                organizationPrivacy.current(workspace.getOrgId()));
        assertNotNull(assistant.get(affected, 0, 100));
        var operation = transaction().execute(status -> organizationPrivacy.invalidateOrganization(
                workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
        assertNotNull(operation);
        transaction().executeWithoutResult(status -> privacyService.enrollOperation(workspace.getId(), operation));
        int current = fixtureSession("New shared chat", "active", "New retained free text",
                organizationPrivacy.current(workspace.getOrgId()));
        finish(operation.getOperationKey());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND id=?", Integer.class, workspace.getId(), affected));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND session_id=?", Integer.class, workspace.getId(), affected));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session_participant WHERE workspace_id=? AND session_id=?", Integer.class, workspace.getId(), affected));
        assertNotNull(assistant.get(current, 0, 100));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    private String bootstrapKey() {
        return java.util.Objects.requireNonNull(control.getFence(workspace.getOrgId())).getBootstrapOperationId();
    }

    private int historicalSession(String title, String status, String content) {
        return fixtureSession(title, status, content, 0);
    }

    private int fixtureSession(String title, String status, String content, long organizationGeneration) {
        jdbc.update("INSERT INTO ai_chat_session (workspace_id,created_by_user_id,title,title_user_set,visibility,status,archived_at,organization_privacy_generation,message_seq_high_water) VALUES (?,NULL,?,TRUE,'shared',?,IF(?='archived',UTC_TIMESTAMP(6),NULL),?,1)",
                workspace.getId(), title, status, status, organizationGeneration);
        Integer sessionId = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session WHERE workspace_id=?", Integer.class, workspace.getId());
        int id = java.util.Objects.requireNonNull(sessionId);
        jdbc.update("INSERT INTO ai_chat_session_participant (workspace_id,session_id,user_id,role,status,joined_at) VALUES (?,?,?,'member','joined',UTC_TIMESTAMP(6))",
                workspace.getId(), id, currentUser.getId());
        jdbc.update("INSERT INTO ai_chat_message (workspace_id,session_id,seq,author_kind,content,organization_privacy_generation) VALUES (?,?,1,'user',?,?)",
                workspace.getId(), id, content, organizationGeneration);
        return id;
    }
}
