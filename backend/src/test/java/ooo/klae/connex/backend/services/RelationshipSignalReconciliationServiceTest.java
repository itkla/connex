package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.services.RelationshipSignalDetectorService.Detection;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;

/** Pins independent family outcomes when control reads fail and invariant refusals remain fatal. */
class RelationshipSignalReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void ordinaryScopeFailureMarksDependentFamiliesUnavailableAndStillRunsWarmPaths() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var failure = new DataAccessResourceFailureException("control read unavailable");
            when(fixture.control.findOrganizationWorkspacesForShare(7)).thenThrow(failure);
            var detectors = mock(RelationshipSignalDetectorService.class);
            var writes = mock(RelationshipSignalWriteService.class);
            when(detectors.detectDecay(eq(7), anyString())).thenAnswer(call -> {
                fixture.scopes.getForWorkspace(7);
                throw new AssertionError("scope failure must prevent detection");
            });
            when(detectors.detectDealRisk(eq(7), anyString())).thenAnswer(call -> {
                fixture.scopes.getForWorkspace(7);
                throw new AssertionError("scope failure must prevent detection");
            });
            when(detectors.detectWarmPaths(eq(7), anyString(), eq(NOW)))
                .thenReturn(new Detection(List.of(), NOW, "warm"));
            var service = service(detectors, writes, fixture);

            assertEquals(new RelationshipSignalReconciliationService.Result(Set.of("warm_path"), 2),
                service.reconcileWorkspace(7));

            verify(writes).markUnavailable(eq(7), eq("relationship_decay"), any(), eq("detector_failed"));
            verify(writes).markUnavailable(eq(7), eq("deal_risk"), any(), eq("detector_failed"));
            verify(detectors).detectDealRisk(eq(7), anyString());
            verify(detectors).detectWarmPaths(eq(7), anyString(), eq(NOW));
            verify(writes).replaceFamily(eq(7), eq("warm_path"), eq("warm"), eq(List.of()), any(), any());
            verify(writes).enforceWorkspaceCap(eq(7), any());
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotInvariantRefusalsEscapeBeforeAnyFamilyStateIsChanged(boolean missing) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            TenantScopeSnapshotException failure = missing
                ? new TenantScopeSnapshotMissingException("workspace 7")
                : new TenantScopeSnapshotException("snapshot identity mismatch");
            when(fixture.control.findOrganizationWorkspacesForShare(7)).thenThrow(failure);
            var detectors = mock(RelationshipSignalDetectorService.class);
            var writes = mock(RelationshipSignalWriteService.class);
            when(detectors.detectDecay(eq(7), anyString())).thenAnswer(call -> {
                fixture.scopes.getForWorkspace(7);
                throw new AssertionError("snapshot refusal must escape");
            });

            assertSame(failure, assertThrows(TenantScopeSnapshotException.class,
                () -> service(detectors, writes, fixture).reconcileWorkspace(7)));

            verify(detectors, never()).detectDealRisk(eq(7), anyString());
            verify(detectors, never()).detectWarmPaths(eq(7), anyString(), any());
            verifyNoInteractions(writes);
        }
    }

    @Test
    void ordinaryDetectorIllegalStateFailureStillUsesItsExistingFamilyHandler() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var detectors = mock(RelationshipSignalDetectorService.class);
            var writes = mock(RelationshipSignalWriteService.class);
            when(detectors.detectDecay(eq(7), anyString())).thenThrow(new IllegalStateException("detector unavailable"));
            when(detectors.detectDealRisk(eq(7), anyString())).thenReturn(new Detection(List.of(), NOW, "risk"));
            when(detectors.detectWarmPaths(eq(7), anyString(), eq(NOW)))
                .thenReturn(new Detection(List.of(), NOW, "warm"));

            assertEquals(new RelationshipSignalReconciliationService.Result(Set.of("deal_risk", "warm_path"), 1),
                service(detectors, writes, fixture).reconcileWorkspace(7));
            verify(writes).markUnavailable(eq(7), eq("relationship_decay"), any(), eq("detector_failed"));
            verifyNoInteractions(fixture.control);
        }
    }

    private static RelationshipSignalReconciliationService service(
            RelationshipSignalDetectorService detectors, RelationshipSignalWriteService writes,
            RecordVisibilityOperationFixture fixture) {
        return new RelationshipSignalReconciliationService(detectors, writes, Clock.fixed(NOW, ZoneOffset.UTC), fixture.scopes);
    }
    @Test
    void theSchedulerRethrowsSnapshotRefusalsInsteadOfRecordingAnOrdinaryWorkspaceFailure() {
        var control = mock(ooo.klae.connex.backend.mappers.WorkspaceMapper.class);
        when(control.findWorkspaceIds()).thenReturn(List.of(7));
        var routing = mock(ooo.klae.connex.backend.tenant.TenantWorkScope.class);
        when(routing.unrouted(org.mockito.ArgumentMatchers.<java.util.function.Supplier<List<Integer>>>any()))
            .thenAnswer(call -> call.<java.util.function.Supplier<List<Integer>>>getArgument(0).get());
        org.mockito.Mockito.doAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(routing).inWorkspace(eq(7), any(Runnable.class));
        var reconciliation = mock(RelationshipSignalReconciliationService.class);
        var refusal = new TenantScopeSnapshotMissingException("workspace 7");
        when(reconciliation.reconcileWorkspace(7)).thenThrow(refusal);
        var jobs = mock(ooo.klae.connex.backend.observability.JobRunRecorder.class);
        var scheduler = new RelationshipSignalScheduler(control, routing, reconciliation, jobs);
        assertSame(refusal, assertThrows(TenantScopeSnapshotException.class, scheduler::reconcile));
        verifyNoInteractions(jobs);
    }

}
