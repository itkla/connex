package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Database constraints and lifecycle maintenance are exercised independently of execution gates. */
class AiAgentPrivacyLifecycleIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAgentRunPersistenceService persistence;
    @Autowired private AiAgentRunMapper runs;
    @Autowired private AiAgentCapabilityGate capability;
    @Autowired private AiProperties properties;
    @Autowired private AiRestrictionEpoch epoch;
    @Autowired private AiAgentDepartureMaintenanceService departures;
    @Autowired private TenantLifecycleMapper lifecycle;
    @Autowired private TenantWorkScope scope;
    @Autowired private ObjectMapper json;
    @Autowired private ooo.klae.connex.backend.services.PersonService people;

    @Test
    void restrictionAndInvalidationCommitTogether() {
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, workspace.getId());
        jdbc.update("INSERT INTO person (workspace_id, name) VALUES (?, 'Restriction transaction subject')", workspace.getId());
        int personId = java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM person WHERE workspace_id=?", Integer.class, workspace.getId()));
        try {
            assertThrows(IllegalStateException.class, () -> transaction().executeWithoutResult(status -> {
                people.updateProcessingRestrictions(personId, true, false);
                throw new IllegalStateException("Injected crash before restriction commit");
            }));
            assertNull(jdbc.queryForObject("SELECT suspended_at FROM person WHERE id=?", java.sql.Timestamp.class, personId));
            assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
            assertEquals(0, privacyMapper.countIncompleteWork(workspace.getId()));
            people.updateProcessingRestrictions(personId, true, false);
            assertNotNull(jdbc.queryForObject("SELECT suspended_at FROM person WHERE id=?", java.sql.Timestamp.class, personId));
            assertEquals(2, privacyMapper.getBarrier(workspace.getId()).getGeneration());
            assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
            assertEquals(1, privacyMapper.countIncompleteWork(workspace.getId()));
            privacyService.initialize(workspace.getId());
            assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        } finally {
            jdbc.update("DELETE FROM person WHERE id=?", personId);
        }
    }

    @Test
    void privacyCleanupRunsWithAiDisabledAndOwnerGone() {
        AiAgentRun run = create();
        var attempt = persistence.claim(run, AiAgentCapabilityGate.hash("lifecycle fixture"),
                new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        persistence.reserveDispatch(attempt, 100);
        String operation = invalidate();
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?", workspace.getId(), currentUser.getId());
        jdbc.update("DELETE FROM app_user WHERE id=?", currentUser.getId());
        boolean previousAi = properties.isEnabled();
        boolean previousMissions = properties.isMissionsEnabled();
        try {
            properties.setEnabled(false);
            properties.setMissionsEnabled(false);
            finish(operation);
            privacyService.reconcileErasedOwners(workspace.getId());
        } finally {
            properties.setEnabled(previousAi);
            properties.setMissionsEnabled(previousMissions);
        }
        AiAgentRun purged = runs.getRun(workspace.getId(), run.getId());
        assertEquals("cancelled", purged.getStatus());
        assertNotNull(purged.getPurgedAt());
        assertNull(purged.getOwnerUserId());
        assertNull(purged.getGoal());
        assertTrue(runs.listSegments(workspace.getId(), run.getId()).isEmpty());
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), 1));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM ai_run_lease"
                + " WHERE workspace_id=? AND subject_kind='agent_run' AND subject_id=? AND released_at IS NULL",
                Integer.class, workspace.getId(), run.getId()));
    }

    @Test
    void departureCrashCannotReviveAnUnsettledMissionOnRejoin() {
        AiAgentRun run = create();
        persistence.claim(run, AiAgentCapabilityGate.hash("departure fixture"),
                new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?", workspace.getId(), currentUser.getId());
        assertThrows(ConflictException.class, () -> transaction().executeWithoutResult(status -> {
            workspaceMapper.lockAuthorizationMembership(workspace.getId(), currentUser.getId());
            departures.requireSettledBeforeFreshMembership(workspace.getId(), currentUser.getId());
        }));
        scope.inLifecycleWorkspace(workspace.getId(), () -> departures.reconcile(workspace.getId()));
        assertEquals("cancelled", runs.getRun(workspace.getId(), run.getId()).getStatus());
        assertNull(runs.getRun(workspace.getId(), run.getId()).getPurgedAt());
        assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        transaction().executeWithoutResult(status -> {
            workspaceMapper.lockAuthorizationMembership(workspace.getId(), currentUser.getId());
            departures.requireSettledBeforeFreshMembership(workspace.getId(), currentUser.getId());
            workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        });
        AiAgentRun cancelled = runs.getRun(workspace.getId(), run.getId());
        assertThrows(ConflictException.class, () -> persistence.control(cancelled, cancelled.getStateVersion(), "resume"));
    }

    @Test
    void delayedDepartureEventCannotCancelANewMembershipMission() {
        AiAgentRun old = create();
        persistence.cancelForDeparture(old);
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?", workspace.getId(), currentUser.getId());
        transaction().executeWithoutResult(status -> {
            workspaceMapper.lockAuthorizationMembership(workspace.getId(), currentUser.getId());
            departures.requireSettledBeforeFreshMembership(workspace.getId(), currentUser.getId());
            workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        });
        AiAgentRun current = create();

        departures.departed(new AiAgentDepartureMaintenanceService.Departure(workspace.getId(), currentUser.getId()));

        assertEquals("cancelled", runs.getRun(workspace.getId(), old.getId()).getStatus());
        assertEquals("queued", runs.getRun(workspace.getId(), current.getId()).getStatus());
        assertEquals(current.getExecutionGeneration(), runs.getRun(workspace.getId(), current.getId()).getExecutionGeneration());
        assertEquals(1, privacyMapper.getBarrier(workspace.getId()).getGeneration());
    }

    @Test
    void v231CompositeConstraintsRejectCrossWorkspaceMissionChildren() {
        AiAgentRun run = create();
        var attempt = persistence.claim(run, AiAgentCapabilityGate.hash("constraint fixture"),
                new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        int unrelatedWorkspace = Integer.MAX_VALUE;
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE ai_agent_segment SET workspace_id=? WHERE workspace_id=? AND id=?",
                unrelatedWorkspace, workspace.getId(), attempt.segmentId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE ai_chat_turn SET mission_segment_id=? WHERE workspace_id=? AND id=?",
                Integer.MAX_VALUE, workspace.getId(), attempt.turn().turnId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE ai_chat_message SET mission_run_id=? WHERE workspace_id=? AND mission_run_id=?",
                Integer.MAX_VALUE, workspace.getId(), run.getId()));
        assertEquals(workspace.getId(), runs.getSegment(workspace.getId(), attempt.segmentId()).getWorkspaceId());
        assertEquals(attempt.segmentId(), runs.getRun(workspace.getId(), run.getId()).getActiveSegmentId());
    }

    @Test
    void cyclicMissionLinksAreSettledBeforeRegistryTeardown() {
        AiAgentRun run = create();
        persistence.claim(run, AiAgentCapabilityGate.hash("teardown fixture"),
                new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        privacyService.prepareWorkspaceTeardown(workspace.getId());
        var declarations = List.of("ai_chat_tool_call", "ai_chat_message", "ai_chat_turn", "ai_agent_segment",
                "ai_agent_run", "ai_agent_privacy_work", "ai_agent_privacy_barrier", "ai_chat_session").stream()
                .map(TenantLifecycleRegistry::require)
                .sorted(Comparator.comparingInt(TenantLifecycleRegistry.TableLifecycle::deleteOrder)).toList();
        transaction().executeWithoutResult(status -> {
            for (var declaration : declarations) {
                for (var preparation : declaration.preparations()) {
                    if (preparation instanceof TenantLifecycleRegistry.NullifyReference reference) {
                        lifecycle.nullifyReference(workspace.getId(), declaration, reference);
                    }
                }
            }
            for (var declaration : declarations) {
                lifecycle.deleteDirectBatch(workspace.getId(), declaration, 100);
            }
        });
        for (var declaration : declarations) {
            assertEquals(0, lifecycle.countRows(workspace.getId(), declaration), declaration.table());
        }
    }

    @Test
    void purgeAfterMoreThanThreeAttemptsErasesContinuationHoldings() {
        AiAgentRun run = create();
        for (int logical = 1; logical <= 2; logical++) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                var policy = persistence.claim(runs.getRun(workspace.getId(), run.getId()),
                        AiAgentCapabilityGate.hash("privacy continuation"), new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
                if (attempt < 3) {
                    persistence.interrupt(runs.getRun(workspace.getId(), run.getId()), "interrupted");
                    assertTrue(persistence.requeueInterrupted(runs.getRun(workspace.getId(), run.getId())));
                } else {
                    var snapshot = persistence.snapshot(policy);
                    var args = json.createObjectNode().put("expectedPlanRevision", snapshot.plan().revision())
                            .put("expectedCheckpointRevision", snapshot.checkpoint().revision()).putNull("nextPlanItemKey").put("phase", "reading");
                    args.putObject("memory").put("summary", "Sensitive durable continuation").putArray("citations");
                    var prepared = new AiAgentPlanService(new AiAgentContinuationCodec()).prepareCheckpoint(snapshot, args,
                            policy.segmentNo(), text -> new AiAgentMemory.DurableText(text, List.of()), ignored -> List.of());
                    persistence.commitCheckpoint(policy, 1, 0, json.writeValueAsString(prepared.arguments()),
                            json.writeValueAsString(prepared.result()), prepared.snapshot());
                }
            }
        }
        assertEquals(6, runs.listSegments(workspace.getId(), run.getId()).size());
        assertTrue(runs.getRun(workspace.getId(), run.getId()).getMemoryJson().contains("Sensitive durable continuation"));
        finish(invalidate());
        var erased = runs.getRun(workspace.getId(), run.getId());
        assertTrue(AiAgentContinuationCodec.erased(erased));
        assertNull(erased.getGoal());
        assertTrue(runs.listSegments(workspace.getId(), run.getId()).isEmpty());
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), erased.getPrivacyGeneration()));
        privacyService.prepareWorkspaceTeardown(workspace.getId());
        assertEquals(1, lifecycle.countRows(workspace.getId(), TenantLifecycleRegistry.require("ai_agent_run")));
        assertEquals(0, lifecycle.countRows(workspace.getId(), TenantLifecycleRegistry.require("ai_agent_segment")));
    }

    @Test
    void purgedContinuationResiduePreventsCompletionUntilErased() {
        var run = create();
        finish(invalidate());
        jdbc.update("UPDATE ai_agent_run SET plan_json='{\"unknown\":true}', memory_schema_version=99, checkpoint_revision=9, last_committed_segment_no=7 WHERE workspace_id=? AND id=?",
                workspace.getId(), run.getId());
        assertTrue(privacyMapper.residualCount(workspace.getId(), run.getPrivacyGeneration()) > 0);
        String repair = invalidate();
        var work = privacyMapper.getWork(workspace.getId(), repair);
        assertEquals(List.of(run.getId()), privacyMapper.listAffectedRunIds(workspace.getId(), work.getCutoffGeneration(), 0, work.getMaxRunId(), 100));
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        finish(repair);
        assertTrue(AiAgentContinuationCodec.erased(runs.getRun(workspace.getId(), run.getId())));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), work.getCutoffGeneration()));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    @Test
    void actionSlotsAndCanonicalProposalsPurgeBeforeOriginSegments() {
        AiAgentRun run = createPreparationHoldings();
        String operation = invalidate();
        assertFalse(privacyService.batch(workspace.getId(), operation));
        assertEquals(25, count("ai_agent_action"));
        assertEquals(25, count("ai_chat_tool_call"));
        assertEquals(24, count("ai_agent_segment"));
        assertEquals(0, privacyMapper.getWork(workspace.getId(), operation).getLastRunId());
        assertNull(runs.getRun(workspace.getId(), run.getId()).getPurgedAt());
        assertNotNull(runs.getRun(workspace.getId(), run.getId()).getWithheldAt());
        assertFalse(privacyService.batch(workspace.getId(), operation));
        assertEquals(0, count("ai_agent_action"));
        assertEquals(0, count("ai_chat_tool_call"));
        assertEquals(0, count("ai_agent_segment"));
        assertEquals(run.getId(), privacyMapper.getWork(workspace.getId(), operation).getLastRunId());
        var erased = runs.getRun(workspace.getId(), run.getId());
        assertEquals(0, erased.getProposalMembershipRevision());
        assertNull(erased.getProposalFrozenAt());
        assertNull(erased.getProposalFirstPreparedAt());
        assertNull(erased.getReviewExpiresAt());
        finish(operation);
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), 1));
    }

    @Test
    void disabledAiAndErasedOwnerDoNotBlockProposalPurge() {
        AiAgentRun run = createPreparationHoldings();
        String operation = invalidate();
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?", workspace.getId(), currentUser.getId());
        jdbc.update("DELETE FROM app_user WHERE id=?", currentUser.getId());
        boolean enabled = properties.isEnabled();
        boolean missions = properties.isMissionsEnabled();
        try {
            properties.setEnabled(false);
            properties.setMissionsEnabled(false);
            finish(operation);
        } finally {
            properties.setEnabled(enabled);
            properties.setMissionsEnabled(missions);
        }
        assertNotNull(runs.getRun(workspace.getId(), run.getId()).getPurgedAt());
        assertEquals(0, count("ai_agent_action"));
        assertEquals(0, count("ai_chat_tool_call"));
        assertEquals(0, count("ai_agent_segment"));
    }

    @Test
    void workspaceTeardownRemovesActionProposalAndSegmentRows() {
        createPreparationHoldings();
        privacyService.prepareWorkspaceTeardown(workspace.getId());
        for (String table : List.of("ai_agent_action", "ai_chat_tool_call", "ai_agent_segment")) {
            assertEquals(0, lifecycle.countRows(workspace.getId(), TenantLifecycleRegistry.require(table)));
        }
    }

    @Test
    void actionResidualCountingIgnoresDiscoveryWatermarks() {
        AiAgentRun run = createPreparationHoldings();
        String operation = invalidate();
        jdbc.update("UPDATE ai_agent_privacy_work SET max_run_id=0, last_run_id=0, phase='verify' WHERE workspace_id=? AND operation_key=?",
                workspace.getId(), operation);
        assertFalse(privacyService.batch(workspace.getId(), operation));
        assertTrue(privacyMapper.getWork(workspace.getId(), operation).getLastResidualCount() >= 50);
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        jdbc.update("UPDATE ai_agent_privacy_work SET max_run_id=?, phase='runs' WHERE workspace_id=? AND operation_key=?",
                run.getId(), workspace.getId(), operation);
        finish(operation);
    }

    @Test
    void sourceObservationCheckpointResiduePreventsCompletion() {
        AiAgentRun run = createPreparationHoldings();
        finish(invalidate());
        jdbc.update("UPDATE ai_agent_run SET checkpoint_schema_version=2, checkpoint_json=? WHERE workspace_id=? AND id=?",
                "{\"sourceObservations\":[{\"observedAt\":\"2026-01-01T00:00:00Z\"}]}", workspace.getId(), run.getId());
        assertTrue(privacyMapper.residualCount(workspace.getId(), run.getPrivacyGeneration()) > 0);
        String repair = invalidate();
        var work = privacyMapper.getWork(workspace.getId(), repair);
        assertEquals(List.of(run.getId()), privacyMapper.listAffectedRunIds(workspace.getId(),
                work.getCutoffGeneration(), 0, work.getMaxRunId(), 100));
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        finish(repair);
        assertTrue(AiAgentContinuationCodec.erased(runs.getRun(workspace.getId(), run.getId())));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), work.getCutoffGeneration()));
    }

    @Test
    void blockedOrStaleActionsAreOmittedWhileResidualCountsRemainUnconditional() {
        createPreparationHoldings();
        assertEquals(50, exportedActions());
        String operation = invalidate();
        assertEquals(0, exportedActions());
        assertTrue(privacyMapper.residualCount(workspace.getId(), 1) >= 50);
        transaction().executeWithoutResult(status -> {
            jdbc.update("UPDATE ai_agent_privacy_barrier SET blocked=FALSE WHERE workspace_id=?", workspace.getId());
            assertEquals(0, exportedActions());
            assertTrue(privacyMapper.residualCount(workspace.getId(), 1) >= 50);
            status.setRollbackOnly();
        });
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        finish(operation);
    }

    private int exportedActions() {
        return java.util.Objects.requireNonNull(transaction().execute(status -> {
            int count = 0;
            try (var cursor = lifecycle.streamRows(workspace.getId(), TenantLifecycleRegistry.require("ai_agent_action"))) {
                for (var ignored : cursor) {
                    count++;
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            return count;
        }));
    }

    private int count(String table) {
        return Math.toIntExact(lifecycle.countRows(workspace.getId(), TenantLifecycleRegistry.require(table)));
    }

    private AiAgentRun createPreparationHoldings() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Preparation privacy fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_preparation_v1", "Prepare changes",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        AiAgentRun run = persistence.create(session.getId(), request, "en", AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
        int firstSegment = 0;
        for (int segment = 1; segment <= 8; segment++) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                jdbc.update("INSERT INTO ai_agent_segment (workspace_id,run_id,segment_no,attempt_no,execution_generation,privacy_generation,"
                        + "status,reserved_steps,reserved_tokens,provider_declaration_fingerprint,deadline_at,settled_at) "
                        + "VALUES (?,?,?,?,1,?,'completed',1,8192,UNHEX(REPEAT('ab',32)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                        workspace.getId(), run.getId(), segment, attempt, run.getPrivacyGeneration());
            }
        }
        firstSegment = java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT MIN(id) FROM ai_agent_segment WHERE workspace_id=? AND run_id=?", Integer.class, workspace.getId(), run.getId()));
        int messageId = chatMapper.getMissionMessage(workspace.getId(), run.getId(), "goal").getId();
        for (int index = 1; index <= 50; index++) {
            String source = "{\"schemaVersion\":1,\"sources\":[{\"kind\":\"company\",\"id\":" + index
                    + ",\"source\":\"record\",\"limit\":0,\"sha256\":\"" + "a".repeat(64)
                    + "\",\"observedAt\":\"2026-01-01T00:00:00Z\"}]}";
            jdbc.update("INSERT INTO ai_agent_action (workspace_id,root_run_id,producer_run_id,action_slot,semantic_slot_key,target_kind,target_id,"
                    + "tool_name,plan_item_key,plan_revision,preparation_id,intent_fingerprint,prepared_at,source_observed_at,source_versions_json,"
                    + "origin_segment_id,origin_execution_generation,privacy_generation) VALUES (?,?,?,?,?,'company',?,'assign_owner','p0001',1,?,"
                    + "UNHEX(REPEAT('ab',32)),'2026-01-02','2026-01-01',CONVERT(? USING utf8mb4),?,1,?)",
                    workspace.getId(), run.getId(), run.getId(), UUID.randomUUID().toString(), "company:" + index + ":assign_owner",
                    index, UUID.randomUUID().toString(), source.getBytes(java.nio.charset.StandardCharsets.UTF_8), firstSegment, run.getPrivacyGeneration());
        }
        jdbc.update("INSERT INTO ai_chat_tool_call (workspace_id,message_id,tool_name,status,arguments_json,server_proposal,mission_run_id,"
                + "artifact_origin,privacy_generation,proposal_origin,mission_action_id) SELECT workspace_id,?,'assign_owner','proposed','{}',"
                + "TRUE,root_run_id,'mission',privacy_generation,'mission',id FROM ai_agent_action WHERE workspace_id=? AND root_run_id=?",
                messageId, workspace.getId(), run.getId());
        return run;
    }

    private AiAgentRun create() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Lifecycle mission");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review this record",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        return persistence.create(session.getId(), request, "en", AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
    }
}
