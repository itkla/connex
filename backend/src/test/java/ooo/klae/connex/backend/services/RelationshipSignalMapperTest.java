package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;
import ooo.klae.connex.backend.services.RelationshipSignalDetectorService.Detection;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.Cascade;

class RelationshipSignalMapperTest extends AbstractServiceTest {
    @Autowired private RelationshipSignalMapper signalMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void threeIdenticalGenerationCyclesCreateNoDuplicateDomainRowsOrNotifications() {
        int notificationsBefore = count("notification");
        int activitiesBefore = count("activity");
        int notesBefore = count("note");
        int tasksBefore = count("task");
        RelationshipSignalDetectorService detector = mock(RelationshipSignalDetectorService.class);
        RelationshipSignalWriteService writer = new RelationshipSignalWriteService(signalMapper);
        Instant asOfInstant = Instant.parse("2026-08-08T12:00:00Z");
        when(detector.detectDecay(eq(workspace.getId()), anyString()))
            .thenAnswer(invocation -> detection(
                RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
                "person",
                41,
                invocation.getArgument(1),
                asOfInstant));
        when(detector.detectDealRisk(eq(workspace.getId()), anyString()))
            .thenAnswer(invocation -> detection(
                RelationshipSignalDetectorService.DEAL_RISK,
                "deal",
                42,
                invocation.getArgument(1),
                asOfInstant));
        when(detector.detectWarmPaths(eq(workspace.getId()), anyString(), eq(asOfInstant)))
            .thenAnswer(invocation -> detection(
                RelationshipSignalDetectorService.WARM_PATH,
                "person",
                43,
                invocation.getArgument(1),
                asOfInstant));
        RelationshipSignalReconciliationService reconciliation =
            new RelationshipSignalReconciliationService(
                detector, writer, Clock.fixed(asOfInstant, ZoneOffset.UTC));
        for (int cycle = 0; cycle < 3; cycle++) {
            reconciliation.reconcileWorkspace(workspace.getId());
        }

        assertEquals(3, count("relationship_signal"));
        assertEquals(3, count("relationship_signal_family_state"));
        assertEquals(0, count("relationship_signal_state"));
        assertEquals(notificationsBefore, count("notification"));
        assertEquals(activitiesBefore, count("activity"));
        assertEquals(notesBefore, count("note"));
        assertEquals(tasksBefore, count("task"));
        assertEquals(3, signalMapper.findActiveForActor(workspace.getId(), currentUser.getId()).size());
    }

    @Test
    void sourceReconciliationResolvesMissingRowsAndWrongWorkspaceReadsRefuseThem() {
        RelationshipSignal signal = signal(
            RelationshipSignalDetectorService.DEAL_RISK,
            "deal",
            52,
            "deal_risk:deal:52",
            "present");
        signalMapper.upsertSignal(signal);

        assertNull(signalMapper.getActiveForActor(
            workspace.getId() + 99, signal.getId(), currentUser.getId()));
        assertEquals(1, signalMapper.resolveMissing(
            workspace.getId(),
            RelationshipSignalDetectorService.DEAL_RISK,
            "absent",
            LocalDateTime.of(2026, 8, 8, 13, 0)));
        assertTrue(signalMapper.findActiveForActor(
            workspace.getId(), currentUser.getId()).isEmpty());
    }

    @Test
    void olderReconciliationCannotReplaceOrHideNewerFamilyState() {
        RelationshipSignalWriteService writer = new RelationshipSignalWriteService(signalMapper);
        LocalDateTime newerAttempt = LocalDateTime.of(2026, 8, 8, 13, 0);
        LocalDateTime olderAttempt = newerAttempt.minusMinutes(5);
        RelationshipSignal newer = signal(
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            "person",
            53,
            "relationship_decay:person:53",
            "newer");
        newer.setSourceStateHash("n".repeat(64));
        writer.replaceFamily(
            workspace.getId(),
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            "newer",
            List.of(newer),
            newerAttempt,
            newerAttempt);
        RelationshipSignal older = signal(
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            "person",
            53,
            "relationship_decay:person:53",
            "older");
        older.setSourceStateHash("o".repeat(64));

        writer.replaceFamily(
            workspace.getId(),
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            "older",
            List.of(older),
            olderAttempt,
            olderAttempt);
        writer.markUnavailable(
            workspace.getId(),
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            olderAttempt,
            "detector_failed");
        writer.markUnavailable(
            workspace.getId(),
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            newerAttempt,
            "equal_time_failure");

        RelationshipSignal persisted = signalMapper.findActiveForActor(
            workspace.getId(), currentUser.getId()).getFirst();
        var familyState = signalMapper.findFamilyStates(workspace.getId()).getFirst();
        assertEquals("n".repeat(64), persisted.getSourceStateHash());
        assertEquals("newer", persisted.getGenerationToken());
        assertEquals("available", familyState.getStatus());
        assertEquals(newerAttempt, familyState.getLastAttemptAt());
        assertEquals(newerAttempt, familyState.getLastSuccessAt());
        assertEquals(newerAttempt, familyState.getEvidenceAsOf());
        assertNull(familyState.getErrorCode());
    }

