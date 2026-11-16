package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Preserves ordinary compatibility isolation while snapshot refusals escape dispatch roots. */
class LegacyRuleScopePropagationTest {
    @ParameterizedTest
    @ValueSource(strings = {"ordinary", "snapshot", "missing"})
    void listenerPropagatesSnapshotRefusalsAndRetainsOrdinaryFailureIsolation(String kind) {
        var work = mock(TenantWorkScope.class);
        var engine = mock(RuleEngineService.class);
        var dispatch = new RuleTriggerEvent(7, "deal", 41, "deal.updated", "event-7", Instant.EPOCH);
        RuntimeException failure = failure(kind);
        doAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(work).inWorkspace(eq(7), any(Runnable.class));
        doThrow(failure).when(engine).onEntityChange(any(WorkflowTriggerDispatch.EntityChange.class));
        var listener = new RuleTriggerListener(mock(WorkflowRuntimeProperties.class), engine, work);

        if (kind.equals("ordinary")) {
            assertDoesNotThrow(() -> listener.onTrigger(dispatch));
        } else {
            assertSame(failure, assertThrows(TenantScopeSnapshotException.class, () -> listener.onTrigger(dispatch)));
        }
        verify(engine).onEntityChange(any(WorkflowTriggerDispatch.EntityChange.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordinary", "snapshot", "missing"})
    void schedulerPropagatesSnapshotRefusalsAndRetainsOrdinaryCadenceIsolation(String kind) {
        var rules = mock(RuleMapper.class);
        var workflows = mock(WorkflowMapper.class);
        var placements = mock(PlacementRegistry.class);
        var work = mock(TenantWorkScope.class);
        var intake = mock(WorkflowTriggerIntake.class);
        var engine = mock(RuleEngineService.class);
        RuntimeException failure = failure(kind);
        when(placements.activeCatalogs()).thenReturn(List.of("tenant-catalog"));
        when(work.withCatalog(anyString(), any(Supplier.class)))
                .thenAnswer(call -> call.getArgument(1, Supplier.class).get());
        when(rules.workspaceIdsWithEnabledScheduleRules()).thenReturn(List.of(7));
        doAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(work).inWorkspace(eq(7), any(Runnable.class));
        doThrow(failure).when(engine).runSchedule(any(WorkflowTriggerDispatch.ScheduleTick.class));
        var scheduler = new RuleScheduler(rules, workflows, placements, work, intake,
                mock(WorkflowRuntimeProperties.class), engine, mock(JobRunRecorder.class));
        ReflectionTestUtils.setField(scheduler, "schedulingEnabled", true);

        if (kind.equals("ordinary")) {
            assertDoesNotThrow(scheduler::evaluate);
        } else {
            assertSame(failure, assertThrows(TenantScopeSnapshotException.class, scheduler::evaluate));
        }
        verify(engine, times(kind.equals("ordinary") ? 3 : 1))
                .runSchedule(any(WorkflowTriggerDispatch.ScheduleTick.class));
    }

    private static RuntimeException failure(String kind) {
        return switch (kind) {
            case "snapshot" -> new TenantScopeSnapshotException("scope mismatch");
            case "missing" -> new TenantScopeSnapshotMissingException("missing payload");
            default -> new DataAccessResourceFailureException("control unavailable");
        };
    }
}
