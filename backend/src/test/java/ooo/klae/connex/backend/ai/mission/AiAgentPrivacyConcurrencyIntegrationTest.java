package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyToken;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiAgentPrivacyConcurrencyIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @MockitoSpyBean private AiAgentPrivacyMapper workMapper;

    @Test
    void duplicateBatchCannotCommitTwice() throws Exception {
        AiChatSession session = taintedSession();
        transaction().executeWithoutResult(status -> {
            chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
            insertMessage(session.getId(), 1);
        });
        int messageId = java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM ai_chat_message WHERE workspace_id=? AND session_id=?",
                Integer.class, workspace.getId(), session.getId()));
        String operation = invalidate();
        AiAgentPrivacyWork first = privacyMapper.getWork(workspace.getId(), operation);
        AiAgentPrivacyWork second = privacyMapper.getWork(workspace.getId(), operation);
        long revision = first.getRevision();
        first.setPhase("sessions");
        first.setRevision(revision + 1);
        second.setPhase("sessions");
        second.setRevision(revision + 1);
        second.setLastMessageId(messageId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch tentativelyDeleted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var owner = workers.submit(() -> tenantWorkScope.inWorkspace(workspace.getId(),
                    () -> transaction().execute(status -> {
                        privacyMapper.lockBarrierForShare(workspace.getId());
                        privacyMapper.lockWork(workspace.getId(), operation);
                        locked.countDown();
                        await(release);
                        return privacyMapper.updateWork(first, revision);
                    })));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var duplicate = workers.submit(() -> {
                assertThrows(IllegalStateException.class, () -> tenantWorkScope.inWorkspace(workspace.getId(),
                        () -> transaction().executeWithoutResult(status -> {
                            chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
                            assertEquals(1, privacyMapper.deleteAffectedMessages(workspace.getId(), session.getId(),
                                    second.getCutoffGeneration(), List.of(messageId)));
                            assertEquals(0, messageCount(session.getId()));
                            tentativelyDeleted.countDown();
                            privacyMapper.lockBarrierForShare(workspace.getId());
                            privacyMapper.lockWork(workspace.getId(), operation);
                            if (privacyMapper.updateWork(second, revision) != 1) {
                                throw new IllegalStateException("Stale worker revision must roll back tentative deletion");
                            }
                        })));
                return true;
            });
            assertTrue(tentativelyDeleted.await(10, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(Integer.valueOf(1), owner.get(10, TimeUnit.SECONDS));
            assertTrue(duplicate.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals(revision + 1, privacyMapper.getWork(workspace.getId(), operation).getRevision());
        assertEquals(0, privacyMapper.getWork(workspace.getId(), operation).getLastMessageId());
        assertEquals(1, messageCount(session.getId()));
        finish(operation);
        assertEquals(0, messageCount(session.getId()));
    }

    @Test
    void latestWorkCompletingBeforeOlderWorkStillUnblocksOnlyAfterAllResiduals() {
        String older = invalidate();
        String newer = invalidate();
        finish(newer);
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        finish(older);
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        assertEquals(3, privacyMapper.getBarrier(workspace.getId()).getGeneration());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void purgeCompletionWaitsForEarlierWriterCommitOrRollback(boolean commit) throws Exception {
        AiChatSession session = taintedSession();
        AiChatPrivacyToken token = AiChatPrivacyGuard.capture(chatMapper,
                chatMapper.getPrivacySession(workspace.getId(), session.getId()));
        CountDownLatch retained = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch invalidating = new CountDownLatch(1);
        AtomicLong invalidationConnection = new AtomicLong();
        UUID operation = UUID.randomUUID();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var writer = workers.submit(() -> tenantWorkScope.inWorkspace(workspace.getId(), () -> {
                transaction().executeWithoutResult(status -> {
                    chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
                    AiChatPrivacyGuard.retain(chatMapper, token);
                    insertMessage(session.getId(), token.privacyGeneration());
                    retained.countDown();
                    await(release);
                    if (!commit) {
                        status.setRollbackOnly();
                    }
                });
                return true;
            }));
            assertTrue(retained.await(10, TimeUnit.SECONDS));
            var invalidation = workers.submit(() -> tenantWorkScope.inWorkspace(workspace.getId(), () -> {
                transaction().executeWithoutResult(status -> {
                    invalidationConnection.set(connectionId());
                    invalidating.countDown();
                    privacyService.invalidateWorkspaces(List.of(workspace.getId()), operation, "restriction", null, null);
                });
                return true;
            }));
            assertTrue(invalidating.await(10, TimeUnit.SECONDS));
            MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, invalidationConnection.get(),
                    "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
            release.countDown();
            assertTrue(writer.get(10, TimeUnit.SECONDS));
            assertTrue(invalidation.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals(commit ? 1 : 0, messageCount(session.getId()));
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        finish(operation.toString());
        assertEquals(0, messageCount(session.getId()));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    @Test
    void invalidationBeforeWriterForcesRollbackAfterObservedBarrierWait() throws Exception {
        AiChatSession session = taintedSession();
        AiChatPrivacyToken token = AiChatPrivacyGuard.capture(chatMapper,
                chatMapper.getPrivacySession(workspace.getId(), session.getId()));
        CountDownLatch invalidated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        UUID operation = UUID.randomUUID();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var invalidation = workers.submit(() -> tenantWorkScope.inWorkspace(workspace.getId(), () -> {
                transaction().executeWithoutResult(status -> {
                    privacyService.invalidateWorkspaces(List.of(workspace.getId()), operation, "restriction", null, null);
                    invalidated.countDown();
                    await(release);
                });
                return true;
            }));
            assertTrue(invalidated.await(10, TimeUnit.SECONDS));
            var writer = workers.submit(() -> tenantWorkScope.inWorkspace(workspace.getId(), () -> {
                assertThrows(ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException.class,
                        () -> transaction().executeWithoutResult(status -> {
                            chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
                            insertMessage(session.getId(), token.privacyGeneration());
                            writerConnection.set(connectionId());
                            writing.countDown();
                            AiChatPrivacyGuard.retain(chatMapper, token);
                        }));
                return true;
            }));
            assertTrue(writing.await(10, TimeUnit.SECONDS));
            MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, writerConnection.get(),
                    "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
            release.countDown();
            assertTrue(invalidation.get(10, TimeUnit.SECONDS));
            assertTrue(writer.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals(0, messageCount(session.getId()));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT message_seq_high_water FROM ai_chat_session WHERE workspace_id=? AND id=?",
                Integer.class, workspace.getId(), session.getId()));
        finish(operation.toString());
    }

    @Test
    void batchCrashDoesNotSkipContent() {
        AiChatSession session = taintedSession();
        transaction().executeWithoutResult(status -> {
            chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
            insertMessage(session.getId(), 1);
        });
        String operation = invalidate();
        for (int batch = 0; batch < 10; batch++) {
            var work = privacyMapper.getWork(workspace.getId(), operation);
            if ("messages".equals(work.getSessionPhase())) {
                break;
            }
            privacyService.batch(workspace.getId(), operation);
        }
        var before = privacyMapper.getWork(workspace.getId(), operation);
        assertEquals("messages", before.getSessionPhase());
        AiAgentPrivacyMapper real = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        doAnswer(invocation -> {
            int deleted = real.deleteAffectedMessages(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
            assertEquals(1, deleted);
            throw new IllegalStateException("Injected crash after deletion before cursor persistence");
        }).when(workMapper).deleteAffectedMessages(anyInt(), anyInt(), anyLong(), anyList());
        assertThrows(IllegalStateException.class, () -> privacyService.batch(workspace.getId(), operation));
        assertEquals(1, messageCount(session.getId()));
        var afterFailure = privacyMapper.getWork(workspace.getId(), operation);
        assertEquals(before.getLastMessageId(), afterFailure.getLastMessageId());
        assertEquals(before.getSessionPhase(), afterFailure.getSessionPhase());
        assertEquals(1, afterFailure.getRetryCount());
        doAnswer(invocation -> real.deleteAffectedMessages(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), invocation.getArgument(3)))
                .when(workMapper).deleteAffectedMessages(anyInt(), anyInt(), anyLong(), anyList());
        privacyService.initialize(workspace.getId());
        privacyService.batch(workspace.getId(), operation);
        assertEquals(0, messageCount(session.getId()));
        int committedCursor = privacyMapper.getWork(workspace.getId(), operation).getLastMessageId();
        assertTrue(committedCursor > 0);
        privacyService.initialize(workspace.getId());
        assertEquals(committedCursor, privacyMapper.getWork(workspace.getId(), operation).getLastMessageId());
        finish(operation);
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completionCannotUnblockNewerInvalidation(boolean completionWins) throws Exception {
        String older = invalidate();
        jdbc.update("UPDATE ai_agent_privacy_work SET phase='verify' WHERE workspace_id=? AND operation_key=?",
                workspace.getId(), older);
        UUID newer = UUID.randomUUID();
        AiAgentPrivacyMapper real = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        CountDownLatch ownerLocked = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        AtomicLong contenderConnection = new AtomicLong();
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (!first.compareAndSet(true, false)) {
                contenderConnection.set(connectionId());
                contenderStarted.countDown();
                return real.lockBarrier(invocation.getArgument(0));
            }
            var barrier = real.lockBarrier(invocation.getArgument(0));
            ownerLocked.countDown();
            await(releaseOwner);
            return barrier;
        }).when(workMapper).lockBarrier(workspace.getId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> complete = () -> tenantWorkScope.inWorkspace(workspace.getId(),
                    () -> privacyService.batch(workspace.getId(), older));
            java.util.concurrent.Callable<Boolean> invalidate = () -> tenantWorkScope.inWorkspace(workspace.getId(), () -> {
                transaction().executeWithoutResult(status -> privacyService.invalidateWorkspaces(
                        List.of(workspace.getId()), newer, "restriction", null, null));
                return true;
            });
            var owner = workers.submit(completionWins ? complete : invalidate);
            assertTrue(ownerLocked.await(10, TimeUnit.SECONDS));
            var contender = workers.submit(completionWins ? invalidate : complete);
            assertTrue(contenderStarted.await(10, TimeUnit.SECONDS));
            MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, contenderConnection.get(),
                    "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
            releaseOwner.countDown();
            assertTrue(owner.get(10, TimeUnit.SECONDS));
            assertTrue(contender.get(10, TimeUnit.SECONDS));
        } finally {
            releaseOwner.countDown();
        }
        assertEquals("complete", privacyMapper.getWork(workspace.getId(), older).getPhase());
        assertEquals("runs", privacyMapper.getWork(workspace.getId(), newer.toString()).getPhase());
        assertTrue(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        assertEquals(3, privacyMapper.getBarrier(workspace.getId()).getGeneration());
        finish(newer.toString());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    private AiChatSession taintedSession() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Private cleanup fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        jdbc.update("UPDATE ai_chat_session SET mission_taint_generation=1,mission_taint_seq=1,"
                + "message_seq_high_water=1 WHERE workspace_id=? AND id=?", workspace.getId(), session.getId());
        return session;
    }

    private void insertMessage(int sessionId, long generation) {
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(workspace.getId());
        message.setSessionId(sessionId);
        message.setSeq(chatMapper.nextMessageSequence(workspace.getId(), sessionId));
        message.setAuthorKind("assistant");
        message.setContent("Fenced writer content");
        message.setPrivacyGeneration(generation);
        chatMapper.insertMessage(message);
    }

    private long connectionId() {
        return java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
    }

    private int messageCount(int sessionId) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND session_id=?",
                Integer.class, workspace.getId(), sessionId));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Expected lock coordination did not complete");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
