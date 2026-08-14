package ooo.klae.connex.backend.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.LogoutAuditService;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

class LogoutAuditHandlerTest {

    @Test
    void authenticatedSessionDelegatesItsOneWayDigest() {
        LogoutAuditService logoutAuditService = mock(LogoutAuditService.class);
        LogoutAuditHandler handler = new LogoutAuditHandler(logoutAuditService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        User user = new User();
        user.setId(42);
        user.setDisplayName("Logout User");
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "member");

        handler.logout(request, new MockHttpServletResponse(), authentication);

        verify(logoutAuditService).record(
            request, user, OneTimeTokenDigest.sha256(session.getId()));
    }

    @Test
    void unauthenticatedUserPrincipalDoesNotCreateLogoutEvent() {
        LogoutAuditService logoutAuditService = mock(LogoutAuditService.class);
        LogoutAuditHandler handler = new LogoutAuditHandler(logoutAuditService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        User user = new User();
        user.setId(42);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null);
        authentication.setAuthenticated(false);

        handler.logout(request, new MockHttpServletResponse(), authentication);

        verifyNoInteractions(logoutAuditService);
    }
}