    @Test
    void allTablesAreClassifiedForPlaneAndLifecycleWithActorStateCascading() {
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.containsAll(List.of(
            "relationship_signal",
            "relationship_signal_state",
            "relationship_signal_family_state")));
        assertTrue(TenantLifecycleRegistry.require("relationship_signal").direct());
        assertTrue(TenantLifecycleRegistry.require("relationship_signal_family_state").direct());
        Cascade state = (Cascade) TenantLifecycleRegistry
            .require("relationship_signal_state").reach();
        assertEquals("relationship_signal", state.parentTable());
        assertEquals("fk_relationship_signal_state_signal", state.constraintName());
        assertEquals(2, state.columns().size());
    }

    @Test
    void actorTaskBindingIsPrivateAndClearsWhenTheTaskIsDeleted() {
        RelationshipSignal signal = signal(
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            "person",
            61,
            "relationship_decay:person:61",
            "present");
        signalMapper.upsertSignal(signal);
        User otherActor = newUser();
        signalMapper.insertState(
            workspace.getId(), signal.getId(), currentUser.getId(), "followed", null, null);
        Task firstTask = newTask(currentUser, null, null);
        assertEquals(1, signalMapper.attachTask(
            workspace.getId(),
            signal.getId(),
            currentUser.getId(),
            firstTask.getId(),
            signal.getSourceStateHash(),
            1));

        assertEquals(firstTask.getId(), signalMapper.getActiveForActor(
            workspace.getId(), signal.getId(), currentUser.getId()).getTaskId());
        assertNull(signalMapper.getActiveForActor(
            workspace.getId(), signal.getId(), otherActor.getId()).getTaskId());

        taskMapper.delete(workspace.getId(), firstTask.getId());
        RelationshipSignal afterDeletion = signalMapper.getActiveForActor(
            workspace.getId(), signal.getId(), currentUser.getId());
        assertNull(afterDeletion.getTaskId());
        Task replacement = newTask(currentUser, null, null);
        assertEquals(1, signalMapper.attachTask(
            workspace.getId(),
            signal.getId(),
            currentUser.getId(),
            replacement.getId(),
            signal.getSourceStateHash(),
            afterDeletion.getStateVersion()));
        assertEquals(replacement.getId(), signalMapper.getActiveForActor(
            workspace.getId(), signal.getId(), currentUser.getId()).getTaskId());
    }

    @Test
    void clockOnlyDecayKeepsFingerprintAttachedTaskAndDismissal() {
        Person subject = newPerson(null);
        ScoringService scoringService = mock(ScoringService.class);
        PersonMapper detectorPersonMapper = mock(PersonMapper.class);
        String persistedSourceHash = "c".repeat(64);
        Instant firstAsOf = Instant.parse("2026-08-08T12:00:00Z");
        Instant laterAsOf = firstAsOf.plusSeconds(86_400);
        RelationshipTemperatureDto before = temperature(
            subject.getId(), 60, 7, 9, "warm", "2026-08-20", firstAsOf);
        RelationshipTemperatureDto later = temperature(
            subject.getId(), 47, 8, 8, "cool", "2026-08-19", laterAsOf);
        when(scoringService.scoreWorkspace(workspace.getId())).thenReturn(
            new ScoringService.WorkspaceScores(List.of(before), List.of()),
            new ScoringService.WorkspaceScores(List.of(later), List.of()));
        when(scoringService.contactSourceStateHashes(
                workspace.getId(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of()))
            .thenReturn(Map.of(subject.getId(), persistedSourceHash));
        when(scoringService.companySourceStateHashes(workspace.getId())).thenReturn(Map.of());
        when(detectorPersonMapper.getByIds(workspace.getId(), List.of(subject.getId())))
            .thenReturn(List.of(subject));
        RelationshipSignalDetectorService detector = new RelationshipSignalDetectorService(
            scoringService,
            mock(DealRiskService.class),
            mock(WarmPathService.class),
            detectorPersonMapper,
            mock(CompanyMapper.class),
            mock(DealMapper.class),
            new ObjectMapper(),
            Clock.fixed(firstAsOf, ZoneOffset.UTC));
        RelationshipSignalWriteService writer = new RelationshipSignalWriteService(signalMapper);

        Detection first = detector.detectDecay(workspace.getId(), "first");
        RelationshipSignal firstSignal = first.candidates().getFirst();
        writer.replaceFamily(
            workspace.getId(),
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            first.generationToken(),
            first.candidates(),
            LocalDateTime.ofInstant(firstAsOf, ZoneOffset.UTC),
            LocalDateTime.ofInstant(first.evidenceAsOf(), ZoneOffset.UTC));
        signalMapper.insertState(
            workspace.getId(),
            firstSignal.getId(),
            currentUser.getId(),
            "dismissed",
            null,
            persistedSourceHash);
        Task attachedTask = newTask(currentUser, null, null);
        assertEquals(1, signalMapper.attachTask(
            workspace.getId(),
            firstSignal.getId(),
            currentUser.getId(),
            attachedTask.getId(),
            persistedSourceHash,
            1));

        Detection laterDetection = detector.detectDecay(workspace.getId(), "later");
        RelationshipSignal laterSignal = laterDetection.candidates().getFirst();
        writer.replaceFamily(
            workspace.getId(),
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            laterDetection.generationToken(),
            laterDetection.candidates(),
            LocalDateTime.ofInstant(laterAsOf, ZoneOffset.UTC),
            LocalDateTime.ofInstant(laterDetection.evidenceAsOf(), ZoneOffset.UTC));

        RelationshipSignal persisted = signalMapper.getActiveForActor(
            workspace.getId(), firstSignal.getId(), currentUser.getId());
        assertEquals(firstSignal.getSourceStateHash(), laterSignal.getSourceStateHash());
        assertNotEquals(firstSignal.getEvidenceJson(), laterSignal.getEvidenceJson());
        assertEquals(attachedTask.getId(), persisted.getTaskId());
        assertEquals("dismissed", persisted.getDisposition());
        assertEquals(persisted.getSourceStateHash(), persisted.getDismissedSourceHash());
        assertEquals(persisted.getSourceStateHash(), persisted.getTaskSourceHash());
    }

    private Detection detection(
            String family,
            String subjectType,
            int subjectId,
            String generationToken,
            Instant evidenceAsOf) {
        RelationshipSignal signal = signal(
            family,
            subjectType,
            subjectId,
            family + ":" + subjectType + ":" + subjectId,
            generationToken);
        return new Detection(List.of(signal), evidenceAsOf, generationToken);
    }

    private RelationshipSignal signal(
            String family,
            String subjectType,
            int subjectId,
            String dedupeKey,
            String generationToken) {
        RelationshipSignal signal = new RelationshipSignal();
        signal.setWorkspaceId(workspace.getId());
        signal.setFamily(family);
        signal.setSubjectType(subjectType);
        signal.setSubjectId(subjectId);
        signal.setSubjectLabel("Subject " + subjectId);
        signal.setPriority("high");
        signal.setPriorityRank(0);
        signal.setRankValue(90);
        signal.setDedupeKey(dedupeKey);
        signal.setEvidenceJson("[]");
        signal.setRankExplanationJson("{\"rule\":\"priority_then_source_strength_then_subject\",\"factors\":[]}");
        signal.setEvidenceAsOf(LocalDateTime.of(2026, 8, 8, 12, 0));
        signal.setSourceStateHash("a".repeat(64));
        signal.setGenerationToken(generationToken);
        return signal;
    }

    private static RelationshipTemperatureDto temperature(
            int id,
            int score,
            int daysSinceTouch,
            int daysUntilCold,
            String band,
            String goesColdAt,
            Instant asOf) {
        return new RelationshipTemperatureDto(
            id,
            score,
            band,
            "cooling",
            "2026-08-01 12:00:00",
            daysSinceTouch,
            3,
            goesColdAt,
            daysUntilCold,
            "warmth-v1",
            asOf);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());
    }
}
