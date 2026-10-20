package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import tools.jackson.databind.ObjectMapper;

class AiAssistantPrivacyConcurrencyIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @Autowired private AiAssistantPrivacyService organizationPrivacy;
    @Autowired private AiAgentRunPersistenceService missions;
    @MockitoSpyBean private AiAgentRunMapper runs;
    @MockitoSpyBean private ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper workRows;
    @MockitoSpyBean private ooo.klae.connex.backend.ai.lease.AiRunLeaseService leases;
    @Autowired private ooo.klae.connex.backend.services.AuditService audit;
    @Autowired private AiRestrictionEpoch epoch;
    @Autowired private AiAgentCapabilityGate capability;
    @Autowired private AiProperties properties;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private AiChatMapper lockedChats;
    @MockitoSpyBean private ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper control;
    @Autowired private ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard privacyGuard;
    @Autowired private ooo.klae.connex.backend.services.AiAssistantService assistant;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void organizationFenceContentionRejectsStalePublisher(boolean invalidationFirst) throws Exception {
        AiChatSession session = session();
        var token = privacyGuard.capture(chatMapper, session);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contending = new CountDownLatch(1);
        AtomicLong connection = new AtomicLong();
        AtomicBoolean published = new AtomicBoolean();
        observeFenceContention(invalidationFirst, held, release, contending, connection);
        UUID operation = UUID.randomUUID();
        Runnable invalidation = () -> transaction().executeWithoutResult(status -> organizationPrivacy.invalidateOrganization(
                workspace.getOrgId(), operation, "restriction", null, null));
        Runnable publication = () -> transaction().executeWithoutResult(status -> {
            lockedChats.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
            privacyGuard.retain(chatMapper, token);
            published.set(true);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = workers.submit(() -> scoped("assistant-org-holder", invalidationFirst ? invalidation : publication, false));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var contender = workers.submit(() -> scoped("assistant-org-contender", () -> {
                if (invalidationFirst) {
                    assertThrows(ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException.class, publication::run);
                } else {
                    invalidation.run();
                }
            }, false));
            try {
                assertTrue(contending.await(10, TimeUnit.SECONDS));
                awaitFenceWait(connection.get(), invalidationFirst);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            contender.get(10, TimeUnit.SECONDS);
        }
        assertEquals(!invalidationFirst, published.get());
        assertEquals(2, organizationPrivacy.current(workspace.getOrgId()));
        assertEquals(1, token.organizationPrivacyGeneration());
        assertNotNull(chatMapper.getPrivacySession(workspace.getId(), session.getId()));
    }

    @Test
    void omittedWorkspaceIsWithheldBeforeEnrollment() {
        AiChatSession session = session();
        assertNotNull(assistant.get(session.getId(), 1, 20));
        UUID operation = UUID.randomUUID();
        transaction().executeWithoutResult(status -> privacyService.invalidateAssistantOrganization(
                workspace.getOrgId(), List.of(), operation, "restriction", null, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_privacy_work WHERE workspace_id=? AND operation_key=?",
                Integer.class, workspace.getId(), operation.toString()));
        assertNotNull(chatMapper.getPrivacySession(workspace.getId(), session.getId()));
        assertEquals(0, assistant.page(1, 20).total());
        assertThrows(RuntimeException.class, () -> assistant.get(session.getId(), 1, 20));
        privacyService.initialize(workspace.getId());
        assertNotNull(privacyMapper.getWork(workspace.getId(), operation.toString()));
        finish(operation.toString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND id=?",
                Integer.class, workspace.getId(), session.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializerAndWriterSerializeAtOrganizationAuthority(boolean initializerFirst) throws Exception {
        AiChatSession session = session();
        var token = privacyGuard.capture(chatMapper, session);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contending = new CountDownLatch(1);
        AtomicLong connection = new AtomicLong();
        observeFenceContention(initializerFirst, held, release, contending, connection);
        Runnable initialization = () -> privacyService.initialize(workspace.getId());
        Runnable write = () -> transaction().executeWithoutResult(status -> {
            lockedChats.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
            privacyGuard.retain(chatMapper, token);
            jdbc.update("UPDATE ai_chat_session SET title='Current writer survives initialization' WHERE workspace_id=? AND id=?",
                    workspace.getId(), session.getId());
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = workers.submit(() -> scoped("assistant-org-holder", initializerFirst ? initialization : write, false));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var contender = workers.submit(() -> scoped("assistant-org-contender", initializerFirst ? write : initialization, false));
            try {
                assertTrue(contending.await(10, TimeUnit.SECONDS));
                awaitFenceWait(connection.get(), initializerFirst);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            contender.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, organizationPrivacy.current(workspace.getOrgId()));
        assertEquals("Current writer survives initialization", jdbc.queryForObject(
                "SELECT title FROM ai_chat_session WHERE workspace_id=? AND id=?", String.class, workspace.getId(), session.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT organization_privacy_generation FROM ai_chat_session WHERE workspace_id=? AND id=?",
                Long.class, workspace.getId(), session.getId()));
    }

    @Test
    void duplicateV2BatchCommitsOnce() throws Exception {
        AiChatSession session = session();
        jdbc.update("INSERT INTO ai_chat_message (workspace_id,session_id,seq,author_kind,content,organization_privacy_generation) VALUES (?,?,1,'user','Atomic duplicate cleanup',1)",
                workspace.getId(), session.getId());
        Integer messageId = jdbc.queryForObject("SELECT id FROM ai_chat_message WHERE workspace_id=? AND session_id=?",
                Integer.class, workspace.getId(), session.getId());
        var operation = transaction().execute(status -> organizationPrivacy.invalidateOrganization(
                workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
        assertNotNull(operation);
        transaction().executeWithoutResult(status -> privacyService.enrollOperation(workspace.getId(), operation));
        var first = privacyMapper.getWork(workspace.getId(), operation.getOperationKey());
        var duplicate = privacyMapper.getWork(workspace.getId(), operation.getOperationKey());
        assertNotNull(first);
        assertNotNull(duplicate);
        long revision = first.getRevision();
        first.setPhase("sessions");
        first.setRevision(revision + 1);
        duplicate.setPhase("sessions");
        duplicate.setLastMessageId(java.util.Objects.requireNonNull(messageId));
        duplicate.setRevision(revision + 1);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contending = new CountDownLatch(1);
        AtomicLong connection = new AtomicLong();
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("assistant-v2-duplicate")) {
                connection.set(java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)));
                contending.countDown();
            }
            return sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper.class).lockOperation(invocation.getArgument(0), invocation.getArgument(1));
        }).when(control).lockOperation(workspace.getOrgId(), operation.getOperationKey());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var owner = workers.submit(() -> scoped("assistant-v2-owner", () -> transaction().executeWithoutResult(status -> {
                control.lockFenceForShare(workspace.getOrgId());
                privacyMapper.lockBarrierForShare(workspace.getId());
                control.lockOperation(workspace.getOrgId(), operation.getOperationKey());
                control.lockWorkspaceIntent(workspace.getOrgId(), operation.getOperationKey(), workspace.getId());
                privacyMapper.lockWork(workspace.getId(), operation.getOperationKey());
                held.countDown();
                await(release);
                assertEquals(1, privacyMapper.updateWork(first, revision));
            }), false));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var contender = workers.submit(() -> scoped("assistant-v2-duplicate", () -> assertThrows(
                    ooo.klae.connex.backend.exceptions.ConflictException.class, () -> transaction().executeWithoutResult(status -> {
                lockedChats.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
                privacyMapper.lockMessage(workspace.getId(), session.getId(), messageId);
                assertEquals(1, privacyMapper.deleteAllChatMessages(workspace.getId(), session.getId(), List.of(messageId)));
                control.lockFenceForShare(workspace.getOrgId());
                privacyMapper.lockBarrierForShare(workspace.getId());
                control.lockOperation(workspace.getOrgId(), operation.getOperationKey());
                control.lockWorkspaceIntent(workspace.getOrgId(), operation.getOperationKey(), workspace.getId());
                privacyMapper.lockWork(workspace.getId(), operation.getOperationKey());
                if (privacyMapper.updateWork(duplicate, revision) != 1) {
                    throw new ooo.klae.connex.backend.exceptions.ConflictException("mission_privacy_cursor_conflict");
                }
            })), false));
            try {
                assertTrue(contending.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection.get(), "ai_assistant_privacy_operation",
                        workspace.getOrgId() + ", '" + operation.getOperationKey() + "'");
            } finally {
                release.countDown();
            }
            owner.get(10, TimeUnit.SECONDS);
            contender.get(10, TimeUnit.SECONDS);
        }
        assertEquals(revision + 1, privacyMapper.getWork(workspace.getId(), operation.getOperationKey()).getRevision());
        assertEquals(0, privacyMapper.getWork(workspace.getId(), operation.getOperationKey()).getLastMessageId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND id=?", Integer.class, workspace.getId(), messageId));
        finish(operation.getOperationKey());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void olderCompletionCannotCompleteNewerOperation(boolean newerInvalidationFirst) throws Exception {
        var older = transaction().execute(status -> organizationPrivacy.invalidateOrganization(
                workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
        assertNotNull(older);
        transaction().executeWithoutResult(status -> privacyService.enrollOperation(workspace.getId(), older));
        jdbc.update("UPDATE ai_agent_privacy_work SET phase='verify' WHERE workspace_id=? AND operation_key=?",
                workspace.getId(), older.getOperationKey());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contending = new CountDownLatch(1);
        AtomicLong connection = new AtomicLong();
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("assistant-org-contender") && !newerInvalidationFirst) {
                connection.set(java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)));
                contending.countDown();
            }
            Object row = sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper.class).lockFence(invocation.getArgument(0));
            if (Thread.currentThread().getName().equals("assistant-org-holder")) {
                held.countDown();
                await(release);
            }
            return row;
        }).when(control).lockFence(workspace.getOrgId());
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("assistant-org-contender") && newerInvalidationFirst) {
                connection.set(java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)));
                contending.countDown();
            }
            return sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper.class).lockFenceForShare(invocation.getArgument(0));
        }).when(control).lockFenceForShare(workspace.getOrgId());
        UUID newerKey = UUID.randomUUID();
        Runnable newer = () -> transaction().executeWithoutResult(status -> organizationPrivacy.invalidateOrganization(
                workspace.getOrgId(), newerKey, "restriction", null, null));
        Runnable completion = () -> assertTrue(privacyService.batch(workspace.getId(), older.getOperationKey()));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = workers.submit(() -> scoped("assistant-org-holder", newerInvalidationFirst ? newer : completion, false));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var contender = workers.submit(() -> scoped("assistant-org-contender", newerInvalidationFirst ? completion : newer, false));
            try {
                assertTrue(contending.await(10, TimeUnit.SECONDS));
                awaitFenceWait(connection.get(), newerInvalidationFirst);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            contender.get(10, TimeUnit.SECONDS);
        }
        assertNotNull(control.getOperation(workspace.getOrgId(), older.getOperationKey()).getCompletedAt());
        assertNull(control.getOperation(workspace.getOrgId(), newerKey.toString()).getCompletedAt());
        assertEquals(3, organizationPrivacy.current(workspace.getOrgId()));
        assertEquals(older.getCutoffGeneration(), privacyMapper.getWork(workspace.getId(), older.getOperationKey()).getCutoffGeneration());
    }

    private void observeFenceContention(boolean exclusiveFirst, CountDownLatch held, CountDownLatch release,
            CountDownLatch contending, AtomicLong connection) {
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("assistant-org-contender") && !exclusiveFirst) {
                connection.set(java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)));
                contending.countDown();
            }
            Object row = sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper.class).lockFence(invocation.getArgument(0));
            if (Thread.currentThread().getName().equals("assistant-org-holder") && exclusiveFirst) {
                held.countDown();
                await(release);
            }
            return row;
        }).when(control).lockFence(workspace.getOrgId());
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("assistant-org-contender") && exclusiveFirst) {
                connection.set(java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)));
                contending.countDown();
            }
            Object row = sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper.class).lockFenceForShare(invocation.getArgument(0));
            if (Thread.currentThread().getName().equals("assistant-org-holder") && !exclusiveFirst) {
                held.countDown();
                await(release);
            }
            return row;
        }).when(control).lockFenceForShare(workspace.getOrgId());
    }

    private void awaitFenceWait(long connection, boolean shared) {
        if (shared) {
            MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, connection, "ai_assistant_privacy_fence", Integer.toString(workspace.getOrgId()));
        } else {
            MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection, "ai_assistant_privacy_fence", Integer.toString(workspace.getOrgId()));
        }
    }

    private static void await(CountDownLatch release) {
        try {
            assertTrue(release.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Privacy contention interrupted", interrupted);
        }
    }

    @ParameterizedTest
    @CsvSource({"cancel,false", "cancel,true", "expiry,false", "expiry,true"})
    void cancellationAndExpirySerializeWithV2PurgeAtTheContentRoot(String action, boolean purgeFirst) throws Exception {
        AiChatSession session = session();
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_review_v1", "Review",
                new AiAgentRunCreateRequest.Scope("record", "company", 1), null);
        AiAgentRun created = missions.create(session.getId(), request, "en", AiAgentCapabilityGate.hash(json.writeValueAsString(request)),
                json.writeValueAsString(request.scope()), epoch.current(workspace.getId()));
        var policy = missions.claim(created, new byte[32], new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        int toolId = missions.proposeTool(policy, 1, 0, "get_record", "{\"id\":1}");
        transaction().executeWithoutResult(status -> audit.recordStrict("ai.tool.invoke", "ai_chat_tool_call", toolId,
                null, "Assistant tool invocation", Map.of("tier", "read")));
        Integer invocationAudit = jdbc.queryForObject("SELECT MAX(id) FROM audit_log WHERE workspace_id=? AND action='ai.tool.invoke'", Integer.class, workspace.getId());
        assertNotNull(invocationAudit);
        var reservation = missions.reserveDispatch(policy, 17);
        assertNotNull(reservation);
        if ("expiry".equals(action)) {
            jdbc.update("UPDATE ai_agent_run SET execution_deadline_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE workspace_id=? AND id=?",
                    workspace.getId(), created.getId());
        }
        AiAgentRun discovered = runs.getRun(workspace.getId(), created.getId());
        assertNotNull(discovered);
        long consumedBefore = discovered.getConsumedTokens();
        long reservedBefore = discovered.getReservedTokens();
        var operation = transaction().execute(status -> organizationPrivacy.invalidateOrganization(
                workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
        assertNotNull(operation);
        transaction().executeWithoutResult(status -> {
            organizationPrivacy.reconcileIntents(workspace.getOrgId(), operation.getOperationKey());
            privacyService.enrollOperation(workspace.getId(), operation);
        });
        CountDownLatch rootHeld = new CountDownLatch(1);
        CountDownLatch releaseRoot = new CountDownLatch(1);
        CountDownLatch contenderCalling = new CountDownLatch(1);
        AtomicLong contenderConnection = new AtomicLong();
        doAnswer(invocation -> {
            String name = Thread.currentThread().getName();
            if (name.equals("assistant-privacy-race-contender")) {
                Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                contenderConnection.set(java.util.Objects.requireNonNull(connection));
                contenderCalling.countDown();
            }
            Object row = sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiChatMapper.class).getSessionByIdForMaintenanceUpdate(invocation.getArgument(0), invocation.getArgument(1));
            if (name.equals("assistant-privacy-race-holder")) {
                rootHeld.countDown();
                assertTrue(releaseRoot.await(30, TimeUnit.SECONDS));
            }
            return row;
        }).when(lockedChats).getSessionByIdForMaintenanceUpdate(anyInt(), anyInt());
        ThreadLocal<Boolean> purging = new ThreadLocal<>();
        ThreadLocal<Boolean> leaseReleased = ThreadLocal.withInitial(() -> false);
        AtomicBoolean releaseObserved = new AtomicBoolean();
        AtomicBoolean terminalReleaseObserved = new AtomicBoolean();
        doAnswer(invocation -> {
            if (Boolean.TRUE.equals(purging.get())) {
                AiAgentRun settled = runs.getRun(workspace.getId(), created.getId());
                assertNotNull(settled);
                assertEquals(0, settled.getReservedTokens());
                assertNotNull(settled.getPurgedAt());
                assertTrue(privacyMapper.getWork(workspace.getId(), operation.getOperationKey()).getRevision() > 1);
                releaseObserved.set(true);
            } else {
                AiAgentRun settled = runs.getRun(workspace.getId(), created.getId());
                assertNotNull(settled);
                assertEquals(0, settled.getReservedTokens());
                assertTrue(List.of("failed", "cancelled").contains(settled.getStatus()));
                terminalReleaseObserved.set(true);
            }
            leaseReleased.set(true);
            return invocation.callRealMethod();
        }).when(org.springframework.test.util.AopTestUtils.<ooo.klae.connex.backend.ai.lease.AiRunLeaseService>getUltimateTargetObject(leases)).releaseHeldInCurrentTransaction(org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            assertFalse(leaseReleased.get(), "Run settlement must precede lease release");
            return sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAgentRunMapper.class).updateRun(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).when(runs).updateRun(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        doAnswer(invocation -> {
            assertFalse(leaseReleased.get(), "Segment settlement must precede lease release");
            return sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAgentRunMapper.class).updateSegment(invocation.getArgument(0), invocation.getArgument(1));
        }).when(runs).updateSegment(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        doAnswer(invocation -> {
            if (Boolean.TRUE.equals(purging.get())) {
                assertFalse(releaseObserved.get(), "Work changes must precede lease release");
            }
            return sqlSessions.getMapper(ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper.class).updateWork(invocation.getArgument(0), invocation.getArgument(1));
        }).when(workRows).updateWork(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        Runnable purge = () -> {
            purging.set(true);
            leaseReleased.set(false);
            try {
                privacyService.batch(workspace.getId(), operation.getOperationKey());
            } finally {
                purging.remove();
                leaseReleased.remove();
            }
        };
        Runnable terminate = () -> {
            leaseReleased.set(false);
            try {
                if ("expiry".equals(action)) {
                    missions.expire(discovered);
                } else {
                    missions.cancelForDeparture(discovered);
                }
            } finally {
                leaseReleased.remove();
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = workers.submit(() -> scoped("assistant-privacy-race-holder", purgeFirst ? purge : terminate, false));
            assertTrue(rootHeld.await(10, TimeUnit.SECONDS));
            var contender = workers.submit(() -> scoped("assistant-privacy-race-contender", purgeFirst ? terminate : purge, purgeFirst));
            try {
                assertTrue(contenderCalling.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, contenderConnection.get(), "ai_chat_session", Integer.toString(session.getId()));
            } finally {
                releaseRoot.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            contender.get(10, TimeUnit.SECONDS);
        }
        finish(operation.getOperationKey());
        AiAgentRun erased = runs.getRun(workspace.getId(), created.getId());
        assertNotNull(erased);
        assertTrue(AiAgentContinuationCodec.erased(erased));
        assertEquals(0, erased.getReservedTokens());
        assertEquals(consumedBefore + reservedBefore, erased.getConsumedTokens());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id=? AND owner IS NOT NULL",
                Integer.class, workspace.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND id=?",
                Integer.class, workspace.getId(), session.getId()));
        assertTrue(releaseObserved.get());
        assertEquals(!purgeFirst, terminalReleaseObserved.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE workspace_id=? AND id=? AND action='ai.tool.invoke'", Integer.class, workspace.getId(), invocationAudit));
        assertThrows(RuntimeException.class, () -> missions.recordUsage(policy, reservation, 1, 1));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    private void scoped(String name, Runnable action, boolean mayRefuse) {
        Thread.currentThread().setName(name);
        authenticateAs(currentUser, workspace.getId());
        try {
            action.run();
        } catch (RuntimeException refused) {
            if (!mayRefuse) {
                throw refused;
            }
        } finally {
            clearAuthentication();
        }
    }

    private AiChatSession session() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(organizationPrivacy.current(workspace.getOrgId()));
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Privacy contention fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }
}
