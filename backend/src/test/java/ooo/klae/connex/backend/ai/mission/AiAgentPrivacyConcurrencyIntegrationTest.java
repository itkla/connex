package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.ai.assistant.AiAssistantTurnService;
import ooo.klae.connex.backend.ai.assistant.AiChatProgressService;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyToken;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.AiAssistantService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiAgentPrivacyConcurrencyIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @MockitoSpyBean private AiAgentPrivacyMapper workMapper;
    @MockitoSpyBean private AiChatMapper chat;
    @Autowired private AiAssistantService assistant;
    @Autowired private AiAssistantTurnService turns;
    @MockitoSpyBean private AiChatProgressService progress;
    @MockitoSpyBean private AiRestrictionEpoch epochs;
    @Autowired private AiChatTurnPersistenceService persistence;
    @Autowired private PersonService persons;
    @Autowired private AiAgentRunPersistenceService missions;
    @Autowired private ooo.klae.connex.backend.mappers.AiAgentRunMapper missionRuns;
    @Autowired private AiAgentCapabilityGate missionCapability;
    @Autowired private ooo.klae.connex.backend.ai.AiProperties missionProperties;
    @Autowired private tools.jackson.databind.ObjectMapper missionJson;
    @MockitoSpyBean private ooo.klae.connex.backend.services.WorkspaceService missionWorkspaces;

    @Test
    void completedPurgeRejectsDelayedPlanAndCheckpointWriters() throws Exception {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Delayed continuation fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new ooo.klae.connex.backend.dto.AiAgentRunCreateRequest(UUID.randomUUID().toString(),
                "record_review_v1", "Review", new ooo.klae.connex.backend.dto.AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        var run = missions.create(session.getId(), request, "en", AiAgentCapabilityGate.hash(missionJson.writeValueAsString(request)),
                missionJson.writeValueAsString(request.scope()), epochs.current(workspace.getId()));
        var policy = missions.claim(run, new byte[32], new ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard(missionProperties.getRunLeaseTtl()), missionCapability);
        var snapshot = missions.snapshot(policy);
        var plans = new AiAgentPlanService(new AiAgentContinuationCodec());
        var plan = plans.prepare(snapshot, missionJson.readTree("""
                {"expectedPlanRevision":"1","expectedCheckpointRevision":"1","items":[]}
                """), 1, text -> new AiAgentMemory.DurableText(text, List.of()), ignored -> List.of());
        var checkpoint = plans.prepareCheckpoint(snapshot, missionJson.readTree("""
                {"expectedPlanRevision":"1","expectedCheckpointRevision":"1","nextPlanItemKey":null,
                 "memory":{"summary":"Delayed secret continuation","citations":[]},"phase":"reporting"}
                """), 1, text -> new AiAgentMemory.DurableText(text, List.of()), ignored -> List.of());
        CountDownLatch writersAtAuthority = new CountDownLatch(2);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().startsWith("delayed-continuation-")) {
                writersAtAuthority.countDown();
                assertTrue(resume.await(30, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(missionWorkspaces).lockAndRequirePermissionsSnapshot(anyInt(), anyMap());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var latePlan = workers.submit(() -> delayedContinuation("delayed-continuation-plan", () -> missions.commitPlan(policy, 1, 0,
                    missionJson.writeValueAsString(plan.arguments()), missionJson.writeValueAsString(plan.result()), plan.snapshot())));
            var lateCheckpoint = workers.submit(() -> delayedContinuation("delayed-continuation-checkpoint", () -> missions.commitCheckpoint(policy, 2, 0,
                    missionJson.writeValueAsString(checkpoint.arguments()), missionJson.writeValueAsString(checkpoint.result()), checkpoint.snapshot())));
            try {
                assertTrue(writersAtAuthority.await(10, TimeUnit.SECONDS));
                finish(invalidate());
                assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
                resume.countDown();
                assertTrue(latePlan.get(10, TimeUnit.SECONDS));
                assertTrue(lateCheckpoint.get(10, TimeUnit.SECONDS));
            } finally {
                resume.countDown();
            }
        }
        assertTrue(AiAgentContinuationCodec.erased(missionRuns.getRun(workspace.getId(), run.getId())));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), run.getPrivacyGeneration()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND mission_run_id=?",
                Integer.class, workspace.getId(), run.getId()));
    }

    private boolean delayedContinuation(String name, Runnable write) {
        Thread.currentThread().setName(name);
        authenticateAs(currentUser, workspace.getId());
        try {
            write.run();
            return false;
        } catch (RuntimeException refused) {
            return true;
        } finally {
            clearAuthentication();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transcriptReadOpenedBeforePurgeProjectsOnlyFreshCommittedContent(boolean retained) throws Exception {
        AiChatSession session = taintedSession();
        transaction().executeWithoutResult(status -> {
            chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
            insertMessage(session.getId(), 1);
        });
        if (retained) {
            workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "owner");
            jdbc.update("UPDATE ai_chat_session SET created_by_user_id=NULL WHERE workspace_id=? AND id=?",
                    workspace.getId(), session.getId());
        }
        AiChatMapper real = sqlSessions.getMapper(AiChatMapper.class);
        CountDownLatch snapshotOpened = new CountDownLatch(1);
        CountDownLatch resumeReader = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        org.mockito.stubbing.Answer<AiChatSession> snapshot = invocation -> {
            AiChatSession found = retained
                    ? real.getRetainedSessionById(invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2), invocation.getArgument(3))
                    : real.getAccessibleSessionById(invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2));
            if (Thread.currentThread().getName().equals("mission-transcript-reader")
                    && firstRead.compareAndSet(true, false)) {
                assertNotNull(found);
                assertEquals("Private cleanup fixture", found.getTitle());
                snapshotOpened.countDown();
                await(resumeReader);
            }
            return found;
        };
        if (retained) {
            doAnswer(snapshot).when(chat).getRetainedSessionById(
                    eq(workspace.getId()), eq(currentUser.getId()), eq(session.getId()), anyList());
        } else {
            doAnswer(snapshot).when(chat).getAccessibleSessionById(
                    workspace.getId(), currentUser.getId(), session.getId());
        }
        try (var workers = Executors.newSingleThreadExecutor()) {
            var reader = workers.submit(() -> {
                Thread.currentThread().setName("mission-transcript-reader");
                authenticateAs(currentUser, workspace.getId());
                try {
                    return retained ? assistant.getRetained(session.getId(), 1, 50)
                            : assistant.get(session.getId(), 1, 50);
                } finally {
                    clearAuthentication();
                }
            });
            try {
                assertTrue(snapshotOpened.await(10, TimeUnit.SECONDS));
                finish(invalidate());
                assertEquals(0, messageCount(session.getId()));
                assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
                String currentTitle = real.getPrivacySession(workspace.getId(), session.getId()).getTitle();
                assertFalse("Private cleanup fixture".equals(currentTitle));
                resumeReader.countDown();
                var projected = reader.get(10, TimeUnit.SECONDS);
                assertEquals(currentTitle, projected.session().getTitle());
                assertTrue(projected.messages().items().isEmpty());
                assertEquals(0, projected.messages().total());
                assertNull(projected.activeTurn());
            } finally {
                resumeReader.countDown();
            }
        }
    }

    @Test
    void transcriptProjectionKeepsInvalidationBehindItsPrivacyBarrier() throws Exception {
        AiChatSession session = taintedSession();
        transaction().executeWithoutResult(status -> {
            chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
            insertMessage(session.getId(), 1);
        });
        AiChatMapper realChat = sqlSessions.getMapper(AiChatMapper.class);
        AiAgentPrivacyMapper realPrivacy = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        CountDownLatch invalidating = new CountDownLatch(1);
        AtomicLong waitingConnection = new AtomicLong();
        doAnswer(invocation -> {
            var messages = realChat.listMessages(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
            if (Thread.currentThread().getName().equals("mission-projecting-reader")) {
                projecting.countDown();
                await(releaseProjection);
            }
            return messages;
        }).when(chat).listMessages(workspace.getId(), session.getId(), 50, 0);
        doAnswer(invocation -> {
            waitingConnection.set(connectionId());
            invalidating.countDown();
            return realPrivacy.lockBarrier(invocation.getArgument(0));
        }).when(workMapper).lockBarrier(workspace.getId());
        String operation;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var reader = workers.submit(() -> {
                Thread.currentThread().setName("mission-projecting-reader");
                authenticateAs(currentUser, workspace.getId());
                try {
                    return assistant.get(session.getId(), 1, 50);
                } finally {
                    clearAuthentication();
                }
            });
            try {
                assertTrue(projecting.await(10, TimeUnit.SECONDS));
                var invalidation = workers.submit(() -> tenantWorkScope.inWorkspace(workspace.getId(), this::invalidate));
                assertTrue(invalidating.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, waitingConnection.get(),
                        "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
                releaseProjection.countDown();
                assertEquals(1, reader.get(10, TimeUnit.SECONDS).messages().total());
                operation = invalidation.get(10, TimeUnit.SECONDS);
            } finally {
                releaseProjection.countDown();
            }
        }
        finish(operation);
        assertTrue(assistant.get(session.getId(), 1, 50).messages().items().isEmpty());
    }

    /**
     * Restriction waits for barrier X while retaining its write fence; admission pauses before
     * requesting the conflicting read fence. Restriction must commit before admission can recheck its epoch,
     * proving admission has not retained the downstream audit head at that checkpoint.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionAndRestrictionCannotInvertFenceAndAuditHead(boolean admissionFirst) throws Exception {
        var author = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), author.getId(), "owner");
        var person = newPerson(null);
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(author.getId());
        session.setTitle("Shared ordinary session");
        session.setVisibility("shared");
        session.setStatus("active");
        chatMapper.insertSession(session);
        chatMapper.insertParticipant(workspace.getId(), session.getId(), currentUser.getId());
        long epoch = epochs.current(workspace.getId());
        CountDownLatch admissionBeforeFence = new CountDownLatch(1);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        CountDownLatch restrictionAtBarrier = new CountDownLatch(1);
        AtomicLong restrictionConnection = new AtomicLong();
        AiAgentPrivacyMapper realPrivacy = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        doAnswer(invocation -> {
            admissionBeforeFence.countDown();
            await(releaseAdmission);
            return invocation.callRealMethod();
        }).when(epochs).retainReadFenceUntilTransactionCompletionIfCurrent(workspace.getId(), epoch);
        doAnswer(invocation -> {
            restrictionConnection.set(connectionId());
            restrictionAtBarrier.countDown();
            return realPrivacy.lockBarrier(invocation.getArgument(0));
        }).when(workMapper).lockBarrier(workspace.getId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<AiAssistantLoopException> admission = () -> {
                authenticateAs(currentUser, workspace.getId());
                try {
                    return assertThrows(AiAssistantLoopException.class, () -> persistence.queue(session.getId(),
                            new AiChatTurnCreateRequest("A question", List.of()), epoch));
                } finally {
                    clearAuthentication();
                }
            };
            java.util.concurrent.Callable<Boolean> restriction = () -> {
                authenticateAs(author, workspace.getId());
                try {
                    persons.updateProcessingRestrictions(person.getId(), true, false);
                    return true;
                } finally {
                    clearAuthentication();
                }
            };
            var admitted = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<AiAssistantLoopException>>();
            var restricted = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
            try {
                transaction().executeWithoutResult(status -> {
                    privacyMapper.lockBarrierForShare(workspace.getId());
                    if (admissionFirst) {
                        admitted.set(workers.submit(admission));
                        await(admissionBeforeFence);
                    }
                    restricted.set(workers.submit(restriction));
                    await(restrictionAtBarrier);
                    MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, restrictionConnection.get(),
                            "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
                    if (!admissionFirst) {
                        admitted.set(workers.submit(admission));
                        await(admissionBeforeFence);
                        releaseAdmission.countDown();
                    }
                });
                assertTrue(restricted.get().get(10, TimeUnit.SECONDS));
                releaseAdmission.countDown();
                assertEquals("restrictions_changed", admitted.get().get(10, TimeUnit.SECONDS).detailReason());
            } finally {
                releaseAdmission.countDown();
            }
        } finally {
            jdbc.update("DELETE FROM person WHERE workspace_id=? AND id=?", workspace.getId(), person.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?", workspace.getId(), author.getId());
            jdbc.update("UPDATE ai_chat_session SET created_by_user_id=NULL WHERE workspace_id=? AND id=?",
                    workspace.getId(), session.getId());
            jdbc.update("DELETE FROM app_user WHERE id=?", author.getId());
        }
    }

    @Test
    void pollingRetainsPrivacyUntilTheCompleteResponseIsProjected() throws Exception {
        AiChatSession session = taintedSession();
        AiChatTurn turn = ordinaryPartial(session);
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        CountDownLatch invalidating = new CountDownLatch(1);
        AtomicLong waitingConnection = new AtomicLong();
        AiAgentPrivacyMapper realPrivacy = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("mission-polling-reader")) {
                projecting.countDown();
                await(releaseProjection);
            }
            return invocation.callRealMethod();
        }).when(progress).projectTurn(
                org.mockito.ArgumentMatchers.any(AiChatTurn.class), eq(currentUser.getId()),
                org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            waitingConnection.set(connectionId());
            invalidating.countDown();
            return realPrivacy.lockBarrier(invocation.getArgument(0));
        }).when(workMapper).lockBarrier(workspace.getId());
        String operation;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var reader = workers.submit(() -> {
                Thread.currentThread().setName("mission-polling-reader");
                authenticateAs(currentUser, workspace.getId());
                try {
                    return turns.get(session.getId(), turn.getId());
                } finally {
                    clearAuthentication();
                }
            });
            try {
                assertTrue(projecting.await(10, TimeUnit.SECONDS));
                var invalidation = workers.submit(() -> tenantWorkScope.inWorkspace(workspace.getId(), this::invalidate));
                assertTrue(invalidating.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, waitingConnection.get(),
                        "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
                releaseProjection.countDown();
                var response = reader.get(10, TimeUnit.SECONDS);
                assertEquals("Private partial", response.partialContent());
                assertNotNull(response.scope());
                assertEquals("workspace", response.scope().ownerMode());
                operation = invalidation.get(10, TimeUnit.SECONDS);
            } finally {
                releaseProjection.countDown();
            }
        }
        assertNull(turns.get(session.getId(), turn.getId()).partialContent());
        assertNull(turns.get(session.getId(), turn.getId()).scope());
        finish(operation);
        assertThrows(ooo.klae.connex.backend.exceptions.ResourceNotFoundException.class,
                () -> turns.get(session.getId(), turn.getId()));
    }

    @Test
    void pollingRechecksProvenanceAfterWaitingForInvalidation() throws Exception {
        AiChatSession session = taintedSession();
        AiChatTurn turn = ordinaryPartial(session);
        CountDownLatch invalidated = new CountDownLatch(1);
        CountDownLatch releaseInvalidation = new CountDownLatch(1);
        CountDownLatch reading = new CountDownLatch(1);
        AtomicLong waitingConnection = new AtomicLong();
        AiChatMapper realChat = sqlSessions.getMapper(AiChatMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("mission-polling-reader")) {
                waitingConnection.set(connectionId());
                reading.countDown();
            }
            return realChat.lockPrivacyBarrierForShare(invocation.getArgument(0));
        }).when(chat).lockPrivacyBarrierForShare(workspace.getId());
        String operation;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var invalidation = workers.submit(() -> tenantWorkScope.inWorkspace(workspace.getId(),
                    () -> transaction().execute(status -> {
                        String accepted = invalidate();
                        invalidated.countDown();
                        await(releaseInvalidation);
                        return accepted;
                    })));
            try {
                assertTrue(invalidated.await(10, TimeUnit.SECONDS));
                var reader = workers.submit(() -> {
                    Thread.currentThread().setName("mission-polling-reader");
                    authenticateAs(currentUser, workspace.getId());
                    try {
                        return turns.get(session.getId(), turn.getId());
                    } finally {
                        clearAuthentication();
                    }
                });
                assertTrue(reading.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, waitingConnection.get(),
                        "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
                releaseInvalidation.countDown();
                operation = invalidation.get(10, TimeUnit.SECONDS);
                var response = reader.get(10, TimeUnit.SECONDS);
                assertNull(response.partialContent());
                assertNull(response.scope());
            } finally {
                releaseInvalidation.countDown();
            }
        }
        finish(operation);
    }

    private AiChatTurn ordinaryPartial(AiChatSession session) {
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(session.getId());
        turn.setRequestedByUserId(currentUser.getId());
        turn.setStatus("running");
        turn.setPrivacyMode("masked");
        turn.setStreamed(true);
        turn.setPrivacyGeneration(1L);
        turn.setPrivacyTaintSeq(1);
        turn.setScopeJson(new tools.jackson.databind.ObjectMapper().writeValueAsString(
                new ooo.klae.connex.backend.dto.AiChatQueryScopeDto(true, null, null, null, "workspace",
                        List.of(), List.of(), List.of("person"), List.of(), List.of(), List.of(),
                        null, null, false, 20, 100, 10, List.of())));
        chatMapper.insertTurn(turn);
        jdbc.update("UPDATE ai_chat_turn SET partial_content='Private partial', partial_content_utf16_offset=15"
                + " WHERE workspace_id=? AND id=?", workspace.getId(), turn.getId());
        return turn;
    }

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
        session.setTitleUserSet(false);
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
