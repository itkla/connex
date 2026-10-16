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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.ObjectMapper;

/** Observes real MySQL contention before releasing mission claims and competing admissions. */
class AiAgentRunConcurrencyIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAgentRunPersistenceService persistence;
    @Autowired private AiAgentCapabilityGate capability;
    @Autowired private AiProperties properties;
    @Autowired private AiRestrictionEpoch epoch;
    @Autowired private ObjectMapper json;
    @Autowired private AiChatTurnPersistenceService turns;
    @MockitoSpyBean private AiAgentRunMapper runs;
    @MockitoSpyBean private AiChatMapper chat;
    @MockitoSpyBean private WorkspaceService workspaces;

    @Test
    void twoSchedulersClaimOneAttempt() throws Exception {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
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
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
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
    void reactiveAdmissionWinsBeforeFirstMissionTaint() throws Exception {
        assertAdmissionRace(false);
    }

    @Test
    void firstMissionTaintWinsBeforeReactiveAdmission() throws Exception {
        assertAdmissionRace(true);
    }

    private void assertAdmissionRace(boolean missionFirst) throws Exception {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
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
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
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
