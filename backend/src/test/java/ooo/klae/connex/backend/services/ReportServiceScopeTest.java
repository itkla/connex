package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.report.AiReportNarrativeService;
import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSchedule;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportFilters;
import ooo.klae.connex.backend.dto.ReportLayoutItem;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.dto.ReportRange;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.mappers.GoalMapper;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.ScheduleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.ObjectMapper;

/** Proves an entire report operation resolves one immutable ceiling before any persistence lock. */
class ReportServiceScopeTest {
    private static final int WORKSPACE = 7;
    private static final int REPORT = 11;
    private static final int ACTOR = 23;
    private static final RecordVisibilityScope SCOPE = new RecordVisibilityScope(900, List.of(7, 11, 19));
    private static final String JSON = "[7,11,19]";
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime PRIOR_END = LocalDateTime.of(2026, 8, 31, 0, 0);
    private final ReportMapper reports = mock(ReportMapper.class);
    private final ScheduleMapper schedules = mock(ScheduleMapper.class);
    private final PersonMapper people = mock(PersonMapper.class);
    private final IntroductionMapper introductions = mock(IntroductionMapper.class);
    private final PersonEdgeReadService edges = mock(PersonEdgeReadService.class);
    private final ScoringService scoring = mock(ScoringService.class);
    private final RecordVisibilityScopeControlAccess scopes = ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    private final ReportNetworkService network = spy(new ReportNetworkService(reports, people, edges, introductions, scoring));
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final AuthService auth = mock(AuthService.class);
    private final AiRestrictionEpoch epoch = mock(AiRestrictionEpoch.class);
    private final AiReportNarrativeService narratives = mock(AiReportNarrativeService.class);
    private final AiGenerationService generations = mock(AiGenerationService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ReportPermissionPolicy permissions = mock(ReportPermissionPolicy.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(mock(SessionSecurityService.class), mock(PrivilegedAccountService.class), reports,
            schedules, mock(UserMapper.class), mock(GoalMapper.class), workspace, auth, scoring,
            mock(DealRiskService.class), network, scopes, narratives, generations, epoch, permissions,
            mock(AuditService.class), mock(DeletionPolicy.class), objectMapper,
            Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC), transactions);
        when(workspace.getCurrentWorkspaceId()).thenReturn(WORKSPACE);
        when(workspace.getCurrentAnalyticsTimezone()).thenReturn("UTC");
        when(scopes.getForWorkspace(WORKSPACE)).thenReturn(SCOPE);
        when(epoch.current(WORKSPACE)).thenReturn(5L);
        when(permissions.requiredFor(any(ReportDefinition.class))).thenReturn(Set.of(Permission.REPORT_READ));
        when(narratives.generate(anyInt(), anyString(), any(), any(), anyList(), anyLong()))
            .thenReturn(ReportNarrativeDto.unavailable("not_configured"));
        when(narratives.cachedNarrative(anyInt(), anyString(), any(), any(), anyList()))
            .thenReturn(ReportNarrativeDto.unavailable("not_cached"));
        List<IntroCandidatePerson> candidates = IntStream.rangeClosed(501, 502).mapToObj(id -> {
            IntroCandidatePerson person = new IntroCandidatePerson();
            person.setId(id);
            person.setName("Candidate " + id);
            return person;
        }).toList();
        when(introductions.findCandidatePersonsForReport(WORKSPACE, JSON, 501)).thenReturn(candidates);
        definition();
    }

