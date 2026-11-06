package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.DealCurrencyMetricsDto;
import ooo.klae.connex.backend.dto.DealMetricsDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.MyWorkRelationshipsDto.Counts;
import ooo.klae.connex.backend.dto.NoteActivityDayDto;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.MyWorkReadMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.util.AnalyticsPeriods.Window;

/** Personal projection bounds, aggregate independence, self scope, and honest failure behavior. */
@ExtendWith(MockitoExtension.class)
class MyWorkReadServiceTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private MyWorkReadMapper mapper;
    @Mock private PersonMapper personMapper;
    @Mock private DealMapper dealMapper;
    @Mock private UserService userService;
    @Mock private ReferenceService referenceService;
    @Mock private RecordVisibilityScopeControlAccess visibility;
    @Mock private ScoringService scoringService;
    @Mock private DealService dealService;
    @Mock private DealRiskService dealRiskService;
    private MyWorkReadService service;

    @BeforeEach
    void setUp() {
        service = new MyWorkReadService(workspaceService, mapper, personMapper, dealMapper, userService,
            referenceService, visibility, scoringService, dealService, dealRiskService,
            Clock.fixed(Instant.parse("2026-07-15T23:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void kpisAlwaysBindSelfAndWorkspaceCalendarWithOneExplicitCurrency() {
        when(workspaceService.getCurrentUserId()).thenReturn(51);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("Asia/Tokyo");
        var scope = MemberScope.fromRequest("me", null, 51);
        var metrics = new DealMetricsDto(List.of(
            new DealCurrencyMetricsDto("JPY", 5, 0, 1, 0, 1_200_000, 1, 0),
            new DealCurrencyMetricsDto("USD", 7, 0, 3, 0, 35_400, 2, 1)), 16);
        when(dealService.queryDealMetrics(null, null, null, null, null, null, false, null, null, scope))
            .thenReturn(metrics);
        assertEquals("JPY", service.dealKpis("JPY").currency());
        var window = ArgumentCaptor.forClass(Window.class);
        verify(dealService).getDealKpis(eq("JPY"), window.capture(), any(), eq(scope));
        assertEquals(LocalDate.parse("2026-04-18"), window.getValue().fromDate());
        assertEquals(LocalDate.parse("2026-07-16"), window.getValue().toDate());
        assertEquals("USD", service.dealKpis("EUR").currency());
        verify(dealService).getDealKpis(eq("USD"), any(Window.class), any(), eq(scope));
    }

    @Test
    void pulseMergesAllDailyCountsWithoutReadingHistory() {
        when(workspaceService.getCurrentUserId()).thenReturn(51);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        var date = LocalDate.of(2026, 7, 15);
        when(mapper.activityPulse(7, 51, date.minusDays(83).atStartOfDay(), date.plusDays(1).atStartOfDay()))
            .thenReturn(List.of(new NoteActivityDayDto(date, 200)));
        when(userService.getNoteActivityByUserId(51)).thenReturn(List.of(new NoteActivityDayDto(date, 105)));
        assertEquals(List.of(new NoteActivityDayDto(date, 305)), service.pulse());
        verifyNoInteractions(referenceService);
    }

    @Test
    void emptyPersonalNetworkHasRealCountsAndDoesNotExpandAnyOtherSource() {
        when(workspaceService.getCurrentUserId()).thenReturn(51);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        var counts = new Counts(0, 0, 0, 0, 0);
        when(mapper.relationshipCounts(eq(7), eq(51), any(LocalDateTime.class), any())).thenReturn(counts);
        when(mapper.relationshipIds(eq(7), eq(51), any(LocalDateTime.class), any(), eq(false), eq(18)))
            .thenReturn(List.of());
        when(mapper.relationshipIds(eq(7), eq(51), any(LocalDateTime.class), any(), eq(true), eq(6)))
            .thenReturn(List.of());
        assertEquals(counts, service.relationships().counts());
        verifyNoInteractions(personMapper, scoringService, visibility, dealMapper);
    }

    @Test
    void historyFailurePropagatesInsteadOfReturningAnEmptyTimeline() {
        when(workspaceService.getCurrentUserId()).thenReturn(51);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.recentTasks(7, 51)).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, service::history);
    }

    @Test
    void riskCapacityNeverPublishesAnIncompleteRanking() {
        when(workspaceService.getCurrentUserId()).thenReturn(51);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(dealRiskService.assessPersonal(7, MemberScope.fromRequest("me", null, 51)))
            .thenReturn(new DealRiskService.BoundedRiskAssessment(List.of(), true));
        assertEquals(true, service.risks().inputLimitExceeded());
        verifyNoInteractions(dealMapper);
    }
}
