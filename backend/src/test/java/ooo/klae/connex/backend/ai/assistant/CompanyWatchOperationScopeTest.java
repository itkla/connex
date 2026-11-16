package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.AiWatch;
import ooo.klae.connex.backend.mappers.AiWatchMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** A workspace watch sweep owns its Company visibility ceiling across independent evaluations. */
class CompanyWatchOperationScopeTest {
    @Test
    void oneHundredWatchTransactionsUseOneControlRead() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            AiWatchMapper watches = mock(AiWatchMapper.class);
            AiWatchEvaluationService evaluations = mock(AiWatchEvaluationService.class);
            List<AiWatch> rows = IntStream.rangeClosed(1, 100).mapToObj(id -> {
                AiWatch watch = new AiWatch();
                watch.setId(id);
                watch.setWorkspaceId(7);
                watch.setSubjectKind("company");
                watch.setSubjectId(id);
                return watch;
            }).toList();
            when(watches.findEvaluable(eq(7), any())).thenReturn(rows);
            when(evaluations.evaluate(any())).thenAnswer(call ->
                new TransactionTemplate(fixture.transactions).execute(status -> {
                    assertEquals(List.of(7, 17), fixture.scopes.getForWorkspace(7).workspaceIds());
                    return AiWatchEvaluationService.Outcome.QUIET;
                }));
            AiWatchScheduler scheduler = new AiWatchScheduler(watches, evaluations, mock(WorkspaceMapper.class),
                mock(TenantWorkScope.class), mock(JobRunRecorder.class), Clock.systemUTC(), fixture.scopes);
            scheduler.sweepWorkspace(7);
            assertEquals(100, fixture.transactions.beginCount());
            verify(evaluations, times(100)).evaluate(any());
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
        }
    }
    @Test
    void anEmptySweepRecordsItsResultWithoutReadingControlScopes() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var evaluations = mock(AiWatchEvaluationService.class);
            var jobs = mock(JobRunRecorder.class);
            var scheduler = new AiWatchScheduler(mock(AiWatchMapper.class), evaluations, mock(WorkspaceMapper.class),
                mock(TenantWorkScope.class), jobs, Clock.systemUTC(), fixture.scopes);
            scheduler.sweepWorkspace(7);
            org.mockito.Mockito.verifyNoInteractions(fixture.control, evaluations);
            verify(jobs).record(eq(JobRunRecorder.AI_WATCH_EVALUATION), eq(7),
                eq(ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus.SUCCEEDED), any());
        }
    }

    @Test
    void anOrdinaryScopeFailureStaysInsideEachWatchHandlerAndLaterIndependentWatchesRun() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            when(fixture.control.findOrganizationWorkspacesForShare(7))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("control unavailable"));
            var watches = mock(AiWatchMapper.class);
            var evaluations = mock(AiWatchEvaluationService.class);
            var jobs = mock(JobRunRecorder.class);
            AiWatch first = new AiWatch();
            first.setId(1);
            AiWatch second = new AiWatch();
            second.setId(2);
            AiWatch independent = new AiWatch();
            independent.setId(3);
            when(watches.findEvaluable(eq(7), any())).thenReturn(List.of(first, second, independent));
            when(evaluations.evaluate(first)).thenAnswer(call -> {
                fixture.scopes.getForWorkspace(7);
                return AiWatchEvaluationService.Outcome.QUIET;
            });
            when(evaluations.evaluate(second)).thenAnswer(call -> {
                fixture.scopes.getForWorkspace(7);
                return AiWatchEvaluationService.Outcome.QUIET;
            });
            when(evaluations.evaluate(independent)).thenReturn(AiWatchEvaluationService.Outcome.SKIPPED);
            var scheduler = new AiWatchScheduler(watches, evaluations, mock(WorkspaceMapper.class),
                mock(TenantWorkScope.class), jobs, Clock.systemUTC(), fixture.scopes);
            scheduler.sweepWorkspace(7);
            verify(evaluations).evaluate(independent);
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            var detail = org.mockito.ArgumentCaptor.forClass(JobRunRecorder.JobRunDetail.class);
            verify(jobs).record(eq(JobRunRecorder.AI_WATCH_EVALUATION), eq(7),
                eq(JobRunRecorder.JobRunStatus.FAILED), detail.capture());
            assertEquals(2, detail.getValue().metadata().get("failedCount"));
        }
    }

    @Test
    void aSnapshotRefusalStopsTheSweepWithoutRecordingAnOrdinaryFailedWatch() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var refusal = new ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException("workspace 7");
            when(fixture.control.findOrganizationWorkspacesForShare(7)).thenThrow(refusal);
            var watches = mock(AiWatchMapper.class);
            var evaluations = mock(AiWatchEvaluationService.class);
            var jobs = mock(JobRunRecorder.class);
            AiWatch first = new AiWatch();
            first.setId(1);
            AiWatch later = new AiWatch();
            later.setId(2);
            when(watches.findEvaluable(eq(7), any())).thenReturn(List.of(first, later));
            when(evaluations.evaluate(first)).thenAnswer(call -> {
                fixture.scopes.getForWorkspace(7);
                return AiWatchEvaluationService.Outcome.QUIET;
            });
            var scheduler = new AiWatchScheduler(watches, evaluations, mock(WorkspaceMapper.class),
                mock(TenantWorkScope.class), jobs, Clock.systemUTC(), fixture.scopes);
            org.junit.jupiter.api.Assertions.assertSame(refusal,
                org.junit.jupiter.api.Assertions.assertThrows(ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException.class,
                    () -> scheduler.sweepWorkspace(7)));
            verify(evaluations, org.mockito.Mockito.never()).evaluate(later);
            org.mockito.Mockito.verifyNoInteractions(jobs);
        }
    }

}