    @Test
    void generationReusesOneScopeAcrossWidgetsAndPeriods() {
        ReportDocumentDto document = service.generate(REPORT, null, ReportService.NarrativeMode.NONE);
        assertEquals(11, document.widgets().size());
        verifyCompleteInputs();
        var order = inOrder(scopes, epoch, reports);
        order.verify(scopes).getForWorkspace(WORKSPACE);
        order.verify(epoch).current(WORKSPACE);
        order.verify(reports).getNetworkAccountValues(any(), eq(JSON), eq(50_001));
        assertNotNull(document.widgets().getFirst().priorTotal());
        assertEquals(null, document.widgets().get(1).priorTotal());
        assertEquals(null, document.widgets().get(2).priorTotal());
        assertEquals(null, document.widgets().get(3).priorTotal());
        verifyNoInteractions(narratives, generations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gap-count", "warm-value", "reverse", "contacts"})
    void kpiUsesOneScopeAndPreservesSelectedInputBounds(String widgetId) {
        service.widgetKpi(REPORT, widgetId, null);
        verifyOneScope();
        if (widgetId.equals("gap-count")) {
            verify(reports, times(2)).aggregateCoverageGaps(any(), eq(JSON), any(), any());
            verifyNoInteractions(network, people, introductions, scoring);
        } else if (widgetId.equals("contacts")) {
            verify(reports).getVisiblePersonIdsAt(WORKSPACE, JSON, END.minusNanos(1));
            verify(reports).getVisiblePersonIdsAt(WORKSPACE, JSON, PRIOR_END.minusNanos(1));
            verifyNoInteractions(network, people, introductions);
        } else {
            verify(network).snapshot(any(), eq(true), same(SCOPE));
            verify(reports).getNetworkAccountValues(any(), eq(JSON), eq(50_001));
            verify(introductions).findCandidatePersonsForReport(WORKSPACE, JSON, 501);
        }
        verifyNoInteractions(narratives, generations);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotsResolveScopeBeforePersistenceLocks(boolean delivery) {
        User user = new User();
        user.setId(ACTOR);
        when(auth.getCurrentUser()).thenReturn(user);
        AtomicReference<ReportSnapshot> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            ReportSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(31);
            saved.set(snapshot);
            return null;
        }).when(reports).insertSnapshot(any());
        when(reports.getSnapshot(WORKSPACE, REPORT, 31)).thenAnswer(invocation -> saved.get());
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<ReportSnapshot> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        ReportSchedule schedule = new ReportSchedule();
        schedule.setEnabled(true);
        schedule.setReportDefinitionId(REPORT);
        schedule.setRunAsUserId(ACTOR);
        when(schedules.lockById(WORKSPACE, 41)).thenReturn(schedule);
        if (delivery) {
            assertEquals("scheduled", service.createDeliverySnapshot(REPORT, 41).origin());
        } else {
            assertEquals("manual", service.createSnapshot(REPORT, null).origin());
        }
        verifyCompleteInputs();
        var order = inOrder(scopes, transactions, reports, schedules);
        order.verify(scopes).getForWorkspace(WORKSPACE);
        order.verify(reports).getNetworkAccountValues(any(), eq(JSON), eq(50_001));
        order.verify(transactions).execute(any());
        order.verify(reports).lockDefinitions(WORKSPACE);
        if (delivery) {
            order.verify(schedules).lockById(WORKSPACE, 41);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ReportService.NarrativeMode.class, names = {"CACHED", "FULL"})
    void narrativesReuseFrozenFiguresWithoutAnotherScopeRead(ReportService.NarrativeMode mode) {
        AtomicReference<List<ReportAppendixRowDto>> appendix = new AtomicReference<>();
        if (mode == ReportService.NarrativeMode.FULL) {
            doAnswer(invocation -> {
                appendix.set(invocation.getArgument(4));
                return ReportNarrativeDto.unavailable("not_configured");
            }).when(narratives).generate(anyInt(), anyString(), any(), any(), anyList(), anyLong());
        } else {
            doAnswer(invocation -> {
                appendix.set(invocation.getArgument(4));
                return ReportNarrativeDto.unavailable("not_cached");
            }).when(narratives).cachedNarrative(anyInt(), anyString(), any(), any(), anyList());
        }
        ReportDocumentDto document = service.generate(REPORT, null, mode);
        assertSame(document.appendix(), appendix.get());
        verifyCompleteInputs();
        verifyNoInteractions(generations);
    }

    @Test
    void asynchronousNarrativeUsesThePreparedFiguresWithoutAnotherRead() {
        AtomicReference<Supplier<AiGenerationTaskResult<ReportDocumentDto>>> task = new AtomicReference<>();
        when(generations.startAtRestrictionEpoch(eq(AiFeature.REPORT_NARRATIVE), any(), anySet(),
                any(ReportDocumentDto.class), any(), eq(5L))).thenAnswer(invocation -> {
                    task.set(invocation.getArgument(4));
                    return new AiGenerationStatusDto("handle", "report_narrative", "accepted", null, null,
                        100, 1_000, "2026-10-20T12:01:00Z");
                });
        ReportDocumentDto document = service.generateInteractive(REPORT, null, ReportService.NarrativeMode.FULL);
        assertNotNull(task.get());
        assertNotNull(task.get().get());
        verify(narratives).cachedNarrative(eq(REPORT), anyString(), any(), any(), same(document.appendix()));
        verify(narratives).generate(eq(REPORT), anyString(), any(), any(), same(document.appendix()), eq(5L));
        verifyCompleteInputs();
    }

    @Test
    void scopeFailureStopsBeforeReportInputs() {
        doThrow(new IllegalStateException("tenant mismatch")).when(scopes).getForWorkspace(WORKSPACE);
        assertThrows(IllegalStateException.class, () -> service.generate(REPORT, null, ReportService.NarrativeMode.NONE));
        verify(scopes).getForWorkspace(WORKSPACE);
        verifyNoMoreInteractions(scopes);
        verify(reports).getDefinition(WORKSPACE, REPORT);
        verifyNoMoreInteractions(reports);
        verifyNoInteractions(network, people, introductions, scoring, epoch, narratives, generations, transactions);
    }

    @Test
    void warmOnlyGenerationSuppliesItsSingleScopeToAccountSql() {
        definition(List.of(widget("warm-only", "companies", "warm_intro_opportunity_value", "company")));
        service.generate(REPORT, null, ReportService.NarrativeMode.NONE);
        verifyOneScope();
        verify(network).snapshot(any(), eq(false), same(SCOPE));
        verify(reports).getNetworkAccountValues(any(), eq(JSON), eq(50_001));
        verify(introductions, never()).findCandidatePersonsForReport(anyInt(), anyString(), anyInt());
        verify(reports, never()).aggregateCoverageGaps(any(), anyString(), any(), any());
    }

    @Test
    void reverseOnlyGenerationSuppliesItsSingleScopeToCandidates() {
        definition(List.of(widget("reverse-only", "relationships", "reverse_intro_weighted_opportunities", "pair")));
        service.generate(REPORT, null, ReportService.NarrativeMode.NONE);
        verifyOneScope();
        verify(network).reverseIntroSuggestions(WORKSPACE, SCOPE);
        verify(introductions).findCandidatePersonsForReport(WORKSPACE, JSON, 501);
        verify(reports, never()).getNetworkAccountValues(any(), anyString(), anyInt());
        verify(people, never()).getPersonsForNetworkReport(anyInt(), org.mockito.ArgumentMatchers.anyString(), anyInt());
    }

    private void verifyOneScope() {
        verify(scopes).getForWorkspace(WORKSPACE);
        verify(scopes).withWorkspaceScopeRead(eq(WORKSPACE), same(SCOPE), any());
        verifyNoMoreInteractions(scopes);
    }

    private void verifyCompleteInputs() {
        verifyOneScope();
        verify(network).snapshot(any(), eq(true), same(SCOPE));
        verify(reports).getNetworkAccountValues(any(), eq(JSON), eq(50_001));
        verify(introductions).findCandidatePersonsForReport(WORKSPACE, JSON, 501);
        verify(reports).getVisiblePersonIdsAt(WORKSPACE, JSON, END.minusNanos(1));
        verify(reports).getVisiblePersonIdsAt(WORKSPACE, JSON, PRIOR_END.minusNanos(1));
        verify(reports).getVisibleCompanyIdsAt(WORKSPACE, JSON, END.minusNanos(1));
        verify(reports).getVisibleCompanyIdsAt(WORKSPACE, JSON, PRIOR_END.minusNanos(1));
        ArgumentCaptor<ReportAggregateQuery> queries = ArgumentCaptor.forClass(ReportAggregateQuery.class);
        verify(reports, times(4)).aggregateCoverageGaps(queries.capture(), eq(JSON), any(), any());
        assertEquals(3, queries.getAllValues().stream().filter(query -> query.endUtc().equals(END)).count());
        assertEquals(1, queries.getAllValues().stream().filter(query -> query.endUtc().equals(PRIOR_END)).count());
        verify(reports, times(2)).aggregateSingleThreadedDeals(any(), eq(JSON));
    }

    private void definition() {
        definition(List.of(
            widget("gap-count", "companies", "coverage_gap_count", "none"),
            widget("gap-company", "companies", "coverage_gap_count", "company"),
            widget("gap-value", "companies", "coverage_gap_open_pipeline_value", "company"),
            widget("single-count", "deals", "single_threaded_deal_count", "company"),
            widget("single-value", "deals", "single_threaded_deal_value", "deal"),
            widget("contacts", "relationships", "count", "warmth_band"),
            widget("companies", "relationships", "company_count", "warmth_band"),
            widget("warm-value", "companies", "warm_intro_opportunity_value", "company"),
            widget("warm-count", "companies", "warm_intro_reachable_account_count", "connector"),
            widget("reverse", "relationships", "reverse_intro_weighted_opportunities", "pair"),
            widget("repeat-gap-count", "companies", "coverage_gap_count", "none")));
    }

    private void definition(List<ReportWidgetConfig> widgets) {
        ReportDefinition definition = new ReportDefinition();
        definition.setId(REPORT);
        definition.setWorkspaceId(WORKSPACE);
        definition.setName("Scoped report");
        definition.setCadence("custom");
        ReportConfig config = new ReportConfig(widgets, new ReportFilters(null, null, null, null, null),
            new ReportRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), "month",
            IntStream.range(0, widgets.size()).mapToObj(index ->
                new ReportLayoutItem(widgets.get(index).id(), 0, index * 4, 6, 4)).toList());
        definition.setConfigJson(objectMapper.writeValueAsString(config));
        when(reports.getDefinition(WORKSPACE, REPORT)).thenReturn(definition);
    }

    private static ReportWidgetConfig widget(String id, String source, String measure, String group) {
        return new ReportWidgetConfig(id, null, source, measure, group, "table");
    }
}
