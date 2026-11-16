package ooo.klae.connex.backend.services;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/** Verifies warm-path company scope is resolved once from the explicit workspace. */
class WarmPathCompanyScopeTest {
    private final IntroductionMapper mapper = mock(IntroductionMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final RecordVisibilityScopeControlAccess scopes = ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    private final WarmPathService service = new WarmPathService(mapper, mock(PersonEdgeReadService.class),
        mock(PersonMapper.class), mock(ScoringService.class), workspaceService, mock(AuthService.class),
        mock(TaskService.class), Clock.systemUTC(), scopes);

    @Test
    void pathsResolveOneCompanyScopeFromExplicitWorkspace() {
        when(scopes.getForWorkspace(7)).thenReturn(new RecordVisibilityScope(900, List.of(7, 11)));
        service.computePaths(7, 20);
        verify(scopes).getForWorkspace(7);
        verify(mapper).findWarmPathCandidates(7, "[7,11]");
        verifyNoInteractions(workspaceService);
    }

    @Test
    void nonpositiveLimitDoesNotResolveCompanyScope() {
        service.computePaths(7, 0);
        verifyNoInteractions(scopes, mapper, workspaceService);
    }
}
