package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatQueryScopeDto;
import ooo.klae.connex.backend.dto.AiChatQueryScopeRequest;
import ooo.klae.connex.backend.dto.AiChatScopeReferenceDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.services.SavedViewService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiChatQueryScopeResolverTest {
    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 11;
    private static final Instant NOW = Instant.parse("2026-08-23T04:00:00Z");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private WorkspaceService workspaceService;
    private PipelineMapper pipelineMapper;
    private SavedViewService savedViewService;
    private AiChatQueryScopeResolver resolver;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        pipelineMapper = mock(PipelineMapper.class);
        savedViewService = mock(SavedViewService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(USER_ID);
        resolver = new AiChatQueryScopeResolver(
                workspaceService, pipelineMapper, savedViewService, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anAbsentScopeResolvesToTheUndeclaredScope() {
        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(null);

        assertFalse(resolution.scope().declared());
        assertFalse(resolution.interpreted().declared());
        assertEquals(MemberScope.Mode.ALL_TEAM, resolution.scope().memberScope().mode());
        assertNull(resolution.interpreted().periodStart());
    }

    @Test
    void aScopeWhoseEveryFieldIsEmptyIsTreatedAsUndeclared() {
        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, "all_team", List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), null));

        assertFalse(resolution.scope().declared());
    }

    @Test
    void aTrailingWindowResolvesToExactInclusiveDatesTheClientCanRestate() {
        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, 90, null, List.of(), List.of("cool", "cold"),
                        List.of("company"), List.of(), List.of(), List.of(), null));

        AiChatQueryScopeDto interpreted = resolution.interpreted();
        assertTrue(interpreted.declared());
        assertEquals("2026-08-23", interpreted.periodEnd());
        assertEquals("2026-05-26", interpreted.periodStart());
        assertEquals(Integer.valueOf(90), interpreted.periodDays());
        assertEquals(List.of("cool", "cold"), interpreted.warmthBands());
        assertEquals(List.of("company"), interpreted.recordKinds());
        assertEquals(AiChatScopeBounds.MAX_COHORT_RECORDS, interpreted.recordCap());
        assertEquals(AiChatScopeBounds.MAX_ACTIVITY_ROWS, interpreted.activityCap());
        assertEquals(
                AiChatScopeBounds.MAX_ACTIVITY_ROWS_PER_RECORD, interpreted.perRecordCap());
    }

    @Test
    void anOversizedExplicitRangeIsCappedAndTheCapIsDisclosed() {
        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        "2020-01-01", "2026-08-23", null, null, List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), null));

        assertEquals(
                Integer.valueOf(AiChatScopeBounds.MAX_PERIOD_DAYS),
                resolution.interpreted().periodDays());
        assertEquals("2025-08-24", resolution.interpreted().periodStart());
        assertTrue(resolution.interpreted().unavailable().contains("period_capped"));
    }

    @Test
    void anInvertedRangeIsRejectedRatherThanSilentlyReordered() {
        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        "2026-08-23", "2026-01-01", null, null, List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), null)));
    }

    @Test
    void theMeScopeResolvesFromTheServerSessionRatherThanARequestSuppliedId() {
        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, "me", List.of(999), List.of(), List.of(),
                        List.of(), List.of(), List.of(), null));

        assertEquals(MemberScope.Mode.ME, resolution.scope().memberScope().mode());
        assertEquals(Integer.valueOf(USER_ID), resolution.scope().memberScope().userId());
        assertEquals("me", resolution.interpreted().ownerMode());
        assertTrue(resolution.interpreted().owners().isEmpty());
    }

    @Test
    void selectedOwnersMustBeActiveMembersOfTheCurrentWorkspace() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(member(4, "Aiko")));

        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, "members", List.of(4, 8_888), List.of(),
                        List.of(), List.of(), List.of(), List.of(), null)));
    }

    @Test
    void selectedOwnersResolveThroughMembershipAndEchoTheirDisplayNames() {
        when(workspaceService.getMembers(WORKSPACE_ID))
                .thenReturn(List.of(member(4, "Aiko"), member(9, "Hunter")));

        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, "members", List.of(9, 4), List.of(), List.of(),
                        List.of(), List.of(), List.of(), null));

        assertEquals(MemberScope.Mode.MEMBERS, resolution.scope().memberScope().mode());
        assertEquals(List.of(9, 4), resolution.scope().memberScope().memberIds());
        assertEquals("members", resolution.interpreted().ownerMode());
        assertEquals(
                List.of("Hunter", "Aiko"),
                resolution.interpreted().owners().stream()
                        .map(owner -> owner.label())
                        .toList());
    }

    @Test
    void stagesMustExistInTheCurrentWorkspace() {
        when(pipelineMapper.getVisibleStageById(WORKSPACE_ID, 3)).thenReturn(null);

        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(3), List.of(), List.of(), null)));
    }

    /**
     * A warmth filter on a deal-only cohort is refused at admission, not queued: deals carry no
     * warmth, and no model argument during the turn could cure the declaration, so accepting it
     * would guarantee a turn that only fails.
     */
    @Test
    void aWarmthFilterOnADealOnlyCohortIsRejectedAtAdmission() {
        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> resolver.resolve(new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of("cold"), List.of("deal"),
                        List.of(), List.of(), List.of(), null)));

        assertTrue(failure.getMessage().contains("warmth_unsupported_for_deals"));
    }

    @Test
    void anInaccessibleSavedViewIsRejected() {
        when(savedViewService.getById(17))
                .thenThrow(new ResourceNotFoundException("Saved view not found"));

        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 17)));
    }

    @Test
    void aSavedViewWithFacetsTheServerCannotExecuteIsRefusedRatherThanPartlyApplied() {
        when(savedViewService.getById(17)).thenReturn(savedView(17, "Japan accounts",
                "{\"version\":1,\"query\":\"tokyo\"}"));

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> resolver.resolve(new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 17)));

        assertTrue(failure.getMessage().contains("saved_view_scope_unsupported"));
    }

    @Test
    void aSavedViewWithColumnFiltersIsAlsoRefused() {
        when(savedViewService.getById(17)).thenReturn(savedView(17, "Japan accounts",
                "{\"version\":1,\"filters\":{\"industry\":[\"finance\"]}}"));

        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 17)));
    }

    @Test
    void aSavedViewWhoseWholeScopeIsAServerEvaluableSegmentIsAccepted() {
        when(savedViewService.getById(17)).thenReturn(savedView(17, "Cooling enterprise",
                "{\"version\":1,\"segments\":{\"match\":\"all\",\"conditions\":"
                        + "[{\"type\":\"predicate\",\"key\":\"cooling\"}]}}"));

        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 17));

        assertEquals(Integer.valueOf(17), resolution.scope().savedViewId());
        assertEquals(17, resolution.interpreted().savedView().id());
        assertEquals("Cooling enterprise", resolution.interpreted().savedView().label());
    }

    @Test
    void unsupportedFilterValuesAreRejectedInsteadOfIgnored() {
        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of("lukewarm"), List.of(),
                        List.of(), List.of(), List.of(), null)));
        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of("  "), null)));
    }

    @Test
    void aSavedViewWithoutAnyServerEvaluableSegmentIsRefusedRatherThanQuietlyIgnored() {
        when(savedViewService.getById(17)).thenReturn(savedView(17, "Japan accounts",
                "{\"version\":1}"));

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> resolver.resolve(new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 17)));

        assertTrue(failure.getMessage().contains("saved_view_scope_unsupported"));
    }

    @Test
    void aSavedViewWhoseSegmentConstrainsNothingIsRefused() {
        when(savedViewService.getById(17)).thenReturn(savedView(17, "Everything",
                "{\"version\":1,\"segments\":{\"match\":\"all\","
                        + "\"conditions\":[],\"groups\":[]}}"));

        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 17)));
    }

    /**
     * An accepted view binds its own record type, so the cohort the turn reads is the cohort the
     * view describes rather than a derived kind the view's definition could never apply to.
     */
    @Test
    void anAcceptedSavedViewBindsItsRecordTypeIntoTheInterpretedScope() {
        when(savedViewService.getById(17)).thenReturn(savedView(17, "Cooling enterprise",
                "{\"version\":1,\"segments\":{\"match\":\"all\",\"conditions\":"
                        + "[{\"type\":\"predicate\",\"key\":\"cooling\"}]}}"));

        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 17));

        assertEquals(List.of("company"), resolution.scope().recordKinds());
        assertEquals(List.of("company"), resolution.interpreted().recordKinds());
    }

    @Test
    void aSavedViewContradictingTheDeclaredRecordKindsIsRefused() {
        when(savedViewService.getById(17)).thenReturn(savedView(17, "Cooling enterprise",
                "{\"version\":1,\"segments\":{\"match\":\"all\",\"conditions\":"
                        + "[{\"type\":\"predicate\",\"key\":\"cooling\"}]}}"));

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> resolver.resolve(new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of("person"),
                        List.of(), List.of(), List.of(), 17)));

        assertTrue(failure.getMessage().contains("saved_view_scope_unsupported"));
    }

    @Test
    void stageAndStatusFiltersAreRefusedWhenTheDeclaredKindsExcludeDeals() {
        when(pipelineMapper.getVisibleStageById(WORKSPACE_ID, 3)).thenReturn(stage(3, "Won"));

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> resolver.resolve(new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of("company"),
                        List.of(3), List.of(), List.of(), null)));

        assertTrue(failure.getMessage().contains("stage_scope_unsupported_for_cohort"));
        assertThrows(BadRequestException.class, () -> resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of("person"),
                        List.of(), List.of("open"), List.of(), null)));
    }

    /**
     * Durable turn scope stores identifiers only; the labels a reader sees are re-resolved under
     * that reader's own authorization, so an erased member is never named by a stored turn.
     */
    @Test
    void storedScopeCarriesNoLabelsAndIsRelabelledUnderTheReadersAuthorization() {
        when(workspaceService.getMembers(WORKSPACE_ID))
                .thenReturn(List.of(member(4, "Aiko")));
        when(pipelineMapper.getVisibleStageById(WORKSPACE_ID, 3)).thenReturn(stage(3, "Won"));
        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, "members", List.of(4), List.of(), List.of("deal"),
                        List.of(3), List.of("open"), List.of(), null));

        AiChatQueryScopeDto stored = resolution.interpreted().withoutLabels();
        assertEquals(List.of(""), stored.owners().stream().map(owner -> owner.label()).toList());
        assertEquals(List.of(""), stored.stages().stream().map(stage -> stage.label()).toList());
        assertEquals(List.of(4), stored.owners().stream().map(owner -> owner.id()).toList());

        AiChatQueryScopeDto relabelled = resolver.relabel(stored);
        assertEquals("Aiko", relabelled.owners().getFirst().label());
        assertEquals(4, relabelled.owners().getFirst().id());
        assertEquals("Won", relabelled.stages().getFirst().label());
        assertEquals(List.of("open"), relabelled.dealStatuses());
    }

    @Test
    void aStoredSavedViewIsRestatedUnderItsCurrentNameRatherThanTheStoredOne() {
        when(savedViewService.getById(17))
                .thenReturn(savedView(17, "Renamed enterprise", "{\"version\":1}"));

        AiChatQueryScopeDto relabelled = resolver.relabel(new AiChatQueryScopeDto(
                true, null, null, 90, "all_team", List.of(), List.of(), List.of("company"),
                List.of(), List.of(), List.of(), new AiChatScopeReferenceDto(17, ""),
                null, false, 200, 100, 10, List.of()));

        assertEquals("Renamed enterprise", relabelled.savedView().label());
    }

    @Test
    void aRelabelledScopeDropsOnlyTheLabelOfAReferenceThatNoLongerResolves() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of());
        when(savedViewService.getById(17))
                .thenThrow(new ResourceNotFoundException("Saved view not found"));

        AiChatQueryScopeDto relabelled = resolver.relabel(new AiChatQueryScopeDto(
                true, null, null, 90, "members",
                List.of(new AiChatScopeReferenceDto(4, "")), List.of(), List.of("company"),
                List.of(), List.of(), List.of(), new AiChatScopeReferenceDto(17, ""),
                null, false, 200, 100, 10, List.of()));

        assertEquals(4, relabelled.owners().getFirst().id());
        assertEquals("", relabelled.owners().getFirst().label());
        assertEquals(17, relabelled.savedView().id());
        assertEquals("", relabelled.savedView().label());
    }

    /**
     * The digest binds the definition this admission accepted, so the executed read can tell an
     * unchanged view from one edited into a different, still-executable definition.
     */
    @Test
    void anAcceptedSavedViewCarriesTheDigestOfTheDefinitionItWasAdmittedWith() {
        SavedView admitted = savedView(17, "Cooling enterprise",
                "{\"version\":1,\"segments\":{\"match\":\"all\",\"conditions\":"
                        + "[{\"type\":\"predicate\",\"key\":\"cooling\"}]}}");
        when(savedViewService.getById(17)).thenReturn(admitted);

        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 17));

        assertEquals(
                AiChatSavedViewScope.fingerprint(objectMapper, admitted).orElseThrow(),
                resolution.scope().savedViewFingerprint());
    }

    /**
     * Restating a completed turn without a filter it actually applied makes the historical read look
     * broader than it was, so an unresolvable stage keeps its identifier exactly as an erased owner
     * and an inaccessible saved view already do.
     */
    @Test
    void aStoredStageThatNoLongerResolvesKeepsItsIdentifierAndLosesOnlyItsLabel() {
        when(pipelineMapper.getVisibleStageById(WORKSPACE_ID, 3)).thenReturn(null);

        AiChatQueryScopeDto relabelled = resolver.relabel(new AiChatQueryScopeDto(
                true, null, null, 90, "all_team", List.of(), List.of(), List.of("deal"),
                List.of(new AiChatScopeReferenceDto(3, "")), List.of("open"), List.of(),
                null, null, false, 200, 100, 10, List.of()));

        assertEquals(1, relabelled.stages().size());
        assertEquals(3, relabelled.stages().getFirst().id());
        assertEquals("", relabelled.stages().getFirst().label());
    }

    /**
     * Every other user-facing date window in the product resolves against the workspace's reporting
     * calendar, and a scope chip that names a day has to name the same day those surfaces do.
     */
    @Test
    void aTrailingWindowResolvesInTheWorkspaceReportingCalendarRatherThanUtc() {
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("America/Los_Angeles");

        AiChatQueryScopeResolver.Resolution resolution = resolver.resolve(
                new AiChatQueryScopeRequest(
                        null, null, 1, null, List.of(), List.of(), List.of("company"),
                        List.of(), List.of(), List.of(), null));

        assertEquals("2026-08-22", resolution.interpreted().periodEnd());
        assertEquals("2026-08-22", resolution.interpreted().periodStart());
    }

    private static Stage stage(int id, String name) {
        Stage stage = new Stage();
        stage.setId(id);
        stage.setName(name);
        return stage;
    }

    private static User member(int id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        return user;
    }

    private SavedView savedView(int id, String name, String config) {
        SavedView view = new SavedView();
        view.setId(id);
        view.setWorkspaceId(WORKSPACE_ID);
        view.setName(name);
        view.setRecordType("company");
        view.setConfig(objectMapper.readTree(config));
        return view;
    }
}
