package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Duration;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;

/**
 * Tracks authenticated-session age and WebAuthn step-up stamps in the servlet session.
 */
@Service
@RequiredArgsConstructor
public class SessionSecurityService {
    public static final String AUTHENTICATED_AT_ATTR = "connex.authenticatedAt";
    public static final String AUTHENTICATED_USER_ATTR = "connex.authenticatedUserId";
    public static final String WEBAUTHN_STEP_UP_AT_ATTR = "connex.webauthnStepUpAt";
    public static final String WEBAUTHN_STEP_UP_USER_ATTR = "connex.webauthnStepUpUserId";
    public static final String FIRST_PASSKEY_BOOTSTRAP_AT_ATTR = "connex.firstPasskeyBootstrapAt";
    public static final String FIRST_PASSKEY_BOOTSTRAP_USER_ATTR = "connex.firstPasskeyBootstrapUserId";

    private final SessionSecurityProperties properties;
    private final Clock clock;

    public void markAuthenticated(HttpServletRequest request, int userId) {
        HttpSession session = request.getSession();
        session.setAttribute(AUTHENTICATED_AT_ATTR, clock.millis());
        session.setAttribute(AUTHENTICATED_USER_ATTR, userId);
        session.removeAttribute(WEBAUTHN_STEP_UP_AT_ATTR);
        session.removeAttribute(WEBAUTHN_STEP_UP_USER_ATTR);
    }

    public void markStepUp(HttpServletRequest request, int userId) {
        markStepUp(request.getSession(), userId, clock.millis());
    }

    public void markFirstPasskeyBootstrap(HttpServletRequest request, int userId) {
        HttpSession session = request.getSession();
        session.setAttribute(FIRST_PASSKEY_BOOTSTRAP_AT_ATTR, clock.millis());
        session.setAttribute(FIRST_PASSKEY_BOOTSTRAP_USER_ATTR, userId);
    }

    public boolean hasFreshFirstPasskeyBootstrap(HttpServletRequest request, int userId) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Long authenticatedAt = longAttribute(session, FIRST_PASSKEY_BOOTSTRAP_AT_ATTR);
        Integer authenticatedUserId = integerAttribute(session, FIRST_PASSKEY_BOOTSTRAP_USER_ATTR);
        boolean fresh = authenticatedAt != null
            && authenticatedUserId != null
            && authenticatedUserId == userId
            && isWithinRecentAuthenticationWindow(authenticatedAt);
        if (!fresh) {
            clearFirstPasskeyBootstrap(request);
        }
        return fresh;
    }

    public void clearFirstPasskeyBootstrap(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(FIRST_PASSKEY_BOOTSTRAP_AT_ATTR);
            session.removeAttribute(FIRST_PASSKEY_BOOTSTRAP_USER_ATTR);
        }
    }

    public boolean isAbsoluteExpired(HttpSession session) {
        Duration timeout = properties.getAbsoluteTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return false;
        }
        Long authenticatedAt = authenticatedAt(session);
        return authenticatedAt != null && clock.millis() - authenticatedAt > timeout.toMillis();
    }

    public void ensureAuthenticatedSessionStarted(HttpSession session) {
        Integer currentUserId = currentUserId();
        if (currentUserId == null) {
            return;
        }
        if (longAttribute(session, AUTHENTICATED_AT_ATTR) == null) {
            session.setAttribute(AUTHENTICATED_AT_ATTR, session.getCreationTime());
        }
        if (integerAttribute(session, AUTHENTICATED_USER_ATTR) == null) {
            session.setAttribute(AUTHENTICATED_USER_ATTR, currentUserId);
        }
    }

    public void requireRecentAuthentication(int userId) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            throw new RecentAuthenticationRequiredException();
        }
        HttpSession session = request.getSession(false);
        if (session == null || !hasFreshRecentAuthentication(session, userId)) {
            throw new RecentAuthenticationRequiredException();
        }
    }

    /**
     * Requires the current session to have been established recently for the same account.
     * @param request the current HTTP request
     * @param userId the authenticated account
     */
    public void requireFreshAuthenticatedSession(HttpServletRequest request, int userId) {
        HttpSession session = request.getSession(false);
        if (session == null || !hasFreshAuthenticatedSession(session, userId)) {
            throw new ForbiddenException("Recent sign-in required");
        }
    }

    /**
     * Reports whether the session was established recently for the same account.
     * @param session the authenticated HTTP session
     * @param userId the expected account
     * @return true when the authenticated-session proof is current and user-bound
     */
    public boolean hasFreshAuthenticatedSession(HttpSession session, int userId) {
        Long authenticatedAt = longAttribute(session, AUTHENTICATED_AT_ATTR);
        Integer authenticatedUserId = integerAttribute(session, AUTHENTICATED_USER_ATTR);
        if (authenticatedAt == null || authenticatedUserId == null || authenticatedUserId != userId) {
            return false;
        }
        return isWithinRecentAuthenticationWindow(authenticatedAt);
    }

    public boolean hasFreshRecentAuthentication(HttpSession session, int userId) {
        Duration window = properties.getRecentAuthenticationWindow();
        if (window == null || window.isZero() || window.isNegative()) {
            return true;
        }
        Long recentAt = longAttribute(session, WEBAUTHN_STEP_UP_AT_ATTR);
        Integer recentUserId = integerAttribute(session, WEBAUTHN_STEP_UP_USER_ATTR);
        return recentAt != null
            && recentUserId != null
            && recentUserId == userId
            && isWithinRecentAuthenticationWindow(recentAt);
    }

    private void markStepUp(HttpSession session, int userId, long now) {
        session.setAttribute(WEBAUTHN_STEP_UP_AT_ATTR, now);
        session.setAttribute(WEBAUTHN_STEP_UP_USER_ATTR, userId);
    }

    private Long authenticatedAt(HttpSession session) {
        Long authenticatedAt = longAttribute(session, AUTHENTICATED_AT_ATTR);
        if (authenticatedAt != null) {
            return authenticatedAt;
        }
        return currentUserId() == null ? null : session.getCreationTime();
    }

    private boolean isWithinRecentAuthenticationWindow(long timestamp) {
        Duration window = properties.getRecentAuthenticationWindow();
        if (window == null || window.isZero() || window.isNegative()) {
            return true;
        }
        long age = clock.millis() - timestamp;
        return age >= 0 && age <= window.toMillis();
    }

    private static Long longAttribute(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof Long typed ? typed : null;
    }

    private static Integer integerAttribute(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof Integer typed ? typed : null;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private static Integer currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }
}
