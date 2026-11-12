package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.assistant.AiBriefScheduler;
import ooo.klae.connex.backend.ai.assistant.AiWatchScheduler;
import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.JobRunMapper;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationScheduler;
import ooo.klae.connex.backend.notifications.NotificationPushPublisher;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.ApprovalReconciliationScheduler;
import ooo.klae.connex.backend.services.LeadResponseSlaScheduler;
import ooo.klae.connex.backend.services.NotificationReconciliationService;
import ooo.klae.connex.backend.services.RelationshipSignalScheduler;
import ooo.klae.connex.backend.services.RelationshipSignalWriteService;
import ooo.klae.connex.backend.services.RelationshipSignalDetectorService;
import ooo.klae.connex.backend.services.RelationshipSignalDetectorService.Detection;
import ooo.klae.connex.backend.services.RelationshipSignalReconciliationService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Exercises unchanged domain reconciliation and idle bookkeeping against isolated committed workspaces. */
class IdleReconciliationIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAgentPrivacyInitializer initializer;
    @Autowired private NotificationReconciliationService notifications;
    @Autowired private RelationshipSignalWriteService signals;
    @Autowired private RelationshipSignalMapper signalMapper;
    @Autowired private JobRunMapper jobRunMapper;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper json;
    @Autowired private JobRunRecorder recorder;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private AiAgentRunMapper runs;
    @Autowired private AiAgentSegmentRunner runner;
    @Autowired private AiAgentRunPersistenceService persistence;
    @Autowired private AiAgentRealtimePublisher publisher;
    @Autowired private AiBriefScheduler briefs;
    @Autowired private AiWatchScheduler watches;
    @Autowired private LeadResponseSlaScheduler leadSla;
    @Autowired private ApprovalReconciliationScheduler approvals;
    @Autowired private RelationshipSignalReconciliationService signalReconciliation;
    @MockitoBean private NotificationPushPublisher notificationPushPublisher;

    @BeforeEach
    void finishBootstrapBeforeMeasuringIdleWork() {
        for (String operation : jdbc.queryForList(
                "SELECT operation_key FROM ai_agent_privacy_work WHERE workspace_id=? AND phase<>'complete'",
                String.class, workspace.getId())) {
            finish(operation);
        }
        assertEquals(0, privacyService.initialize(workspace.getId()));
    }

    @AfterEach
    void removeReconciliationFixtures() {
        if (workspace == null) return;
        int id = workspace.getId();
        jdbc.update("DELETE FROM notification WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM relationship_signal_state WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM relationship_signal WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM relationship_signal_family_state WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM job_run WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM task WHERE workspace_id=?", id);
    }

    @Test
    void allEightSchedulerPathsKeepTheirIdleHistoryConstantWithinTheSamplingWindow() {
        int id = workspace.getId();
        WorkspaceMapper enumeration = org.mockito.Mockito.mock(WorkspaceMapper.class);
        org.mockito.Mockito.when(enumeration.findWorkspaceIds()).thenReturn(List.of(id));
        org.mockito.Mockito.when(enumeration.findWorkspaceIdsPage(org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.eq(100))).thenReturn(List.of(id));
        AiAgentPrivacyInitializer ready = org.mockito.Mockito.mock(AiAgentPrivacyInitializer.class);
        org.mockito.Mockito.when(ready.isReady()).thenReturn(true);
        AiProperties enabled = new AiProperties();
        enabled.setMissionsEnabled(true);
        AiAgentScheduler missions = new AiAgentScheduler(enumeration, tenantWorkScope, runs, runner,
            ready, enabled, persistence, publisher, recorder);
        RelationshipSignalScheduler radar = new RelationshipSignalScheduler(
            enumeration, tenantWorkScope, signalReconciliation, recorder);
        NotificationScheduler reminders = new NotificationScheduler(
            enumeration, tenantWorkScope, notifications, recorder);
        Runnable tick = () -> {
            initializer.initialize(id);
            ReflectionTestUtils.invokeMethod(missions, "sweepPage");
            tenantWorkScope.inWorkspace(id, () -> {
                ReflectionTestUtils.invokeMethod(briefs, "sweepWorkspace", id);
                ReflectionTestUtils.invokeMethod(watches, "sweepWorkspace", id);
                ReflectionTestUtils.invokeMethod(leadSla, "sweepWorkspace", id);
                ReflectionTestUtils.invokeMethod(approvals, "reconcileWorkspace", id);
            });
            radar.reconcile();
            reminders.reconcileAndPurge();
        };
        try {
            tick.run();
            tick.run();
            List<Map<String, Object>> sampled = allJobSnapshots();
            assertEquals(8, sampled.stream().map(row -> row.get("job_name")).distinct().count());
            for (Map<String, Object> row : sampled) {
                assertEquals("succeeded", row.get("status"));
            }
            for (int pass = 0; pass < 4; pass++) {
                tick.run();
                assertEquals(sampled, allJobSnapshots());
            }
        } finally {
            missions.shutdown();
        }
    }

    private List<Map<String, Object>> allJobSnapshots() {
        return jdbc.queryForList("SELECT id,job_name,status,started_at,finished_at,detail FROM job_run WHERE workspace_id=? ORDER BY id",
            workspace.getId());
    }

    @Test
    void idlePrivacyTicksKeepOneSampleAndInitializingABarrierStillRecordsWork() {
        int id = workspace.getId();
        initializer.initialize(id);
        List<Integer> sample = jobIds(JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION);
        assertEquals(1, sample.size());
        for (int tick = 0; tick < 5; tick++) initializer.initialize(id);
        assertEquals(sample, jobIds(JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION));

        ooo.klae.connex.backend.support.TestEntityFixtures.deleteChatPrivacy(jdbc, id);
        initializer.initialize(id);
        assertEquals(2, jobIds(JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION).size());
        assertEquals(1, privacyMapper.getBarrier(id).getGeneration());
    }

    @Test
    void privacyFailureStillRecordsAFailedRun() {
        int id = workspace.getId();
        jdbc.update("INSERT INTO ai_chat_session (workspace_id,created_by_user_id,title,message_seq_high_water,mission_taint_generation,mission_taint_seq) VALUES (?,?,?,1,1,1)",
            id, currentUser.getId(), "Privacy refusal fixture");
        ooo.klae.connex.backend.support.TestEntityFixtures.deleteChatPrivacy(jdbc, id);

        assertThrows(ooo.klae.connex.backend.exceptions.ServiceUnavailableException.class, () -> initializer.initialize(id));

        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM job_run WHERE workspace_id=? AND job_name=? AND status='failed'",
            Integer.class, id, JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION));
        assertEquals("initialization", jdbc.queryForObject(
            "SELECT JSON_UNQUOTE(JSON_EXTRACT(detail,'$.phase')) FROM job_run WHERE workspace_id=? AND job_name=? AND status='failed'",
            String.class, id, JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION));
    }

    @Test
    void unchangedSignalPreservesRecencyVersionAndGenerationAndReportsZeroChanges() {
        int id = workspace.getId();
        LocalDateTime at = LocalDateTime.of(2026, 8, 8, 12, 0);
        RelationshipSignal first = signal("first", "a".repeat(64));
        assertEquals(1, signals.replaceFamily(id, first.getFamily(), "first", List.of(first), at, at));
        jdbc.update("UPDATE relationship_signal SET updated_at='2001-01-01 00:00:00' WHERE workspace_id=?", id);
        Map<String, Object> before = signalSnapshot(first.getDedupeKey());
        RelationshipSignal same = signal("next", first.getSourceStateHash());
        same.setEvidenceAsOf(at.plusMinutes(5));

        assertEquals(0, signals.replaceFamily(id, same.getFamily(), "next", List.of(same), at.plusMinutes(5), at.plusMinutes(5)));
        assertEquals(before, signalSnapshot(first.getDedupeKey()));
        assertEquals(1, signalMapper.findActiveForActor(id, currentUser.getId()).size());

        same.setSourceStateHash("b".repeat(64));
        same.setGenerationToken("changed");
        assertEquals(1, signals.replaceFamily(id, same.getFamily(), "changed", List.of(same), at.plusMinutes(10), at.plusMinutes(10)));
        Map<String, Object> changed = signalSnapshot(first.getDedupeKey());
        assertNotEquals(before.get("updated_at"), changed.get("updated_at"));
        assertEquals(((Number) before.get("version")).longValue() + 1, ((Number) changed.get("version")).longValue());
        assertEquals("changed", changed.get("generation_token"));
        assertEquals(0, signals.replaceFamily(id, same.getFamily(), "stale", List.of(first), at.plusMinutes(1), at));
        assertEquals(changed, signalSnapshot(first.getDedupeKey()));
        assertEquals(1, signals.replaceFamily(id, same.getFamily(), "absent", List.of(), at.plusMinutes(15), at));
        assertTrue(signalMapper.findActiveForActor(id, currentUser.getId()).isEmpty());
    }

    @Test
    void populatedReminderSchedulerDoesNotInvalidateUnchangedRecipients() {
        int id = workspace.getId();
        var company = newCompany();
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 1);
        var deal = newDeal(pipeline, stage, company);
        jdbc.update("UPDATE deal SET expected_close_date=?,created_at='2001-01-01 00:00:00' WHERE id=?",
            LocalDate.now(clock).minusDays(1).toString(), deal.getId());
        var firstTask = newTask(currentUser, null, deal);
        var secondTask = newTask(currentUser, null, deal);
        jdbc.update("UPDATE task SET due_date=? WHERE workspace_id=?",
            LocalDate.now(clock).toString(), id);
        WorkspaceMapper enumeration = mock(WorkspaceMapper.class);
        when(enumeration.findWorkspaceIds()).thenReturn(List.of(id));
        NotificationScheduler scheduler = new NotificationScheduler(
            enumeration, tenantWorkScope, notifications, recorder);
        long initialVersion = notificationMapper.getStateVersion(currentUser.getId());
        clearInvocations(notificationPushPublisher);
        try {
            scheduler.reconcileAndPurge();
            List<Map<String, Object>> seeded = reminderSnapshots();
            assertEquals(4, seeded.size());
            assertEquals(3, seeded.stream().map(row -> row.get("type")).distinct().count());
            assertEquals(initialVersion + 1, notificationMapper.getStateVersion(currentUser.getId()));
            verify(notificationPushPublisher, times(4)).created(eq(currentUser.getId()), any(), any());
            verifyNoMoreInteractions(notificationPushPublisher);
            for (var notification : notificationMapper.findWorkspaceReminderNotifications(id)) {
                notification.setActorId(0);
                assertTrue(notificationMapper.upsert(notification) <= 1);
            }
            jdbc.update("UPDATE notification SET updated_at='2001-01-01 00:00:00' WHERE workspace_id=?", id);
            List<Map<String, Object>> before = reminderSnapshots();
            Map<String, Object> version = recipientVersion();
            clearInvocations(notificationPushPublisher);

            scheduler.reconcileAndPurge();
            scheduler.reconcileAndPurge();

            assertEquals(before, reminderSnapshots());
            assertEquals(version, recipientVersion());
            verifyNoInteractions(notificationPushPublisher);

            firstTask.setDescription("Changed task reminder");
            firstTask.setDueDate(LocalDate.now(clock).toString());
            taskMapper.update(firstTask);
            scheduler.reconcileAndPurge();
            assertEquals(initialVersion + 2, notificationMapper.getStateVersion(currentUser.getId()));
            verify(notificationPushPublisher).updated(eq(currentUser.getId()), any(), eq("task.due:" + firstTask.getId()));
            verifyNoMoreInteractions(notificationPushPublisher);
            clearInvocations(notificationPushPublisher);
            scheduler.reconcileAndPurge();
            assertEquals(initialVersion + 2, notificationMapper.getStateVersion(currentUser.getId()));
            verifyNoInteractions(notificationPushPublisher);

            jdbc.update("UPDATE task SET completed=TRUE,status='done' WHERE workspace_id=?", id);
            scheduler.reconcileAndPurge();
            assertEquals(initialVersion + 3, notificationMapper.getStateVersion(currentUser.getId()));
            verify(notificationPushPublisher).invalidated(currentUser.getId());
            verifyNoMoreInteractions(notificationPushPublisher);
            assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE workspace_id=? AND type='task.due' AND resolved_at IS NOT NULL",
                Integer.class, id));
            clearInvocations(notificationPushPublisher);
            scheduler.reconcileAndPurge();
            assertEquals(initialVersion + 3, notificationMapper.getStateVersion(currentUser.getId()));
            verifyNoInteractions(notificationPushPublisher);
        } finally {
            jdbc.update("DELETE FROM notification WHERE workspace_id=?", id);
            jdbc.update("DELETE FROM task WHERE id IN (?,?)", firstTask.getId(), secondTask.getId());
            jdbc.update("DELETE FROM deal WHERE id=?", deal.getId());
            jdbc.update("DELETE FROM stage WHERE pipeline_id=?", pipeline.getId());
            jdbc.update("DELETE FROM pipeline WHERE id=?", pipeline.getId());
            jdbc.update("DELETE FROM company WHERE id=?", company.getId());
        }
    }

    private List<Map<String, Object>> reminderSnapshots() {
        return jdbc.queryForList("SELECT * FROM notification WHERE workspace_id=? ORDER BY id", workspace.getId());
    }

    @Test
    void repeatedTaskReconciliationReportsZeroChangesAndKeepsNotificationAndJobState() {
        int id = workspace.getId();
        var task = newTask(currentUser, null, null);
        task.setDueDate(LocalDate.now(clock).toString());
        taskMapper.update(task);
        assertTrue(notifications.reconcileWorkspace(id, false) > 0);
        String dedupe = "task.due:" + task.getId();
        assertEquals(1, notificationMapper.claimEmailDelivery(id, currentUser.getId(), dedupe));
        jdbc.update("UPDATE notification SET updated_at='2001-01-01 00:00:00',read_at='2001-01-01 00:00:00' WHERE workspace_id=?", id);
        Map<String, Object> before = notificationSnapshot(dedupe);
        Map<String, Object> version = recipientVersion();
        JobRunRecorder recorder = new JobRunRecorder(jobRunMapper, json, clock, transactionManager);
        int changed = notifications.reconcileWorkspace(id, false);
        assertEquals(0, changed);
        recordNotificationTick(recorder, changed);
        List<Integer> sample = jobIds(JobRunRecorder.NOTIFICATION_RECONCILIATION);
        assertEquals(1, sample.size());
        for (int tick = 0; tick < 5; tick++) {
            changed = notifications.reconcileWorkspace(id, false);
            assertEquals(0, changed);
            recordNotificationTick(recorder, changed);
        }
        assertEquals(before, notificationSnapshot(dedupe));
        assertEquals(version, recipientVersion());
        assertEquals(sample, jobIds(JobRunRecorder.NOTIFICATION_RECONCILIATION));
        assertEquals(0, notificationMapper.claimEmailDelivery(id, currentUser.getId(), dedupe));

        task.setDescription("Changed task evidence");
        taskMapper.update(task);
        changed = notifications.reconcileWorkspace(id, false);
        assertTrue(changed > 0);
        recordNotificationTick(recorder, changed);
        assertEquals(2, jobIds(JobRunRecorder.NOTIFICATION_RECONCILIATION).size());
        assertNotEquals(before.get("updated_at"), notificationSnapshot(dedupe).get("updated_at"));
        assertEquals(before.get("email_delivery_claimed_at"), notificationSnapshot(dedupe).get("email_delivery_claimed_at"));
        assertEquals(before.get("read_at"), notificationSnapshot(dedupe).get("read_at"));
    }

    @Test
    void unchangedOverflowStaysInactiveAndAChangedRankCanEnterWithoutLosingFailedFamilies() {
        int id = workspace.getId();
        List<RelationshipSignal> decay = candidates("relationship_decay", 30, 2);
        List<RelationshipSignal> risk = candidates("deal_risk", 20, 0);
        List<RelationshipSignal> warm = candidates("warm_path", 10, 3);
        var detector = org.mockito.Mockito.mock(RelationshipSignalDetectorService.class);
        Instant at = Instant.parse("2026-08-08T12:00:00Z");
        Clock ticks = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(ticks.instant()).thenReturn(at, at.plusSeconds(300), at.plusSeconds(600),
            at.plusSeconds(900), at.plusSeconds(1200));
        org.mockito.Mockito.when(detector.detectDecay(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(call -> detection(decay, call.getArgument(1), at));
        org.mockito.Mockito.when(detector.detectDealRisk(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(call -> detection(risk, call.getArgument(1), at));
        org.mockito.Mockito.when(detector.detectWarmPaths(org.mockito.ArgumentMatchers.eq(id),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Instant.class)))
            .thenAnswer(call -> detection(warm, call.getArgument(1), at));
        var reconciliation = new RelationshipSignalReconciliationService(detector, signals, ticks,
            ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered());

        var initial = reconciliation.reconcileWorkspace(id);
        assertEquals(0, initial.failedCount());
        assertEquals(50, initial.changedCount());
        List<Map<String, Object>> before = allSignalSnapshots();
        assertEquals(0, reconciliation.reconcileWorkspace(id).changedCount());
        assertEquals(before, allSignalSnapshots());
        assertEquals(50, signalMapper.findActiveForActor(id, currentUser.getId()).size());

        RelationshipSignal promoted = warm.getFirst();
        promoted.setRankValue(100);
        decay.removeFirst();
        var changed = reconciliation.reconcileWorkspace(id);
        assertEquals(0, changed.failedCount());
        assertEquals(2, changed.changedCount());
        assertTrue(signalMapper.findActiveForActor(id, currentUser.getId()).stream()
            .anyMatch(value -> promoted.getDedupeKey().equals(value.getDedupeKey())));
        before = allSignalSnapshots();
        assertEquals(0, reconciliation.reconcileWorkspace(id).changedCount());
        assertEquals(before, allSignalSnapshots());

        org.mockito.Mockito.when(detector.detectWarmPaths(org.mockito.ArgumentMatchers.eq(id),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Instant.class)))
            .thenThrow(new IllegalStateException("detector unavailable"));
        var partial = reconciliation.reconcileWorkspace(id);
        assertEquals(1, partial.failedCount());
        assertEquals(0, partial.changedCount());
        assertEquals(before, allSignalSnapshots());
    }

    private List<Map<String, Object>> allSignalSnapshots() {
        return jdbc.queryForList("SELECT dedupe_key,updated_at,version,generation_token,resolved_at FROM relationship_signal WHERE workspace_id=? ORDER BY dedupe_key", workspace.getId());
    }

    private List<RelationshipSignal> candidates(String family, int count, int priority) {
        List<RelationshipSignal> candidates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            RelationshipSignal candidate = signal("initial", "a".repeat(64));
            candidate.setFamily(family);
            candidate.setPriority("deal_risk".equals(family) ? "high" : "warm_path".equals(family) ? "opportunity" : "cooling");
            candidate.setSubjectType("deal_risk".equals(family) ? "deal" : "person");
            candidate.setSubjectId(index + 1);
            candidate.setDedupeKey(family + ":" + candidate.getSubjectType() + ":" + candidate.getSubjectId());
            candidate.setPriorityRank(priority);
            candidate.setRankValue(index);
            candidates.add(candidate);
        }
        return candidates;
    }

    private Detection detection(List<RelationshipSignal> candidates, String generation, Instant at) {
        candidates.forEach(candidate -> candidate.setGenerationToken(generation));
        return new Detection(candidates, at, generation);
    }

    private void recordNotificationTick(JobRunRecorder recorder, int changed) {
        recorder.record(JobRunRecorder.NOTIFICATION_RECONCILIATION, workspace.getId(), JobRunStatus.SUCCEEDED,
            JobRunDetail.started(clock, Map.of("purgedCount", 0, "changedCount", changed)));
    }

    private List<Integer> jobIds(String name) {
        return jdbc.queryForList("SELECT id FROM job_run WHERE workspace_id=? AND job_name=? ORDER BY id",
            Integer.class, workspace.getId(), name);
    }

    private Map<String, Object> signalSnapshot(String key) {
        return jdbc.queryForMap("SELECT updated_at,version,generation_token,evidence_as_of,resolved_at FROM relationship_signal WHERE workspace_id=? AND dedupe_key=?",
            workspace.getId(), key);
    }

    private Map<String, Object> notificationSnapshot(String key) {
        return jdbc.queryForMap("SELECT updated_at,triggered_at,read_at,email_delivery_claimed_at FROM notification WHERE workspace_id=? AND recipient_id=? AND dedupe_key=?",
            workspace.getId(), currentUser.getId(), key);
    }

    private Map<String, Object> recipientVersion() {
        return jdbc.queryForMap("SELECT state_version,updated_at FROM notification_recipient_state WHERE recipient_id=?", currentUser.getId());
    }

    private RelationshipSignal signal(String generation, String hash) {
        RelationshipSignal signal = new RelationshipSignal();
        signal.setWorkspaceId(workspace.getId());
        signal.setFamily("relationship_decay");
        signal.setSubjectType("person");
        signal.setSubjectId(41);
        signal.setSubjectLabel("Signal fixture");
        signal.setPriority("cooling");
        signal.setPriorityRank(2);
        signal.setRankValue(90);
        signal.setDedupeKey("relationship_decay:person:41");
        signal.setEvidenceJson("[]");
        signal.setRankExplanationJson("{}");
        signal.setEvidenceAsOf(LocalDateTime.of(2026, 8, 8, 12, 0));
        signal.setSourceStateHash(hash);
        signal.setGenerationToken(generation);
        return signal;
    }
}
