package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

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

/**
 * Tracks authenticated-session age and WebAuthn step-up stamps in the servlet session.
 */
@Service
@RequiredArgsConstructor
public class SessionSecurityService {
    public static final String AUTHENTICATED_AT_ATTR = "connex.authenticatedAt";
    public static final String WEBAUTHN_STEP_UP_AT_ATTR = "connex.webauthnStepUpAt";
    public static final String WEBAUTHN_STEP_UP_USER_ATTR = "connex.webauthnStepUpUserId";
    static final String REQUEST_IDENTITY_ATTR = "connex.requestIdentity";
    static final String REQUEST_IDENTITY_SESSION_ATTR = "connex.requestIdentitySessionId";
    static final String REQUEST_IDENTITY_USER_ATTR = "connex.requestIdentityUserId";

    private final SessionSecurityProperties properties;
    private final Clock clock;

    public void markAuthenticated(HttpServletRequest request, int userId) {
        HttpSession session = request.getSession();
        session.setAttribute(AUTHENTICATED_AT_ATTR, clock.millis());
        session.removeAttribute(WEBAUTHN_STEP_UP_AT_ATTR);
        session.removeAttribute(WEBAUTHN_STEP_UP_USER_ATTR);
        replaceRequestIdentity(session, userId);
    }

    /**
     * Returns an opaque identity for the current authenticated principal and session generation.
     * The value is replaced on every authentication ceremony and never contains a user or session
     * identifier.
     * @param request current servlet request
     * @return stable opaque identity for this authenticated session generation, or null
     */
    public String requestIdentity(HttpServletRequest request) {
        Integer userId = currentUserId();
        HttpSession session = request == null ? null : request.getSession(false);
        if (userId == null || session == null) {
            return null;
        }
        try {
            synchronized (session) {
                String identity = stringAttribute(session, REQUEST_IDENTITY_ATTR);
                String identitySessionId = stringAttribute(session, REQUEST_IDENTITY_SESSION_ATTR);
                Integer identityUserId = integerAttribute(session, REQUEST_IDENTITY_USER_ATTR);
                if (identity == null || !session.getId().equals(identitySessionId)
                        || !userId.equals(identityUserId)) {
                    identity = replaceRequestIdentity(session, userId);
                }
                return identity;
            }
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    public void markStepUp(HttpServletRequest request, int userId) {
        markStepUp(request.getSession(), userId, clock.millis());
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
        if (longAttribute(session, AUTHENTICATED_AT_ATTR) == null && currentUserId() != null) {
            session.setAttribute(AUTHENTICATED_AT_ATTR, session.getCreationTime());
        }
    }

    public void requireRecentAuthentication(int userId) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            throw new ForbiddenException("Recent WebAuthn authentication required");
        }
        HttpSession session = request.getSession(false);
        if (session == null || !hasFreshRecentAuthentication(session, userId)) {
            throw new ForbiddenException("Recent WebAuthn authentication required");
        }
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
            && clock.millis() - recentAt <= window.toMillis();
    }

    private void markStepUp(HttpSession session, int userId, long now) {
        session.setAttribute(WEBAUTHN_STEP_UP_AT_ATTR, now);
        session.setAttribute(WEBAUTHN_STEP_UP_USER_ATTR, userId);
    }

    private static String replaceRequestIdentity(HttpSession session, int userId) {
        String identity = UUID.randomUUID().toString();
        session.setAttribute(REQUEST_IDENTITY_ATTR, identity);
        session.setAttribute(REQUEST_IDENTITY_SESSION_ATTR, session.getId());
        session.setAttribute(REQUEST_IDENTITY_USER_ATTR, userId);
        return identity;
    }

    private Long authenticatedAt(HttpSession session) {
        Long authenticatedAt = longAttribute(session, AUTHENTICATED_AT_ATTR);
        if (authenticatedAt != null) {
            return authenticatedAt;
        }
        return currentUserId() == null ? null : session.getCreationTime();
    }

    private static Long longAttribute(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof Long typed ? typed : null;
    }

    private static Integer integerAttribute(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof Integer typed ? typed : null;
    }

    private static String stringAttribute(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof String typed && !typed.isBlank() ? typed : null;
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
