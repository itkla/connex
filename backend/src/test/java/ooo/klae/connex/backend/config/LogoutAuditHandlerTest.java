package ooo.klae.connex.backend.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        LogoutAuditHandler handler = new LogoutAuditHandler(
            logoutAuditService, new SimpleMeterRegistry());
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
        LogoutAuditHandler handler = new LogoutAuditHandler(
            logoutAuditService, new SimpleMeterRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        User user = new User();
        user.setId(42);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null);
        authentication.setAuthenticated(false);

        handler.logout(request, new MockHttpServletResponse(), authentication);

        verifyNoInteractions(logoutAuditService);
    }

    @Test
    void auditFailureIncrementsTheFixedMetricWithoutInterruptingLogout() {
        LogoutAuditService logoutAuditService = mock(LogoutAuditService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LogoutAuditHandler handler = new LogoutAuditHandler(logoutAuditService, meterRegistry);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        User user = new User();
        user.setId(42);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
            user, null, "member");
        doThrow(new IllegalStateException("audit unavailable"))
            .when(logoutAuditService)
            .record(request, user, OneTimeTokenDigest.sha256(session.getId()));

        handler.logout(request, new MockHttpServletResponse(), authentication);

        org.junit.jupiter.api.Assertions.assertEquals(
            1.0,
            meterRegistry.get("connex.security.logout.audit.failures").counter().count());
    }
}
