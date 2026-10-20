package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionDefinition;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.ai.assistant.AiBriefRunService;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper;
import ooo.klae.connex.backend.mappers.AiBriefScheduleMapper;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Reproduces the reviewed JVM/SQL lock cycles and the tenant-to-control completion crash gap. */
class AiAssistantPrivacyRemediationIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private SqlSessionTemplate sqlSessions;
    @Autowired private AiAssistantPrivacyService authority;
    @Autowired private AiOutputCacheStore cacheStore;
    @Autowired private PersonService persons;
    @Autowired private AiBriefRunService briefs;
    @Autowired private OrganizationMapper organizations;
    @Autowired private TenantWorkScope workScope;
    @MockitoSpyBean private AiRestrictionEpoch epochs;
    @MockitoSpyBean private AiOutputCacheMapper cache;
    @MockitoSpyBean private AiAgentPrivacyMapper work;
    @MockitoSpyBean private AiAssistantPrivacyControlMapper control;
    @MockitoSpyBean private AiBriefScheduleMapper schedules;
    @MockitoSpyBean private AiFeatureGate features;

    @Test
    void simultaneousNewOrganizationsInsertTheirFirstWorkspaceBeforeBootstrapEnrollment() throws Exception {
        var first = new Workspace();
        var second = new Workspace();
        var created = List.of(first, second);
        var provisioned = new CountDownLatch(2);
        var insertWorkspace = new CountDownLatch(1);
        clearAuthentication();
        try {
            for (var next : created) {
                String key = unique();
                var organization = new Organization();
                organization.setName("Concurrent bootstrap " + key);
                organization.setSlug("concurrent-bootstrap-" + key);
                organizations.insert(organization);
                next.setOrgId(organization.getId());
                next.setName("Concurrent bootstrap " + key);
                next.setSlug("concurrent-bootstrap-" + key);
            }
            try (var workers = Executors.newFixedThreadPool(2)) {
                try {
                    var futures = created.stream().map(next -> workers.submit(() -> workScope.unrouted(() -> {
                        var transaction = transaction();
                        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                        transaction.executeWithoutResult(status -> {
                            authority.provision(next.getOrgId());
                            provisioned.countDown();
                            await(insertWorkspace);
                            workspaceMapper.insert(next);
                        });
                        return null;
                    }))).toList();
                    assertTrue(provisioned.await(10, TimeUnit.SECONDS));
                    insertWorkspace.countDown();
                    for (var future : futures) {
                        future.get(10, TimeUnit.SECONDS);
                    }
                } finally {
                    insertWorkspace.countDown();
                }
            }
            for (var next : created) {
                String key = control.getFence(next.getOrgId()).getBootstrapOperationId();
                assertTrue(authority.operationWorkspaceIds(next.getOrgId(), key).isEmpty());
                workScope.inWorkspace(next.getId(), () -> {
                    privacyService.initialize(next.getId());
                    for (int batch = 0; batch < 10 && !privacyService.batch(next.getId(), key); batch++) {
                        assertNull(control.getOperation(next.getOrgId(), key).getCompletedAt());
                    }
                });
                assertEquals(List.of(next.getId()), authority.operationWorkspaceIds(next.getOrgId(), key));
                assertNotNull(control.getOperation(next.getOrgId(), key).getCompletedAt());
                assertNotNull(control.getFence(next.getOrgId()).getBootstrapCompletedAt());
            }
        } finally {
            for (var next : created) {
                if (next.getId() != 0) {
                    TestEntityFixtures.deleteChatPrivacy(jdbc, next.getId());
                    jdbc.update("DELETE FROM workspace WHERE id=?", next.getId());
                }
                if (next.getOrgId() != 0) {
                    jdbc.update("DELETE FROM ai_assistant_privacy_operation_workspace WHERE organization_id=?", next.getOrgId());
                    jdbc.update("DELETE FROM ai_assistant_privacy_operation WHERE organization_id=?", next.getOrgId());
                    jdbc.update("DELETE FROM ai_assistant_privacy_fence WHERE organization_id=?", next.getOrgId());
                    jdbc.update("DELETE FROM organization WHERE id=?", next.getOrgId());
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"bootstrap,true", "bootstrap,false", "restriction,true", "restriction,false"})
    void completedTenantWorkRecoversControlCompletionCrash(String reason, boolean initializer) {
        String key;
        if ("bootstrap".equals(reason)) {
            key = control.getFence(workspace.getOrgId()).getBootstrapOperationId();
        } else {
            var operation = transaction().execute(status -> authority.invalidateOrganization(
                    workspace.getOrgId(), UUID.randomUUID(), reason, null, null));
            assertNotNull(operation);
            transaction().executeWithoutResult(status -> privacyService.enrollOperation(workspace.getId(), operation));
            key = operation.getOperationKey();
        }
        for (int batch = 0; batch < 10 && !"verify".equals(privacyMapper.getWork(workspace.getId(), key).getPhase()); batch++) {
            assertFalse(privacyService.batch(workspace.getId(), key));
        }
        assertEquals("verify", privacyMapper.getWork(workspace.getId(), key).getPhase());
        var interrupted = new AtomicBoolean();
        var real = sqlSessions.getMapper(AiAssistantPrivacyControlMapper.class);
        doAnswer(call -> {
            if ("complete".equals(privacyMapper.getWork(workspace.getId(), key).getPhase())
                    && interrupted.compareAndSet(false, true)) {
                throw new IllegalStateException("Interrupted after tenant completion committed");
            }
            return real.lockFence(call.getArgument(0));
        }).when(control).lockFence(workspace.getOrgId());
        assertThrows(IllegalStateException.class, () -> privacyService.batch(workspace.getId(), key));
        assertTrue(interrupted.get());
        assertEquals("complete", privacyMapper.getWork(workspace.getId(), key).getPhase());
        assertEquals("verified", jdbc.queryForObject("SELECT state FROM ai_assistant_privacy_operation_workspace"
                + " WHERE organization_id=? AND operation_key=? AND workspace_id=?", String.class,
                workspace.getOrgId(), key, workspace.getId()));
        assertNull(control.getOperation(workspace.getOrgId(), key).getCompletedAt());
        if (initializer) {
            privacyService.initialize(workspace.getId());
        } else {
            assertTrue(privacyService.batch(workspace.getId(), key));
        }
        assertNotNull(control.getOperation(workspace.getOrgId(), key).getCompletedAt());
        if ("bootstrap".equals(reason)) {
            assertNotNull(control.getFence(workspace.getOrgId()).getBootstrapCompletedAt());
        }
    }

    @Test
    void restrictionWaitsForCacheReadFenceBeforeDeletingItsRows() throws Exception {
        var person = newPerson(null);
        long epoch = epochs.current(workspace.getId());
        assertTrue(cacheStore.save(workspace.getId(), "report.narrative:drill", person.getId(), 0,
                "original", List.of("original"), 0, "2026-10-20T00:00:00Z", epoch));
        var writerAtUpsert = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var restrictionAtFence = new CountDownLatch(1);
        var restrictionThread = new AtomicReference<Thread>();
        var upserted = new AtomicBoolean();
        var real = sqlSessions.getMapper(AiOutputCacheMapper.class);
        doAnswer(call -> {
            if (named("cache-writer")) {
                writerAtUpsert.countDown();
                await(releaseWriter);
                int written = real.upsert(call.getArgument(0));
                upserted.set(true);
                return written;
            }
            return real.upsert(call.getArgument(0));
        }).when(cache).upsert(any());
        doAnswer(call -> {
            assertTrue(upserted.get(), "Restriction must obtain its write fence before touching cache rows");
            return real.deleteForPerson(call.getArgument(0), call.getArgument(1));
        }).when(cache).deleteForPerson(workspace.getId(), person.getId());
        doAnswer(call -> {
            if (named("cache-restriction")) {
                restrictionThread.set(Thread.currentThread());
                restrictionAtFence.countDown();
            }
            return call.callRealMethod();
        }).when(AopTestUtils.<AiRestrictionEpoch>getUltimateTargetObject(epochs))
                .retainWriteFencesUntilTransactionCompletion(anyList());
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var writer = workers.submit(() -> scoped("cache-writer", () -> assertTrue(cacheStore.save(
                        workspace.getId(), "report.narrative:drill", person.getId(), 0, "replacement",
                        List.of("replacement"), 0, "2026-10-20T00:00:00Z", epoch))));
                assertTrue(writerAtUpsert.await(10, TimeUnit.SECONDS));
                var restricting = workers.submit(() -> scoped("cache-restriction",
                        () -> persons.updateProcessingRestrictions(person.getId(), true, false)));
                try {
                    assertTrue(restrictionAtFence.await(10, TimeUnit.SECONDS));
                    awaitWriteFence(restrictionThread.get());
                    assertFalse(upserted.get());
                } finally {
                    releaseWriter.countDown();
                }
                writer.get(10, TimeUnit.SECONDS);
                restricting.get(10, TimeUnit.SECONDS);
            } finally {
                releaseWriter.countDown();
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_output_cache WHERE workspace_id=?",
                Integer.class, workspace.getId()));
    }

    @Test
    void sharedBriefSchedulePrecedesOrganizationAuthorityDuringCleanup() throws Exception {
        var old = session(1);
        var operation = transaction().execute(status -> authority.invalidateOrganization(
                workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
        assertNotNull(operation);
        transaction().executeWithoutResult(status -> privacyService.enrollOperation(workspace.getId(), operation));
        var fresh = session(2);
        var turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(fresh.getId());
        turn.setRequestedByUserId(currentUser.getId());
        turn.setStatus("resolved");
        turn.setPrivacyGeneration(1L);
        turn.setOrganizationPrivacyGeneration(2);
        chatMapper.insertTurn(turn);
        jdbc.update("INSERT INTO ai_brief_schedule (workspace_id,user_id,pending_kind,pending_session_id,"
                + "pending_turn_id,last_delivered_session_id,last_delivered_kind) VALUES (?,?,'daily',?, ?,?,'daily')",
                workspace.getId(), currentUser.getId(), fresh.getId(), turn.getId(), old.getId());
        var schedule = schedules.findForMember(workspace.getId(), currentUser.getId());
        assertNotNull(schedule);
        jdbc.update("UPDATE ai_agent_privacy_work SET phase='sessions',current_session_id=?,session_phase='title'"
                + " WHERE workspace_id=? AND operation_key=?", old.getId(), workspace.getId(), operation.getOperationKey());
        doReturn(true).when(features).isAiUsable(AiFeature.ASSISTANT_CHAT);
        var scheduleHeld = new CountDownLatch(1);
        var releaseDelivery = new CountDownLatch(1);
        var cleanupAtSchedule = new CountDownLatch(1);
        var exclusiveHeld = new CountDownLatch(1);
        var releaseExclusive = new CountDownLatch(1);
        var deliveryAtShare = new CountDownLatch(1);
        var cleanupConnection = new AtomicLong();
        var deliveryConnection = new AtomicLong();
        var realSchedules = sqlSessions.getMapper(AiBriefScheduleMapper.class);
        var realControl = sqlSessions.getMapper(AiAssistantPrivacyControlMapper.class);
        var realWork = sqlSessions.getMapper(AiAgentPrivacyMapper.class);
        doAnswer(call -> {
            int released = realSchedules.releasePendingTurn(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
            if (named("brief-delivery") && released == 1) {
                scheduleHeld.countDown();
                await(releaseDelivery);
            }
            return released;
        }).when(schedules).releasePendingTurn(anyInt(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.nullable(String.class), any());
        doAnswer(call -> {
            if (named("brief-cleanup")) {
                cleanupConnection.set(connectionId());
                cleanupAtSchedule.countDown();
            }
            return realWork.clearSessionProjections(call.getArgument(0), call.getArgument(1));
        }).when(work).clearSessionProjections(workspace.getId(), old.getId());
        doAnswer(call -> {
            var fence = realControl.lockFence(call.getArgument(0));
            if (named("brief-invalidation")) {
                exclusiveHeld.countDown();
                await(releaseExclusive);
            }
            return fence;
        }).when(control).lockFence(workspace.getOrgId());
        doAnswer(call -> {
            if (named("brief-delivery") && scheduleHeld.getCount() == 0) {
                deliveryConnection.set(connectionId());
                deliveryAtShare.countDown();
            }
            return realControl.lockFenceForShare(call.getArgument(0));
        }).when(control).lockFenceForShare(workspace.getOrgId());
        try (var workers = Executors.newFixedThreadPool(3)) {
            try {
                var delivery = workers.submit(() -> scoped("brief-delivery",
                        () -> assertThrows(RuntimeException.class, () -> briefs.deliverPending(schedule))));
                assertTrue(scheduleHeld.await(10, TimeUnit.SECONDS));
                var cleanup = workers.submit(() -> scoped("brief-cleanup",
                        () -> privacyService.batch(workspace.getId(), operation.getOperationKey())));
                assertTrue(cleanupAtSchedule.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, cleanupConnection.get(), "ai_brief_schedule",
                        Integer.toString(schedule.getId()));
                var invalidation = workers.submit(() -> scoped("brief-invalidation",
                        () -> transaction().executeWithoutResult(status -> authority.invalidateOrganization(
                                workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null))));
                try {
                    assertTrue(exclusiveHeld.await(10, TimeUnit.SECONDS));
                    releaseDelivery.countDown();
                    assertTrue(deliveryAtShare.await(10, TimeUnit.SECONDS));
                    MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, deliveryConnection.get(), "ai_assistant_privacy_fence",
                            Integer.toString(workspace.getOrgId()));
                } finally {
                    releaseDelivery.countDown();
                    releaseExclusive.countDown();
                }
                delivery.get(10, TimeUnit.SECONDS);
                invalidation.get(10, TimeUnit.SECONDS);
                cleanup.get(10, TimeUnit.SECONDS);
            } finally {
                releaseDelivery.countDown();
                releaseExclusive.countDown();
            }
        }
        assertNull(chatMapper.getPrivacySession(workspace.getId(), old.getId()));
        assertNull(schedules.findForMember(workspace.getId(), currentUser.getId()).getLastDeliveredSessionId());
    }

    private AiChatSession session(long generation) {
        var session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setOrganizationPrivacyGeneration(generation);
        session.setTitle("Schedule contention");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private long connectionId() {
        return java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
    }

    private void scoped(String name, Runnable action) {
        Thread.currentThread().setName(name);
        authenticateAs(currentUser, workspace.getId());
        try {
            action.run();
        } finally {
            clearAuthentication();
        }
    }

    private static boolean named(String name) {
        return Thread.currentThread().getName().equals(name);
    }

    private static void awaitWriteFence(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING
                    && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().contains("ReentrantReadWriteLock") && frame.getMethodName().equals("lock"))) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Restriction did not wait on the cache publication read fence");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @AfterEach
    void removeRemediationFixtures() {
        jdbc.update("DELETE FROM ai_brief_schedule WHERE workspace_id=?", workspace.getId());
        jdbc.update("DELETE FROM ai_output_cache WHERE workspace_id=?", workspace.getId());
        jdbc.update("DELETE FROM person WHERE workspace_id=?", workspace.getId());
    }
}
