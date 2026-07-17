package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DealRiskProjectionServiceTest {
    @Test
    void analyticsCapsCandidateHydrationAndReportsTruncation() {
        DealMapper dealMapper = mock(DealMapper.class);
        List<Integer> candidates = IntStream.rangeClosed(1, 1_001).boxed().toList();
        when(dealMapper.getRiskCandidateIds(7, MemberScope.allTeam(), 1_001)).thenReturn(candidates);
        when(dealMapper.getByIds(
            eq(7), argThat(ids -> ids.size() == 1_000 && ids.getFirst() == 1 && ids.getLast() == 1_000)))
            .thenReturn(List.of());
        DealRiskService service = new DealRiskService(
            dealMapper,
            mock(ActivityMapper.class),
            mock(NoteMapper.class),
            mock(TaskMapper.class),
            mock(ScoringService.class),
            Clock.systemUTC());

        var analytics = service.analytics(7, MemberScope.allTeam());

        assertTrue(analytics.truncated());
        assertTrue(analytics.currencies().isEmpty());
        verify(dealMapper).getRiskCandidateIds(7, MemberScope.allTeam(), 1_001);
        verify(dealMapper).getByIds(
            eq(7), argThat(ids -> ids.size() == 1_000 && ids.getFirst() == 1 && ids.getLast() == 1_000));
    }

    @Test
    void dashboardCapsCandidateHydrationAndReportsTruncation() {
        DealMapper dealMapper = mock(DealMapper.class);
        List<Integer> candidates = IntStream.rangeClosed(1, 1_001).boxed().toList();
        when(dealMapper.getRiskCandidateIds(7, MemberScope.allTeam(), 1_001)).thenReturn(candidates);
        when(dealMapper.getByIds(
            eq(7), argThat(ids -> ids.size() == 1_000 && ids.getFirst() == 1 && ids.getLast() == 1_000)))
            .thenReturn(List.of());
        DealRiskService service = new DealRiskService(
            dealMapper,
            mock(ActivityMapper.class),
            mock(NoteMapper.class),
            mock(TaskMapper.class),
            mock(ScoringService.class),
            Clock.systemUTC());

        var dashboard = service.assessDashboard(7, Map.of(), 6);

        assertTrue(dashboard.truncated());
        assertTrue(dashboard.items().isEmpty());
        verify(dealMapper).getRiskCandidateIds(7, MemberScope.allTeam(), 1_001);
        verify(dealMapper).getByIds(
            eq(7), argThat(ids -> ids.size() == 1_000 && ids.getFirst() == 1 && ids.getLast() == 1_000));
    }
}
