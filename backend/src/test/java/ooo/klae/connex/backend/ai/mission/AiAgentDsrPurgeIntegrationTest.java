package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentDsrControlMapper;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DataSubjectRequestService;
import ooo.klae.connex.backend.services.DataSubjectRequestSnapshot;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Real control and tenant transactions retain accepted authority across erasure failures. */
class AiAgentDsrPurgeIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAgentDsrPurgeService purgeService;
    @Autowired private ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService organizationPrivacy;
    @Autowired private DataSubjectRequestService requestService;
    @Autowired private DataSubjectRequestMapper requestMapper;
    @Autowired private AiAgentDsrControlMapper receipts;
    @Autowired private TenantWorkScope workScope;
    @Autowired private ObjectMapper json;
    @Autowired private ooo.klae.connex.backend.ai.AiRestrictionEpoch epochs;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @MockitoSpyBean private AuditService audit;
    @MockitoSpyBean private AiAgentPrivacyMapper workMapper;
    private long requestId;
    private int personId;
    private Workspace laterWorkspace;
    private Workspace acceptedWorkspace;

    @BeforeEach
    void prepareVerifiedSubject() {
        jdbc.update("INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'admin')",
                workspace.getOrgId(), currentUser.getId());
        approveForStepUp(currentUser);
        jdbc.update("INSERT INTO person (workspace_id, name) VALUES (?, 'Mission purge subject')", workspace.getId());
        personId = java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM person WHERE workspace_id=?", Integer.class, workspace.getId()));
        var created = requestService.create(workspace.getOrgId(), currentUser.getId(), workflow("received"));
        requestId = created.getId();
    }

    @AfterEach
    void removeDsrFixtures() {
        if (laterWorkspace != null) {
            removeAdditionalWorkspace(laterWorkspace.getId());
        }
        if (acceptedWorkspace != null) {
            removeAdditionalWorkspace(acceptedWorkspace.getId());
        }
        jdbc.update("DELETE FROM data_subject_request WHERE org_id=?", workspace.getOrgId());
        jdbc.update("DELETE FROM person WHERE workspace_id=?", workspace.getId());
        jdbc.update("DELETE FROM org_member WHERE org_id=?", workspace.getOrgId());
    }

    @Test
    void dsrSnapshotUsesFullStoredState() {
        AiAgentHoldingsPurgeRequest stale = command();
        jdbc.update("UPDATE data_subject_request SET summary='Changed within the same stored timestamp',"
                + " updated_at=updated_at WHERE id=?", requestId);

        ConflictException failure = assertThrows(ConflictException.class,
                () -> purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), stale));

        assertEquals("dsr_snapshot_conflict", failure.getMessage());
        assertEquals(0, acceptedCount());
        assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertEquals(0, privacyMapper.countIncompleteWork(workspace.getId()));
    }

    @Test
    void dsrStatusEditDoesNotDelete() {
        requestService.update(workspace.getOrgId(), requestId, currentUser.getId(), workflow("closed"));

        assertEquals("closed", requestMapper.findById(workspace.getOrgId(), requestId).getStatus());
        assertEquals(0, acceptedCount());
        assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        assertEquals(0, privacyMapper.countIncompleteWork(workspace.getId()));
    }

    @Test
    void dsrControlAcceptanceFailureCannotInvalidateTenant() {
        failAudit(AiAgentPurgeAcceptance.ACCEPTED);

        assertThrows(RuntimeException.class, () -> purgeService.initiate(
                workspace.getOrgId(), requestId, currentUser.getId(), command()));

        assertEquals(0, acceptedCount());
        assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertEquals(0, privacyMapper.countIncompleteWork(workspace.getId()));
    }

    @Test
    void dsrReplayAcrossEveryCrashBoundaryPreservesAcceptedOperation() {
        acceptedWorkspace = additionalWorkspace("Accepted before tenant interruption");
        AiAgentHoldingsPurgeRequest command = command();
        AiAgentPrivacyMapper realMapper = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        doAnswer(invocation -> {
            int changed = realMapper.advanceBarrier(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            if (Integer.valueOf(acceptedWorkspace.getId()).equals(invocation.getArgument(0))) {
                throw new IllegalStateException("Injected rollback after both workspace advances");
            }
            return changed;
        }).when(workMapper).advanceBarrier(anyInt(), anyLong(), anyString());
        var pending = purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertEquals("invalidation_pending", pending.state());
        assertEquals(1, acceptedCount());
        assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertNull(privacyMapper.getWork(workspace.getId(), pending.operationId()));
        assertEquals(2, pending.workspaceCount());
        assertEquals(1, workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getBarrier(acceptedWorkspace.getId()).getGeneration()));
        assertNull(workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getWork(acceptedWorkspace.getId(), pending.operationId())));

        doAnswer(invocation -> realMapper.advanceBarrier(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))
                .when(workMapper).advanceBarrier(anyInt(), anyLong(), anyString());
        var installed = purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertEquals(pending.operationId(), installed.operationId());
        var frozen = privacyMapper.getWork(workspace.getId(), installed.operationId());
        assertNotNull(frozen);
        assertEquals(1, frozen.getCutoffGeneration());
        assertEquals(2, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        var siblingFrozen = workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getWork(acceptedWorkspace.getId(), installed.operationId()));
        assertNotNull(siblingFrozen);
        assertEquals(1, siblingFrozen.getCutoffGeneration());
        assertEquals(2, workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getBarrier(acceptedWorkspace.getId()).getGeneration()));
        privacyService.batch(workspace.getId(), installed.operationId());
        var interrupted = privacyMapper.getWork(workspace.getId(), installed.operationId());
        privacyService.initialize(workspace.getId());
        long localEpoch = epochs.current(workspace.getId());
        var afterRestart = purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertEquals(installed.operationId(), afterRestart.operationId());
        assertEquals(localEpoch, epochs.current(workspace.getId()));
        assertEquals(interrupted.getRevision(), privacyMapper.getWork(workspace.getId(), installed.operationId()).getRevision());
        assertEquals(frozen.getMaxMessageId(), privacyMapper.getWork(workspace.getId(), installed.operationId()).getMaxMessageId());
        assertEquals(siblingFrozen, workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getWork(acceptedWorkspace.getId(), installed.operationId())));
        finish(installed.operationId());
        finishWorkspace(acceptedWorkspace.getId(), installed.operationId());
        failAudit(AiAgentPurgeAcceptance.COMPLETED);
        var beforeCompletion = purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertEquals("purging", beforeCompletion.state());
        assertEquals(2, beforeCompletion.completedWorkspaceCount());
        assertNull(receipts.findCompletion(workspace.getOrgId(), requestId, installed.operationId()));
        allowAudit(AiAgentPurgeAcceptance.COMPLETED);
        var completed = purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertEquals("complete", completed.state());
        assertEquals(completed, purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command));
        assertEquals(2, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertEquals(2, workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getBarrier(acceptedWorkspace.getId()).getGeneration()));
        assertEquals(1, acceptedCount());
        assertEquals(1, auditCount(AiAgentPurgeAcceptance.COMPLETED));
    }

    @Test
    void dsrRecoveryPreservesTwoWorkspaceAcceptanceAtEachCommittedBoundary() {
        acceptedWorkspace = additionalWorkspace("Accepted durable recovery fixture");
        int firstSession = newTaintedSession("Accepted first workspace content");
        int secondSession = newTaintedSession(acceptedWorkspace.getId(), "Accepted second workspace content");
        AiAgentHoldingsPurgeRequest command = command();
        AiAgentPurgeAcceptance accepted = requestService.acceptMissionPurge(
                workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertEquals(List.of(workspace.getId(), acceptedWorkspace.getId()), accepted.workspaceIds());
        assertEquals(accepted, AiAgentPurgeAcceptance.from(receipts.findAcceptance(
                workspace.getOrgId(), requestId, accepted.operationId()), json));
        assertNull(privacyMapper.getWork(workspace.getId(), accepted.operationId()));
        assertNull(workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getWork(acceptedWorkspace.getId(), accepted.operationId())));

        purgeService.recoverAccepted();
        var firstInstalled = privacyMapper.getWork(workspace.getId(), accepted.operationId());
        var secondInstalled = workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getWork(acceptedWorkspace.getId(), accepted.operationId()));
        assertNotNull(firstInstalled);
        assertNotNull(secondInstalled);
        assertEquals(1, firstInstalled.getCutoffGeneration());
        assertEquals(1, secondInstalled.getCutoffGeneration());
        assertEquals(accepted.auditId(), firstInstalled.getControlAcceptanceAuditId());
        assertEquals(accepted.auditId(), secondInstalled.getControlAcceptanceAuditId());
        assertEquals(1, firstInstalled.getRevision());
        assertEquals(1, secondInstalled.getRevision());
        assertTrue(firstInstalled.getMaxSessionId() >= firstSession);
        assertTrue(secondInstalled.getMaxSessionId() >= secondSession);
        assertEquals(2, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertEquals(2, workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getBarrier(acceptedWorkspace.getId()).getGeneration()));

        privacyService.initialize(workspace.getId());
        workScope.inWorkspace(acceptedWorkspace.getId(), () -> privacyService.initialize(acceptedWorkspace.getId()));
        purgeService.recoverAccepted();
        purgeService.recoverAccepted();
        assertEquals(firstInstalled, privacyMapper.getWork(workspace.getId(), accepted.operationId()));
        assertEquals(secondInstalled, workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getWork(acceptedWorkspace.getId(), accepted.operationId())));
        finish(accepted.operationId());
        assertEquals(1, purgeService.get(workspace.getOrgId(), requestId, currentUser.getId(),
                accepted.operationId()).completedWorkspaceCount());
        assertNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
        assertTrue(workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getBarrier(acceptedWorkspace.getId()).isBlocked()));

        finishWorkspace(acceptedWorkspace.getId(), accepted.operationId());
        var beforeC2 = purgeService.get(workspace.getOrgId(), requestId, currentUser.getId(), accepted.operationId());
        assertEquals("purging", beforeC2.state());
        assertEquals(2, beforeC2.completedWorkspaceCount());
        assertNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE session_id IN (?,?)",
                Integer.class, firstSession, secondSession));
        purgeService.recoverAccepted();
        purgeService.recoverAccepted();
        var complete = purgeService.get(workspace.getOrgId(), requestId, currentUser.getId(), accepted.operationId());
        assertEquals("complete", complete.state());
        assertEquals(complete, purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command));
        assertEquals(1, acceptedCount());
        assertEquals(1, auditCount(AiAgentPurgeAcceptance.COMPLETED));
        assertEquals(2, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertEquals(2, workScope.inWorkspace(acceptedWorkspace.getId(),
                () -> privacyMapper.getBarrier(acceptedWorkspace.getId()).getGeneration()));
    }

    @Test
    void dsrAcceptanceToInvalidationUsesDeclaredCutoffPolicy() {
        AiAgentHoldingsPurgeRequest command = command();
        AiAgentPurgeAcceptance accepted = requestService.acceptMissionPurge(
                workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertNull(privacyMapper.getWork(workspace.getId(), accepted.operationId()));
        int sessionId = newTaintedSession("Content committed after acceptance");
        laterWorkspace = new Workspace();
        laterWorkspace.setOrgId(workspace.getOrgId());
        laterWorkspace.setName("Created after immutable C1 scope");
        laterWorkspace.setSlug("mission-after-c1-" + UUID.randomUUID());
        workspaceMapper.insert(laterWorkspace);
        workScope.inWorkspace(laterWorkspace.getId(), () -> privacyService.initialize(laterWorkspace.getId()));

        var progress = purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command);
        var installed = privacyMapper.getWork(workspace.getId(), accepted.operationId());
        assertEquals(1, progress.workspaceCount());
        assertTrue(installed.getMaxSessionId() >= sessionId);
        assertEquals(List.of(workspace.getId()), accepted.workspaceIds());
        assertNull(workScope.inWorkspace(laterWorkspace.getId(),
                () -> privacyMapper.getWork(laterWorkspace.getId(), accepted.operationId())));
        assertEquals(1, workScope.inWorkspace(laterWorkspace.getId(),
                () -> privacyMapper.getBarrier(laterWorkspace.getId()).getGeneration()));
        finish(accepted.operationId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE session_id=?", Integer.class, sessionId));
        assertEquals("complete", purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command).state());
        assertEquals(installed.getMaxSessionId(), privacyMapper.getWork(workspace.getId(), accepted.operationId()).getMaxSessionId());
    }

    @Test
    void dsrRequestRelinkCannotRetargetAcceptedPurge() {
        AiAgentHoldingsPurgeRequest command = command();
        AiAgentPurgeAcceptance accepted = requestService.acceptMissionPurge(
                workspace.getOrgId(), requestId, currentUser.getId(), command);
        jdbc.update("UPDATE data_subject_request SET subject_workspace_id=NULL, subject_person_id=NULL,"
                + " identity_verified_at=NULL, summary='Edited after accepted authority' WHERE id=?", requestId);
        jdbc.update("DELETE FROM org_member WHERE org_id=? AND user_id=?", workspace.getOrgId(), currentUser.getId());
        purgeService.recoverAccepted();
        var installed = privacyMapper.getWork(workspace.getId(), accepted.operationId());
        assertNotNull(installed);
        assertEquals(accepted.auditId(), installed.getControlAcceptanceAuditId());
        finish(accepted.operationId());
        purgeService.recoverAccepted();
        purgeService.recoverAccepted();
        assertNotNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
        assertThrows(ooo.klae.connex.backend.exceptions.ForbiddenException.class,
                () -> purgeService.get(workspace.getOrgId(), requestId, currentUser.getId(), accepted.operationId()));
        var durable = AiAgentPurgeAcceptance.from(receipts.findAcceptance(
                workspace.getOrgId(), requestId, accepted.operationId()), json);
        assertEquals(accepted, durable);
    }

    @Test
    void v2AcceptanceAdvancesOrganizationOnce() {
        AiAgentHoldingsPurgeRequest command = assistantCommand();
        var accepted = requestService.acceptMissionPurge(workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertEquals(2, accepted.schemaVersion());
        assertEquals(Long.valueOf(1), accepted.cutoffGeneration());
        assertEquals(Long.valueOf(2), accepted.invalidatedGeneration());
        assertEquals(2, organizationPrivacy.current(workspace.getOrgId()));
        assertEquals(accepted, requestService.acceptMissionPurge(workspace.getOrgId(), requestId, currentUser.getId(), command));
        assertEquals(2, organizationPrivacy.current(workspace.getOrgId()));
        assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        var operation = organizationPrivacy.findOperation(workspace.getOrgId(), accepted.operationId());
        assertEquals(accepted.auditId(), operation.getControlAcceptanceAuditId());
        assertEquals(1, acceptedCount());
    }

    @Test
    void v1ReplayCannotBecomeV2() {
        AiAgentHoldingsPurgeRequest original = command();
        var accepted = requestService.acceptMissionPurge(workspace.getOrgId(), requestId, currentUser.getId(), original);
        var changed = new AiAgentHoldingsPurgeRequest(original.idempotencyKey(), original.snapshotToken(),
                AiAgentPurgeAcceptance.ASSISTANT_SCOPE, AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY);
        assertThrows(ConflictException.class, () -> requestService.acceptMissionPurge(
                workspace.getOrgId(), requestId, currentUser.getId(), changed));
        assertEquals(accepted, AiAgentPurgeAcceptance.from(receipts.findAcceptance(
                workspace.getOrgId(), requestId, accepted.operationId()), json));
        assertEquals(1, organizationPrivacy.current(workspace.getOrgId()));
    }

    @Test
    void v2RecoveryUsesAcceptedCutoff() {
        int old = newTaintedSession("Accepted old content");
        var command = assistantCommand();
        var accepted = requestService.acceptMissionPurge(workspace.getOrgId(), requestId, currentUser.getId(), command);
        int current = newTaintedSession("Current content after acceptance");
        jdbc.update("UPDATE data_subject_request SET subject_person_id=NULL, subject_workspace_id=NULL, identity_verified_at=NULL WHERE id=?", requestId);
        jdbc.update("DELETE FROM org_member WHERE org_id=? AND user_id=?", workspace.getOrgId(), currentUser.getId());
        purgeService.recoverAccepted();
        var installed = privacyMapper.getWork(workspace.getId(), accepted.operationId());
        assertNotNull(installed);
        assertEquals(1, installed.getCutoffGeneration());
        assertEquals(AiAgentPurgeAcceptance.ASSISTANT_SCOPE, installed.getContentScope());
        byte[] fingerprint = installed.getOperationFingerprint();
        privacyService.initialize(workspace.getId());
        purgeService.recoverAccepted();
        org.junit.jupiter.api.Assertions.assertArrayEquals(fingerprint,
                privacyMapper.getWork(workspace.getId(), accepted.operationId()).getOperationFingerprint());
        finish(accepted.operationId());
        purgeService.recoverAccepted();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND id=?", Integer.class, workspace.getId(), old));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND id=?", Integer.class, workspace.getId(), current));
        assertNotNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
    }

    @Test
    void missingWorkspaceStillRequiresLifecycleEvidence() {
        acceptedWorkspace = additionalWorkspace("Accepted workspace later missing");
        var accepted = purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), assistantCommand());
        finish(accepted.operationId());
        removeAdditionalWorkspace(acceptedWorkspace.getId());
        purgeService.recoverAccepted();
        assertNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
        assertThrows(RuntimeException.class, () -> purgeService.get(
                workspace.getOrgId(), requestId, currentUser.getId(), accepted.operationId()));
    }

    @Test
    void verifiedDeletedWorkspaceCanFinishRecoveryAndC2FromItsDurableReceipt() {
        acceptedWorkspace = additionalWorkspace("Verified workspace later deleted");
        var accepted = purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), assistantCommand());
        finish(accepted.operationId());
        finishWorkspace(acceptedWorkspace.getId(), accepted.operationId());
        assertNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
        jdbc.update("UPDATE workspace SET lifecycle_state='tearing_down' WHERE id=?", acceptedWorkspace.getId());
        jdbc.update("DELETE FROM ai_agent_privacy_object WHERE workspace_id=?", acceptedWorkspace.getId());
        jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id=?", acceptedWorkspace.getId());
        purgeService.recoverAccepted();
        workScope.inLifecycleWorkspace(acceptedWorkspace.getId(), () -> privacyService.initialize(acceptedWorkspace.getId()));
        assertNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_assistant_privacy_operation_workspace"
                + " WHERE organization_id=? AND operation_key=? AND workspace_id=? AND state='verified'", Integer.class,
                workspace.getOrgId(), accepted.operationId(), acceptedWorkspace.getId()));
        removeAdditionalWorkspace(acceptedWorkspace.getId());

        purgeService.recoverAccepted();

        assertNotNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
        var current = purgeService.get(workspace.getOrgId(), requestId, currentUser.getId(), accepted.operationId());
        assertEquals("complete", current.state());
        assertEquals(2, current.completedWorkspaceCount());
    }

    private AiAgentHoldingsPurgeRequest assistantCommand() {
        return new AiAgentHoldingsPurgeRequest(UUID.randomUUID().toString(),
                DataSubjectRequestSnapshot.token(requestMapper.findById(workspace.getOrgId(), requestId)),
                AiAgentPurgeAcceptance.ASSISTANT_SCOPE, AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY);
    }

    private AiAgentHoldingsPurgeRequest command() {
        return new AiAgentHoldingsPurgeRequest(UUID.randomUUID().toString(),
                DataSubjectRequestSnapshot.token(requestMapper.findById(workspace.getOrgId(), requestId)),
                AiAgentPurgeAcceptance.SCOPE, AiAgentPurgeAcceptance.CUTOFF_POLICY);
    }

    private DataSubjectRequestUpsertRequest workflow(String status) {
        DataSubjectRequestUpsertRequest request = new DataSubjectRequestUpsertRequest();
        request.setRequestType("cease_use");
        request.setStatus(status);
        request.setRequesterName("Mission purge requester");
        request.setSubjectName("Mission purge subject");
        request.setSubjectWorkspaceId(workspace.getId());
        request.setSubjectPersonId(personId);
        request.setReceivedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        request.setIdentityVerifiedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        return request;
    }

    private int newTaintedSession(String content) {
        return newTaintedSession(workspace.getId(), content);
    }

    private int newTaintedSession(int workspaceId, String content) {
        jdbc.update("INSERT INTO ai_chat_session (workspace_id, created_by_user_id, title, mission_taint_generation,"
                + " mission_taint_seq, message_seq_high_water, title_privacy_generation, organization_privacy_generation) VALUES (?, ?, 'Ask Connex', 1, 1, 1, 1, ?)",
                workspaceId, currentUser.getId(), organizationPrivacy.current(workspace.getOrgId()));
        int session = java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT MAX(id) FROM ai_chat_session WHERE workspace_id=?", Integer.class, workspaceId));
        jdbc.update("INSERT INTO ai_chat_message (workspace_id, session_id, seq, content, author_kind, privacy_generation, organization_privacy_generation)"
                + " VALUES (?, ?, 1, ?, 'user', 1, ?)", workspaceId, session, content, organizationPrivacy.current(workspace.getOrgId()));
        return session;
    }

    private Workspace additionalWorkspace(String name) {
        Workspace additional = new Workspace();
        additional.setOrgId(workspace.getOrgId());
        additional.setName(name);
        additional.setSlug("mission-dsr-recovery-" + UUID.randomUUID());
        workspaceMapper.insert(additional);
        workScope.inWorkspace(additional.getId(), () -> privacyService.initialize(additional.getId()));
        return additional;
    }

    private void finishWorkspace(int workspaceId, String operation) {
        workScope.inWorkspace(workspaceId, () -> {
            for (int count = 0; count < 100; count++) {
                if (privacyService.batch(workspaceId, operation)) {
                    return;
                }
            }
            throw new AssertionError("Accepted workspace did not complete within its fixture batch bound");
        });
    }

    private void removeAdditionalWorkspace(int workspaceId) {
        jdbc.update("DELETE FROM ai_chat_turn WHERE workspace_id=?", workspaceId);
        jdbc.update("DELETE FROM ai_chat_tool_call WHERE workspace_id=?", workspaceId);
        jdbc.update("DELETE FROM ai_chat_message WHERE workspace_id=?", workspaceId);
        jdbc.update("DELETE FROM ai_chat_session WHERE workspace_id=?", workspaceId);
        jdbc.update("UPDATE ai_agent_privacy_barrier SET latest_operation_key=NULL WHERE workspace_id=?", workspaceId);
        jdbc.update("DELETE FROM ai_agent_privacy_object WHERE workspace_id=?", workspaceId);
        jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id=?", workspaceId);
        jdbc.update("DELETE FROM ai_agent_privacy_barrier WHERE workspace_id=?", workspaceId);
        jdbc.update("DELETE FROM workspace WHERE id=?", workspaceId);
    }

    private int acceptedCount() {
        return auditCount(AiAgentPurgeAcceptance.ACCEPTED);
    }

    private int auditCount(String action) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE org_id=? AND action=?", Integer.class, workspace.getOrgId(), action));
    }

    private void failAudit(String action) {
        doThrow(new IllegalStateException("Injected strict audit failure")).when(audit).recordStrictScoped(
                eq(action), eq("organization"), eq(workspace.getOrgId()), isNull(), eq(workspace.getOrgId()),
                anyString(), anyString(), any());
    }

    private void allowAudit(String action) {
        doCallRealMethod().when(audit).recordStrictScoped(
                eq(action), eq("organization"), eq(workspace.getOrgId()), isNull(), eq(workspace.getOrgId()),
                anyString(), anyString(), any());
    }
}
