package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.services.AttachmentQuarantineService;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ObjectDeletionTransactionExecutor;
import ooo.klae.connex.backend.storage.ObjectStorage;
import ooo.klae.connex.backend.storage.ObjectStorageException;

class AiAssistantPrivacyObjectIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAssistantPrivacyService organizationPrivacy;
    @Autowired private ObjectDeletionTransactionExecutor deletion;
    @Autowired private ManagedObjectService managed;
    @Autowired private AttachmentQuarantineService quarantine;
    @MockitoSpyBean private ObjectStorage provider;
    @MockitoSpyBean private ooo.klae.connex.backend.mappers.AiAgentPrivacyObjectMapper receipts;

    @Test
    void quarantineDeleteBeforeDiscoveryCannotLoseFailedObjectObligation() {
        Holding holding = holding();
        AiAssistantPrivacyOperation operation = invalidateAll();
        assertThrows(ConflictException.class, () -> quarantine.delete(holding.attachmentId()));
        assertEquals(1, attachmentCount(holding.attachmentId()));
        drainReference(operation);
        doThrow(new ObjectStorageException("provider unavailable")).when(provider).delete(holding.objectKey());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        assertEquals("pending", disposition(operation));
        doCallRealMethod().when(provider).delete(holding.objectKey());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        finish(operation.getOperationKey());
        assertEquals("deleted", disposition(operation));
    }

    @Test
    void objectFailureKeepsErasurePending() {
        Holding holding = holding();
        AiAssistantPrivacyOperation operation = invalidateAll();
        drainReference(operation);
        doThrow(new ObjectStorageException("provider unavailable")).when(provider).delete(holding.objectKey());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertEquals("pending", disposition(operation));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        }
    }

    @Test
    void queueAbsenceIsNotDeletionEvidence() {
        Holding holding = holding();
        AiAssistantPrivacyOperation operation = invalidateAll();
        drainReference(operation);
        jdbc.update("DELETE FROM object_deletion_queue WHERE workspace_id=? AND object_key=?", workspace.getId(), holding.objectKey());
        assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        assertEquals("pending", disposition(operation));
    }

    @Test
    void finalDeleteReceiptSurvivesRestart() {
        Holding holding = holding();
        AiAssistantPrivacyOperation operation = invalidateAll();
        drainReference(operation);
        jdbc.update("UPDATE object_deletion_queue SET delete_passes_remaining=2 WHERE workspace_id=? AND object_key=?", workspace.getId(), holding.objectKey());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertEquals("pending", disposition(operation));
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertEquals("deleted", disposition(operation));
        privacyService.initialize(workspace.getId());
        finish(operation.getOperationKey());
        privacyService.initialize(workspace.getId());
        assertTrue(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        assertEquals("deleted", disposition(operation));
    }

    @Test
    void providerSuccessBeforeReceiptCommitCanRetryAfterRestart() {
        Holding holding = holding();
        AiAssistantPrivacyOperation operation = invalidateAll();
        drainReference(operation);
        doThrow(new IllegalStateException("simulated metadata interruption")).when(receipts)
                .acknowledgeDeleted(org.mockito.ArgumentMatchers.eq(workspace.getId()), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(holding.objectKey()));
        assertThrows(IllegalStateException.class, () -> deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1)));
        assertEquals("pending", disposition(operation));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id=? AND object_key=?", Integer.class, workspace.getId(), holding.objectKey()));
        doCallRealMethod().when(receipts).acknowledgeDeleted(org.mockito.ArgumentMatchers.eq(workspace.getId()), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(holding.objectKey()));
        privacyService.initialize(workspace.getId());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        finish(operation.getOperationKey());
        assertEquals("deleted", disposition(operation));
    }

    @Test
    void rediscoveredReferenceRequiresTheNewExactQueueReceipt() {
        Holding holding = holding();
        AiAssistantPrivacyOperation operation = invalidateAll();
        drainReference(operation);
        Long firstQueue = jdbc.queryForObject("SELECT deletion_queue_id FROM ai_agent_privacy_object WHERE workspace_id=? AND operation_key=?",
                Long.class, workspace.getId(), operation.getOperationKey());
        assertNotNull(firstQueue);
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertEquals("deleted", disposition(operation));
        jdbc.update("INSERT INTO ai_chat_session (workspace_id,created_by_user_id,title,organization_privacy_generation) VALUES (?,NULL,'Restored file reference',?)",
                workspace.getId(), operation.getCutoffGeneration());
        Integer restoredSession = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session WHERE workspace_id=?", Integer.class, workspace.getId());
        jdbc.update("INSERT INTO attachment (workspace_id,entity_type,entity_id,file_name,url) VALUES (?,'ai_chat_session',?,'restored.txt',?)",
                workspace.getId(), java.util.Objects.requireNonNull(restoredSession), holding.url());
        assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        drainReference(operation);
        Long newQueue = jdbc.queryForObject("SELECT deletion_queue_id FROM ai_agent_privacy_object WHERE workspace_id=? AND operation_key=?",
                Long.class, workspace.getId(), operation.getOperationKey());
        assertNotNull(newQueue);
        assertTrue(newQueue > firstQueue);
        assertEquals("pending", disposition(operation));
        doThrow(new ObjectStorageException("provider unavailable")).when(provider).delete(holding.objectKey());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        doCallRealMethod().when(provider).delete(holding.objectKey());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        finish(operation.getOperationKey());
        assertEquals("deleted", disposition(operation));
    }

    @Test
    void lifecycleObjectObligationPreventsOverlappingV2Completion() {
        Holding holding = holding();
        jdbc.update("UPDATE workspace SET lifecycle_state='tearing_down' WHERE id=?", workspace.getId());
        UUID localKey = UUID.randomUUID();
        transaction().executeWithoutResult(status -> privacyService.invalidateWorkspaces(
                java.util.List.of(workspace.getId()), localKey, "offboarding", null, null));
        AiAssistantPrivacyOperation local = new AiAssistantPrivacyOperation();
        local.setOperationKey(localKey.toString());
        drainReference(local);
        assertEquals("organization_mission_holdings_v1", privacyMapper.getWork(workspace.getId(), localKey.toString()).getContentScope());
        assertEquals("pending", disposition(local));
        AiAssistantPrivacyOperation organization = invalidateAll();
        doThrow(new ObjectStorageException("provider unavailable")).when(provider).delete(holding.objectKey());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        for (int pass = 0; pass < 5; pass++) {
            assertFalse(privacyService.batch(workspace.getId(), organization.getOperationKey()));
        }
        assertEquals("verify", privacyMapper.getWork(workspace.getId(), organization.getOperationKey()).getPhase());
        assertEquals("pending", disposition(local));
        doCallRealMethod().when(provider).delete(holding.objectKey());
        deletion.processTenant(workspace.getId(), holding.objectKey(), LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        finish(organization.getOperationKey());
        finish(local.getOperationKey());
        assertEquals("deleted", disposition(local));
    }

    @Test
    void crmReferenceRetainsBytesButChatLinkIsRemoved() {
        Holding holding = holding();
        var company = newCompany();
        jdbc.update("INSERT INTO attachment (workspace_id,entity_type,entity_id,file_name,url) VALUES (?,'company',?,'saved.txt',?)",
                workspace.getId(), company.getId(), holding.url());
        AiAssistantPrivacyOperation operation = invalidateAll();
        finish(operation.getOperationKey());
        assertEquals(0, attachmentCount(holding.attachmentId()));
        assertEquals("retained", disposition(operation));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE workspace_id=? AND entity_type='company' AND entity_id=? AND url=?",
                Integer.class, workspace.getId(), company.getId(), holding.url()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id=? AND object_key=?",
                Integer.class, workspace.getId(), holding.objectKey()));
    }

    @AfterEach
    void removeObjects() {
        jdbc.update("DELETE FROM attachment WHERE workspace_id=?", workspace.getId());
        jdbc.update("DELETE FROM object_deletion_queue WHERE workspace_id=?", workspace.getId());
        jdbc.update("DELETE FROM company WHERE workspace_id=?", workspace.getId());
    }

    private Holding holding() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(organizationPrivacy.current(workspace.getOrgId()));
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Chat file fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        String url = ManagedObjectService.ATTACHMENT_URL_PREFIX + UUID.randomUUID() + ".txt";
        jdbc.update("INSERT INTO attachment (workspace_id,entity_type,entity_id,file_name,url,scan_state) VALUES (?,'ai_chat_session',?,'chat.txt',?,'quarantined')",
                workspace.getId(), session.getId(), url);
        Integer id = jdbc.queryForObject("SELECT MAX(id) FROM attachment WHERE workspace_id=? AND entity_type='ai_chat_session' AND entity_id=?",
                Integer.class, workspace.getId(), session.getId());
        return new Holding(java.util.Objects.requireNonNull(id), url, managed.managedAttachmentKey(workspace.getId(), url).orElseThrow());
    }

    private AiAssistantPrivacyOperation invalidateAll() {
        var operation = transaction().execute(status -> organizationPrivacy.invalidateOrganization(workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
        assertNotNull(operation);
        transaction().executeWithoutResult(status -> privacyService.enrollOperation(workspace.getId(), operation));
        return operation;
    }

    private void drainReference(AiAssistantPrivacyOperation operation) {
        for (int step = 0; step < 40; step++) {
            var work = privacyMapper.getWork(workspace.getId(), operation.getOperationKey());
            assertNotNull(work);
            if ("verify".equals(work.getPhase())) {
                return;
            }
            assertFalse(privacyService.batch(workspace.getId(), operation.getOperationKey()));
        }
        throw new AssertionError("Chat reference cleanup failed to reach verification");
    }

    private int attachmentCount(int id) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE workspace_id=? AND id=?", Integer.class, workspace.getId(), id));
    }

    private String disposition(AiAssistantPrivacyOperation operation) {
        return jdbc.queryForObject("SELECT disposition FROM ai_agent_privacy_object WHERE workspace_id=? AND operation_key=?", String.class,
                workspace.getId(), operation.getOperationKey());
    }

    private record Holding(int attachmentId, String url, String objectKey) {}
}
