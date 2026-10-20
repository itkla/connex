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
    @Autowired private AiAgentRunPersistenceService missionPersistence;
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
    void acceptedMissionPurgeIncludesProposalsPreparedBeforeTenantInvalidation() {
        var command = command();
        AiAgentPurgeAcceptance accepted = requestService.acceptMissionPurge(workspace.getOrgId(), requestId, currentUser.getId(), command);
        int sessionId = newTaintedSession("Accepted-cutoff proposal fixture");
        var scope = new ooo.klae.connex.backend.dto.AiAgentRunCreateRequest.Scope("record", "person", personId);
        var request = new ooo.klae.connex.backend.dto.AiAgentRunCreateRequest(UUID.randomUUID().toString(),
                "record_preparation_v1", "Prepare changes", scope, null);
        var run = missionPersistence.create(sessionId, request, "en", AiAgentCapabilityGate.hash(request.clientRequestKey()),
                json.writeValueAsString(scope), epochs.current(workspace.getId()));
        jdbc.update("INSERT INTO ai_agent_segment (workspace_id,run_id,segment_no,attempt_no,execution_generation,privacy_generation,"
                + "status,reserved_steps,reserved_tokens,provider_declaration_fingerprint,deadline_at,settled_at) "
                + "VALUES (?,?,1,1,1,1,'completed',1,8192,UNHEX(REPEAT('ab',32)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                workspace.getId(), run.getId());
        int segment = java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM ai_agent_segment WHERE workspace_id=? AND run_id=?", Integer.class, workspace.getId(), run.getId()));
        String source = "{\"schemaVersion\":1,\"sources\":[{\"kind\":\"person\",\"id\":" + personId
                + ",\"source\":\"record\",\"limit\":0,\"sha256\":\"" + "a".repeat(64)
                + "\",\"observedAt\":\"2026-01-01T00:00:00Z\"}]}";
        jdbc.update("INSERT INTO ai_agent_action (workspace_id,root_run_id,producer_run_id,action_slot,semantic_slot_key,target_kind,target_id,"
                + "tool_name,plan_item_key,plan_revision,preparation_id,intent_fingerprint,prepared_at,source_observed_at,source_versions_json,"
                + "origin_segment_id,origin_execution_generation,privacy_generation) VALUES (?,?,?,?,?,'person',?,'assign_owner','p0001',1,?,"
                + "UNHEX(REPEAT('ab',32)),'2026-01-02','2026-01-01',CONVERT(? USING utf8mb4),?,1,1)", workspace.getId(), run.getId(), run.getId(),
                UUID.randomUUID().toString(), "person:" + personId + ":assign_owner", personId, UUID.randomUUID().toString(),
                source.getBytes(java.nio.charset.StandardCharsets.UTF_8), segment);
        int message = chatMapper.getMissionMessage(workspace.getId(), run.getId(), "goal").getId();
        jdbc.update("INSERT INTO ai_chat_tool_call (workspace_id,message_id,tool_name,status,arguments_json,server_proposal,mission_run_id,"
                + "artifact_origin,privacy_generation,proposal_origin,mission_action_id) SELECT workspace_id,?,'assign_owner','proposed','{}',"
                + "TRUE,root_run_id,'mission',privacy_generation,'mission',id FROM ai_agent_action WHERE workspace_id=? AND root_run_id=?",
                message, workspace.getId(), run.getId());
        purgeService.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertTrue(privacyMapper.getWork(workspace.getId(), accepted.operationId()).getMaxRunId() >= run.getId());
        finish(accepted.operationId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_action WHERE workspace_id=?", Integer.class, workspace.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=?", Integer.class, workspace.getId()));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), 1));
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
                + " mission_taint_seq, message_seq_high_water, title_privacy_generation) VALUES (?, ?, 'Ask Connex', 1, 1, 1, 1)",
                workspaceId, currentUser.getId());
        int session = java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT MAX(id) FROM ai_chat_session WHERE workspace_id=?", Integer.class, workspaceId));
        jdbc.update("INSERT INTO ai_chat_message (workspace_id, session_id, seq, content, author_kind, privacy_generation)"
                + " VALUES (?, ?, 1, ?, 'user', 1)", workspaceId, session, content);
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
