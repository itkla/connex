package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionResponse;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Every racing writer is released only after the competing physical connection reaches its real row wait. */
class AiAgentProposalConcurrencyIntegrationTest extends AbstractAiAgentProposalIntegrationTest {
    @MockitoSpyBean private AiAgentRunMapper runLocks;
    @MockitoSpyBean private WorkspaceService authority;
    @MockitoSpyBean private CompanyMapper companyLocks;
    @MockitoSpyBean private ooo.klae.connex.backend.mappers.AiChatMapper chatLocks;
    @MockitoSpyBean private ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper privacyLocks;
    @Autowired private SqlSessionTemplate sqlSessions;
    @Autowired private AiAgentRealtimePublisher realtime;
    @MockitoBean private org.springframework.messaging.simp.SimpMessagingTemplate messaging;

    @Test
    void listReleasesEachItemsBarrierBeforePurgeAndQueuedBarrierWriterContend() throws Exception {
        var older = frozen("assign_owner");
        frozen("assign_owner");
        String operation = invalidate();
        CountDownLatch listBarrierHeld = new CountDownLatch(1);
        CountDownLatch releaseList = new CountDownLatch(1);
        CountDownLatch purgeSessionHeld = new CountDownLatch(1);
        CountDownLatch releasePurge = new CountDownLatch(1);
        CountDownLatch writerReached = new CountDownLatch(1);
        CountDownLatch purgeBarrierReached = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        AtomicLong purgeConnection = new AtomicLong();
        AtomicBoolean firstListBarrier = new AtomicBoolean();
        var real = sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiChatMapper.class);
        doAnswer(invocation -> {
            var barrier = real.lockPrivacyBarrierForShare(invocation.getArgument(0));
            if (Thread.currentThread().getName().equals("mission-list") && firstListBarrier.compareAndSet(false, true)) {
                listBarrierHeld.countDown();
                await(releaseList);
            }
            return barrier;
        }).when(chatLocks).lockPrivacyBarrierForShare(workspace.getId());
        var realPrivacy = sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("mission-purge")) {
                purgeConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                purgeBarrierReached.countDown();
            }
            return realPrivacy.lockBarrierForShare(invocation.getArgument(0));
        }).when(privacyLocks).lockBarrierForShare(workspace.getId());
        doAnswer(invocation -> {
            var session = real.getSessionByIdForMaintenanceUpdate(invocation.getArgument(0), invocation.getArgument(1));
            if (Thread.currentThread().getName().equals("mission-purge")) {
                purgeSessionHeld.countDown();
                await(releasePurge);
            }
            return session;
        }).when(chatLocks).getSessionByIdForMaintenanceUpdate(workspace.getId(), older.sessionId());
        try (var executor = Executors.newFixedThreadPool(3)) {
            var listing = executor.submit(() -> asActor("mission-list", () -> service.list(20, null, null, null)));
            try {
                assertTrue(listBarrierHeld.await(10, TimeUnit.SECONDS));
                var pruning = executor.submit(() -> asActor("mission-purge", () -> privacyService.batch(workspace.getId(), operation)));
                assertTrue(purgeSessionHeld.await(10, TimeUnit.SECONDS));
                var writer = executor.submit(() -> asActor("barrier-writer", () -> transaction().execute(status -> {
                    writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    writerReached.countDown();
                    return jdbc.queryForList("SELECT workspace_id FROM ai_agent_privacy_barrier WHERE workspace_id=? FOR UPDATE", workspace.getId());
                })));
                assertTrue(writerReached.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, writerConnection.get(), "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
                releasePurge.countDown();
                assertTrue(purgeBarrierReached.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitSharedRecordLockBehindQueuedWriter(jdbc, purgeConnection.get(), "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
                releaseList.countDown();
                assertNull(listing.get(10, TimeUnit.SECONDS).error());
                assertNull(pruning.get(10, TimeUnit.SECONDS).error());
                assertNull(writer.get(10, TimeUnit.SECONDS).error());
            } finally {
                releaseList.countDown();
                releasePurge.countDown();
            }
        }
    }

    @Test
    void twoFirstBindingsCommitOneOriginalEnvelope() throws Exception {
        var fixture = staged();
        var first = candidate(fixture, "assign_owner");
        var second = candidate(fixture, "assign_owner");
        var results = rootRace(fixture,
                () -> persistence.publishPrepared(fixture.policy(), first),
                () -> persistence.publishPrepared(fixture.policy(), second));
        assertNull(results.getFirst().error());
        assertNull(results.getLast().error());
        var stored = actions.getById(workspace.getId(), fixture.runId(), first.action().getId());
        assertEquals(first.action().getPreparationId(), stored.getPreparationId());
        assertEquals(first.action().getPreparedAt(), stored.getPreparedAt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND mission_run_id=? AND proposal_origin='mission'", Integer.class, workspace.getId(), fixture.runId()));
    }

    @Test
    void cancelWinsAgainstSuspendedPublisher() throws Exception {
        var fixture = staged();
        var prepared = candidate(fixture, "assign_owner");
        var results = rootRace(fixture,
                () -> cancel(fixture), () -> persistence.publishPrepared(fixture.policy(), prepared));
        assertNull(results.getFirst().error());
        assertNotNull(results.getLast().error());
        assertEquals("cancelled", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertEquals(0, pending(fixture));
        assertNull(actions.getById(workspace.getId(), fixture.runId(), prepared.action().getId()).getPreparationId());
    }

    @Test
    void publisherWinsBeforeCancelAndIsWithdrawn() throws Exception {
        var fixture = staged();
        var prepared = candidate(fixture, "assign_owner");
        var results = rootRace(fixture,
                () -> persistence.publishPrepared(fixture.policy(), prepared), () -> cancelRetry(fixture));
        assertNull(results.getFirst().error());
        assertNull(results.getLast().error());
        assertEquals("cancelled", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertEquals(0, pending(fixture));
        assertEquals("mission_withdrawn", json.readTree(chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), prepared.action().getId()).getResultJson()).path("reason").asString());
    }

    @Test
    void freezeAndCancelSerializeWithoutOrphanProposal() throws Exception {
        var fixture = staged("assign_owner");
        var results = rootRace(fixture, () -> {
            freeze(fixture);
            return true;
        }, () -> cancelRetry(fixture));
        assertNull(results.getFirst().error());
        assertNull(results.getLast().error());
        assertNotNull(runs.getRun(workspace.getId(), fixture.runId()).getReportMessageId());
        assertEquals("cancelled", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertEquals(0, pending(fixture));
    }

    @Test
    void cancelWinsAgainstSuspendedFreezeWithoutReportOrMembership() throws Exception {
        var fixture = staged("assign_owner");
        var selected = fixture.selected().getFirst();
        var results = rootRace(fixture, () -> cancel(fixture), () -> {
            freeze(fixture);
            return true;
        });
        assertNull(results.getFirst().error());
        assertInstanceOf(ConflictException.class, results.getLast().error());
        var cancelled = runs.getRun(workspace.getId(), fixture.runId());
        assertEquals("cancelled", cancelled.getStatus());
        assertNull(cancelled.getReportMessageId());
        assertNull(cancelled.getProposalFrozenAt());
        assertNull(cancelled.getReviewExpiresAt());
        assertNull(actions.getById(workspace.getId(), fixture.runId(), selected.getId()).getOutputOrdinal());
        assertEquals(0, pending(fixture));
        var canonical = chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), selected.getId());
        assertEquals("failed", canonical.getStatus());
        var outcome = json.readTree(canonical.getResultJson());
        assertEquals("mission_withdrawn", outcome.path("reason").asString());
        assertEquals("cancelled", outcome.path("withdrawalReason").asString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND mission_run_id=? AND mission_message_kind='report'", Integer.class, workspace.getId(), fixture.runId()));
    }

    @Test
    void approvalWaitingOnDomainLockCrossesDeadlineAndRollsBack() throws Exception {
        var fixture = frozen("update_record_fields");
        var request = request(fixture, "approve", fixture.selected().getFirst());
        var result = whileCompanyLocked(() -> decisions.decide(fixture.runId(), request), () ->
                clock.set(runs.getRun(workspace.getId(), fixture.runId()).getReviewExpiresAt().toInstant(ZoneOffset.UTC)));
        assertEquals("refused", result.items().getFirst().outcome());
        assertEquals(primary.getIndustry(), jdbc.queryForObject("SELECT industry FROM company WHERE workspace_id=? AND id=?", String.class, workspace.getId(), primary.getId()));
        assertEquals(1, pending(fixture));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND mission_run_id=? AND status='executed' AND proposal_origin='mission'", Integer.class, workspace.getId(), fixture.runId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE workspace_id=? AND action='company.update'", Integer.class, workspace.getId()));
    }

    @Test
    void permissionRevocationAndApprovalRespectAuthorityOrder() throws Exception {
        var fixture = frozen("update_record_fields");
        jdbc.update("INSERT INTO workspace_role (workspace_id,name) VALUES (?, 'Mission AI review only')", workspace.getId());
        Integer role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id=? AND name='Mission AI review only'", Integer.class, workspace.getId());
        assertNotNull(role);
        jdbc.update("INSERT INTO workspace_role_permission (workspace_role_id,permission) VALUES (?,'AI_USE')", role);
        var response = whileMembershipLocked(() -> decisions.decide(fixture.runId(), request(fixture, "approve", fixture.selected().getFirst())), () ->
                jdbc.update("UPDATE workspace_member SET role='member',role_id=? WHERE workspace_id=? AND user_id=?", role, workspace.getId(), currentUser.getId()));
        assertTrue(response.items().isEmpty() || response.items().stream().noneMatch(item -> "executed".equals(item.outcome())));
        assertEquals(1, pending(fixture));
        assertEquals(primary.getIndustry(), jdbc.queryForObject("SELECT industry FROM company WHERE workspace_id=? AND id=?", String.class, workspace.getId(), primary.getId()));
        jdbc.update("UPDATE workspace_member SET role='owner',role_id=NULL WHERE workspace_id=? AND user_id=?", workspace.getId(), currentUser.getId());
        var winner = frozen("update_record_fields");
        var ordered = rootRace(winner,
                () -> decisions.decide(winner.runId(), request(winner, "approve", winner.selected().getFirst())),
                () -> transaction().execute(status -> {
                    authority.lockAndRequirePermissionsSnapshot(workspace.getId(), Map.of(currentUser.getId(),
                            java.util.Set.of(ooo.klae.connex.backend.tenant.Permission.AI_USE)));
                    jdbc.update("UPDATE workspace_member SET role='member',role_id=? WHERE workspace_id=? AND user_id=?", role, workspace.getId(), currentUser.getId());
                    return true;
                }));
        assertNull(ordered.getFirst().error());
        assertNull(ordered.getLast().error());
        assertEquals("executed", chatMapper.getMissionToolCall(workspace.getId(), winner.runId(), winner.selected().getFirst().getId()).getStatus());
        assertEquals("Prepared industry", jdbc.queryForObject("SELECT industry FROM company WHERE workspace_id=? AND id=?", String.class, workspace.getId(), primary.getId()));
    }

    @Test
    void privacyInvalidationRollsBackTentativeEffectAndReceipt() throws Exception {
        var fixture = frozen("update_record_fields");
        var request = request(fixture, "approve", fixture.selected().getFirst());
        var result = whileCompanyLocked(() -> decisions.decide(fixture.runId(), request), this::invalidate);
        assertTrue(result.contentWithheld());
        assertEquals(primary.getIndustry(), jdbc.queryForObject("SELECT industry FROM company WHERE workspace_id=? AND id=?", String.class, workspace.getId(), primary.getId()));
        assertEquals(1, pending(fixture));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND mission_run_id=? AND status='executed' AND proposal_origin='mission'", Integer.class, workspace.getId(), fixture.runId()));
    }

    @Test
    void targetChangesBetweenPreparationAndInsertion() throws Exception {
        var fixture = staged();
        var prepared = candidate(fixture, "assign_owner");
        var original = prepared.action().getPreparedAt();
        var result = whileMembershipLocked(() -> {
            try {
                persistence.publishPrepared(fixture.policy(), prepared);
                return false;
            } catch (RuntimeException changed) {
                return true;
            }
        }, () -> jdbc.update("UPDATE company SET industry='Changed before insertion' WHERE workspace_id=? AND id=?", workspace.getId(), primary.getId()));
        assertTrue(result);
        assertEquals(original, prepared.action().getPreparedAt());
        assertNull(actions.getById(workspace.getId(), fixture.runId(), prepared.action().getId()).getPreparationId());
        assertEquals(0, pending(fixture));
    }

    @Test
    void concurrentDecisionsCompleteRootExactlyOnce() throws Exception {
        var fixture = frozen("assign_owner");
        var selected = fixture.selected().getFirst();
        var before = runs.getRun(workspace.getId(), fixture.runId()).getStateVersion();
        var results = rootRace(fixture,
                () -> decisions.decide(fixture.runId(), request(fixture, "discard", selected)),
                () -> decisions.decide(fixture.runId(), request(fixture, "approve", selected)));
        assertNull(results.getFirst().error());
        assertNull(results.getLast().error());
        assertEquals("rejected", chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), selected.getId()).getStatus());
        assertEquals("done", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertEquals(before + 1, runs.getRun(workspace.getId(), fixture.runId()).getStateVersion());
    }

    @Test
    void concurrentApprovalsCommitOneAuditedEffectAndCompleteRootOnce() throws Exception {
        var fixture = frozen("update_record_fields");
        var selected = fixture.selected().getFirst();
        var request = request(fixture, "approve", selected);
        var before = runs.getRun(workspace.getId(), fixture.runId()).getStateVersion();
        var results = rootRace(fixture,
                () -> decisions.decide(fixture.runId(), request),
                () -> decisions.decide(fixture.runId(), request));
        for (var result : results) {
            assertNull(result.error());
            var response = assertInstanceOf(AiAgentProposalDecisionResponse.class, result.value());
            assertTrue(response.complete());
            assertEquals(1, response.items().size());
            assertEquals(selected.getActionSlot(), response.items().getFirst().actionSlot());
            assertEquals("executed", response.items().getFirst().outcome());
        }
        var canonical = chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), selected.getId());
        assertEquals("executed", canonical.getStatus());
        assertNotNull(canonical.getResultJson());
        assertEquals("approved", json.readTree(canonical.getResultJson()).path("approval").path("status").asString());
        assertEquals("Prepared industry", jdbc.queryForObject("SELECT industry FROM company WHERE workspace_id=? AND id=?", String.class, workspace.getId(), primary.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE workspace_id=? AND entity_type='company' AND entity_id=? AND action='company.update'", Integer.class, workspace.getId(), primary.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND mission_run_id=? AND mission_action_id=? AND proposal_origin='mission' AND status='executed'", Integer.class, workspace.getId(), fixture.runId(), selected.getId()));
        assertEquals(0, pending(fixture));
        var completed = runs.getRun(workspace.getId(), fixture.runId());
        assertEquals("done", completed.getStatus());
        assertNotNull(completed.getFinishedAt());
        assertEquals(before + 1, completed.getStateVersion());
    }

    @Test
    void oldAttemptCannotBindOrFreezeAfterSuccessorClaim() throws Exception {
        var fixture = staged();
        var prepared = candidate(fixture, "assign_owner");
        persistence.interrupt(runs.getRun(workspace.getId(), fixture.runId()), "interrupted");
        assertTrue(persistence.requeueInterrupted(runs.getRun(workspace.getId(), fixture.runId())));
        var results = rootRace(fixture,
                () -> persistence.claim(runs.getRun(workspace.getId(), fixture.runId()), new byte[32], new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability),
                () -> persistence.publishPrepared(fixture.policy(), prepared));
        assertNull(results.getFirst().error());
        assertNotNull(results.getLast().error());
        assertThrows(RuntimeException.class, () -> freeze(fixture));
        assertEquals("running", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND subject_kind='agent_run' AND subject_id=? AND owner IS NOT NULL", Integer.class, workspace.getId(), fixture.runId()));
        assertEquals(0, pending(fixture));
    }

    @Test
    void revocationBetweenCommitAndRealtimeSendSuppressesDelivery() throws Exception {
        var fixture = frozen("assign_owner");
        var current = runs.getRun(workspace.getId(), fixture.runId());
        org.mockito.Mockito.clearInvocations(messaging);
        jdbc.update("INSERT INTO workspace_role (workspace_id,name) VALUES (?, 'No mission delivery')", workspace.getId());
        Integer role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id=? AND name='No mission delivery'", Integer.class, workspace.getId());
        assertNotNull(role);
        Boolean completed = whileMembershipLocked(() -> {
            realtime.publish(current, "mission_output");
            return true;
        }, () -> jdbc.update("UPDATE workspace_member SET role='member',role_id=? WHERE workspace_id=? AND user_id=?", role, workspace.getId(), currentUser.getId()));
        assertTrue(completed);
        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    private AiAgentProposalService.Prepared candidate(Fixture fixture, String tool) {
        evidence.begin(fixture.policy());
        try {
            return fixture.policy().execute(() -> {
                AiChatResourceRegistry resources = new AiChatResourceRegistry();
                String handle = resources.register("company", primary.getId());
                tools.execute("get_record", json.createObjectNode().put("handle", handle), resources, false, fixture.policy().turn().scope());
                var arguments = json.createObjectNode().put("handle", handle);
                if ("assign_owner".equals(tool)) {
                    arguments.put("owner", currentUser.getUsername());
                } else {
                    arguments.put("industry", "Prepared industry");
                }
                return preparation.prepare(fixture.policy(), tool, arguments, resources, Map.of());
            });
        } finally {
            evidence.clear();
        }
    }

    private Object cancel(Fixture fixture) {
        AiAgentRun run = runs.getRun(workspace.getId(), fixture.runId());
        return persistence.control(run, run.getStateVersion(), "cancel");
    }

    private Object cancelRetry(Fixture fixture) {
        try {
            return cancel(fixture);
        } catch (ConflictException changed) {
            return cancel(fixture);
        }
    }

    private int pending(Fixture fixture) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND mission_run_id=? AND proposal_origin='mission' AND status='proposed'", Integer.class, workspace.getId(), fixture.runId());
        return java.util.Objects.requireNonNull(count);
    }

    private List<Result> rootRace(Fixture fixture, Supplier<Object> winner, Supplier<Object> contender) throws Exception {
        CountDownLatch rootsHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch atAuthority = new CountDownLatch(1);
        AtomicLong connection = new AtomicLong();
        AtomicBoolean stopped = new AtomicBoolean();
        AiAgentRunMapper real = sqlSessions.getMapper(AiAgentRunMapper.class);
        doAnswer(invocation -> {
            AiAgentRun run = real.lockRun(invocation.getArgument(0), invocation.getArgument(1));
            if (Thread.currentThread().getName().equals("proposal-first") && stopped.compareAndSet(false, true)) {
                rootsHeld.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
            }
            return run;
        }).when(runLocks).lockRun(workspace.getId(), fixture.runId());
        observeAuthority(atAuthority, connection);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> asActor("proposal-first", winner));
            try {
                assertTrue(rootsHeld.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> asActor("proposal-second", contender));
                assertTrue(atAuthority.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection.get(), "workspace_member", workspace.getId() + ", " + currentUser.getId());
                release.countDown();
                return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    private void observeAuthority(CountDownLatch reached, AtomicLong connection) {
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("proposal-second")) {
                Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertNotNull(id);
                connection.set(id);
                reached.countDown();
            }
            return invocation.callRealMethod();
        }).when(authority).lockAndRequirePermissionsSnapshot(anyInt(), anyMap());
    }

    private <T> T whileMembershipLocked(Supplier<T> contender, Runnable beforeCommit) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reached = new CountDownLatch(1);
        AtomicLong connection = new AtomicLong();
        observeAuthority(reached, connection);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> asActor("membership-holder", () -> transaction().execute(status -> {
                jdbc.queryForList("SELECT user_id FROM workspace_member WHERE workspace_id=? AND user_id=? FOR UPDATE", workspace.getId(), currentUser.getId());
                beforeCommit.run();
                held.countDown();
                await(release);
                return true;
            })));
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    Thread.currentThread().setName("proposal-second");
                    authenticateAs(currentUser, workspace.getId());
                    try {
                        return contender.get();
                    } finally {
                        clearAuthentication();
                    }
                });
                assertTrue(reached.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection.get(), "workspace_member", workspace.getId() + ", " + currentUser.getId());
                release.countDown();
                assertNull(first.get(10, TimeUnit.SECONDS).error());
                return second.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private ooo.klae.connex.backend.dto.AiAgentProposalDecisionResponse whileCompanyLocked(
            Supplier<ooo.klae.connex.backend.dto.AiAgentProposalDecisionResponse> contender, Runnable whileWaiting) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reached = new CountDownLatch(1);
        AtomicLong connection = new AtomicLong();
        CompanyMapper real = sqlSessions.getMapper(CompanyMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("proposal-second")) {
                Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertNotNull(id);
                connection.set(id);
                reached.countDown();
            }
            return real.getOwnedCompanyByIdForUpdate(invocation.getArgument(0), invocation.getArgument(1));
        }).when(companyLocks).getOwnedCompanyByIdForUpdate(workspace.getId(), primary.getId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> asActor("company-holder", () -> transaction().execute(status -> {
                jdbc.queryForList("SELECT id FROM company WHERE workspace_id=? AND id=? FOR UPDATE", workspace.getId(), primary.getId());
                held.countDown();
                await(release);
                return true;
            })));
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    Thread.currentThread().setName("proposal-second");
                    authenticateAs(currentUser, workspace.getId());
                    try {
                        return contender.get();
                    } finally {
                        clearAuthentication();
                    }
                });
                assertTrue(reached.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection.get(), "company", Integer.toString(primary.getId()));
                whileWaiting.run();
                release.countDown();
                assertNull(first.get(10, TimeUnit.SECONDS).error());
                return second.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private Result asActor(String name, Supplier<Object> action) {
        Thread.currentThread().setName(name);
        authenticateAs(currentUser, workspace.getId());
        try {
            return new Result(action.get(), null);
        } catch (RuntimeException failure) {
            return new Result(null, failure);
        } finally {
            clearAuthentication();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record Result(Object value, RuntimeException error) {}
}
