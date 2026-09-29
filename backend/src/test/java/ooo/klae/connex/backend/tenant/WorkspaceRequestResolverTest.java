package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        verifyNoInteractions(workspaceService);
    }

    @Test
    void invalidHeaderFallsBackToValidWorkspaceCookie() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "not-an-id");
        request.setCookies(new Cookie(WorkspaceCookie.NAME, "23"));

        assertEquals(23, resolver.resolve(request, 9));
        verifyNoInteractions(workspaceService);
    }

    @Test
    void explicitNumericCandidateRemainsFailClosedForMembershipValidation() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "-1");

        assertEquals(-1, resolver.resolve(request, 9));
        verifyNoInteractions(workspaceService);
    }

    @Test
    void absentOrMalformedCandidatesUseRememberedWorkspace() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(WorkspaceCookie.NAME, "malformed"));
        when(workspaceService.rememberedWorkspaceIdFor(9)).thenReturn(31);

        assertEquals(31, resolver.resolve(request, 9));
        verify(workspaceService, never()).firstMembershipWorkspaceIdFor(9);
        verify(workspaceService, never()).defaultWorkspaceIdFor(9);
    }

    @Test
    void aRevokedRememberedWorkspaceIsNotHealedAwayDuringResolution() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(workspaceService.rememberedWorkspaceIdFor(9)).thenReturn(11);

        assertEquals(11, resolver.resolve(request, 9));
        verify(workspaceService, never()).defaultWorkspaceIdFor(9);
    }

    @Test
    void anAbsentRememberedWorkspaceReadsTheFirstMembershipWithoutARepeatedLookup() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(workspaceService.rememberedWorkspaceIdFor(9)).thenReturn(null);
        when(workspaceService.firstMembershipWorkspaceIdFor(9)).thenReturn(31);

        assertEquals(31, resolver.resolve(request, 9));
        verify(workspaceService).rememberedWorkspaceIdFor(9);
        verify(workspaceService).firstMembershipWorkspaceIdFor(9);
        verify(workspaceService, never()).defaultWorkspaceIdFor(9);
    }

    @Test
    void cookieOnlySelectionIsRecognizedAsHealable() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(WorkspaceCookie.NAME, "11"));

        assertEquals(11, resolver.resolve(request, 9));
        assertTrue(resolver.isStaleWorkspacePin(request, 11));
        verifyNoInteractions(workspaceService);
    }

    @Test
    void aPinlessRememberedSelectionIsRecognizedAsHealable() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertTrue(resolver.isStaleWorkspacePin(request, 11));
    }

    @Test
    void aHeaderDisagreeingWithTheCookieIsNotHealable() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "19");
        request.setCookies(new Cookie(WorkspaceCookie.NAME, "11"));

        assertFalse(resolver.isStaleWorkspacePin(request, 19));
        assertFalse(resolver.isStaleWorkspacePin(request, 11));
    }

    @Test
    void matchingStaleHeaderAndCookieAreRecognizedAsHealable() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "11");
        request.setCookies(new Cookie(WorkspaceCookie.NAME, "11"));

        assertTrue(resolver.isStaleWorkspacePin(request, 11));
        assertFalse(resolver.isStaleWorkspacePin(request, 19));
    }

    @Test
    void headerOnlyForeignPinIsNotHealable() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "99");

        assertFalse(resolver.isStaleWorkspacePin(request, 99));
    }

    @Test
    void userWithoutMembershipResolvesNoWorkspace() {
        WorkspaceRequestResolver resolver = new WorkspaceRequestResolver(workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(workspaceService.rememberedWorkspaceIdFor(9)).thenReturn(null);
        when(workspaceService.firstMembershipWorkspaceIdFor(9)).thenReturn(null);

        assertNull(resolver.resolve(request, 9));
    }
}
