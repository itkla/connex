package ooo.klae.connex.backend.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;

class LogoutAuditHandlerTest {

    @Test
    void duplicateInvocationForOneLiveSessionRecordsOnce() {
        AuditService auditService = mock(AuditService.class);
        WorkspaceRequestResolver workspaceRequestResolver = mock(WorkspaceRequestResolver.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        LogoutAuditHandler handler = new LogoutAuditHandler(
            auditService, workspaceRequestResolver, workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        User user = new User();
        user.setId(42);
        user.setDisplayName("Logout User");
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "member");
        when(workspaceRequestResolver.resolve(request, user.getId())).thenReturn(7);
        when(workspaceService.getRole(7, user.getId())).thenReturn("member");
        when(workspaceService.getOrgId(7)).thenReturn(8);

        handler.logout(request, new MockHttpServletResponse(), authentication);
        handler.logout(request, new MockHttpServletResponse(), authentication);

        verify(auditService).recordScoped(
            "auth.logout", "user", 42, 7, 8, "Logout User", "Logout User logged out", null);
    }

    @Test
    void unauthenticatedUserPrincipalDoesNotCreateLogoutEvent() {
        AuditService auditService = mock(AuditService.class);
        WorkspaceRequestResolver workspaceRequestResolver = mock(WorkspaceRequestResolver.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        LogoutAuditHandler handler = new LogoutAuditHandler(
            auditService, workspaceRequestResolver, workspaceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        User user = new User();
        user.setId(42);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null);
        authentication.setAuthenticated(false);

        handler.logout(request, new MockHttpServletResponse(), authentication);

        verifyNoInteractions(auditService, workspaceRequestResolver, workspaceService);
    }
}
