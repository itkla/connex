package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.Cookie;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class WorkspaceRequestResolverTest {
    @Mock WorkspaceService workspaceService;

    @Test
    void validHeaderTakesPrecedenceOverCookieAndDefault() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", " 17 ");
        request.setCookies(new Cookie(WorkspaceCookie.NAME, "23"));

        assertEquals(17, resolver.resolve(request, 9));
        verify(workspaceService, never()).defaultWorkspaceIdFor(9);
    }

    @Test
    void invalidHeaderFallsBackToValidWorkspaceCookie() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "not-an-id");
        request.setCookies(new Cookie(WorkspaceCookie.NAME, "23"));

        assertEquals(23, resolver.resolve(request, 9));
        verify(workspaceService, never()).defaultWorkspaceIdFor(9);
    }

    @Test
    void explicitNumericCandidateRemainsFailClosedForMembershipValidation() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "-1");

        assertEquals(-1, resolver.resolve(request, 9));
        verify(workspaceService, never()).defaultWorkspaceIdFor(9);
    }

    @Test
    void absentOrMalformedCandidatesUseRememberedWorkspace() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(WorkspaceCookie.NAME, "malformed"));
        when(workspaceService.defaultWorkspaceIdFor(9)).thenReturn(31);

        assertEquals(31, resolver.resolve(request, 9));
    }

    @Test
    void userWithoutMembershipResolvesNoWorkspace() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(workspaceService.defaultWorkspaceIdFor(9)).thenReturn(null);

        assertNull(resolver.resolve(request, 9));
    }
}
