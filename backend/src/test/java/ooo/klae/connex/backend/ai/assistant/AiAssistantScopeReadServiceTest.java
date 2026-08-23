package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.services.SavedViewService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.SegmentService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantScopeReadServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final Instant NOW = Instant.parse("2026-08-23T04:00:00Z");

    private ActivityMapper activityMapper;
    private SegmentService segmentService;
    private SegmentMapper segmentMapper;
    private CompanyMapper companyMapper;
    private SavedViewService savedViewService;
    private AiAssistantScopeReadService service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(ActivityMapper.class);
        segmentService = mock(SegmentService.class);
        segmentMapper = mock(SegmentMapper.class);
        companyMapper = mock(CompanyMapper.class);
        savedViewService = mock(SavedViewService.class);
        ScoringService scoringService = mock(ScoringService.class);
        DealRiskService dealRiskService = mock(DealRiskService.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess =
                mock(OrganizationWorkspaceScopeControlAccess.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceScopeControlAccess.getForWorkspace(WORKSPACE_ID)).thenReturn(
                new OrganizationWorkspaceScopeControlOperations.WorkspaceScope(
                        1, List.of(WORKSPACE_ID), "[" + WORKSPACE_ID + "]"));
        service = new AiAssistantScopeReadService(
                activityMapper, segmentService, segmentMapper, savedViewService,
                scoringService, dealRiskService, personMapper, companyMapper, dealMapper,
                workspaceService, workspaceScopeControlAccess, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void bothTheRowLimitAndThePerRecordLimitReachTheQueryRatherThanTruncatingAfterwards() {
        cohortOf(41);
        when(activityMapper.countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean())).thenReturn(388L);
        when(activityMapper.getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt()))
                .thenReturn(activities(41, 100));

        AiAssistantToolResult result = service.scopeActivities(
                coolAndColdCompanies(), "company", null, List.of("cool", "cold"), 90,
                AiChatScopeBounds.MAX_ACTIVITY_ROWS,
                AiChatScopeBounds.DEFAULT_ACTIVITY_ROWS_PER_RECORD,
                new AiChatResourceRegistry());

        ArgumentCaptor<Integer> perRecord = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(activityMapper).getAiAssistantScopeActivities(
                eq(WORKSPACE_ID), anyList(), eq("company"), anyList(),
                eq(LocalDateTime.parse("2026-05-26T00:00")),
                any(), anyList(), eq(true), perRecord.capture(), limit.capture());
        assertEquals(
                Integer.valueOf(AiChatScopeBounds.DEFAULT_ACTIVITY_ROWS_PER_RECORD),
                perRecord.getValue());
        assertEquals(
                Integer.valueOf(AiChatScopeBounds.MAX_ACTIVITY_ROWS), limit.getValue());
        assertEquals(41, result.data().get("matchedRecords"));
        assertEquals(388L, result.data().get("matchingActivities"));
        assertEquals(100, result.data().get("returnedActivities"));
        assertEquals(Boolean.TRUE, result.data().get("activitiesTruncated"));
        assertEquals("timestamp_desc", result.data().get("sort"));
    }

    /**
     * The bound is disclosed; no resumption is offered. The per-record cap re-partitions the rows on
     * every read and the period boundary is inclusive to the whole day, so any continuation handle
     * this result could carry would either replay the boundary day forever or name rows a follow-up
     * could never reach.
     */
    @Test
    void aBoundedResultDisclosesTruncationWithoutOfferingAResumptionItCannotHonour() {
        cohortOf(41);
        when(activityMapper.countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean())).thenReturn(388L);
        when(activityMapper.getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt()))
                .thenReturn(activities(41, 100));

        AiAssistantToolResult result = service.scopeActivities(
                coolAndColdCompanies(), "company", null, List.of("cool", "cold"), 90,
                AiChatScopeBounds.MAX_ACTIVITY_ROWS,
                AiChatScopeBounds.DEFAULT_ACTIVITY_ROWS_PER_RECORD,
                new AiChatResourceRegistry());

        assertNull(result.data().get("continuation"));
        assertEquals(388L, result.data().get("matchingActivities"));
        assertEquals(100, result.data().get("returnedActivities"));
        assertEquals(Boolean.TRUE, result.data().get("activitiesTruncated"));
        assertTrue(exclusions(result).contains("bounded_results"));
    }

    @Test
    void aModelWarmthArgumentNarrowsTheDeclaredBandsAndNeverReplacesThem() {
        cohortOf(4);
        when(activityMapper.countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean())).thenReturn(2L);
        when(activityMapper.getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());

        AiAssistantToolResult result = service.scopeActivities(
                coolAndColdCompanies(), null, null, List.of("cold", "hot"), 90, 50, 5,
                new AiChatResourceRegistry());

        assertEquals(List.of("cold"), interpretedScope(result).get("warmth"));
        ArgumentCaptor<SegmentDefinition> definition =
                ArgumentCaptor.forClass(SegmentDefinition.class);
        verify(segmentService).evaluate(eq("company"), definition.capture());
        assertEquals(
                List.of("warmth_cold"),
                definition.getValue().getGroups().getFirst().getConditions().stream()
                        .map(condition -> condition.getKey())
                        .toList());
    }

    @Test
    void aModelWarmthArgumentDisjointFromTheDeclaredBandsIsRefused() {
        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> service.scopeActivities(
                        coolAndColdCompanies(), null, null, List.of("hot"), 90, 50, 5,
                        new AiChatResourceRegistry()));

        assertEquals("warmth_outside_declared_scope", failure.detailReason());
        verify(segmentService, never()).evaluate(anyString(), any(SegmentDefinition.class));
    }

    @Test
    void aModelRecordKindOutsideTheDeclaredKindsIsRefusedRatherThanSubstituted() {
        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> service.scopeActivities(
                        coolAndColdCompanies(), "person", null, List.of(), 90, 50, 5,
                        new AiChatResourceRegistry()));

        assertEquals("record_kind_outside_declared_scope", failure.detailReason());
        verify(segmentService, never()).evaluate(anyString(), any(SegmentDefinition.class));
    }

    /**
     * The page anchor is server-derived context, not a model argument, so it fills the kind only
     * where the declaration leaves it open and never overrides a declared kind.
     */
    @Test
    void theAnchoringPageKindNeverOverridesADeclaredRecordKind() {
        cohortOf(2);
        when(activityMapper.countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean())).thenReturn(0L);
        when(activityMapper.getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());

        AiAssistantToolResult result = service.scopeActivities(
                coolAndColdCompanies(), null, "person", List.of(), 90, 50, 5,
                new AiChatResourceRegistry());

        assertEquals("company", interpretedScope(result).get("records"));
    }

    /**
     * Every facet the echo states must be a facet the executed cohort definition carried. A scope
     * whose stage filter the resolved cohort cannot honour is refused, never echoed and dropped.
     */
    @Test
    void theEchoedScopeIsExactlyTheScopeTheCohortDefinitionExecuted() {
        when(segmentService.evaluate(eq("deal"), any(SegmentDefinition.class)))
                .thenReturn(List.of(5));
        when(activityMapper.countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean())).thenReturn(0L);
        when(activityMapper.getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());
        AiChatQueryScope scope = new AiChatQueryScope(
                true, null, null, 90, MemberScope.allTeam(), List.of(), List.of("deal"),
                List.of(3), List.of("open"), List.of("meeting"), null);

        AiAssistantToolResult result = service.scopeActivities(
                scope, null, null, List.of(), 90, 50, 5, new AiChatResourceRegistry());

        Map<String, Object> echo = interpretedScope(result);
        assertEquals("deal", echo.get("records"));
        assertEquals(List.of("open"), echo.get("statuses"));
        assertEquals(Boolean.TRUE, echo.get("stages"));
        assertEquals(List.of("meeting"), echo.get("types"));
        assertEquals(Boolean.FALSE, echo.get("savedView"));
        ArgumentCaptor<SegmentDefinition> definition =
                ArgumentCaptor.forClass(SegmentDefinition.class);
        verify(segmentService).evaluate(eq("deal"), definition.capture());
        assertEquals(
                List.of("3"),
                definition.getValue().getConditions().getFirst().getValues());
        assertEquals(
                List.of("open"),
                definition.getValue().getGroups().getFirst().getConditions().stream()
                        .map(condition -> condition.getValue())
                        .toList());
    }

    @Test
    void aStageFilterOnANonDealCohortIsRefusedRatherThanEchoedAndDropped() {
        AiChatQueryScope scope = new AiChatQueryScope(
                true, null, null, 90, MemberScope.allTeam(), List.of(), List.of("company"),
                List.of(3), List.of(), List.of(), null);

        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> service.scopeActivities(
                        scope, null, null, List.of(), 90, 50, 5,
                        new AiChatResourceRegistry()));

        assertEquals("stage_scope_unsupported_for_cohort", failure.detailReason());
    }

    /**
     * The saved view is re-checked at execution, so a view edited between admission and retrieval
     * fails the read instead of quietly widening the cohort to the workspace universe.
     */
    @Test
    void aSavedViewThatNoLongerAppliesRefusesInsteadOfWideningTheCohort() {
        SavedView view = new SavedView();
        view.setId(17);
        view.setRecordType("company");
        view.setName("Cooling enterprise");
        view.setConfig(JsonMapper.builder().build().readTree("{\"version\":1}"));
        when(savedViewService.getById(17)).thenReturn(view);
        AiChatQueryScope scope = new AiChatQueryScope(
                true, null, null, 90, MemberScope.allTeam(), List.of(), List.of("company"),
                List.of(), List.of(), List.of(), 17);

        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> service.cohort("company", scope, List.of()));

        assertEquals("saved_view_scope_unsupported", failure.detailReason());
        verify(segmentMapper, never()).companyIdsInWorkspace(anyInt());
    }

    @Test
    void aSavedViewOfAnotherRecordTypeRefusesRatherThanBeingIgnored() {
        SavedView view = new SavedView();
        view.setId(17);
        view.setRecordType("person");
        view.setName("Cooling contacts");
        view.setConfig(JsonMapper.builder().build().readTree(
                "{\"segments\":{\"match\":\"all\",\"conditions\":"
                        + "[{\"type\":\"predicate\",\"key\":\"cooling\"}]}}"));
        when(savedViewService.getById(17)).thenReturn(view);
        AiChatQueryScope scope = new AiChatQueryScope(
                true, null, null, 90, MemberScope.allTeam(), List.of(), List.of("company"),
                List.of(), List.of(), List.of(), 17);

        assertThrows(
                AiAssistantLoopException.class,
                () -> service.cohort("company", scope, List.of()));
    }

    /**
     * The preview counts what the turn will read and refuses what the turn would refuse, so a
     * confirmed sentence can never describe a query the retrieval then declines.
     */
    @Test
    void thePreviewRefusesExactlyWhatTheExecutedRetrievalRefuses() {
        AiChatQueryScope scope = new AiChatQueryScope(
                true, null, null, 90, MemberScope.allTeam(), List.of("cool"), List.of("deal"),
                List.of(), List.of(), List.of(), null);

        assertThrows(
                AiAssistantLoopException.class,
                () -> service.previewCohort(scope, null, false));
        assertThrows(
                AiAssistantLoopException.class,
                () -> service.previewCohort(scope, null, true));
    }

    @Test
    void thePreviewOfAPipelineReviewCountsTheOpenDealsThatReviewWillRead() {
        when(segmentService.evaluate(eq("deal"), any(SegmentDefinition.class)))
                .thenReturn(List.of(4, 5, 6));

        AiAssistantScopeReadService.Cohort cohort = service.previewCohort(
                AiChatQueryScope.none(), "company", true);

        assertEquals(3, cohort.matchedCount());
        ArgumentCaptor<SegmentDefinition> definition =
                ArgumentCaptor.forClass(SegmentDefinition.class);
        verify(segmentService).evaluate(eq("deal"), definition.capture());
        assertEquals(
                List.of("open"),
                definition.getValue().getGroups().getFirst().getConditions().stream()
                        .map(condition -> condition.getValue())
                        .toList());
    }

    @Test
    void aCohortLargerThanTheCapIsBoundedInsideTheQueryAndDisclosedAsTruncated() {
        cohortOf(AiChatScopeBounds.MAX_COHORT_RECORDS + 50);
        when(activityMapper.countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean())).thenReturn(10L);
        when(activityMapper.getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());

        AiAssistantToolResult result = service.scopeActivities(
                coolAndColdCompanies(), "company", null, List.of("cool", "cold"), 90, 100, 5,
                new AiChatResourceRegistry());

        assertEquals(AiChatScopeBounds.MAX_COHORT_RECORDS + 50, result.data().get("matchedRecords"));
        assertEquals(AiChatScopeBounds.MAX_COHORT_RECORDS, result.data().get("readRecords"));
        assertEquals(Boolean.TRUE, result.data().get("recordsTruncated"));
        assertTrue(exclusions(result).contains("bounded_results"));
        ArgumentCaptor<List<Integer>> ids = idsCaptor();
        verify(activityMapper).getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), ids.capture(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt());
        assertEquals(AiChatScopeBounds.MAX_COHORT_RECORDS, ids.getValue().size());
    }

    @Test
    void restrictedSubjectsAreExcludedFromTheRowsAndDisclosedAsACategory() {
        cohortOf(3);
        when(activityMapper.countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                eq(true))).thenReturn(4L);
        when(activityMapper.countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                eq(false))).thenReturn(9L);
        when(activityMapper.getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt()))
                .thenReturn(activities(3, 4));

        AiAssistantToolResult result = service.scopeActivities(
                coolAndColdCompanies(), "company", null, List.of("cool"), 30, 50, 5,
                new AiChatResourceRegistry());

        assertTrue(exclusions(result).contains("restricted_records"));
    }

    @Test
    void anEmptyCohortShortCircuitsBeforeAnyActivityQueryRuns() {
        when(segmentService.evaluate(eq("company"), any(SegmentDefinition.class)))
                .thenReturn(List.of());

        AiAssistantToolResult result = service.scopeActivities(
                coolAndColdCompanies(), "company", null, List.of("cool"), 30, 50, 5,
                new AiChatResourceRegistry());

        assertEquals(0, result.data().get("matchedRecords"));
        assertEquals(0, result.data().get("returnedActivities"));
        assertFalse((Boolean) result.data().get("activitiesTruncated"));
        verify(activityMapper, never()).getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt());
        verify(activityMapper, never()).countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean());
    }

    @Test
    void warmthIsRefusedForDealCohortsInsteadOfBeingSilentlyDropped() {
        AiChatQueryScope deals = new AiChatQueryScope(
                true, null, null, 90, MemberScope.allTeam(), List.of("cool"), List.of("deal"),
                List.of(), List.of(), List.of(), null);

        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> service.scopeActivities(
                        deals, "deal", null, List.of("cool"), 30, 50, 5,
                        new AiChatResourceRegistry()));

        assertEquals("warmth_unsupported_for_deals", failure.detailReason());
    }

    @Test
    void anUnconstrainedCohortUsesTheWorkspaceUniverseWithoutBuildingASegment() {
        when(segmentMapper.companyIdsInWorkspace(WORKSPACE_ID)).thenReturn(List.of(1, 2, 3));

        AiAssistantScopeReadService.Cohort cohort = service.cohort(
                "company", AiChatQueryScope.none(), List.of());

        assertEquals(3, cohort.matchedCount());
        assertFalse(cohort.truncated());
        verify(segmentService, never()).evaluate(anyString(), any(SegmentDefinition.class));
    }

    @Test
    void aDeclaredOwnerScopeReachesTheCohortAsAnAuthorizedSegmentCondition() {
        when(segmentService.evaluate(eq("company"), any(SegmentDefinition.class)))
                .thenReturn(List.of(1, 2));
        AiChatQueryScope scope = new AiChatQueryScope(
                true, null, null, 90,
                new MemberScope(MemberScope.Mode.ME, 11, List.of()),
                List.of(), List.of("company"), List.of(), List.of(), List.of(), null);

        service.cohort("company", scope, List.of());

        ArgumentCaptor<SegmentDefinition> definition =
                ArgumentCaptor.forClass(SegmentDefinition.class);
        verify(segmentService).evaluate(eq("company"), definition.capture());
        assertEquals("all", definition.getValue().getMatch());
        assertEquals(1, definition.getValue().getConditions().size());
        assertEquals("owner", definition.getValue().getConditions().getFirst().getField());
        assertEquals("11", definition.getValue().getConditions().getFirst().getValue());
    }

    @Test
    void declaredWarmthBandsReachTheCohortAsAnAnyGroupOfWarmthPredicates() {
        when(segmentService.evaluate(eq("company"), any(SegmentDefinition.class)))
                .thenReturn(List.of(1));

        service.cohort("company", coolAndColdCompanies(), List.of("cool", "cold"));

        ArgumentCaptor<SegmentDefinition> definition =
                ArgumentCaptor.forClass(SegmentDefinition.class);
        verify(segmentService).evaluate(eq("company"), definition.capture());
        SegmentDefinition warmth = definition.getValue().getGroups().getFirst();
        assertEquals("any", warmth.getMatch());
        assertEquals(
                List.of("warmth_cool", "warmth_cold"),
                warmth.getConditions().stream().map(condition -> condition.getKey()).toList());
    }

    private static AiChatQueryScope coolAndColdCompanies() {
        return new AiChatQueryScope(
                true, null, null, 90, MemberScope.allTeam(),
                List.of("cool", "cold"), List.of("company"), List.of(), List.of(),
                List.of(), null);
    }

    private void cohortOf(int size) {
        List<Integer> ids = IntStream.rangeClosed(1, size).boxed().toList();
        when(segmentService.evaluate(eq("company"), any(SegmentDefinition.class)))
                .thenReturn(ids);
        when(companyMapper.getByIds(eq(WORKSPACE_ID), anyList()))
                .thenAnswer(invocation -> {
                    List<Integer> requested = invocation.getArgument(1);
                    List<Company> companies = new ArrayList<>();
                    for (Integer id : requested) {
                        Company company = new Company();
                        company.setId(id);
                        company.setName("Company " + id);
                        companies.add(company);
                    }
                    return companies;
                });
    }

    /**
     * Rows land newest-first and wrap around the cohort so the oldest returned row is the
     * continuation boundary a narrowed follow-up would resume from.
     */
    private static List<AiAssistantScopeActivity> activities(int cohortSize, int rows) {
        List<AiAssistantScopeActivity> activities = new ArrayList<>();
        for (int index = 0; index < rows; index++) {
            activities.add(new AiAssistantScopeActivity(
                    1_000 + index,
                    (index % cohortSize) + 1,
                    "meeting",
                    "Review " + index,
                    "Notes " + index,
                    index == rows - 1 ? "2026-06-02 10:00:00" : "2026-08-0"
                            + (1 + (index % 9)) + " 09:00:00",
                    null,
                    null));
        }
        return activities;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Integer>> idsCaptor() {
        return ArgumentCaptor.forClass((Class<List<Integer>>) (Class<?>) List.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> interpretedScope(AiAssistantToolResult result) {
        return (Map<String, Object>) result.data().get("scope");
    }

    @SuppressWarnings("unchecked")
    private static List<String> exclusions(AiAssistantToolResult result) {
        return (List<String>) result.data().get("exclusions");
    }
}
