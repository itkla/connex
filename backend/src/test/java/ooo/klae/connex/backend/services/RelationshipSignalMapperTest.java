package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
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

    private int count(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());
    }
}
