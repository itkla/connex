package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies operation-wide company scope reuse without borrowing request identity in background work. */
class IntroductionCompanyScopeTest {
    private final IntroductionMapper mapper = mock(IntroductionMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final RecordVisibilityScopeControlAccess scopes = ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    private final IntroductionService service = new IntroductionService(
        mapper, mock(UserMapper.class), mock(PersonEdgeMapper.class), mock(PersonEdgeReadService.class),
        mock(PersonMapper.class), mock(ScoringService.class), mock(WarmPathService.class), workspaceService,
        mock(AuthService.class), Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC),
        mock(ReferenceService.class), mock(NotificationDelivery.class), mock(NotificationPreferenceService.class),
        new ObjectMapper(), mock(TenantWorkScope.class), scopes);

    @Test
    void overviewResolvesOneCompanyScopeForBothCandidateQueries() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scopes.getForWorkspace(7)).thenReturn(new RecordVisibilityScope(900, List.of(7, 11)));
        service.getOverview(20, 20);
        verify(scopes).getForWorkspace(7);
        verify(mapper).findCandidatePersons(7, "[7,11]");
        verify(mapper).findWarmPathCandidates(7, "[7,11]");
    }

    @Test
    void scheduledSuggestionsResolveScopeFromExplicitWorkspace() {
        assertFalse(new TenantContext().isResolved());
        when(scopes.getForWorkspace(7)).thenReturn(new RecordVisibilityScope(900, List.of(7, 11)));
        service.computeSuggestions(7, 20);
        verify(scopes).getForWorkspace(7);
        verify(mapper).findCandidatePersons(7, "[7,11]");
        verifyNoInteractions(workspaceService);
    }

    @Test
    void lockedCallerReusesItsPreloadedScopeWithoutControlAccess() {
        service.computeSuggestions(7, 20, Map.of(), new RecordVisibilityScope(900, List.of(7, 11)));
        verify(mapper).findCandidatePersons(7, "[7,11]");
        verifyNoInteractions(scopes, workspaceService);
    }

    @Test
    void preloadedScopeMustContainTheRequestedWorkspace() {
        assertThrows(IllegalArgumentException.class,
            () -> service.computeSuggestions(7, 20, Map.of(), new RecordVisibilityScope(901, List.of(99))));
        verifyNoInteractions(scopes, mapper, workspaceService);
    }

    @Test
    void nonpositiveLimitDoesNotResolveCompanyScope() {
        service.computeSuggestions(7, 0);
        verifyNoInteractions(scopes, mapper, workspaceService);
    }
}
