package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPreparedWrite;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyToken;
import ooo.klae.connex.backend.ai.assistant.AiChatQueryScope;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AuditIntegrityMapper;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Exercises reciprocal privacy, restriction, and real audit-head contention on committed MySQL fixtures. */
class AiAssistantPrivacyLockOrderIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    private static final String ADMISSION = "privacy-order-admission";
    private static final String WRITE = "privacy-order-write";
    private static final String INITIALIZER = "privacy-order-initializer";
    private static final String UNDO = "privacy-order-undo";
    private static final String RESTRICTION = "privacy-order-restriction";

    @Autowired private AiAssistantWriteToolService writes;
    @Autowired private AiChatTurnPersistenceService persistence;
    @Autowired private PersonService persons;
    @Autowired private SqlSessionTemplate sqlSessions;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private AiChatMapper chat;
    @MockitoSpyBean private AiAgentPrivacyMapper privacy;
    @MockitoSpyBean private AuditIntegrityMapper audit;
    @MockitoSpyBean private AiRestrictionEpoch epochs;

    private User writer;
    private Person target;
    private Person restrictionTarget;

    @BeforeEach
    void createWriteFixtures() {
        writer = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), writer.getId(), "owner");
        target = newPerson(null);
        restrictionTarget = newPerson(null);
    }

    /** Admission takes S before waiting on the real audit head while initialization queues X. */
    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void admissionAndWriteCompleteWithInitializationQueued(boolean admissionFirst, boolean approval) throws Exception {
        ToolFixture fixture = authenticated(writer, () -> proposal(approval));
        authenticateAs(currentUser, workspace.getId());
        AiChatSession admissionSession = session(writer, "shared");
        chatMapper.insertParticipant(workspace.getId(), admissionSession.getId(), currentUser.getId());
        long epoch = epochs.current(workspace.getId());
        AiChatMapper realChat = sqlSessions.getMapper(AiChatMapper.class);
        AiAgentPrivacyMapper realPrivacy = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        AuditIntegrityMapper realAudit = sqlSessions.getMapper(AuditIntegrityMapper.class);
        CountDownLatch admissionHasBarrier = new CountDownLatch(1);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        CountDownLatch writeHasHead = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch admissionAtHead = new CountDownLatch(1);
        CountDownLatch initializerAtBarrier = new CountDownLatch(1);
        AtomicBoolean firstWriteHead = new AtomicBoolean(true);
        AtomicLong admissionConnection = new AtomicLong();
        AtomicLong initializerConnection = new AtomicLong();
        doAnswer(invocation -> {
            var barrier = realChat.lockPrivacyBarrierForShare(workspace.getId());
            if (named(ADMISSION)) {
                admissionConnection.set(connectionId());
                admissionHasBarrier.countDown();
                await(releaseAdmission);
            }
            return barrier;
        }).when(chat).lockPrivacyBarrierForShare(workspace.getId());
        doAnswer(invocation -> {
            var head = realAudit.lockHead("workspace", workspace.getId());
            if (named(WRITE) && firstWriteHead.compareAndSet(true, false)) {
                writeHasHead.countDown();
                await(releaseWrite);
            }
            return head;
        }).when(audit).lockHead("workspace", workspace.getId());
        doAnswer(invocation -> {
            if (named(ADMISSION)) {
                admissionAtHead.countDown();
            }
            return realAudit.ensureHead(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
        }).when(audit).ensureHead(eq("workspace"), eq(workspace.getId()), anyString());
        doAnswer(invocation -> {
            if (named(INITIALIZER)) {
                initializerConnection.set(connectionId());
                initializerAtBarrier.countDown();
            }
            return realPrivacy.lockBarrier(workspace.getId());
        }).when(privacy).lockBarrier(workspace.getId());
        try (var workers = Executors.newFixedThreadPool(3)) {
            Future<AiChatQueuedTurn> admitted = null;
            try {
                if (admissionFirst) {
                    admitted = workers.submit(() -> named(ADMISSION, () -> authenticated(currentUser,
                            () -> persistence.queue(admissionSession.getId(),
                                    new AiChatTurnCreateRequest("A question", List.of()), epoch))));
                    await(admissionHasBarrier);
                }
                Future<String> written = workers.submit(() -> named(WRITE, () -> authenticated(writer,
                        () -> approval ? writes.approve(fixture.sessionId(), fixture.toolCallId()).status()
                                : writes.executeAuto(fixture.turn(), fixture.toolCallId(), result -> {}).toolCall().status())));
                await(writeHasHead);
                if (!admissionFirst) {
                    admitted = workers.submit(() -> named(ADMISSION, () -> authenticated(currentUser,
                            () -> persistence.queue(admissionSession.getId(),
                                    new AiChatTurnCreateRequest("A question", List.of()), epoch))));
                    await(admissionHasBarrier);
                }
                releaseAdmission.countDown();
                await(admissionAtHead);
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, admissionConnection.get(),
                        "audit_log_integrity_head", "'workspace', " + workspace.getId());
                Future<Boolean> initialized = workers.submit(() -> named(INITIALIZER,
                        () -> tenantWorkScope.inWorkspace(workspace.getId(), () -> {
                            privacyService.initialize(workspace.getId());
                            return true;
                        })));
                await(initializerAtBarrier);
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, initializerConnection.get(),
                        "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
                releaseWrite.countDown();
                assertEquals("executed", written.get(20, TimeUnit.SECONDS));
                assertNotNull(Objects.requireNonNull(admitted).get(20, TimeUnit.SECONDS));
                assertTrue(initialized.get(20, TimeUnit.SECONDS));
                assertEquals(1L, privacyMapper.getBarrier(workspace.getId()).getGeneration());
                assertEquals(2, auditCount());
            } finally {
                releaseAdmission.countDown();
                releaseWrite.countDown();
            }
        }
    }

    /** Both real holders release their downstream wait before their contender can finish. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void undoAndRestrictionCompleteInBothArrivalOrders(boolean undoFirst) throws Exception {
        ToolFixture fixture = authenticated(writer, () -> proposal(false));
        authenticated(writer, () -> writes.executeAuto(fixture.turn(), fixture.toolCallId(), result -> {}));
        authenticateAs(currentUser, workspace.getId());
        CountDownLatch undoEntered = new CountDownLatch(1);
        CountDownLatch undoAtHead = new CountDownLatch(1);
        CountDownLatch restrictionAtFence = new CountDownLatch(1);
        CountDownLatch restrictionAtBarrier = new CountDownLatch(1);
        AtomicLong undoConnection = new AtomicLong();
        AtomicLong restrictionConnection = new AtomicLong();
        AiAgentPrivacyMapper realPrivacy = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        AuditIntegrityMapper realAudit = sqlSessions.getMapper(AuditIntegrityMapper.class);
        doAnswer(invocation -> {
            if (named(UNDO)) {
                undoEntered.countDown();
            }
            return invocation.callRealMethod();
        }).when(epochs).current(workspace.getId());
        doAnswer(invocation -> {
            if (named(UNDO)) {
                assertNoAuditHeadHeld(connectionId());
            }
            return invocation.callRealMethod();
        }).when(epochs).retainReadFenceUntilTransactionCompletionIfCurrent(eq(workspace.getId()), anyLong());
        doAnswer(invocation -> {
            if (named(RESTRICTION)) {
                restrictionAtFence.countDown();
            }
            return invocation.callRealMethod();
        }).when(epochs).retainWriteFencesUntilTransactionCompletion(anyList());
        doAnswer(invocation -> {
            if (named(UNDO)) {
                undoConnection.set(connectionId());
                undoAtHead.countDown();
            }
            return realAudit.ensureHead(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
        }).when(audit).ensureHead(eq("workspace"), eq(workspace.getId()), anyString());
        doAnswer(invocation -> {
            if (named(RESTRICTION)) {
                restrictionConnection.set(connectionId());
                restrictionAtBarrier.countDown();
            }
            return realPrivacy.lockBarrier(workspace.getId());
        }).when(privacy).lockBarrier(workspace.getId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<String> undo = () -> named(UNDO, () -> authenticated(writer,
                    () -> writes.undo(fixture.sessionId(), fixture.toolCallId()).status()));
            Callable<Person> restrict = () -> named(RESTRICTION, () -> authenticated(currentUser,
                    () -> persons.updateProcessingRestrictions(restrictionTarget.getId(), true, false)));
            AtomicReference<Future<String>> undone = new AtomicReference<>();
            AtomicReference<Future<Person>> restricted = new AtomicReference<>();
            transaction().executeWithoutResult(status -> {
                if (undoFirst) {
                    assertNotNull(realAudit.lockHead("workspace", workspace.getId()));
                    undone.set(workers.submit(undo));
                    await(undoAtHead);
                    MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, undoConnection.get(),
                            "audit_log_integrity_head", "'workspace', " + workspace.getId());
                    restricted.set(workers.submit(restrict));
                    await(restrictionAtFence);
                } else {
                    privacyMapper.lockBarrierForShare(workspace.getId());
                    restricted.set(workers.submit(restrict));
                    await(restrictionAtBarrier);
                    MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, restrictionConnection.get(),
                            "ai_agent_privacy_barrier", Integer.toString(workspace.getId()));
                    undone.set(workers.submit(undo));
                    await(undoEntered);
                }
            });
            assertEquals("undone", undone.get().get(20, TimeUnit.SECONDS));
            assertNotNull(restricted.get().get(20, TimeUnit.SECONDS).getSuspendedAt());
            assertEquals(0, Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM task WHERE workspace_id=?", Integer.class, workspace.getId())));
            assertEquals("undone", objectMapper.readTree(chatMapper.getToolCallBySession(
                    workspace.getId(), fixture.sessionId(), fixture.toolCallId()).getResultJson())
                    .path("undo").path("status").asString());
            assertEquals(3, auditCount());
        }
    }

    private ToolFixture proposal(boolean approval) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        String tool;
        String arguments;
        if (approval) {
            Task task = new Task();
            task.setWorkspaceId(workspace.getId());
            task.setDescription("Approval target");
            task.setStatus("todo");
            task.setAssignedTo(writer);
            taskMapper.insert(task);
            jdbc.update("UPDATE task SET updated_at=DATE_SUB(NOW(), INTERVAL 20 SECOND) WHERE workspace_id=? AND id=?",
                    workspace.getId(), task.getId());
            resources.registerTask(task.getId());
            tool = "complete_task";
            arguments = "{\"handle\":\"t1\"}";
        } else {
            resources.register("person", target.getId());
            tool = "create_task";
            arguments = "{\"handle\":\"r1\",\"description\":\"Follow up\"}";
        }
        long epoch = epochs.current(workspace.getId());
        AiAssistantPreparedWrite prepared = writes.prepare(tool, objectMapper.readTree(arguments), resources, epoch);
        AiChatSession session = session(writer, "private");
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(workspace.getId());
        message.setSessionId(session.getId());
        message.setSeq(1);
        message.setAuthorKind("user");
        message.setAuthorUserId(writer.getId());
        message.setContent("Assistant write proposal");
        chatMapper.insertMessage(message);
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(session.getId());
        turn.setRequestedByUserId(writer.getId());
        turn.setStatus("running");
        turn.setPrivacyGeneration(1L);
        chatMapper.insertTurn(turn);
        AiChatToolCall call = new AiChatToolCall();
        call.setServerProposal(true);
        call.setWorkspaceId(workspace.getId());
        call.setMessageId(message.getId());
        call.setToolName(prepared.toolName());
        call.setStatus("proposed");
        call.setArgumentsJson(prepared.argumentsJson());
        call.setIdempotencyKey("lock-order-message-" + message.getId());
        chatMapper.insertToolCall(call);
        AiChatQueuedTurn queued = new AiChatQueuedTurn(workspace.getId(), writer.getId(), session.getId(),
                turn.getId(), message.getId(), message.getSeq(), epoch, true, List.of(), List.of(),
                AiPrivacyMode.MASKED, false, AiChatQueryScope.none(),
                new AiChatPrivacyToken(workspace.getId(), session.getId(), 1, null));
        return new ToolFixture(session.getId(), call.getId(), queued);
    }

    private AiChatSession session(User author, String visibility) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(author.getId());
        session.setTitle("Lock-order fixture");
        session.setVisibility(visibility);
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private void assertNoAuditHeadHeld(long connection) {
        Integer held = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_locks held_lock
                JOIN performance_schema.threads holder ON holder.THREAD_ID=held_lock.THREAD_ID
                WHERE holder.PROCESSLIST_ID=? AND held_lock.OBJECT_SCHEMA=DATABASE()
                  AND held_lock.OBJECT_NAME='audit_log_integrity_head'
                  AND held_lock.LOCK_TYPE='RECORD' AND held_lock.LOCK_STATUS='GRANTED'
                """, Integer.class, connection);
        assertEquals(0, held, "Undo acquired the audit head before its restriction fence");
    }

    private int auditCount() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE chain_scope_type='workspace' AND chain_scope_id=?",
                Integer.class, workspace.getId()));
    }

    private long connectionId() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
    }

    private <T> T authenticated(User actor, Callable<T> action) throws Exception {
        authenticateAs(actor, workspace.getId());
        try {
            return action.call();
        } finally {
            clearAuthentication();
        }
    }

    private static boolean named(String name) {
        return name.equals(Thread.currentThread().getName());
    }

    private static <T> T named(String name, Callable<T> action) throws Exception {
        Thread.currentThread().setName(name);
        return action.call();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "Expected lock coordination did not complete");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @AfterEach
    void removeWriteFixtures() {
        if (workspace == null) {
            return;
        }
        authenticateAs(currentUser, workspace.getId());
        int id = workspace.getId();
        jdbc.update("DELETE FROM entity_reference WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM task WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM task_board_lock WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM person WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_turn WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_tool_call WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_message WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_session_participant WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_session WHERE workspace_id=?", id);
        if (writer != null) {
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?", id, writer.getId());
            jdbc.update("DELETE FROM app_user WHERE id=?", writer.getId());
        }
    }

    private record ToolFixture(int sessionId, int toolCallId, AiChatQueuedTurn turn) {}
}
