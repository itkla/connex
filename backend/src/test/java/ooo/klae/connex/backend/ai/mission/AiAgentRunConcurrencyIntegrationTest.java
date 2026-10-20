package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Observes real MySQL contention before releasing mission claims and competing admissions. */
class AiAgentRunConcurrencyIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAgentRunPersistenceService persistence;
    @Autowired private AiAgentCapabilityGate capability;
    @Autowired private AiProperties properties;
    @Autowired private AiRestrictionEpoch epoch;
    @Autowired private ObjectMapper json;
    @Autowired private AiChatTurnPersistenceService turns;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private SqlSessionTemplate sqlSessions;
    @MockitoSpyBean private AiAgentRunMapper runs;
    @MockitoSpyBean private AiChatMapper chat;
    @MockitoSpyBean private WorkspaceService workspaces;

    @Test
    void twoSchedulersClaimOneAttempt() throws Exception {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(1L);
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Claim race fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        AiAgentRun run = persistence.create(session.getId(), request, "en", AiAgentCapabilityGate.hash("race"),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
        CountDownLatch firstHasRoots = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAtAuthority = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        AtomicLong waitingConnection = new AtomicLong();
        AiAgentRunMapper realRuns = sqlSessions.getMapper(AiAgentRunMapper.class);
        doAnswer(invocation -> {
            AiAgentRun result = realRuns.lockRun(invocation.getArgument(0), invocation.getArgument(1));
            if (Thread.currentThread().getName().equals("mission-claim-first") && paused.compareAndSet(false, true)) {
                firstHasRoots.countDown();
                assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
            }
            return result;
        }).when(runs).lockRun(workspace.getId(), run.getId());
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("mission-claim-second")) {
                Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertNotNull(connection);
                waitingConnection.set(connection);
                secondAtAuthority.countDown();
            }
            return invocation.callRealMethod();
        }).when(workspaces).lockAndRequirePermissionsSnapshot(anyInt(), anyMap());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimAs("mission-claim-first", run));
            try {
                assertTrue(firstHasRoots.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> claimAs("mission-claim-second", run));
                assertTrue(secondAtAuthority.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, waitingConnection.get(), "workspace_member",
                        workspace.getId() + ", " + currentUser.getId());
                releaseFirst.countDown();
                assertEquals(List.of(true, false), List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            } finally {
                releaseFirst.countDown();
            }
        }
        assertEquals(1, runs.listSegments(workspace.getId(), run.getId()).size());
        Integer leases = jdbc.queryForObject("SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND subject_kind='agent_run' AND subject_id=? AND released_at IS NULL",
                Integer.class, workspace.getId(), run.getId());
        assertEquals(1, leases);
    }

    @Test
    void claimRootLocksFenceStaleMaintenanceWithoutAuthorizationLocks() throws Exception {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(1L);
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Claim maintenance race fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        AiAgentRun discovered = persistence.create(session.getId(), request, "en",
                AiAgentCapabilityGate.hash("maintenance-race"),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
        CountDownLatch claimHasRoots = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        CountDownLatch maintenanceAtSession = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        AtomicLong waitingConnection = new AtomicLong();
        AiAgentRunMapper realRuns = sqlSessions.getMapper(AiAgentRunMapper.class);
        AiChatMapper realChat = sqlSessions.getMapper(AiChatMapper.class);
        doAnswer(invocation -> {
            AiAgentRun result = realRuns.lockRun(invocation.getArgument(0), invocation.getArgument(1));
            if (Thread.currentThread().getName().equals("mission-claim-holder")
                    && paused.compareAndSet(false, true)) {
                claimHasRoots.countDown();
                assertTrue(releaseClaim.await(30, TimeUnit.SECONDS));
            }
            return result;
        }).when(runs).lockRun(workspace.getId(), discovered.getId());
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("mission-maintenance-contender")) {
                Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertNotNull(connection);
                waitingConnection.set(connection);
                maintenanceAtSession.countDown();
            }
            return realChat.getSessionByIdForMaintenanceUpdate(invocation.getArgument(0), invocation.getArgument(1));
        }).when(chat).getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var claim = workers.submit(() -> claimAs("mission-claim-holder", discovered));
            try {
                assertTrue(claimHasRoots.await(10, TimeUnit.SECONDS));
                var maintenance = workers.submit(() -> {
                    Thread.currentThread().setName("mission-maintenance-contender");
                    tenantWorkScope.inWorkspace(workspace.getId(), () -> persistence.cancelForDeparture(discovered));
                    return true;
                });
                assertTrue(maintenanceAtSession.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, waitingConnection.get(),
                        "ai_chat_session", Integer.toString(session.getId()));
                releaseClaim.countDown();
                assertTrue(claim.get(10, TimeUnit.SECONDS));
                assertTrue(maintenance.get(10, TimeUnit.SECONDS));
            } finally {
                releaseClaim.countDown();
            }
        }
        AiAgentRun stored = runs.getRun(workspace.getId(), discovered.getId());
        assertEquals("running", stored.getStatus());
        assertEquals(discovered.getExecutionGeneration() + 1, stored.getExecutionGeneration());
        var segments = runs.listSegments(workspace.getId(), discovered.getId());
        assertEquals(1, segments.size());
        assertEquals(stored.getActiveSegmentId(), Integer.valueOf(segments.getFirst().getId()));
        assertEquals(stored.getExecutionGeneration(), segments.getFirst().getExecutionGeneration());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND subject_kind='agent_run' AND subject_id=? AND released_at IS NULL",
                Integer.class, workspace.getId(), discovered.getId()));
    }

    @Test
    void reactiveAdmissionWinsBeforeFirstMissionTaint() throws Exception {
        assertAdmissionRace(false);
    }

    @Test
    void firstMissionTaintWinsBeforeReactiveAdmission() throws Exception {
        assertAdmissionRace(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checkpointAndCancelSerializeWithoutLateContinuation(boolean cancelFirst) throws Exception {
        var discovered = drillRun();
        var policy = drillClaim(discovered);
        var checkpoint = drillCheckpoint(policy);
        var live = runs.getRun(workspace.getId(), discovered.getId());
        Supplier<Object> cancel = () -> persistence.control(live, live.getStateVersion(), "cancel");
        Supplier<Object> commit = () -> persistence.commitCheckpoint(policy, 1, 0,
                json.writeValueAsString(checkpoint.arguments()), json.writeValueAsString(checkpoint.result()), checkpoint.snapshot());
        var outcomes = orderedDrill(discovered, cancelFirst ? cancel : commit, cancelFirst ? commit : cancel);
        assertInstanceOf(ConflictException.class, outcomes.get(1));
        var current = runs.getRun(workspace.getId(), discovered.getId());
        if (!cancelFirst) {
            current = persistence.control(current, current.getStateVersion(), "cancel");
        }
        assertEquals("cancelled", current.getStatus());
        assertEquals(cancelFirst ? 0 : 1, current.getLastCommittedSegmentNo());
        assertNull(current.getActiveSegmentId());
        assertEquals(0, drillLeaseCount(discovered));
        var stopped = current;
        org.junit.jupiter.api.Assertions.assertThrows(ConflictException.class, () -> drillClaim(stopped));
    }

    @Test
    void oldAttemptCannotWriteCheckpointOrReleaseSuccessor() throws Exception {
        var discovered = drillRun();
        var old = drillClaim(discovered);
        var prepared = drillCheckpoint(old);
        persistence.interrupt(runs.getRun(workspace.getId(), discovered.getId()), "interrupted");
        assertTrue(persistence.requeueInterrupted(runs.getRun(workspace.getId(), discovered.getId())));
        var queued = runs.getRun(workspace.getId(), discovered.getId());
        var outcomes = orderedDrill(discovered, () -> drillClaim(queued), () -> persistence.commitCheckpoint(old, 1, 0,
                json.writeValueAsString(prepared.arguments()), json.writeValueAsString(prepared.result()), prepared.snapshot()));
        var successor = assertInstanceOf(AiAgentExecutionPolicy.class, outcomes.get(0));
        assertInstanceOf(ConflictException.class, outcomes.get(1));
        var current = runs.getRun(workspace.getId(), discovered.getId());
        assertEquals("running", current.getStatus());
        assertEquals(successor.segmentId(), current.getActiveSegmentId());
        assertEquals(0, current.getLastCommittedSegmentNo());
        assertEquals(1, drillLeaseCount(discovered));
    }

    @Test
    void twoRecoveryPassesQueueOnlyOneRetry() throws Exception {
        var discovered = drillRun();
        drillClaim(discovered);
        persistence.interrupt(runs.getRun(workspace.getId(), discovered.getId()), "interrupted");
        var stopped = runs.getRun(workspace.getId(), discovered.getId());
        var outcomes = orderedDrill(discovered, () -> persistence.requeueInterrupted(stopped),
                () -> persistence.requeueInterrupted(stopped));
        assertEquals(List.of(true, false), outcomes);
        var next = drillClaim(runs.getRun(workspace.getId(), discovered.getId()));
        assertEquals(1, next.segmentNo());
        assertEquals(2, next.attemptNo());
        assertEquals(2, runs.listSegments(workspace.getId(), discovered.getId()).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acknowledgedMemberPauseWinsOverAutomaticRecoveryInEitherLockOrder(boolean pauseFirst) throws Exception {
        var discovered = drillRun();
        drillClaim(discovered);
        persistence.interrupt(runs.getRun(workspace.getId(), discovered.getId()), "interrupted");
        var stopped = runs.getRun(workspace.getId(), discovered.getId());
        Supplier<Object> pause = () -> persistence.control(stopped, stopped.getStateVersion(), "pause");
        Supplier<Object> recovery = () -> persistence.requeueInterrupted(stopped);
        var outcomes = orderedDrill(discovered, pauseFirst ? pause : recovery, pauseFirst ? recovery : pause);
        if (pauseFirst) {
            assertEquals(false, outcomes.get(1));
        } else {
            assertEquals(true, outcomes.get(0));
            assertInstanceOf(ConflictException.class, outcomes.get(1));
            var queued = runs.getRun(workspace.getId(), discovered.getId());
            persistence.control(queued, queued.getStateVersion(), "pause");
        }
        var acknowledged = runs.getRun(workspace.getId(), discovered.getId());
        assertEquals("paused", acknowledged.getStatus());
        assertEquals("member", acknowledged.getReasonCode());
        assertEquals(false, persistence.requeueInterrupted(acknowledged));
        assertTrue(runs.interruptedReadRuns(workspace.getId(), 25).isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(ConflictException.class, () -> drillClaim(acknowledged));
        assertEquals(0, drillLeaseCount(discovered));
    }

    private List<Object> orderedDrill(AiAgentRun run, Supplier<Object> winner, Supplier<Object> contender) throws Exception {
        CountDownLatch holderAtRun = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiterAtAuthority = new CountDownLatch(1);
        AtomicBoolean held = new AtomicBoolean();
        AtomicLong connectionId = new AtomicLong();
        var realRuns = sqlSessions.getMapper(AiAgentRunMapper.class);
        doAnswer(invocation -> {
            var locked = realRuns.lockRun(invocation.getArgument(0), invocation.getArgument(1));
            if (Thread.currentThread().getName().equals("mission-drill-winner") && held.compareAndSet(false, true)) {
                holderAtRun.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
            }
            return locked;
        }).when(runs).lockRun(workspace.getId(), run.getId());
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("mission-drill-contender")) {
                var connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertNotNull(connection);
                connectionId.set(connection);
                waiterAtAuthority.countDown();
            }
            return invocation.callRealMethod();
        }).when(workspaces).lockAndRequirePermissionsSnapshot(anyInt(), anyMap());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> drillAs("mission-drill-winner", winner));
            try {
                assertTrue(holderAtRun.await(10, TimeUnit.SECONDS));
                var second = workers.submit(() -> drillAs("mission-drill-contender", contender));
                assertTrue(waiterAtAuthority.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connectionId.get(), "workspace_member",
                        workspace.getId() + ", " + currentUser.getId());
                release.countDown();
                return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    private Object drillAs(String thread, Supplier<Object> action) {
        Thread.currentThread().setName(thread);
        authenticateAs(currentUser, workspace.getId());
        try {
            return action.get();
        } catch (RuntimeException refused) {
            return refused;
        } finally {
            clearAuthentication();
        }
    }

    private AiAgentRun drillRun() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(1L);
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Continuation lock drill");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        return persistence.create(session.getId(), request, "en", AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
    }

    private AiAgentExecutionPolicy drillClaim(AiAgentRun run) {
        return persistence.claim(run, new byte[32], new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
    }

    private AiAgentPlanService.Prepared drillCheckpoint(AiAgentExecutionPolicy policy) {
        var snapshot = persistence.snapshot(policy);
        var args = json.createObjectNode().put("expectedPlanRevision", snapshot.plan().revision())
                .put("expectedCheckpointRevision", snapshot.checkpoint().revision()).putNull("nextPlanItemKey").put("phase", "reporting");
        args.putObject("memory").put("summary", "Retained memory").putArray("citations");
        return new AiAgentPlanService(new AiAgentContinuationCodec()).prepareCheckpoint(snapshot, args,
                policy.segmentNo(), text -> new AiAgentMemory.DurableText(text, List.of()), ignored -> List.of());
    }

    private int drillLeaseCount(AiAgentRun run) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND subject_kind='agent_run' AND subject_id=? AND released_at IS NULL",
                Integer.class, workspace.getId(), run.getId()));
    }

    private void assertAdmissionRace(boolean missionFirst) throws Exception {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(1L);
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Admission race fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        CountDownLatch firstHasSession = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAtAuthority = new CountDownLatch(1);
        AtomicLong waitingConnection = new AtomicLong();
        AiChatMapper realChat = sqlSessions.getMapper(AiChatMapper.class);
        doAnswer(invocation -> {
            AiChatSession result = realChat.getSessionByIdForUpdate(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            if (Thread.currentThread().getName().equals("admission-first")) {
                firstHasSession.countDown();
                assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
            }
            return result;
        }).when(chat).getSessionByIdForUpdate(workspace.getId(), currentUser.getId(), session.getId());
        doAnswer(invocation -> {
            observeAdmissionWaiter(secondAtAuthority, waitingConnection);
            return invocation.callRealMethod();
        }).when(workspaces).lockAndRequirePermissionsSnapshot(anyInt(), anyMap());
        doAnswer(invocation -> {
            observeAdmissionWaiter(secondAtAuthority, waitingConnection);
            return invocation.callRealMethod();
        }).when(workspaces).lockAndRequireMember(anyInt(), anyInt());
        AdmissionResult firstResult;
        AdmissionResult secondResult;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> admitAs("admission-first", missionFirst, session.getId(), request));
            try {
                assertTrue(firstHasSession.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> admitAs("admission-second", !missionFirst, session.getId(), request));
                assertTrue(secondAtAuthority.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, waitingConnection.get(), "workspace_member",
                        workspace.getId() + ", " + currentUser.getId());
                releaseFirst.countDown();
                firstResult = first.get(10, TimeUnit.SECONDS);
                secondResult = second.get(10, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }
        AiChatSession stored = chatMapper.getPrivacySession(workspace.getId(), session.getId());
        assertNotNull(stored);
        AiChatQueuedTurn reactive = assertInstanceOf(ReactiveAdmission.class,
                missionFirst ? secondResult : firstResult).turn();
        if (missionFirst) {
            AiAgentRun mission = assertInstanceOf(MissionAdmission.class, firstResult).run();
            assertEquals(1, stored.getMissionTaintSeq());
            assertEquals(2, stored.getMessageSeqHighWater());
            assertEquals(2, reactive.userMessageSeq());
            assertEquals(stored.getMissionTaintSeq(), reactive.privacyToken().taintSeqAtAdmission());
            assertEquals(mission.getPrivacyGeneration(), reactive.privacyToken().privacyGeneration());
            assertEquals(1, chatMapper.countActiveTurns(workspace.getId(), session.getId()));
        } else {
            assertEquals("mission_session_busy", assertInstanceOf(RefusedAdmission.class, secondResult).reason());
            assertNull(stored.getMissionTaintSeq());
            assertNull(reactive.privacyToken().taintSeqAtAdmission());
            assertEquals(1, stored.getMessageSeqHighWater());
            assertNull(runs.findByRequestKey(workspace.getId(), currentUser.getId(), request.clientRequestKey()));
        }
    }

    private void observeAdmissionWaiter(CountDownLatch reached, AtomicLong connectionId) {
        if (Thread.currentThread().getName().equals("admission-second")) {
            Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertNotNull(connection);
            connectionId.set(connection);
            reached.countDown();
        }
    }

    private AdmissionResult admitAs(String threadName, boolean mission, int sessionId,
            AiAgentRunCreateRequest request) {
        Thread.currentThread().setName(threadName);
        authenticateAs(currentUser, workspace.getId());
        try {
            if (mission) {
                return new MissionAdmission(persistence.create(sessionId, request, "en",
                        AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                        json.writeValueAsString(request.scope()), epoch.current(workspace.getId())));
            }
            return new ReactiveAdmission(turns.queue(sessionId,
                    new AiChatTurnCreateRequest("Reactive input", List.of()), epoch.current(workspace.getId())));
        } catch (ConflictException conflict) {
            return new RefusedAdmission(conflict.getMessage());
        } finally {
            clearAuthentication();
        }
    }

    private sealed interface AdmissionResult permits MissionAdmission, ReactiveAdmission, RefusedAdmission {}

    private record MissionAdmission(AiAgentRun run) implements AdmissionResult {}

    private record ReactiveAdmission(AiChatQueuedTurn turn) implements AdmissionResult {}

    private record RefusedAdmission(String reason) implements AdmissionResult {}

    private boolean claimAs(String threadName, AiAgentRun run) {
        Thread.currentThread().setName(threadName);
        authenticateAs(currentUser, workspace.getId());
        try {
            persistence.claim(run, AiAgentCapabilityGate.hash("fixture"), new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
            return true;
        } catch (ConflictException lost) {
            return false;
        } finally {
            clearAuthentication();
        }
    }
}
