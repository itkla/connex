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
}
