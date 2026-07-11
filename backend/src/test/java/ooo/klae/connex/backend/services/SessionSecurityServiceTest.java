package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

class SessionSecurityServiceTest {
    private MutableClock clock;
    private SessionSecurityProperties properties;
    private SessionSecurityService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        properties = new SessionSecurityProperties();
        properties.setAbsoluteTimeout(Duration.ofHours(12));
        properties.setRecentAuthenticationWindow(Duration.ofMinutes(10));
        service = new SessionSecurityService(properties, clock);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void markAuthenticatedStartsAbsoluteLifetimeAndClearsWebAuthnStepUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, clock.millis());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, 7);

        service.markAuthenticated(request, 7);

        assertEquals(clock.millis(), request.getSession().getAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR));
        assertNull(request.getSession().getAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR));
        assertNull(request.getSession().getAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR));
    }

    @Test
    void markAuthenticatedRotatesOpaqueRequestIdentityForEverySessionGeneration() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        authenticateUser(7);

        service.markAuthenticated(request, 7);
        String first = service.requestIdentity(request);
        service.markAuthenticated(request, 7);
        String second = service.requestIdentity(request);

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals(7, request.getSession().getAttribute(SessionSecurityService.REQUEST_IDENTITY_USER_ATTR));
    }

    @Test
    void requestIdentityRebindsWhenAuthenticatedPrincipalChanges() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        authenticateUser(7);
        service.markAuthenticated(request, 7);
        String first = service.requestIdentity(request);

        authenticateUser(8);
        String second = service.requestIdentity(request);

        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals(8, request.getSession().getAttribute(SessionSecurityService.REQUEST_IDENTITY_USER_ATTR));
    }

    @Test
    void requestIdentityRotatesWhenServletSessionIdChanges() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        authenticateUser(7);
        service.markAuthenticated(request, 7);
        String first = service.requestIdentity(request);

        request.changeSessionId();
        String second = service.requestIdentity(request);

        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals(request.getSession().getId(),
                request.getSession().getAttribute(SessionSecurityService.REQUEST_IDENTITY_SESSION_ATTR));
    }

    @Test
    void requestIdentityLazilyInitializesLegacyAuthenticatedSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();
        authenticateUser(7);

        String identity = service.requestIdentity(request);

        assertNotNull(identity);
        assertEquals(identity, service.requestIdentity(request));
    }

    @Test
    void requestIdentityIsAbsentWithoutAuthenticatedPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();

        assertNull(service.requestIdentity(request));
    }

    @Test
    void requireRecentAuthenticationRejectsWithoutRequest() {
        assertThrows(ForbiddenException.class, () -> service.requireRecentAuthentication(7));
    }

    @Test
    void requireRecentAuthenticationRejectsStaleWebAuthnStepUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR,
                clock.millis() - Duration.ofMinutes(11).toMillis());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, 7);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThrows(ForbiddenException.class, () -> service.requireRecentAuthentication(7));
    }

    @Test
    void requireRecentAuthenticationAllowsFreshWebAuthnStepUpForSameUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        service.markStepUp(request, 7);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertDoesNotThrow(() -> service.requireRecentAuthentication(7));
    }

    @Test
    void requireRecentAuthenticationRejectsDifferentUserStepUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        service.markStepUp(request, 7);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThrows(ForbiddenException.class, () -> service.requireRecentAuthentication(8));
    }

    @Test
    void absoluteTimeoutExpiresFromAuthenticatedAt() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR,
                clock.millis() - Duration.ofHours(13).toMillis());

        assertTrue(service.isAbsoluteExpired(session));
    }

    @Test
    void missingAuthenticatedAtFallsBackToSessionCreationTimeForAuthenticatedUser() {
        MockHttpSession session = new MockHttpSession();
        authenticateUser(7);

        service.ensureAuthenticatedSessionStarted(session);

        assertEquals(session.getCreationTime(),
                session.getAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR));
    }

    private static void authenticateUser(int userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-09T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
